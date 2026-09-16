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

  /**
   * The submitted date of birth was well formed and below the 13+ floor
   * (RFC 222). Its own arm, not a [ValidationFailure]: the request was valid and
   * was refused on its merits, which the route renders as 403 rather than 400.
   * Nothing was written.
   */
  data object UnderMinimumAge : CreateStudentResult

  data object AlreadyExists : CreateStudentResult
}
