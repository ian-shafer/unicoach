package ed.unicoach.student

sealed interface DeleteStudentResult {
  data object Success : DeleteStudentResult

  data object NotFound : DeleteStudentResult
}
