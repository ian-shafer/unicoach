package ed.unicoach.db.models

/**
 * Input for upserting a [CollegeDeadline] on the natural key
 * `(collegeId, sourceYear, round)` (RFC 140). Carries no `id` (DB-generated)
 * and no timestamps (DB-managed).
 */
data class NewCollegeDeadline(
  val collegeId: CollegeId,
  val sourceYear: Int,
  val round: ApplicationRound,
  val offered: Boolean,
  val closing: CdsMonthDay?,
  val notification: CdsMonthDay?,
  val sourceUrl: String,
  val archiveUrl: String?,
)
