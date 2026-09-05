package ed.unicoach.db.models

/**
 * What a published price is FOR (RFC 158, D2), backing the `price_concepts`
 * vocabulary table. Deliberately carries NO blend concept: `price_figures`'
 * foreign key onto this vocabulary is the "no blend may enter the price
 * table" rule itself (P6).
 */
enum class PriceConcept(
  val value: String,
  /**
   * The RFC 149 rule as data (P3): TRUE when the figure differs by where the
   * student lives. The loader fatals on a `price_figures` row whose
   * arrangement disagrees with this flag.
   */
  val arrangementVaries: Boolean,
) {
  TUITION_AND_FEES("tuition_and_fees", false),

  /** Required fees alone, where a source separates them; no ingested source carries it yet (shape/02). */
  FEES_ONLY("fees_only", false),
  HOUSING_AND_FOOD("housing_and_food", true),
  BOOKS_AND_SUPPLIES("books_and_supplies", false),
  OTHER_EXPENSES("other_expenses", true),

  /** A source-PUBLISHED all-in price; a SUM of components is derived and never stored. */
  PUBLISHED_PRICE("published_price", true),
  ;

  companion object {
    fun fromValue(value: String): PriceConcept? = entries.find { it.value == value }
  }
}
