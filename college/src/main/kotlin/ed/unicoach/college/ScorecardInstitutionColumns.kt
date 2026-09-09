package ed.unicoach.college

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
}
