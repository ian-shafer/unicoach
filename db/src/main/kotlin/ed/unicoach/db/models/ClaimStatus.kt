package ed.unicoach.db.models

/**
 * Lifecycle state of a claim. Persisted as the lowercase [value] string matching
 * the `claims_status_check` CHECK.
 */
enum class ClaimStatus(
  val value: String,
) {
  ACTIVE("active"),
  SUPERSEDED("superseded"),
  RETRACTED("retracted"),
  ;

  companion object {
    fun fromValue(value: String): ClaimStatus? = entries.find { it.value == value }
  }
}
