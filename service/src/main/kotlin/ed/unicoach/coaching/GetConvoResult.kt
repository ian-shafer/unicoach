package ed.unicoach.coaching

import ed.unicoach.db.models.ConvoWithActivity

sealed interface GetConvoResult {
  data class Found(
    val listing: ConvoWithActivity,
  ) : GetConvoResult

  data object NotFound : GetConvoResult
}
