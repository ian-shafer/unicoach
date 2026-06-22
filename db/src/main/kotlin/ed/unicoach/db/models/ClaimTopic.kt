package ed.unicoach.db.models

/**
 * The coaching topic a claim falls under. Persisted as the lowercase [value]
 * string matching the `claims_topic_check` CHECK.
 */
enum class ClaimTopic(
  val value: String,
) {
  ACADEMICS("academics"),
  ACTIVITIES("activities"),
  FINANCES("finances"),
  LOCATION("location"),
  CAREER("career"),
  TIMELINE("timeline"),
  WELLBEING("wellbeing"),
  ;

  companion object {
    fun fromValue(value: String): ClaimTopic? = entries.find { it.value == value }
  }
}
