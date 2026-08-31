package ed.unicoach.db.models

/**
 * Institutional sector (`college_ipeds.sector`, IPEDS `HD.SECTOR`) as a
 * vocabulary: the code -> word mapping with exactly ONE home, beside
 * [InstitutionControl] (RFC 143) and [IncomeBand].
 *
 * `college_search_index.sector` stores the WORD, not the number (RFC 150 D61b).
 * `college_ipeds` keeps storing the raw code — that is CLAUDE.md's rule for a
 * source-defined code — but the derived index is under no such obligation, and
 * a raw code there would only force a code-to-word mapping at the tool boundary.
 *
 * There is no `sector` codebook table and there should not be one: RFC 147's
 * reference tables hold PUBLISHED codebooks, and this vocabulary is authored
 * here. It follows CLAUDE.md's own-enumeration pattern — TEXT + CHECK IN (...)
 * in the schema (`college_search_index_sector_check`, 0064) plus exactly one
 * Kotlin enum. The words are UNDERSCORED, so this enum and [InstitutionControl]
 * speak one dialect; that also means they are not `slug`s, which is why the
 * column is TEXT rather than the shared `slug` DOMAIN.
 *
 * The value list is the authoritative code set, not an invention: `SECTOR_CODES`
 * in `IpedsLoader` (`(0..9).toSet() + 99`), mirrored by
 * `college_ipeds_sector_domain_check` (0055). Eleven values. `10..98` are values
 * IPEDS does not publish, and accepting one would store junk indistinguishable
 * from a real sector.
 *
 * **[UNKNOWN] and NULL are different things.** `99` is the publisher saying
 * "sector unknown (not active)" — a reported fact — and it maps to the explicit
 * word `unknown`. A college with NO `college_ipeds` row at all leaves
 * `college_search_index.sector` NULL: an absence, nothing reported either way.
 * That is the same distinction RFC 148 D10 drew for the honest denominator, and
 * the rebuild keeps it by resolving the word only when there is a row to read.
 */
enum class InstitutionSector(
  /** The IPEDS `HD.SECTOR` code as ingested into `college_ipeds.sector`. */
  val code: Int,
  /** The word the derived index stores and the payload may render. */
  val value: String,
) {
  ADMINISTRATIVE_UNIT(0, "administrative_unit"),
  PUBLIC_FOUR_YEAR(1, "public_four_year"),
  PRIVATE_NONPROFIT_FOUR_YEAR(2, "private_nonprofit_four_year"),
  PRIVATE_FOR_PROFIT_FOUR_YEAR(3, "private_for_profit_four_year"),
  PUBLIC_TWO_YEAR(4, "public_two_year"),
  PRIVATE_NONPROFIT_TWO_YEAR(5, "private_nonprofit_two_year"),
  PRIVATE_FOR_PROFIT_TWO_YEAR(6, "private_for_profit_two_year"),
  PUBLIC_LESS_THAN_TWO_YEAR(7, "public_less_than_two_year"),
  PRIVATE_NONPROFIT_LESS_THAN_TWO_YEAR(8, "private_nonprofit_less_than_two_year"),
  PRIVATE_FOR_PROFIT_LESS_THAN_TWO_YEAR(9, "private_for_profit_less_than_two_year"),

  /**
   * `HD.SECTOR = 99`, "sector unknown (not active)". A REPORTED unknown, which
   * is why it is a word and not a NULL — see the class doc.
   */
  UNKNOWN(99, "unknown"),
  ;

  companion object {
    /**
     * The sector this [code] names, or null when the published vocabulary does
     * not define it. The rebuild reads this: an undefined code cannot reach the
     * index anyway, because `college_ipeds_sector_domain_check` refused it at
     * ingest, so a null here is a schema drift worth seeing as a NULL column
     * rather than a guess.
     */
    fun fromCode(code: Int): InstitutionSector? = entries.find { it.code == code }

    /** The sector this stored [value] names, or null when it is not one of ours. */
    fun fromValue(value: String): InstitutionSector? = entries.find { it.value == value }
  }
}
