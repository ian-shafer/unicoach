package ed.unicoach.rest.models

import com.fasterxml.jackson.annotation.JsonValue
import ed.unicoach.coaching.budget.EntitlementBasis

/**
 * `GET /api/v1/supporters/me/coaching-usage` 200 body (RFC 244): the same
 * abstract meter the student route publishes, plus the one thing a parent's home
 * has to say that a student's does not — WHICH allowance is being metered.
 *
 * [usage] is the shared [CoachingUsage], reused and not re-declared: the two
 * routes report the same [ed.unicoach.coaching.budget.Entitlement] through the
 * same three fields, and a second copy of that shape is how the two bars come to
 * disagree about what 100% means. It carries no dollars, no token counts and no
 * provider name, for the reason RFC 109 gives.
 */
data class SupporterCoachingUsageResponse(
  val usage: CoachingUsage,
  val coverage: CoachingCoverage,
)

/**
 * WHO is paying, on the wire, so the app renders words instead of inferring them
 * from a null `resetsAt`. One field today because the kind is the whole answer:
 * the period itself is already `usage.resetsAt`.
 */
data class CoachingCoverage(
  val kind: CoachingCoverageKind,
)

/**
 * The closed wire vocabulary of [CoachingCoverage.kind], one member per
 * [EntitlementBasis] arm — Jackson serializes [wire] via its `@JsonValue`,
 * exactly as [SubscriptionStatusView] and [ErrorCode] do.
 *
 * TWO members, and the pair is the product decision (RFC 244): an account either
 * paid for itself or is on the one-time free allowance. There is deliberately no
 * "covered by somebody else" member — no account rides another's subscription —
 * so a third kind cannot be published by accident: [from] is exhaustive over
 * [EntitlementBasis], so a third arm there breaks the build here rather than
 * shipping a client that renders an unknown string.
 */
enum class CoachingCoverageKind(
  @get:JsonValue val wire: String,
) {
  /** The account holds its own current subscription; `usage.resetsAt` is its period end. */
  OWN_SUBSCRIPTION("own_subscription"),

  /** The one-time free allowance every account gets; it never resets. */
  FREE_ALLOWANCE("free_allowance"),
  ;

  companion object {
    /** The member that names the metered [basis]. */
    fun from(basis: EntitlementBasis): CoachingCoverageKind =
      when (basis) {
        is EntitlementBasis.Subscription -> OWN_SUBSCRIPTION
        is EntitlementBasis.FreeAllowance -> FREE_ALLOWANCE
      }
  }
}
