package ed.unicoach.coaching

import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.error.FieldError
import kotlinx.coroutines.flow.Flow

/**
 * Outcome of [CoachingService.postTurn]. Shares the two-phase shape of
 * [StartConvoResult.Started] and adds [NotFound] for the ownership/existence
 * pre-flight (missing, soft-deleted, or foreign convo).
 */
sealed interface PostTurnResult {
  data class Started(
    val convo: Convo,
    val userTurn: ConvoRequest,
    val reply: Flow<ReplyEvent>,
  ) : PostTurnResult

  data class ValidationFailure(
    val fieldErrors: List<FieldError>,
  ) : PostTurnResult

  data object NotFound : PostTurnResult
}
