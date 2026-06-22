package ed.unicoach.db.models

/**
 * The kind of belief a claim expresses. Persisted as the lowercase [value]
 * string matching the `claims_kind_check` CHECK.
 */
enum class ClaimKind(
  val value: String,
) {
  GOAL("goal"),
  PREFERENCE("preference"),
  CONSTRAINT("constraint"),
  FACT("fact"),
  CONCERN("concern"),
  ;

  companion object {
    fun fromValue(value: String): ClaimKind? = entries.find { it.value == value }
  }
}
