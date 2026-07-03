package ed.unicoach.db.models

/**
 * The outcome of one billed synthesis pass. `APPLIED` wrote commitments and
 * advanced the freshness marker; `FAILED` billed tokens but produced unusable
 * output (marker unchanged, write counts zero). Persisted as the lowercase
 * [value] string matching the `synthesis_runs_outcome_check` CHECK.
 */
enum class SynthesisOutcome(
  val value: String,
) {
  APPLIED("applied"),
  FAILED("failed"),
  ;

  companion object {
    fun fromValue(value: String): SynthesisOutcome? = entries.find { it.value == value }
  }
}
