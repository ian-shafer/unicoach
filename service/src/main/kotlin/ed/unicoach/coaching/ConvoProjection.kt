package ed.unicoach.coaching

import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.ConvoRequestKind
import ed.unicoach.db.models.ConvoTurn
import ed.unicoach.db.models.ConvoTurnId
import ed.unicoach.db.models.LlmCallOutcome
import ed.unicoach.db.models.LlmResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * One logical user/coach exchange after collapsing any tool excursion into a
 * single visible unit: the opening `kind = 'user'` request paired with the
 * turn's final answer (the first `completed` response with
 * `stop_reason != 'tool_use'`). The intermediate `tool_use` responses and
 * `tool_result` requests an excursion writes are never surfaced.
 *
 * Since RFC 106 both content sides come from the joined generic call log: the
 * request I/O and the response live in `llm_requests` / `llm_responses`.
 * [userContent] is this turn's new user input — the content-block array of the
 * last user message in the opener call's request `messages` array (the stateless
 * API resends prior history, so only the tail is this turn's input).
 * [finalContent] is the completed assistant content the closing response
 * carried, lifted from its [LlmCallOutcome.Completed] arm so consumers read both
 * without re-destructuring.
 */
data class VisibleExchange(
  val userRequest: ConvoRequest,
  val userContent: JsonElement,
  val finalResponse: LlmResponse,
  val finalContent: JsonElement,
)

/**
 * The single owner of the visible-exchange collapse shared by the three
 * after-the-fact readers: model replay ([CoachingService.visibleHistory]), the
 * REST transcript ([CoachingService.listTurns]), and the extraction transcript
 * ([ed.unicoach.coaching.extraction.ExtractionService]). Keeping the collapse in
 * one place keeps all three consistent about what a "message" is.
 */
object ConvoProjection {
  private val logger = LoggerFactory.getLogger(ConvoProjection::class.java)

  /**
   * The Anthropic `stop_reason` that keeps an exchange open: a response bearing
   * it is a mid-excursion tool call, not the turn's final answer. Single owner
   * shared with the loop's dispatch check ([CoachingService]).
   */
  const val TOOL_USE_STOP_REASON = "tool_use"

  /**
   * Groups [turns] by `turn_id` into logical turns, preserving each group's
   * first-appearance (creation, `convo_requests.id`) order and ordering the rows
   * within a group by `id`. This is the single owner of the turn_id grouping
   * shared by every after-the-fact reader — [visibleExchanges] here and the
   * extraction window's whole-turn cap
   * ([ed.unicoach.coaching.extraction.ExtractionService]) — so the two never
   * drift on what a logical turn is. Keyed on the [ConvoTurnId] value class (its
   * own namespace), never a raw `Long`.
   */
  fun groupByTurnId(turns: List<ConvoTurn>): Map<ConvoTurnId, List<ConvoTurn>> {
    val ordered = turns.sortedBy { it.request.id.value }
    // Preserve first-appearance (creation) order of the turn_id groups.
    val groups = LinkedHashMap<ConvoTurnId, MutableList<ConvoTurn>>()
    for (turn in ordered) {
      groups.getOrPut(turn.request.turnId) { mutableListOf() }.add(turn)
    }
    return groups
  }

  /**
   * Groups [turns] by `turn_id` (in `convo_requests.id` / creation order) and
   * emits one [VisibleExchange] per group. For each group: `userRequest` is the
   * `kind = 'user'` opener; the closing answer is the group's first `completed`
   * response whose `stop_reason != 'tool_use'` (the excursion's closing answer).
   * The exchange is emitted only if that completed closing response exists; a
   * group still open (no non-`tool_use` completed response yet) or whose closing
   * call did not complete (a failed/cancelled terminal, which has no content) is
   * omitted — preserving the "failed turns are invisible" behavior.
   * `kind = 'tool_result'` requests and `tool_use` responses never surface on
   * their own.
   *
   * Grouping on the explicit `turn_id` is the turn boundary itself, so — unlike a
   * positional walk that is correct only while a turn's rows are strictly
   * contiguous in `id` order — a continuation stamped with a fresh `turn_id`
   * fragments into a phantom open group this projection drops, rather than
   * silently swallowing an unrelated later turn's answer.
   */
  fun visibleExchanges(turns: List<ConvoTurn>): List<VisibleExchange> {
    val exchanges = mutableListOf<VisibleExchange>()
    for (group in groupByTurnId(turns).values) {
      val openerTurn = group.firstOrNull { it.request.kind == ConvoRequestKind.USER } ?: continue
      // The opener's user input for this turn: the content-block array of the last
      // user message in its joined call request. The opener always joins a call
      // (llm_request_id is NOT NULL), so a missing call is a corrupt row.
      val userContent =
        openerTurn.call?.let { lastUserMessageContent(it.request.content) }
          ?: run {
            logger.warn("opener request [{}] has no user message; dropping exchange", openerTurn.request.id.asString)
            continue
          }
      // The excursion's closing answer: the group's first non-tool_use response. A
      // tool_use response is a mid-excursion call, never the final answer. The
      // stop_reason lives on a completed outcome; a non-completed terminal
      // (failed/cancelled) has no stop_reason and so is never a tool_use call —
      // it is a candidate closing response that fails the completed check below.
      val closing =
        group
          .mapNotNull { it.call?.response }
          .firstOrNull { (it.outcome as? LlmCallOutcome.Completed)?.stopReason != TOOL_USE_STOP_REASON }
          ?: continue
      // Emit only on a completed close; a failed/cancelled close is invisible.
      val outcome = closing.outcome
      if (outcome is LlmCallOutcome.Completed) {
        exchanges.add(
          VisibleExchange(
            userRequest = openerTurn.request,
            userContent = userContent,
            finalResponse = closing,
            finalContent = outcome.content,
          ),
        )
      }
    }

    return exchanges
  }

  /**
   * The content-block array of the last `role = user` message in a call request's
   * `messages` array — this turn's new user input (prior history is resent by the
   * stateless API, so only the tail is new). Returns null when the array carries
   * no user message (a corrupt/empty request), which drops the group.
   */
  private fun lastUserMessageContent(messages: JsonArray): JsonElement? =
    messages
      .lastOrNull { (it as? JsonObject)?.get("role")?.let { role -> role.jsonPrimitiveOrNull() == "user" } == true }
      ?.let { (it as JsonObject)["content"] }

  private fun JsonElement.jsonPrimitiveOrNull(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}
