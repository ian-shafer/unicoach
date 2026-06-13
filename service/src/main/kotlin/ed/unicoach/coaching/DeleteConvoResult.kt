package ed.unicoach.coaching

sealed interface DeleteConvoResult {
  data object Success : DeleteConvoResult

  data object NotFound : DeleteConvoResult
}
