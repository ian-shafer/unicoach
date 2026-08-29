package ed.unicoach.db.models

import java.time.Instant
import java.util.UUID

/**
 * Surface id for a [CollegeAdmissionFactors] row. Lives alongside the model
 * rather than in its own file because `college_admission_factors` is
 * bulk-upserted reference data with no standalone id-keyed read path (the
 * [CollegeProgramId] precedent).
 */
@JvmInline
value class CollegeAdmissionFactorsId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}

/**
 * One CDS cycle's C7 admissions factor grid for one college, from the
 * `college_admission_factors` reference table (RFC 140): one [FactorRating] per
 * factor, NULL = not reported. [sourceYear] is the CDS cycle as the
 * `cds_source_year` domain defines it (db/schema/0054).
 */
data class CollegeAdmissionFactors(
  override val id: CollegeAdmissionFactorsId,
  val collegeId: CollegeId,
  val sourceYear: Int,
  /** C.701. */
  val rigor: FactorRating?,
  /** C.702. */
  val classRank: FactorRating?,
  /** C.703. */
  val gpa: FactorRating?,
  /** C.704. */
  val testScores: FactorRating?,
  /** C.705. */
  val essay: FactorRating?,
  /** C.706. */
  val recommendations: FactorRating?,
  /** C.707. */
  val interview: FactorRating?,
  /** C.708. */
  val extracurriculars: FactorRating?,
  /** C.709. */
  val talent: FactorRating?,
  /** C.710. */
  val characterQualities: FactorRating?,
  /** C.711. */
  val firstGeneration: FactorRating?,
  /** C.712. */
  val alumniRelation: FactorRating?,
  /** C.713. */
  val geography: FactorRating?,
  /** C.714. */
  val stateResidency: FactorRating?,
  /** C.715. */
  val religiousAffiliation: FactorRating?,
  /** C.716. */
  val volunteerWork: FactorRating?,
  /** C.717. */
  val workExperience: FactorRating?,
  /** C.718. */
  val applicantInterest: FactorRating?,
  /** The school's own published CDS document. */
  val sourceUrl: String,
  /** The corpus's archived copy. */
  val archiveUrl: String?,
  override val createdAt: Instant,
  override val updatedAt: Instant,
) : Identifiable<CollegeAdmissionFactorsId>,
  Created,
  Updated
