package ed.unicoach.db.models

/**
 * Input for upserting one `college_programs_census` row (RFC 144) on its natural
 * key `(collegeId, cipCode, awardLevel)`. Carries no `id` (DB-generated) and no
 * timestamps (DB-managed).
 *
 * [cipCode] is the IPEDS `C_A.CIPCODE` with its dot removed, so `"11.0701"`
 * becomes `"110701"` — six digits, enforced by the table's format CHECK. The
 * ingest keeps only `MAJORNUM = 1` rows, which is what makes this key unique:
 * the second-major rows collide on it 19,041 times at `AWLEVEL = 5`.
 */
data class NewCollegeProgramsCensus(
  val collegeId: CollegeId,
  val cipCode: String,
  val awardLevel: Int,
  val awardsCount: Int,
  val surveyYear: Int,
)
