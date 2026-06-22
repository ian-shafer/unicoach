package ed.unicoach.db.models

/**
 * Whether a claim was stated by the student or inferred by the coach. Persisted
 * as the lowercase [value] string matching the `claims_origin_check` CHECK
 * (project convention, not a native pg enum).
 */
enum class ClaimOrigin(
  val value: String,
) {
  STUDENT_STATED("student_stated"),
  COACH_INFERRED("coach_inferred"),
  ;

  companion object {
    fun fromValue(value: String): ClaimOrigin? = entries.find { it.value == value }
  }
}
