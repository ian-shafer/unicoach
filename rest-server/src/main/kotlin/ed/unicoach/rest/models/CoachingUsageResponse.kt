package ed.unicoach.rest.models

import ed.unicoach.coaching.budget.Entitlement
import java.time.Instant

/**
 * The student-facing coaching meter (RFC 109). Deliberately abstract: it carries
 * no dollars, no token counts, and no provider name — a percentage, the block
 * condition, and the reset point are everything a usage bar needs.
 */
data class CoachingUsageResponse(
  val usage: CoachingUsage,
)

data class CoachingUsage(
  /** 0–100, floored and capped. */
  val usedPercent: Int,
  /**
   * Whether coaching is currently blocked. Equivalent to `usedPercent == 100`,
   * but carried explicitly so a client never re-derives the block condition from
   * the bar it renders.
   */
  val exhausted: Boolean,
  /**
   * When the meter resets (RFC 110): the subscription period's end, ISO-8601.
   * Null on the free tier — the lifetime allowance never resets.
   */
  val resetsAt: Instant?,
)

/**
 * The ONE projection of an [Entitlement] onto the meter both surfaces publish
 * (RFC 109 for the student, RFC 244 for the parent), beside the type it produces
 * — the `toResponse()` convention. Sharing the TYPE is not sharing the MAPPING,
 * and it is the mapping that decides what a bar shows: with a copy per handler, a
 * fourth field or a cap change reaches one meter and not the other.
 */
fun Entitlement.toCoachingUsage(): CoachingUsage =
  CoachingUsage(
    usedPercent = usedPercent,
    exhausted = exhausted,
    resetsAt = resetsAt,
  )
