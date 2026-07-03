package ed.unicoach.db.models

/**
 * The surfacing condition for a commitment. Today the only member is
 * `NEXT_SESSION` (the opener rides the student's next conversation); a future
 * time/event trigger widens this by migration. Persisted as the lowercase
 * [value] string matching the `commitments_trigger_kind_check` CHECK.
 */
enum class CommitmentTriggerKind(
  val value: String,
) {
  NEXT_SESSION("next_session"),
  ;

  companion object {
    fun fromValue(value: String): CommitmentTriggerKind? = entries.find { it.value == value }
  }
}
