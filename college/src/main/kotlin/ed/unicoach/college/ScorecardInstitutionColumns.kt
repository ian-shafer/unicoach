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
   * Which of the fill's three year stamps a Scorecard column carries. The years
   * themselves live on [CanonicalMoneyLoader]; this is the AXIS they sit on.
   */
  enum class VintageGroup {
    /** The eight published charges, stamped `CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR`. */
    PUBLISHED_CHARGE,

    /** `COSTT4_A` and the net-price family, stamped `CanonicalMoneyLoader.BLENDED_AVERAGE_VINTAGE`. */
    BLENDED_AVERAGE,

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
   * level -- which column carries which year -- rather than mixing group
   * membership with the string arithmetic that builds a publisher's name.
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
   * carrying the ONE [VintageGroup] the fill stamps it with -- and therefore
   * the ONE statement, anywhere, of which year a Scorecard cell is served at.
   *
   * A MAP, not three sets beside three hand-picked constants at the write
   * seams. The year a family is shown a price under is neither optional nor
   * plural: a key holds one value, so a column dated by two publisher cohorts
   * cannot be written down, and [stampedYearOf] is what the fill asks -- so a
   * ninth charge column added to this map is stamped by joining a group, not
   * by whichever constant its call site happens to type. Getting that wrong is
   * exactly what RFC 183 landed to make impossible: both year constants were
   * wrong by two years for as long as they existed, with nothing able to
   * contradict them.
   *
   * The net-price family is expanded exactly as the fill expands it: one column
   * per income band per `CONTROL` arm, because the stored key IS the literal
   * string.
   */
  val VINTAGE_GROUP_BY_VARIABLE: Map<String, VintageGroup> =
    buildMap {
      for (column in PUBLISHED_CHARGE_COLUMNS) setVintageGroup(column, VintageGroup.PUBLISHED_CHARGE)
      for (column in listOf(COSTT4_A) + NET_PRICE_COLUMNS) setVintageGroup(column, VintageGroup.BLENDED_AVERAGE)
      for (column in listOf(PCTPELL, GRAD_DEBT_MDN, MD_EARN_WNE_P10)) setVintageGroup(column, VintageGroup.UNDATED)
    }

  /**
   * Registers [column] in [group], REFUSING a second claim on it.
   *
   * A plain `put` would let a column registered in two groups resolve itself,
   * last writer winning, while the KDoc above says that cannot be written down
   * -- and the pin test would then agree with whichever year came second. The
   * claim is enforced here instead of asserted in prose.
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
      "column=[$column] is dated by two vintage groups ([$previous] and [$group]): a Scorecard cell is " +
        "served under ONE academic year, so the registry may state it once"
    }
  }

  /**
   * The eight published-charge cells: the columns `mapScorecardPrices` writes,
   * every one of them stamped `CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR`.
   *
   * DERIVED from [VINTAGE_GROUP_BY_VARIABLE], never re-listed: the group is
   * what carries a year, the publisher dates all eight to one academic year,
   * and `ScorecardDictionaryPinTest` pins that year against the committed
   * dictionary transcription group by group.
   */
  val PUBLISHED_CHARGE_VARIABLES: Set<String> = variablesOf(VintageGroup.PUBLISHED_CHARGE)

  /**
   * `COSTT4_A` and the whole net-price family: the cells `mapScorecardStats`
   * stamps `CanonicalMoneyLoader.BLENDED_AVERAGE_VINTAGE`. The publisher dates
   * these one year EARLIER than the published charges above -- they describe a
   * cohort that has already been through a year, not a price list -- which is
   * why the fill carries two year constants rather than one.
   *
   * DERIVED from [VINTAGE_GROUP_BY_VARIABLE], like the group above it.
   */
  val BLENDED_AVERAGE_VARIABLES: Set<String> = variablesOf(VintageGroup.BLENDED_AVERAGE)

  /**
   * The three cells the fill stamps `CanonicalMoneyLoader.VINTAGE_UNDATED`.
   *
   * Two of them are undated by the PUBLISHER: `GRAD_DEBT_MDN` pools NSLDS
   * FY2020 and FY2021, `MD_EARN_WNE_P10` pools two Treasury cohorts, and
   * neither has an academic year to carry. `PCTPELL` is the odd one out -- the
   * dictionary DOES date it (AcadYr 2023-24) and the fill stamps it undated
   * anyway. That divergence is deliberate and scoped (RFC 183 D3: moving a
   * currently-undated key onto a dated one changes who wins that key, and
   * nobody has measured it); it is pinned by `ScorecardDictionaryPinTest` so it
   * stays a known debt rather than becoming folklore.
   */
  val UNDATED_VARIABLES: Set<String> = variablesOf(VintageGroup.UNDATED)

  /**
   * Every Scorecard `source_variable` string the canonical fill CAN WRITE (RFC
   * 179) -- the registry the tier map must answer for, in the module that owns
   * the column names.
   *
   * The loader does not merely agree with this set, it is BOUND to it:
   * [loadedVariableOf] is the only door a Scorecard cell id passes through on its
   * way into a row, and it refuses a string that is not here. So a thirteenth
   * column added to `mapScorecardPrices` or `mapScorecardStats` cannot write a
   * cell this registry does not name, and the closure test derives its expected
   * key space from here rather than re-typing it -- which is how a hand-typed
   * list left every assertion green while the loader wrote an untiered string.
   *
   * DERIVED from [VINTAGE_GROUP_BY_VARIABLE] rather than re-listed: a column
   * belongs to exactly one vintage group, because the year the fill stamps it
   * is not optional. A string that reached this registry without joining a
   * group would be a cell written with no stated vintage at all -- and a union
   * of three sets could still represent one column claiming two years.
   */
  val LOADED_VARIABLES: Set<String> = VINTAGE_GROUP_BY_VARIABLE.keys

  /** The registered columns of one vintage group, in registry order. */
  private fun variablesOf(group: VintageGroup): Set<String> =
    // `.toSet()`, because `filterValues` returns a plain `LinkedHashMap` whose
    // `.keys` is a live MUTABLE view at runtime: the three sets it builds are
    // shared `object`-level vals, and an unchecked cast could shrink one of
    // them under every later reader. A snapshot owns no view.
    VINTAGE_GROUP_BY_VARIABLE.filterValues { it == group }.keys.toSet()

  /**
   * The academic year the fill stamps [column] with -- null for the
   * [VintageGroup.UNDATED] cells, which carry no year at all.
   *
   * The ONE place a Scorecard cell's year is decided, and the seam the fill
   * itself reads: the write sites used to type `PUBLISHED_PRICE_YEAR` or
   * `BLENDED_AVERAGE_VINTAGE` by hand, so a ninth charge column could join
   * [VINTAGE_GROUP_BY_VARIABLE] and still be stamped the other cohort's year
   * with every test green. Now the column decides, and the group is the
   * decision.
   *
   * FATAL for an unregistered column, on [loadedVariableOf]'s own precedent:
   * an unstamped cell is a figure served with no stated year.
   */
  fun stampedYearOf(column: String): AcademicYear? =
    when (VINTAGE_GROUP_BY_VARIABLE[column] ?: throw UnregisteredColumnException(column, LOADED_VARIABLES)) {
      VintageGroup.PUBLISHED_CHARGE -> CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR
      VintageGroup.BLENDED_AVERAGE -> CanonicalMoneyLoader.BLENDED_AVERAGE_VINTAGE
      VintageGroup.UNDATED -> CanonicalMoneyLoader.VINTAGE_UNDATED
    }

  /**
   * As [stampedYearOf], for a cell whose year is REQUIRED -- every
   * `price_figures` row, whose `academic_year` is NOT NULL. An undated column
   * reaching a price write is a mapping error, not a missing year.
   */
  fun datedYearOf(column: String): AcademicYear =
    stampedYearOf(column) ?: throw UndatedColumnException(column, VINTAGE_GROUP_BY_VARIABLE.getValue(column))

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
        "from it, so there is nothing to add there), transcribe its cohort year into " +
        "db/seed/scorecard/dictionary-variable-sources.csv, and give it a tier in " +
        "AssuranceTier.SCORECARD_TIERS (RFC 179/183)",
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
        "carries no academic year -- move it to a dated group in " +
        "ScorecardInstitutionColumns.VINTAGE_GROUP_BY_VARIABLE, or stop writing it as a price " +
        "(RFC 183): a price is served under a year or it is not served",
    )
}
