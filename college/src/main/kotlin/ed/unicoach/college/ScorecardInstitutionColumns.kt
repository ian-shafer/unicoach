package ed.unicoach.college

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
   * The net-price family is expanded exactly as the fill expands it: one column
   * per income band per `CONTROL` arm, because the stored key IS the literal
   * string.
   */
  val LOADED_VARIABLES: Set<String> =
    buildSet {
      addAll(
        listOf(
          TUITIONFEE_IN,
          TUITIONFEE_OUT,
          ROOMBOARD_ON,
          ROOMBOARD_OFF,
          BOOKSUPPLY,
          OTHEREXPENSE_ON,
          OTHEREXPENSE_OFF,
          OTHEREXPENSE_FAM,
          COSTT4_A,
          PCTPELL,
          GRAD_DEBT_MDN,
          MD_EARN_WNE_P10,
        ),
      )
      for (suffix in listOf(SUFFIX_PUBLIC, SUFFIX_PRIVATE)) {
        add("$NET_PRICE_BASE$suffix")
        for (band in IncomeBand.entries) add("$NET_PRICE_BASE${band.bandDigit}$suffix")
      }
    }

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
      "the Scorecard fill may only write a registered cell id: column=[$column] -- add it to " +
        "ScorecardInstitutionColumns.LOADED_VARIABLES and give it a tier in " +
        "AssuranceTier.SCORECARD_TIERS (RFC 179)",
    )
}
