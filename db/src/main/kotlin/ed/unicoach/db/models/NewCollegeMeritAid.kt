package ed.unicoach.db.models

/**
 * Input for upserting a [CollegeMeritAid] on the natural key
 * `(collegeId, sourceYear)` (RFC 140). Carries no `id` (DB-generated) and no
 * timestamps (DB-managed).
 */
data class NewCollegeMeritAid(
  val collegeId: CollegeId,
  val sourceYear: Int,
  val firstTimeFullTimeFreshmenHeadcount: Int?,
  val noNeedMeritRecipientsHeadcount: Int?,
  val noNeedMeritAverageUsd: Int?,
  /**
   * The document these facts were read out of (RFC 170, D13): the school's own
   * CDS filing for this cycle. The urls live on that row, once, rather than on
   * every fact read out of it.
   */
  val sourceDocumentId: SourceDocumentId,
)
