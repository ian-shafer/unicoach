package ed.unicoach.db.models

/**
 * The opener projection (RFC 98): an open `fit_suggestions` row joined to its
 * `colleges` display fields, so the next-session opener can compose the "I found
 * a school you'd love: <name>" line and mark the row surfaced by [id] without a
 * second lookup.
 */
data class FitSuggestionForOpener(
  val id: FitSuggestionId,
  val collegeName: String,
  val city: String,
  val state: String,
  val rationale: String,
)
