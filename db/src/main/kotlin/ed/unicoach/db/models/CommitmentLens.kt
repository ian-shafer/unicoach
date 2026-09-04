package ed.unicoach.db.models

/**
 * The reflection lens that produced a commitment. Persisted as the lowercase
 * [value] string matching the `commitments_lens_check` CHECK.
 */
enum class CommitmentLens(
  val value: String,
) {
  GAP("gap"),
  TIMING("timing"),
  CONTRADICTION("contradiction"),

  /** The deterministic share-nudge commitment (RFC 160): code-written, never LLM-proposed. */
  SHARE_REPORT("share_report"),
  ;

  companion object {
    fun fromValue(value: String): CommitmentLens? = entries.find { it.value == value }
  }
}
