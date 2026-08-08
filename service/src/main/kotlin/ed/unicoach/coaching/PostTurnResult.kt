package ed.unicoach.coaching

import ed.unicoach.coaching.budget.Entitlement
import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.error.FieldError
import kotlinx.coroutines.flow.Flow

/**
 * Outcome of [CoachingService.postTurn]. Shares the two-phase shape of
 * [StartConvoResult.Started] and adds [NotFound] for the ownership/existence
 * pre-flight (missing, soft-deleted, or foreign convo).
 *
 * [NotFound] outranks [BudgetExhausted]: a convo the caller does not own never
 * leaks its owner's budget state (RFC 109).
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

  /**
   * The student's lifetime coaching allowance is spent. No `convo_requests` row
   * and no `llm_requests` row was written.
   *
   * Carries the deciding [Entitlement] for the same reason
   * [StartConvoResult.BudgetExhausted] does: it is the operator's only trace of
   * a refusal, and it never reaches the wire.
   */
  data class BudgetExhausted(
    val entitlement: Entitlement,
  ) : PostTurnResult
}
