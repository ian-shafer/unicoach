package ed.unicoach.db.models

/**
 * The general shape of why a fit-lens call's output was rejected, on a
 * `failed` `fit_lens_runs` row (RFC 98). Deliberately coarse (HTTP-status-code
 * style: broad classes, not one entry per parse site) — a new, more specific
 * category is only worth adding once volume on a given failure shows it's
 * common enough to warrant its own bucket. Persisted as the lowercase [value]
 * string matching the `fit_lens_runs_failure_category_check` CHECK. Always
 * paired with a free-text `failure_reason` carrying the interpolated
 * diagnostic (the offending collegeId, a length count, a raw JSON error) that
 * the category alone would lose.
 */
enum class FitLensFailureCategory(
  val value: String,
) {
  /** The raw output wasn't valid, parseable JSON matching the expected shape. */
  MALFORMED_OUTPUT("malformed_output"),

  /** The output parsed fine, but its content violated a business rule. */
  INVALID_CONTENT("invalid_content"),
  ;

  companion object {
    fun fromValue(value: String): FitLensFailureCategory? = entries.find { it.value == value }
  }
}
