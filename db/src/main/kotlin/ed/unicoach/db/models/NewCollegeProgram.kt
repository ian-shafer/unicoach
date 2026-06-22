package ed.unicoach.db.models

/**
 * Input for upserting a [CollegeProgram] on the natural key
 * `(collegeId, cipCode, credentialLevel)`. Carries no `id` (DB-generated) and no
 * timestamps (DB-managed).
 */
data class NewCollegeProgram(
  val collegeId: CollegeId,
  val cipCode: String,
  val cipTitle: String,
  val credentialLevel: Int,
)
