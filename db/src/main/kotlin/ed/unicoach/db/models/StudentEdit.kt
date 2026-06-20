package ed.unicoach.db.models

/**
 * Update-input record for [Student], sibling of the [NewStudent] creation input.
 * Carries the entity identity, the expected OCC [version], and only the mutable
 * business field.
 */
data class StudentEdit(
  val id: StudentId,
  val version: Int,
  val expectedHighSchoolGraduationDate: PartialDate,
)
