package ed.unicoach.coaching

import ed.unicoach.db.models.ConvoTurn

/**
 * Outcome of [CoachingService.listTurns]. [Found] carries the visible turns
 * (success responses only) in chronological order; [NotFound] folds the
 * missing/soft-deleted/foreign convo case.
 */
sealed interface ListTurnsResult {
  data class Found(
    val turns: List<ConvoTurn>,
  ) : ListTurnsResult

  data object NotFound : ListTurnsResult
}
