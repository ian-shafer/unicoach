package ed.unicoach.coaching

import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.ConvoRequestKind
import ed.unicoach.db.models.ConvoResponse
import ed.unicoach.db.models.ConvoTurn
import ed.unicoach.db.models.ConvoTurnId

/**
 * One logical user/coach exchange after collapsing any tool excursion into a
 * single visible unit: the opening `kind = 'user'` request paired with the
 * turn's final answer (the first response with `stop_reason != 'tool_use'`).
 * The intermediate `tool_use` responses and `tool_result` requests an excursion
 * writes are never surfaced.
 */
data class VisibleExchange(
  val userRequest: ConvoRequest,
  val finalResponse: ConvoResponse,
)

/**
 * The single owner of the visible-exchange collapse shared by the three
 * after-the-fact readers: model replay ([CoachingService.visibleHistory]), the
 * REST transcript ([CoachingService.listTurns]), and the extraction transcript
 * ([ed.unicoach.coaching.extraction.ExtractionService]). Keeping the collapse in
 * one place keeps all three consistent about what a "message" is.
 */
object ConvoProjection {
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
   * `kind = 'user'` opener; `finalResponse` is the group's response whose
   * `stop_reason != 'tool_use'` (the excursion's closing answer). The exchange is
   * emitted only if that closing response exists and succeeded (`content != null`);
   * a group still open (no non-`tool_use` response yet) or whose closing response
   * failed is omitted — preserving the "failed turns are invisible" behavior.
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
      val opener = group.firstOrNull { it.request.kind == ConvoRequestKind.USER }?.request ?: continue
      // The excursion's closing answer: the group's first non-tool_use response.
      // A tool_use response is a mid-excursion call, never the final answer.
      val closing =
        group
          .mapNotNull { it.response }
          .firstOrNull { it.stopReason != TOOL_USE_STOP_REASON }
          ?: continue
      // Emit only on a successful close; a failed close is invisible.
      if (closing.content != null) {
        exchanges.add(VisibleExchange(userRequest = opener, finalResponse = closing))
      }
    }

    return exchanges
  }
}
