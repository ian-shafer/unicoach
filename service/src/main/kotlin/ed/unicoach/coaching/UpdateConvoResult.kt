package ed.unicoach.coaching

import ed.unicoach.db.models.ConvoWithActivity
import ed.unicoach.error.FieldError

sealed interface UpdateConvoResult {
  data class Success(
    val listing: ConvoWithActivity,
  ) : UpdateConvoResult

  data class ValidationFailure(
    val fieldErrors: List<FieldError>,
  ) : UpdateConvoResult

  data object NotFound : UpdateConvoResult
}
