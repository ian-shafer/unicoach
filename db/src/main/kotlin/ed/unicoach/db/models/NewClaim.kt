package ed.unicoach.db.models

/**
 * Insert input for a fresh `claims` row. Omits the DB-generated id, timestamps,
 * and lifecycle pointers; `status` defaults to `active` and `confidence` to 0 in
 * the DB (a freshly-created claim's confidence is recomputed from its support
 * set in the same write transaction).
 */
data class NewClaim(
  val studentId: StudentId,
  val origin: ClaimOrigin,
  val kind: ClaimKind,
  val subject: ClaimSubject,
  val topic: ClaimTopic,
  val visibility: ClaimVisibility,
  val statement: String,
)
