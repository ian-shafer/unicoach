package ed.unicoach.db.models

/**
 * A publisher a canonical money figure came from (RFC 161, decision 6).
 *
 * `price_figures.source` and `cohort_money_stats.source` were decorative while
 * one source existed. RFC 161 makes them load-bearing:
 * `CanonicalMoneyLoader.ORDERED_SOURCES` decides which of two conflicting
 * prices a family is shown, so a mistyped source string would silently change
 * a price with nothing in the schema to notice it. The repo's owned-enumeration
 * rule therefore applies -- `TEXT` + `CHECK IN (...)` in migration 0084, plus
 * exactly THIS enum with a [fromValue] companion.
 *
 * Deliberately NOT a sixth vocabulary table beside the five RFC 158 seeded:
 * those are unicoach CONCEPTS, where D6's "unicoach authors, external maps in"
 * applies. A source is external identity and provenance, not a concept, and
 * precedence stays a code list guarded by the unmapped-source fatal rather
 * than becoming operator-editable data.
 *
 * Declaration order is NOT precedence -- [CanonicalMoneyLoader.ORDERED_SOURCES]
 * is the one place that decides who wins.
 */
enum class MoneySource(
  val value: String,
) {
  /** IPEDS SFA, the Student Financial Aid survey: net prices, aid mixes and the cohort headcounts under them (RFC 162). */
  IPEDS_SFA("ipeds_sfa"),

  /** IPEDS IC_AY, the published-charges survey: three residency tiers, fees split from tuition. */
  IPEDS_IC_AY("ipeds_ic_ay"),

  /** The College Scorecard institution file (RFC 158's v1 source). */
  SCORECARD("scorecard"),

  /**
   * The school's own Common Data Set filing, read through the collegedata.fyi
   * corpus (RFC 170, D8). One more publisher on the same source axis: the CDS
   * FIELD IDS it publishes (H.209, H.801, ...) are source-defined codes and
   * live in `source_variable`, never in a table name, a column name or a
   * vocabulary slug.
   */
  COMMON_DATA_SET("common_data_set"),
  ;

  companion object {
    private val BY_VALUE = entries.associateBy { it.value }

    /** The member for a stored value, or null -- the [PriceConcept] `fromValue` shape. */
    fun fromValue(value: String): MoneySource? = BY_VALUE[value]
  }
}
