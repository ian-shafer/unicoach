package ed.unicoach.db.models

/**
 * The outcome of one billed extraction pass, a sealed ADT carrying the payload
 * that outcome — and only that outcome — has (RFC 101). [Applied] advanced the
 * watermark and wrote memory, carrying its three write counts; [Failed] billed
 * tokens but produced unparseable output (watermark unchanged, write counts
 * zero), carrying the parse-failure [Failed.category]/[Failed.reason].
 * An `applied`-with-a-reason or a `failed`-without-one is unrepresentable.
 * Persisted as the lowercase [value] string matching the
 * `extraction_runs_outcome_check` CHECK; the DAO is the sole boundary that maps
 * this ADT to and from the flat row columns.
 */
sealed interface ExtractionOutcome {
  val value: String

  data class Applied(
    val observationsWritten: Int,
    val claimsWritten: Int,
    val claimsSuperseded: Int,
  ) : ExtractionOutcome {
    override val value: String get() = "applied"
  }

  data class Failed(
    val category: JsonParseFailureCategory,
    val reason: String,
  ) : ExtractionOutcome {
    override val value: String get() = "failed"
  }
}
