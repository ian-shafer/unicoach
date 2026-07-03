package ed.unicoach.db.models

/**
 * Lifecycle state of a commitment: `OPEN` until it is surfaced to the student
 * (`FULFILLED`) or its basis went away (`DROPPED`). Persisted as the lowercase
 * [value] string matching the `commitments_status_check` CHECK.
 */
enum class CommitmentStatus(
  val value: String,
) {
  OPEN("open"),
  FULFILLED("fulfilled"),
  DROPPED("dropped"),
  ;

  companion object {
    fun fromValue(value: String): CommitmentStatus? = entries.find { it.value == value }
  }
}
