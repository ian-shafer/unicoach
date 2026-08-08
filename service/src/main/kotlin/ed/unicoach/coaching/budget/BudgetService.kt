package ed.unicoach.coaching.budget

import ed.unicoach.db.Database
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentLlmCostDao
import ed.unicoach.db.models.StudentId

/**
 * The one read the coaching budget gate is built on (RFC 109) — a student's
 * lifetime ledger total, paired with the configured free allowance into an
 * [Entitlement] — and the gate itself: [verdict] is where "may this student
 * spend" is decided, so the four gates wrap one answer instead of each
 * re-deriving it.
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
 * coaching unavailability.
 */
class BudgetService(
  private val database: Database,
  private val config: BudgetConfig,
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
    StudentLlmCostDao
      .lifetimeCost(session, studentId)
      .map { Entitlement(spent = it.costNanodollars, allowance = config.freeAllowance) }

  /** Standalone form, for the usage route: opens its own connection. */
  suspend fun entitlement(studentId: StudentId): Result<Entitlement> =
    runCatching {
      database.withConnection { session -> entitlement(session, studentId).getOrThrow() }
    }
}
