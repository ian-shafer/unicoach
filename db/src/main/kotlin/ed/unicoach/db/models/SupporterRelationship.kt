package ed.unicoach.db.models

/**
 * Closed enum backing `supporter_student_links.relationship` (RFC 221).
 *
 * Four words, and [SUPPORTER] is the catch-all rather than `other`: a person who
 * supports a student is described by what they do, not by failing to match the
 * first three.
 *
 * It is the ONE Kotlin spelling of the vocabulary the
 * `supporter_student_links_relationship_check` CHECK admits (the repo's TEXT +
 * CHECK + one enum convention). The two lists are held together by a test that
 * inserts every member value, so one of them cannot grow alone.
 */
enum class SupporterRelationship(
  val value: String,
) {
  PARENT("parent"),
  GUARDIAN("guardian"),
  GRANDPARENT("grandparent"),
  SUPPORTER("supporter"),
  ;

  companion object {
    fun fromValue(value: String): SupporterRelationship? = entries.find { it.value == value }
  }
}
