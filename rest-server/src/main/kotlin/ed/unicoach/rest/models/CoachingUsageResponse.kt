package ed.unicoach.rest.models

/**
 * The student-facing coaching meter (RFC 109). Deliberately abstract: it carries
 * no dollars, no token counts, and no provider name — a percentage and the block
 * condition are everything a usage bar needs.
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
)
