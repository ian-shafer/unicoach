package ed.unicoach.db.models

/**
 * The outcome of one billed extraction pass. `APPLIED` advanced the watermark
 * and wrote memory; `FAILED` billed tokens but produced unusable output
 * (watermark unchanged, write counts zero). Persisted as the lowercase [value]
 * string matching the `extraction_runs_outcome_check` CHECK.
 */
enum class ExtractionOutcome(
  val value: String,
) {
  APPLIED("applied"),
  FAILED("failed"),
  ;

  companion object {
    fun fromValue(value: String): ExtractionOutcome? = entries.find { it.value == value }
  }
}
