package ed.unicoach.coaching

import ed.unicoach.coaching.budget.Entitlement
import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.error.FieldError
import kotlinx.coroutines.flow.Flow

/**
 * Outcome of [CoachingService.startConvo]. [Started] carries the just-created
 * convo, the persisted user turn, and the cold reply flow whose collection
 * executes the turn (two-phase: pre-flight returns synchronously, the reply
 * streams on collection).
 *
 * [BudgetExhausted] is decided in that same pre-flight, before anything is
 * persisted (RFC 109).
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

  /**
   * The student's lifetime coaching allowance is spent. No convo row,
   * `convo_requests` row, or `llm_requests` row was written.
   *
   * Carries the [Entitlement] the refusal was decided on, as every other
   * budget-refused pass does, so the caller can log the spend and allowance it
   * refused at. It stays out of the wire body (RFC 109: no dollars, tokens, or
   * provider names to the client) — this is the operator's trace, and the only
   * one there is, since a refusal writes no row anywhere.
   */
  data class BudgetExhausted(
    val entitlement: Entitlement,
  ) : StartConvoResult
}
