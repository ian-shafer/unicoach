package ed.unicoach.db.models

/**
 * Lifecycle state of a fit-lens suggestion (RFC 98): `OPEN` until it is raised in
 * the next-session opener (`SURFACED`). Persisted as the lowercase [value] string
 * matching the `fit_suggestions_status_check` CHECK.
 */
enum class FitSuggestionStatus(
  val value: String,
) {
  OPEN("open"),
  SURFACED("surfaced"),
  ;

  companion object {
    fun fromValue(value: String): FitSuggestionStatus? = entries.find { it.value == value }
  }
}
