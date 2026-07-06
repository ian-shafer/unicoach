package ed.unicoach.db.models

/**
 * The outcome of one billed synthesis pass, a sealed ADT carrying the payload
 * that outcome — and only that outcome — has (RFC 101). [Applied] wrote
 * commitments and advanced the freshness marker, carrying its two write counts;
 * [Failed] billed tokens but produced unparseable output (marker unchanged,
 * write counts zero), carrying the parse-failure
 * [Failed.category]/[Failed.reason]. An `applied`-with-a-reason or
 * a `failed`-without-one is unrepresentable. Persisted as the lowercase [value]
 * string matching the `synthesis_runs_outcome_check` CHECK; the DAO is the sole
 * boundary that maps this ADT to and from the flat row columns.
 */
sealed interface SynthesisOutcome {
  val value: String

  data class Applied(
    val commitmentsWritten: Int,
    val commitmentsDropped: Int,
  ) : SynthesisOutcome {
    override val value: String get() = "applied"
  }

  data class Failed(
    val category: JsonParseFailureCategory,
    val reason: String,
  ) : SynthesisOutcome {
    override val value: String get() = "failed"
  }
}
