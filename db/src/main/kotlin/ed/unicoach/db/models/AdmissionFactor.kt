package ed.unicoach.db.models

/**
 * The CDS C7 admissions factors, in the order the grid reports them, each
 * paired with the words a coach says out loud and the accessor that reads its
 * rating off a [CollegeAdmissionFactors] row (RFC 148).
 *
 * The [label] is the point: the stored [value] is our column name, which is
 * source shape, and no advice surface may put it in front of a family (RFC 142).
 * The two ride together from this one construct, so a renderer cannot emit the
 * code without the words.
 *
 * A null rating is "this school did not report this row" and is a different
 * fact from [FactorRating.NOT_CONSIDERED], which is the school saying it does
 * not weigh it. [ratingOf] therefore stays nullable and callers omit the null
 * case rather than rendering it as anything at all.
 */
enum class AdmissionFactor(
  /** ALSO the column name in `college_admission_factors`: one vocabulary, not two. */
  val value: String,
  val label: String,
  private val read: (CollegeAdmissionFactors) -> FactorRating?,
  private val readNew: (NewCollegeAdmissionFactors) -> FactorRating?,
) {
  RIGOR(
    "rigor",
    "rigor of the high school coursework",
    { it: CollegeAdmissionFactors -> it.rigor },
    { it: NewCollegeAdmissionFactors -> it.rigor },
  ),
  CLASS_RANK(
    "class_rank",
    "class rank",
    { it: CollegeAdmissionFactors -> it.classRank },
    { it: NewCollegeAdmissionFactors -> it.classRank },
  ),
  GPA(
    "gpa",
    "high school grades",
    { it: CollegeAdmissionFactors -> it.gpa },
    { it: NewCollegeAdmissionFactors -> it.gpa },
  ),
  TEST_SCORES(
    "test_scores",
    "standardised test scores",
    { it: CollegeAdmissionFactors -> it.testScores },
    { it: NewCollegeAdmissionFactors -> it.testScores },
  ),
  ESSAY(
    "essay",
    "the application essay",
    { it: CollegeAdmissionFactors -> it.essay },
    { it: NewCollegeAdmissionFactors -> it.essay },
  ),
  RECOMMENDATIONS(
    "recommendations",
    "letters of recommendation",
    { it: CollegeAdmissionFactors -> it.recommendations },
    { it: NewCollegeAdmissionFactors -> it.recommendations },
  ),
  INTERVIEW(
    "interview",
    "the interview",
    { it: CollegeAdmissionFactors -> it.interview },
    { it: NewCollegeAdmissionFactors -> it.interview },
  ),
  EXTRACURRICULARS(
    "extracurriculars",
    "extracurricular activities",
    { it: CollegeAdmissionFactors -> it.extracurriculars },
    { it: NewCollegeAdmissionFactors -> it.extracurriculars },
  ),
  TALENT(
    "talent",
    "talent or ability",
    { it: CollegeAdmissionFactors -> it.talent },
    { it: NewCollegeAdmissionFactors -> it.talent },
  ),
  CHARACTER_QUALITIES(
    "character_qualities",
    "character and personal qualities",
    { it: CollegeAdmissionFactors -> it.characterQualities },
    { it: NewCollegeAdmissionFactors -> it.characterQualities },
  ),
  FIRST_GENERATION(
    "first_generation",
    "being the first in the family to go to college",
    { it: CollegeAdmissionFactors -> it.firstGeneration },
    { it: NewCollegeAdmissionFactors -> it.firstGeneration },
  ),
  ALUMNI_RELATION(
    "alumni_relation",
    "having a relative who went there",
    { it: CollegeAdmissionFactors -> it.alumniRelation },
    { it: NewCollegeAdmissionFactors -> it.alumniRelation },
  ),
  GEOGRAPHY(
    "geography",
    "where the student lives",
    { it: CollegeAdmissionFactors -> it.geography },
    { it: NewCollegeAdmissionFactors -> it.geography },
  ),
  STATE_RESIDENCY(
    "state_residency",
    "living in the school's state",
    { it: CollegeAdmissionFactors -> it.stateResidency },
    { it: NewCollegeAdmissionFactors -> it.stateResidency },
  ),
  RELIGIOUS_AFFILIATION(
    "religious_affiliation",
    "religious affiliation or commitment",
    { it: CollegeAdmissionFactors -> it.religiousAffiliation },
    { it: NewCollegeAdmissionFactors -> it.religiousAffiliation },
  ),
  VOLUNTEER_WORK(
    "volunteer_work",
    "volunteer work",
    { it: CollegeAdmissionFactors -> it.volunteerWork },
    { it: NewCollegeAdmissionFactors -> it.volunteerWork },
  ),
  WORK_EXPERIENCE(
    "work_experience",
    "paid work experience",
    { it: CollegeAdmissionFactors -> it.workExperience },
    { it: NewCollegeAdmissionFactors -> it.workExperience },
  ),
  APPLICANT_INTEREST(
    "applicant_interest",
    "demonstrated interest in the school",
    { it: CollegeAdmissionFactors -> it.applicantInterest },
    { it: NewCollegeAdmissionFactors -> it.applicantInterest },
  ),
  ;

  /** This factor's rating on [factors], or null when the school did not report it. */
  fun ratingOf(factors: CollegeAdmissionFactors): FactorRating? = read(factors)

  /**
   * The same rating on a row about to be WRITTEN. The write path needs it
   * because `CdsAdmissionsDao`'s bind table is derived from these entries
   * rather than re-listing all eighteen column names: a nineteenth factor is
   * then one member here plus its migration, and it cannot land as a column the
   * ingest binds but no reader renders (or the reverse).
   */
  fun ratingOf(factors: NewCollegeAdmissionFactors): FactorRating? = readNew(factors)
}
