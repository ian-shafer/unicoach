package ed.unicoach.db.models

/**
 * Who a form is required OF (RFC 170, D4): the applicant group an
 * `aid_form_requirements` row is about.
 *
 * A domain axis, not a source shape. The CDS carries no "required of" column
 * at all -- the distinction rides in the field-id BLOCK (H.8xx domestic,
 * H.7xx nonresident) -- but a form required of domestic first-years and a form
 * required of international applicants are different requirements, and a
 * family in the wrong group must not be told to file. Flattening the two
 * blocks into one list would produce exactly that.
 *
 * The house enum pattern: mirrors `aid_form_requirements_applicant_group_check`
 * and is pinned to it by test.
 */
enum class AidFormApplicantGroup(
  val value: String,
) {
  /** First-year applicants for aid who are US citizens or permanent residents (CDS H8). */
  DOMESTIC_FIRST_YEAR("domestic_first_year_aid_applicants"),

  /** First-year applicants for aid who are neither (CDS H7): international applicants. */
  NONRESIDENT_FIRST_YEAR("nonresident_first_year_aid_applicants"),
  ;

  companion object {
    private val BY_VALUE = entries.associateBy { it.value }

    /**
     * Uncalled today, and kept: the schema convention for an own enumeration
     * is "TEXT + CHECK plus exactly one Kotlin enum with a `fromValue`
     * companion", so this is the type's contract with the column rather than a
     * reader written ahead of a caller. The first read of a stored group
     * (the H7 nonresident block) uses it as it stands.
     */
    fun fromValue(value: String): AidFormApplicantGroup? = BY_VALUE[value]
  }
}
