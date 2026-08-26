package ed.unicoach.db.models

/**
 * The name-search projection of a college (RFC 137): just enough for a picker
 * row — the id to add with, the name to show, and "City, ST" to disambiguate
 * same-named institutions. Deliberately narrower than [CollegeMatch], which is
 * the structured-search shape for the chat tool.
 */
data class CollegeSummary(
  val id: CollegeId,
  val name: String,
  val city: String,
  val state: String,
)
