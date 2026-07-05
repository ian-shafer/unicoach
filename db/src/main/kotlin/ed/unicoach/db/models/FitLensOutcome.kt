package ed.unicoach.db.models

/**
 * The outcome of one completed fit-lens pass (RFC 98). `APPLIED` completed a full
 * pass and advanced the freshness marker (with or without a suggestion written);
 * `FAILED` billed tokens but produced unusable model output (marker unchanged,
 * `suggestions_written` zero). Persisted as the lowercase [value] string matching
 * the `fit_lens_runs_outcome_check` CHECK.
 */
enum class FitLensOutcome(
  val value: String,
) {
  APPLIED("applied"),
  FAILED("failed"),
  ;

  companion object {
    fun fromValue(value: String): FitLensOutcome? = entries.find { it.value == value }
  }
}
