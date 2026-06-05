package ed.unicoach.student

import ed.unicoach.db.models.Student
import ed.unicoach.error.FieldError

sealed interface UpdateStudentResult {
  data class Success(
    val student: Student,
  ) : UpdateStudentResult

  data class ValidationFailure(
    val fieldErrors: List<FieldError>,
  ) : UpdateStudentResult

  data object NotFound : UpdateStudentResult

  data object VersionConflict : UpdateStudentResult
}
