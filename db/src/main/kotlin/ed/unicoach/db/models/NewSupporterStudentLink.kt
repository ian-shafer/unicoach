package ed.unicoach.db.models

/**
 * The insert input for a `supporter_student_links` row (RFC 221). The id is left
 * to the column's `uuidv7()` default.
 */
data class NewSupporterStudentLink(
  val supporterId: SupporterId,
  val studentId: StudentId,
  val relationship: SupporterRelationship,
)
