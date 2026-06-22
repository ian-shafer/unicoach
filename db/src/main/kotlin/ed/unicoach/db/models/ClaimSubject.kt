package ed.unicoach.db.models

/**
 * What a claim is about. Persisted as the lowercase [value] string matching the
 * `claims_subject_check` CHECK.
 */
enum class ClaimSubject(
  val value: String,
) {
  STUDENT("student"),
  FAMILY("family"),
  COLLEGE("college"),
  APPLICATION("application"),
  ;

  companion object {
    fun fromValue(value: String): ClaimSubject? = entries.find { it.value == value }
  }
}
