package ed.unicoach.db.models

/**
 * The residency axis a published price is on (RFC 158, D4), backing the
 * `residency_bases` vocabulary table. [NOT_APPLICABLE] is a real member, not an
 * absent value: a figure that does not vary by residency says so explicitly.
 *
 * The DB rows and this enum are pinned to byte-agreement, both ways, by
 * `MoneyVocabularyLoader` (fatally, at load) and by test: the mapping has one
 * home, and disagreement is a build error, not drift.
 */
enum class ResidencyBasis(
  val value: String,
) {
  /** The community-college in-district tier; no ingested source carries it yet (shape/02). */
  IN_DISTRICT("in_district"),
  IN_STATE("in_state"),
  OUT_OF_STATE("out_of_state"),

  /** The figure does not vary by residency -- explicit inapplicability, never a NULL default. */
  NOT_APPLICABLE("not_applicable"),
  ;

  companion object {
    fun fromValue(value: String): ResidencyBasis? = entries.find { it.value == value }
  }
}
