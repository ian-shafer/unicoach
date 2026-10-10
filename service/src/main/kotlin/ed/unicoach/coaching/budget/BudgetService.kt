package ed.unicoach.coaching.budget

import ed.unicoach.db.Database
import ed.unicoach.db.dao.AccountLlmCostDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SubscriptionsDao
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.Subscription
import ed.unicoach.db.models.UserId
import ed.unicoach.subscriptions.SubscriptionPlans

/**
 * The gate's subject does not exist: a [StudentId] with no `students` row.
 * Carries the dangling reference, and the DAO's [NotFoundException] it came from
 * as the cause, so a caller or a test reads the id OFF the exception rather than
 * out of its message — `TargetVersionMissingException`'s shape, two modules over.
 *
 * An [IllegalStateException], so the gate fails closed exactly as config drift
 * does and no caller's classification changes.
 */
class UnknownBudgetSubjectException(
  val studentId: StudentId,
  cause: Throwable? = null,
) : IllegalStateException(
    "Student [${studentId.asString}] names no row: the gate fails closed on a dangling subject reference",
    cause,
  )

/**
 * The one read the coaching budget gate is built on (RFC 109; subscribed branch
 * RFC 110; the ACCOUNT subject RFC 244) — an account's meter paired with its
 * allowance into an [Entitlement] — and the gate itself: [verdict] is where "may
 * this subject spend" is decided, so the four gates wrap one answer instead of
 * each re-deriving it.
 *
 * THE SUBJECT IS THE ACCOUNT, not the role (RFC 244). Every account pays for
 * itself and no account rides another's subscription, and one human may hold both
 * a `students` row and a `supporters` row — Apple will not sell that person two
 * subscriptions, so their one subscription carries their spend on both sides. A
 * caller that HAS the account asks for it ([entitlement] on a [UserId]); the four
 * turn gates hold only a [StudentId], so those entries resolve the account one
 * indexed hop away. Either way there is ONE answer, [entitlementForAccount].
 *
 * Two branches, decided by [SubscriptionsDao.findCurrent]: no current
 * entitling subscription meters the lifetime ledger against the configured free
 * allowance; a current one meters `windowedCost` over its `[period_start,
 * period_end)` against the plan's `y × price` budget. A supporter takes the same
 * two branches with the same numbers — no supporter-specific allowance, and no
 * third [EntitlementBasis]. The gate never calls Apple — it reads only the local
 * `subscriptions` row and the cost ledger, so gate latency and availability stay
 * DB-bound.
 *
 * Policy on the two counters [AccountLlmCostDao] returns alongside the total is
 * deliberately simple, and is RFC 108's bias inherited rather than re-decided
 * per caller: `uncostedCalls` (usage the provider never reported) contribute
 * `0`, so the gate fails OPEN by a gap that is already bounded and countable at
 * the ledger; `estimatedCalls` are default-priced over-estimates, so the gate
 * fails CLOSED on those. Neither is compensated here.
 *
 * A failed read is never a [BudgetVerdict.Entitled]: every method here returns
 * [Result.failure] and each caller's existing failure path (a 500 for chat, a
 * retriable failure for the background passes) turns budget unavailability into
 * coaching unavailability. A current subscription whose product has no
 * configured plan is config drift and fails the same way — closed. A [StudentId]
 * naming no row is the same kind of failure, and is stated as one
 * ([accountOfStudent]), rather than metering a subject that does not exist.
 */
class BudgetService(
  private val database: Database,
  private val config: BudgetConfig,
  private val plans: SubscriptionPlans,
) {
  /**
   * The gate, for the four pre-call checks: they hold a session already, and
   * deciding inside the caller's own transaction keeps the verdict consistent
   * with the work it guards. Each caller wraps a [BudgetVerdict.Exhausted] in
   * its own refusal — the decision is shared, the refusal shapes are not.
   */
  fun verdict(
    session: SqlSession,
    studentId: StudentId,
  ): Result<BudgetVerdict> = entitlement(session, studentId).map(::verdictOf)

  /**
   * In-transaction read of the meters themselves, for a caller that wants the
   * numbers rather than the decision (and the read [verdict] is built on).
   */
  fun entitlement(
    session: SqlSession,
    studentId: StudentId,
  ): Result<Entitlement> =
    runCatching {
      entitlementForAccount(session, accountOfStudent(session, studentId))
    }

  /** Standalone form, for the usage route: opens its own connection. */
  suspend fun entitlement(studentId: StudentId): Result<Entitlement> =
    runCatching {
      database.withConnection { session -> entitlement(session, studentId).getOrThrow() }
    }

  /**
   * The subject's own read (RFC 244): an ACCOUNT's meter against its allowance,
   * for a caller that already holds the account — `GET
   * /api/v1/supporters/me/coaching-usage`, where the session names the account
   * and no role row has to be read back to recover it. Opens its own connection,
   * like the student form beside it.
   *
   * There is no supporter-keyed entry. A supporter's spend reaches this answer
   * through `account_llm_cost`, which re-keys the supporter spine onto the
   * account, so the only thing a `SupporterId` would add here is a `supporters`
   * read for a `user_id` every caller of this already has. `family/06` adds a
   * role-keyed gate with the call it gates, which is also where its refusal
   * shape is decided.
   */
  suspend fun entitlement(userId: UserId): Result<Entitlement> =
    runCatching {
      database.withConnection { session -> entitlementForAccount(session, userId) }
    }

  private fun verdictOf(entitlement: Entitlement): BudgetVerdict =
    if (entitlement.exhausted) BudgetVerdict.Exhausted(entitlement) else BudgetVerdict.Entitled

  /**
   * The account behind a [StudentId], read with [SoftDeleteScope.ALL] on purpose:
   * the student-keyed reads this replaced filtered on no `deleted_at` either, and
   * a closed profile's spend is still spend — a meter that forgot it would reset a
   * lifetime allowance by closing a profile.
   *
   * A [StudentId] naming NO row is a dangling reference, not a subject with no
   * spend, so it fails closed exactly as config drift does — as an
   * [UnknownBudgetSubjectException], which IS an [IllegalStateException] and
   * carries the dangling id as a value, and deliberately NOT as the [NotFoundException] the
   * DAO answers with: that is this corpus's word for an ordinary absence, and the
   * REST boundary publishes it as a `404` carrying the DAO's own message
   * (`rest/plugins/StatusPages.kt`). A corrupt reference is a 500.
   */
  private fun accountOfStudent(
    session: SqlSession,
    studentId: StudentId,
  ): UserId =
    StudentsDao
      .findById(session, studentId, SoftDeleteScope.ALL)
      .getOrElse { cause ->
        // Anything else is rethrown untouched, so a DatabaseException keeps the
        // transient/permanent classification its callers retry on.
        if (cause !is NotFoundException) throw cause
        throw UnknownBudgetSubjectException(studentId, cause)
      }.userId

  /**
   * The one answer, once the subject is an account: this account's own
   * subscription decides the branch, and both branches read the ACCOUNT ledger.
   */
  private fun entitlementForAccount(
    session: SqlSession,
    userId: UserId,
  ): Entitlement =
    when (val subscription = SubscriptionsDao.findCurrent(session, userId).getOrThrow()) {
      // Expired, revoked, billing-retry, and elapsed-window rows all land here:
      // a lapsed subscriber meters as a (typically exhausted) free-tier account
      // until a re-verify refreshes the row. So does every supporter today,
      // since nothing sells a parent a subscription yet.
      null -> freeBranch(session, userId)

      else -> subscriptionBranch(session, userId, subscription)
    }

  /** The RFC 109 free branch: the lifetime ledger total against the configured free allowance. */
  private fun freeBranch(
    session: SqlSession,
    userId: UserId,
  ): Entitlement =
    AccountLlmCostDao
      .lifetimeCost(session, userId)
      .map {
        Entitlement(
          spent = it.costNanodollars,
          allowance = config.freeAllowance,
          basis = EntitlementBasis.FreeAllowance,
        )
      }.getOrThrow()

  /**
   * The subscribed branch (RFC 110): the period window's spend against the
   * plan's `y × price` budget. The DB CHECK `period_start < period_end`
   * discharges `windowedCost`'s `require`. A current subscription with no
   * configured plan fails CLOSED (chat 500, background retry) rather than
   * silently granting or denying — that is config drift, not a subject state.
   */
  private fun subscriptionBranch(
    session: SqlSession,
    userId: UserId,
    subscription: Subscription,
  ): Entitlement {
    val budget =
      plans.periodBudget(subscription.productId)
        ?: throw IllegalStateException(
          "Current subscription [${subscription.id.value}] has no configured plan for product " +
            "[${subscription.productId}]: the gate fails closed on config drift",
        )
    return AccountLlmCostDao
      .windowedCost(session, userId, subscription.periodStart, subscription.periodEnd)
      .map {
        Entitlement(
          spent = it.costNanodollars,
          allowance = budget,
          basis = EntitlementBasis.Subscription(resetsAt = subscription.periodEnd),
        )
      }.getOrThrow()
  }
}
