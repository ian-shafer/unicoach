package ed.unicoach.db.models

/**
 * Input for upserting a [CollegeAdmissionFactors] on the natural key
 * `(collegeId, sourceYear)` (RFC 140). Carries no `id` (DB-generated) and no
 * timestamps (DB-managed).
 */
data class NewCollegeAdmissionFactors(
  val collegeId: CollegeId,
  val sourceYear: Int,
  val rigor: FactorRating?,
  val classRank: FactorRating?,
  val gpa: FactorRating?,
  val testScores: FactorRating?,
  val essay: FactorRating?,
  val recommendations: FactorRating?,
  val interview: FactorRating?,
  val extracurriculars: FactorRating?,
  val talent: FactorRating?,
  val characterQualities: FactorRating?,
  val firstGeneration: FactorRating?,
  val alumniRelation: FactorRating?,
  val geography: FactorRating?,
  val stateResidency: FactorRating?,
  val religiousAffiliation: FactorRating?,
  val volunteerWork: FactorRating?,
  val workExperience: FactorRating?,
  val applicantInterest: FactorRating?,
  val sourceUrl: String,
  val archiveUrl: String?,
)
