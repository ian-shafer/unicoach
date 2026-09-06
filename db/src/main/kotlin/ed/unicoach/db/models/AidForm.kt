package ed.unicoach.db.models

/**
 * A financial-aid form a school can require of an applicant (RFC 170, D4).
 *
 * Authored vocabulary in the RFC 158 D6 shape: the rows live in
 * `db/data/money-vocabulary.json`, the table is `aid_forms`, and
 * `MoneyVocabularyLoader` pins the two to this enum fatally, both ways.
 *
 * Named as a FAMILY names the form, never as the CDS numbers it. The CDS field
 * ids (H.801-H.807) are source-defined codes: they ride on
 * `aid_form_requirements.source_variable`, and no bare one may reach a tool
 * result (RFC 143).
 */
enum class AidForm(
  val value: String,
) {
  /** The Free Application for Federal Student Aid (CDS H.801). */
  FAFSA("fafsa"),

  /** The College Board's CSS Profile (CDS H.803). */
  CSS_PROFILE("css_profile"),

  /** The noncustodial parent's CSS Profile (CDS H.805), a second filing by a second household. */
  NONCUSTODIAL_CSS_PROFILE("noncustodial_css_profile"),

  /** The school's own aid form (CDS H.802). */
  INSTITUTIONAL("institutional_form"),

  /** A state aid form (CDS H.804), e.g. the WASFA a Washington resident may file. */
  STATE("state_aid_form"),

  /** The business/farm supplement (CDS H.806), for a self-employed household. */
  BUSINESS_FARM_SUPPLEMENT("business_farm_supplement"),

  /** Another form the school names for itself (CDS H.807). */
  OTHER_INSTITUTIONAL("other_institutional_form"),
  ;

  companion object {
    private val BY_VALUE = entries.associateBy { it.value }

    /** The member for a stored slug, or null -- the [PriceConcept] `fromValue` shape. */
    fun fromValue(value: String): AidForm? = BY_VALUE[value]
  }
}
