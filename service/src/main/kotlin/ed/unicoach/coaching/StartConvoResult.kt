package ed.unicoach.coaching

import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.error.FieldError
import kotlinx.coroutines.flow.Flow

/**
 * Outcome of [CoachingService.startConvo]. [Started] carries the just-created
 * convo, the persisted user turn, and the cold reply flow whose collection
 * executes the turn (two-phase: pre-flight returns synchronously, the reply
 * streams on collection).
 */
sealed interface StartConvoResult {
  data class Started(
    val convo: Convo,
    val userTurn: ConvoRequest,
    val reply: Flow<ReplyEvent>,
  ) : StartConvoResult

  data class ValidationFailure(
    val fieldErrors: List<FieldError>,
  ) : StartConvoResult
}
