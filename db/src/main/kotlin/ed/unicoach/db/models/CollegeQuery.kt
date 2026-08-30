package ed.unicoach.db.models

/**
 * Typed filter for [ed.unicoach.db.dao.CollegesDao.search] (RFC 67). Every field
 * except [limit] is nullable; an absent field is an unconstrained axis. List
 * fields are OR-sets (any member matches). [limit] is mandatory and clamped to
 * `1..25` by the service boundary before reaching the DAO.
 *
 * [sortBy] (RFC 139) selects the result ordering; it never filters — rows NULL
 * on the sort key sink to the end (`NULLS LAST`), they do not vanish (brief
 * 0004 D11). Every ordering ends with the `ipeds_unit_id ASC` tiebreak, so the order
 * is total and deterministic. [credentialLevel] narrows the program join (which
 * program credential must exist), so it is only meaningful alongside
 * [cipPrefix]; the service boundary rejects it without one. It is a named
 * [CredentialLevel] rather than a raw CREDLEV code, so an out-of-domain level
 * is unrepresentable and no reader has to remember which number is a
 * bachelor's.
 */
data class CollegeQuery(
  val cipPrefix: String? = null,
  val states: List<String>? = null,
  val region: Int? = null,
  val locales: List<Int>? = null,
  val control: List<Int>? = null,
  val minUndergradEnrollmentHeadcount: Int? = null,
  val maxUndergradEnrollmentHeadcount: Int? = null,
  val minAdmissionRateShare: Double? = null,
  val maxAdmissionRateShare: Double? = null,
  val maxNetPricePerYearUsd: Int? = null,
  val minCompletionRate150pct4yrShare: Double? = null,
  val sortBy: SortBy = SortBy.ENROLLMENT_DESC,
  val credentialLevel: CredentialLevel? = null,
  val limit: Int,
) {
  /** Result orderings for [ed.unicoach.db.dao.CollegesDao.search] (RFC 139). */
  enum class SortBy {
    /** Today's default: biggest undergraduate enrollment first. */
    ENROLLMENT_DESC,

    /** Most selective first (lowest admission rate). */
    ADMISSION_RATE_SHARE_ASC,

    /** Cheapest first (lowest average annual net price). */
    NET_PRICE_PER_YEAR_USD_ASC,

    /** Best completion first (highest 6-year graduation rate). */
    COMPLETION_RATE_150PCT_4YR_SHARE_DESC,

    /** Alphabetical by institution name. */
    NAME_ASC,
  }
}
