package ed.unicoach.coaching.budget

import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.UserId

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
): String = budgetSkipLine(passName, "student=[${studentId.asString}]", entitlement)

/**
 * The same line for a pass whose budget subject is an ACCOUNT rather than a
 * student (RFC 258): the supporter's coach meters the parent's own account.
 */
fun budgetSkipMessage(
  passName: String,
  account: UserId,
  entitlement: Entitlement,
): String = budgetSkipLine(passName, "account=[${account.asString}]", entitlement)

/** The one format both subjects share; [subject] is already rendered as `kind=[id]`. */
private fun budgetSkipLine(
  passName: String,
  subject: String,
  entitlement: Entitlement,
): String {
  // The operator-facing name of each basis, spelled out here rather than
  // reflected off the type, so renaming the Kotlin declaration cannot silently
  // rewrite what operators grep for. Exhaustive: a third basis fails this build.
  val basis =
    when (entitlement.basis) {
      is EntitlementBasis.FreeAllowance -> "free_allowance"
      is EntitlementBasis.AdminExemption -> "admin_exemption"
      is EntitlementBasis.Subscription -> "subscription"
    }
  // The operator's answer to "blocked until when": a subscription meter resets
  // at its period_end; the lifetime free allowance never resets.
  val resetsAt = entitlement.resetsAt?.let { ", resets at [$it]" } ?: ""
  return "Skipping [$passName] for $subject: coaching budget exhausted " +
    "(basis=[$basis], spent=[${entitlement.spent.toUsdString()}] of allowance=[${entitlement.allowance.toUsdString()}] USD$resetsAt)"
}
