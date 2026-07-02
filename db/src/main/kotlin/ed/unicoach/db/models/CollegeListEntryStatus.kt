package ed.unicoach.db.models

/** Closed enum backing `college_list_entries.status` (RFC 91). */
enum class CollegeListEntryStatus(
  val value: String,
) {
  CONSIDERING("considering"),
  APPLYING("applying"),
  ADMITTED("admitted"),
  REJECTED("rejected"),
  ;

  companion object {
    fun fromValue(value: String): CollegeListEntryStatus? = entries.find { it.value == value }
  }
}
