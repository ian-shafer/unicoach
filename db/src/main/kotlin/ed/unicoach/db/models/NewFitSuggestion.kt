package ed.unicoach.db.models

/**
 * Insert input for a fresh `fit_suggestions` row (RFC 98). Omits the DB-generated
 * id, timestamps, and surfacing columns; `status` defaults to `open` in the DB.
 */
data class NewFitSuggestion(
  val studentId: StudentId,
  val collegeId: CollegeId,
  val rationale: String,
)
