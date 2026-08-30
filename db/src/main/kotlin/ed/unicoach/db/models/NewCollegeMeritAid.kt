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
  val sourceUrl: String,
  val archiveUrl: String?,
)
