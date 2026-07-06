package ed.unicoach.db.models

/**
 * The outcome of one completed fit-lens pass (RFC 98), a sealed ADT carrying the
 * payload that outcome — and only that outcome — has (RFC 101). [Applied]
 * completed a full pass and advanced the freshness marker (with or without a
 * suggestion written), carrying its [Applied.suggestionsWritten] count; [Failed]
 * billed tokens but produced unusable model output (marker unchanged,
 * `suggestions_written` zero), carrying the
 * [Failed.category]/[Failed.reason]. [Failed.category] is a
 * [FitLensFailureCategory] (RFC 98's `malformed_output`/`invalid_content`
 * split), the fit-lens table's own category type — the ADT is per-table. An
 * `applied`-with-a-reason or a `failed`-without-one is unrepresentable.
 * Persisted as the lowercase [value] string matching the
 * `fit_lens_runs_outcome_check` CHECK; the DAO is the sole boundary that maps
 * this ADT to and from the flat row columns.
 */
sealed interface FitLensOutcome {
  val value: String

  data class Applied(
    val suggestionsWritten: Int,
  ) : FitLensOutcome {
    override val value: String get() = "applied"
  }

  data class Failed(
    val category: FitLensFailureCategory,
    val reason: String,
  ) : FitLensOutcome {
    override val value: String get() = "failed"
  }
}
