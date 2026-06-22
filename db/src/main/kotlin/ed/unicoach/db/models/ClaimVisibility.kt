package ed.unicoach.db.models

/**
 * Whether a claim is surfaced to the student unprompted. `internal` means "not
 * surfaced unprompted" (coaching-process notes), not "hidden from the student"
 * (RFC 66). Persisted as the lowercase [value] string matching the
 * `claims_visibility_check` CHECK.
 */
enum class ClaimVisibility(
  val value: String,
) {
  STUDENT_VISIBLE("student_visible"),
  INTERNAL("internal"),
  ;

  companion object {
    fun fromValue(value: String): ClaimVisibility? = entries.find { it.value == value }
  }
}
