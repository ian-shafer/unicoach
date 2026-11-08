package ed.unicoach.college

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.IncomeBand

/**
 * The pinned Scorecard institution CSV's shared column names and value
 * domains (RFC 139/158): the ONE home both the `colleges` fill
 * ([CollegeScorecardLoader]) and the RFC 158 canonical-money fill
 * ([CanonicalMoneyLoader]) read the file through. The two loaders are
 * separate re-parses of the same pinned file on purpose; the names and
 * bounds they share live here so a column rename or a bound change edits one
 * place. Each loader keeps privately only the constants it alone reads.
 */
internal object ScorecardInstitutionColumns {
  const val UNITID = "UNITID"
  const val CONTROL = "CONTROL"
  const val TUITIONFEE_IN = "TUITIONFEE_IN"
  const val TUITIONFEE_OUT = "TUITIONFEE_OUT"
  const val ROOMBOARD_ON = "ROOMBOARD_ON"
  const val ROOMBOARD_OFF = "ROOMBOARD_OFF"
  const val BOOKSUPPLY = "BOOKSUPPLY"
  const val OTHEREXPENSE_ON = "OTHEREXPENSE_ON"
  const val OTHEREXPENSE_OFF = "OTHEREXPENSE_OFF"
  const val OTHEREXPENSE_FAM = "OTHEREXPENSE_FAM"
  const val COSTT4_A = "COSTT4_A"
  const val PCTPELL = "PCTPELL"
  const val GRAD_DEBT_MDN = "GRAD_DEBT_MDN"
  const val MD_EARN_WNE_P10 = "MD_EARN_WNE_P10"

  // The seven outcome columns the `colleges` fill writes (RFC 226, widened by
  // RFC 242): moved here off `CollegeScorecardLoader`'s private literals so the
  // name is stated once and the citation object and the loader cannot spell it
  // two ways.
  const val ADM_RATE = "ADM_RATE"
  const val C150_4 = "C150_4"
  const val UGDS = "UGDS"

  // RFC 242's four. The three `OMAWDP8_*` are the publisher's Outcome Measures
  // family -- everyone who ENTERED, full-time and part-time, new to college and
  // transferring in, awarded within eight years -- split by federal Pell Grant
  // receipt. They are a DIFFERENT measure over a DIFFERENT population and a
  // DIFFERENT entering cohort from [C150_4], never a later version of it.
  // `TRANS_4` is the [C150_4] cohort's transfer-out share and carries that
  // cohort's own dictionary cell.
  const val OMAWDP8_ALL = "OMAWDP8_ALL"
  const val OMAWDP8_PELL_ALL = "OMAWDP8_PELL_ALL"
  const val OMAWDP8_NOPELL_ALL = "OMAWDP8_NOPELL_ALL"
  const val TRANS_4 = "TRANS_4"

  /** The control-keyed net-price column base: overall `NPT4` plus the five income bands. */
  const val NET_PRICE_BASE = "NPT4"

  /** Control-keyed column suffixes: public institutions read `_PUB`, all else `_PRIV`. */
  const val SUFFIX_PUBLIC = "_PUB"
  const val SUFFIX_PRIVATE = "_PRIV"

  // A gross published cost cannot be negative -- the loader-side twin of
  // `db/schema/0083`'s `price_figures_amount_nonneg_check`, the live backstop
  // over the table [CanonicalMoneyLoader] -- now the only reader of this
  // domain -- writes these figures to. (0062's `*_nonneg_check` family was the
  // earlier twin; migration 0094 dropped it with the publisher columns.) The
  // upper end is the column's own INTEGER width, not a business bound: the
  // Scorecard publishes no cap and inventing one would silently drop a real
  // figure.
  const val GROSS_USD_MIN = 0
  const val GROSS_USD_MAX = Int.MAX_VALUE

  // The share/rate domain, mirrored from the 0015 CHECKs (the DB CHECK is
  // the backstop; this duplication is intentional defense-in-depth).
  const val RATE_MIN = 0.0
  const val RATE_MAX = 1.0

  /**
   * WHETHER the fill stamps a Scorecard column with a year. WHICH year is never
   * stated here: it is the publisher's, read from the fetched data dictionary
   * ([ScorecardDictionary.academicYearOf]) by [stampedYearOf] (RFC 274), so a
   * newer release re-dates a figure by being fetched and ingested.
   */
  enum class VintageGroup {
    /**
     * Stamped with the academic year the dictionary states for the column: the
     * eight published charges, `COSTT4_A` and the net-price family. The
     * publisher dates the two halves a year apart (the charges are the price
     * list for the year ahead, the blend and net prices describe a cohort that
     * has been through a year) -- the dictionary says so per column, and the
     * fill no longer has to know it.
     */
    DATED,

    /** The three cells the fill stamps `CanonicalMoneyLoader.VINTAGE_UNDATED` -- no year at all. */
    UNDATED,
  }

  /**
   * The eight published-charge column NAMES, as the fill writes them: the
   * membership fact [VINTAGE_GROUP_BY_VARIABLE] states, listed once.
   */
  private val PUBLISHED_CHARGE_COLUMNS: List<String> =
    listOf(
      TUITIONFEE_IN,
      TUITIONFEE_OUT,
      ROOMBOARD_ON,
      ROOMBOARD_OFF,
      BOOKSUPPLY,
      OTHEREXPENSE_ON,
      OTHEREXPENSE_OFF,
      OTHEREXPENSE_FAM,
    )

  /**
   * The net-price column NAMES, expanded exactly as the fill expands them: one
   * per income band per `CONTROL` arm, because the stored key IS the literal
   * string.
   *
   * Hoisted out of [VINTAGE_GROUP_BY_VARIABLE] so that registry reads at ONE
   * level -- which column is dated -- rather than mixing group membership with
   * the string arithmetic that builds a publisher's name.
   */
  private val NET_PRICE_COLUMNS: List<String> =
    buildList {
      for (suffix in listOf(SUFFIX_PUBLIC, SUFFIX_PRIVATE)) {
        add("$NET_PRICE_BASE$suffix")
        for (band in IncomeBand.entries) add("$NET_PRICE_BASE${band.bandDigit}$suffix")
      }
    }

  /**
   * Every Scorecard `source_variable` the canonical fill CAN WRITE, each
   * carrying the ONE [VintageGroup] that says whether the fill dates it.
   *
   * A MAP, so a column cannot be both dated and undated, and [stampedYearOf]
   * is what the fill asks -- so a ninth charge column added here is dated by
   * joining [VintageGroup.DATED], and then by the publisher's own cell for it,
   * never by whichever constant its call site happens to type.
   *
   * The net-price family is expanded exactly as the fill expands it: one column
   * per income band per `CONTROL` arm, because the stored key IS the literal
   * string.
   */
  val VINTAGE_GROUP_BY_VARIABLE: Map<String, VintageGroup> =
    buildMap {
      for (column in PUBLISHED_CHARGE_COLUMNS + COSTT4_A + NET_PRICE_COLUMNS) setVintageGroup(column, VintageGroup.DATED)
      for (column in listOf(PCTPELL, GRAD_DEBT_MDN, MD_EARN_WNE_P10)) setVintageGroup(column, VintageGroup.UNDATED)
    }

  /**
   * Registers [column] in [group], REFUSING a second claim on it.
   *
   * A plain `put` would let a column registered in both groups resolve itself,
   * last writer winning. The claim is enforced here instead of asserted in
   * prose.
   *
   * Not private, so the refusal is provable: [VINTAGE_GROUP_BY_VARIABLE] must
   * never state a column twice, so the only way to see the guard fire is to
   * build a map that does.
   */
  internal fun MutableMap<String, VintageGroup>.setVintageGroup(
    column: String,
    group: VintageGroup,
  ) {
    val previous = put(column, group)
    require(previous == null) {
      "column=[$column] is claimed by two vintage groups ([$previous] and [$group]): a Scorecard cell is " +
        "either dated or not, so the registry may state it once"
    }
  }

  /**
   * The columns the fill stamps with the year the dictionary states for each:
   * the charges, the published cost of attendance and the net-price family.
   */
  val DATED_MONEY_VARIABLES: Set<String> = variablesOf(VintageGroup.DATED)

  /**
   * The three cells the fill stamps `CanonicalMoneyLoader.VINTAGE_UNDATED`.
   *
   * Two of them are undated by the PUBLISHER: `GRAD_DEBT_MDN` and
   * `MD_EARN_WNE_P10` each pool two cohorts and have no academic year to
   * carry. `PCTPELL` is the odd one out -- the dictionary DOES date it, and the
   * fill stamps it undated anyway. That divergence is deliberate and scoped
   * (RFC 183 D3: moving a currently-undated key onto a dated one changes who
   * wins that key, and nobody has measured it); it is pinned by
   * `ScorecardDictionaryPinTest` so it stays a known debt rather than becoming
   * folklore.
   */
  val UNDATED_VARIABLES: Set<String> = variablesOf(VintageGroup.UNDATED)

  /**
   * Every Scorecard `source_variable` string the canonical fill CAN WRITE (RFC
   * 179) -- the registry the tier map must answer for, in the module that owns
   * the column names.
   *
   * The loader does not merely agree with this set, it is BOUND to it:
   * [loadedVariableOf] is the only door a Scorecard cell id passes through on its
   * way into a row, and it refuses a string that is not here.
   *
   * DERIVED from [VINTAGE_GROUP_BY_VARIABLE] rather than re-listed: a column
   * belongs to exactly one vintage group, because whether the fill dates it is
   * not optional.
   */
  val LOADED_VARIABLES: Set<String> = VINTAGE_GROUP_BY_VARIABLE.keys

  /**
   * The Scorecard columns the `colleges` fill writes that a FAMILY is shown as
   * a figure, each dated by the data dictionary (RFC 226).
   *
   * Not money, so NOT in [VINTAGE_GROUP_BY_VARIABLE]: that map is the money
   * fill's own statement of which cells IT dates, and an outcome column the
   * money fill never writes has no business in it (brief 0011 D36/D37 -- model
   * each thing as what it is). Dated all the same, because brief 0011 D47 dates
   * every figure a family can see and not only the money ones.
   */
  val OUTCOME_VARIABLES: Set<String> =
    setOf(ADM_RATE, C150_4, UGDS, OMAWDP8_ALL, OMAWDP8_PELL_ALL, OMAWDP8_NOPELL_ALL, TRANS_4)

  /**
   * Every Scorecard column whose date and source we cite: the money fill's
   * registry plus the outcome figures -- the key set `AssuranceTier.SCORECARD_TIERS`
   * answers for. Stated HERE, beside the two registries it joins, because the
   * union is a fact about the registries and not about either of the suites
   * that check it.
   */
  val CITED_VARIABLES: Set<String> = LOADED_VARIABLES + OUTCOME_VARIABLES

  /** The registered columns of one vintage group, in registry order. */
  private fun variablesOf(group: VintageGroup): Set<String> =
    // `.toSet()`, because `filterValues` returns a plain `LinkedHashMap` whose
    // `.keys` is a live MUTABLE view at runtime: the sets it builds are shared
    // `object`-level vals, and an unchecked cast could shrink one of them under
    // every later reader. A snapshot owns no view.
    VINTAGE_GROUP_BY_VARIABLE.filterValues { it == group }.keys.toSet()

  /**
   * The academic year the fill stamps [column] with -- the one [dictionary]
   * states for it -- or null for the [VintageGroup.UNDATED] cells, which carry
   * no year at all.
   *
   * The ONE place a Scorecard cell's year is decided, and the seam the fill
   * itself reads: the column decides whether it is dated, the publisher decides
   * when.
   *
   * FATAL for an unregistered column, on [loadedVariableOf]'s own precedent:
   * an unstamped cell is a figure served with no stated year. The dictionary's
   * own refusals ([ScorecardDictionary.Refusal]) pass
   * through: `ScorecardYearGuard` runs them all before the fill reads a row.
   */
  fun stampedYearOf(
    column: String,
    dictionary: ScorecardDictionary,
  ): AcademicYear? =
    when (VINTAGE_GROUP_BY_VARIABLE[column] ?: throw UnregisteredColumnException(column, LOADED_VARIABLES)) {
      VintageGroup.DATED -> dictionary.academicYearOf(column)
      VintageGroup.UNDATED -> CanonicalMoneyLoader.VINTAGE_UNDATED
    }

  /**
   * As [stampedYearOf], for a cell whose year is REQUIRED -- every
   * `price_figures` row, whose `academic_year` is NOT NULL. An undated column
   * reaching a price write is a mapping error, not a missing year.
   */
  fun datedYearOf(
    column: String,
    dictionary: ScorecardDictionary,
  ): AcademicYear =
    stampedYearOf(column, dictionary)
      ?: throw UndatedColumnException(column, VINTAGE_GROUP_BY_VARIABLE.getValue(column))

  /**
   * [column], checked to be a registered Scorecard cell id, on its way into a
   * stored row.
   *
   * FATAL rather than tolerant, on [ed.unicoach.db.models.AssuranceTier.of]'s
   * own precedent: `source_variable` is an open `TEXT` column with no schema
   * CHECK, so an unregistered string would land as a stored cell id the read
   * path then refuses -- a fill that succeeds and a page that throws. It fails
   * at the fill instead, naming the registry that has to be edited.
   */
  fun loadedVariableOf(column: String): String {
    if (column !in LOADED_VARIABLES) throw UnregisteredColumnException(column, LOADED_VARIABLES)
    return column
  }

  /**
   * A Scorecard column the fill tried to write that no registry entry names.
   *
   * TYPED, like this module's other fill faults
   * ([CanonicalMoneyLoader.CorruptStagedChargeException],
   * [CdsSeedLoader.FormatException]): a bare `check` flattens the offending
   * column and the registry it must be added to into a message string, and the
   * ingest abort path can then only re-print prose. Both are properties here --
   * the column is what a fixer adds, the registry is what they compare it
   * against.
   */
  class UnregisteredColumnException(
    val column: String,
    val registered: Set<String>,
  ) : RuntimeException(
      "the Scorecard fill may only write a registered cell id: column=[$column] -- give it a vintage " +
        "group in ScorecardInstitutionColumns.VINTAGE_GROUP_BY_VARIABLE (LOADED_VARIABLES is derived " +
        "from it, so there is nothing to add there) and a tier in AssuranceTier.SCORECARD_TIERS; its year " +
        "is the one the fetched data dictionary states (RFC 179/274)",
    )

  /**
   * A Scorecard column the fill tried to write into a `price_figures` row that
   * its vintage group dates to no academic year.
   *
   * TYPED, like [UnregisteredColumnException] above it and for the same
   * reason: a bare `require` flattens the offending column and the group that
   * refused it into one sentence, and the ingest abort path can then only
   * re-print prose. The column is what a fixer re-groups; the group is what
   * refused it; both stay readable properties.
   */
  class UndatedColumnException(
    val column: String,
    val group: VintageGroup,
  ) : RuntimeException(
      "a price_figures cell must be dated: column=[$column] is in the [$group] vintage group, which " +
        "carries no academic year -- move it to the dated group in " +
        "ScorecardInstitutionColumns.VINTAGE_GROUP_BY_VARIABLE, or stop writing it as a price " +
        "(RFC 183): a price is served under a year or it is not served",
    )
}
