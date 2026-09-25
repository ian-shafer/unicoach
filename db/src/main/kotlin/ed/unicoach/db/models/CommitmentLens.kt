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

  /**
   * The deterministic parent-invite commitment (RFC 240): code-written, never
   * LLM-proposed, and written only for a student who has ALREADY shared the
   * family cost report — so it and [SHARE_REPORT] are mutually exclusive by the
   * synthesis predicate, never by anything this enum can say.
   */
  INVITE_SUPPORTER("invite_supporter"),
  ;

  companion object {
    fun fromValue(value: String): CommitmentLens? = entries.find { it.value == value }
  }
}
