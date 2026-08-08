package ed.unicoach.coaching.budget

import ed.unicoach.db.models.StudentId

/**
 * The one line every coaching pass logs when it declines to run because the
 * student's [Entitlement] is exhausted (RFC 109). A budget skip is a terminal
 * success, not a failure — retrying cannot restore an allowance, so the job must
 * not churn or dead-letter — and this line is the operator's only trace, because
 * no run row exists for a skip.
 *
 * It lives here, beside the budget types and apart from any one pass, so the
 * format is defined once: adding a field means editing this function, not
 * remembering every handler that logs a skip. [passName] is the pass as an
 * operator names it in a log search ("extraction", "synthesis").
 *
 * A free function rather than a member of [Entitlement]: a domain entity models
 * the two meters, it does not render log strings.
 */
fun budgetSkipMessage(
  passName: String,
  studentId: StudentId,
  entitlement: Entitlement,
): String =
  "Skipping [$passName] for student=[${studentId.asString}]: coaching budget exhausted " +
    "(spent=[${entitlement.spent.toUsdString()}] of allowance=[${entitlement.allowance.toUsdString()}] USD)"
