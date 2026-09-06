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
  /**
   * The document these facts were read out of (RFC 170, D13): the school's own
   * CDS filing for this cycle. The urls live on that row, once, rather than on
   * every fact read out of it.
   */
  val sourceDocumentId: SourceDocumentId,
)
