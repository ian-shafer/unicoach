package ed.unicoach.coaching.budget

import ed.unicoach.db.Database
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentLlmCostDao
import ed.unicoach.db.dao.SubscriptionsDao
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.Subscription
import ed.unicoach.subscriptions.SubscriptionPlans

/**
 * The one read the coaching budget gate is built on (RFC 109; subscribed branch
 * RFC 110) — a student's meter paired with its allowance into an [Entitlement] —
 * and the gate itself: [verdict] is where "may this student spend" is decided,
 * so the four gates wrap one answer instead of each re-deriving it.
 *
 * Two branches, decided by [SubscriptionsDao.findCurrent]: no current entitling
 * subscription meters the lifetime ledger against the configured free
 * allowance; a current one meters `windowedCost` over its `[period_start,
 * period_end)` against the plan's `y × price` budget. The gate never calls
 * Apple — it reads only the local `subscriptions` row and the cost ledger, so
 * gate latency and availability stay DB-bound.
 *
 * Policy on the two counters [StudentLlmCostDao] returns alongside the total is
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
 * configured plan is config drift and fails the same way — closed.
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
  ): Result<BudgetVerdict> =
    entitlement(session, studentId).map {
      if (it.exhausted) BudgetVerdict.Exhausted(it) else BudgetVerdict.Entitled
    }

  /**
   * In-transaction read of the meters themselves, for a caller that wants the
   * numbers rather than the decision (and the read [verdict] is built on).
   */
  fun entitlement(
    session: SqlSession,
    studentId: StudentId,
  ): Result<Entitlement> =
    SubscriptionsDao
      .findCurrent(session, studentId)
      .mapCatching { subscription ->
        when (subscription) {
          // Expired, revoked, billing-retry, and elapsed-window rows all land
          // here: a lapsed subscriber meters as a (typically exhausted)
          // free-tier student until a re-verify refreshes the row.
          null -> freeBranch(session, studentId)

          else -> subscriptionBranch(session, studentId, subscription)
        }
      }

  /** Standalone form, for the usage route: opens its own connection. */
  suspend fun entitlement(studentId: StudentId): Result<Entitlement> =
    runCatching {
      database.withConnection { session -> entitlement(session, studentId).getOrThrow() }
    }

  /** The RFC 109 free branch: the lifetime ledger total against the configured free allowance. */
  private fun freeBranch(
    session: SqlSession,
    studentId: StudentId,
  ): Entitlement =
    StudentLlmCostDao
      .lifetimeCost(session, studentId)
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
   * silently granting or denying — that is config drift, not a student state.
   */
  private fun subscriptionBranch(
    session: SqlSession,
    studentId: StudentId,
    subscription: Subscription,
  ): Entitlement {
    val budget =
      plans.periodBudget(subscription.productId)
        ?: throw IllegalStateException(
          "Current subscription [${subscription.id.value}] has no configured plan for product " +
            "[${subscription.productId}]: the gate fails closed on config drift",
        )
    return StudentLlmCostDao
      .windowedCost(session, studentId, subscription.periodStart, subscription.periodEnd)
      .map {
        Entitlement(
          spent = it.costNanodollars,
          allowance = budget,
          basis = EntitlementBasis.Subscription(resetsAt = subscription.periodEnd),
        )
      }.getOrThrow()
  }
}
