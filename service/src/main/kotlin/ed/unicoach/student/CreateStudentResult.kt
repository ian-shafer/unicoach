package ed.unicoach.student

import ed.unicoach.db.models.Student
import ed.unicoach.error.FieldError

sealed interface CreateStudentResult {
  data class Success(
    val student: Student,
  ) : CreateStudentResult

  data class ValidationFailure(
    val fieldErrors: List<FieldError>,
  ) : CreateStudentResult

  data object AlreadyExists : CreateStudentResult
}
