package ed.unicoach.coaching.report

import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.noArgumentToolDefinition
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * The `stop_cost_report_offers` chat tool (RFC 160): the student's durable
 * "never suggest sharing again". It records one `opted_out` share event, which
 * suppresses the synthesis share-nudge forever; it revokes nothing and disables
 * nothing — a student who opted out of the suggestion can still ask to share,
 * and `share_cost_report` keeps working for them. There is deliberately no
 * un-opt-out tool: "never" means never, and an operator can act in the database
 * if a student genuinely recants.
 *
 * Idempotent by the service's contract: a second call records nothing new and
 * confirms. Total by the [ed.unicoach.chat.ChatTool] contract — a malformed
 * call or a failed write returns a structured `{ "error": ... }` object, never
 * a throw. A thin adapter by design: the write lives in
 * [CostReportShareService.stopOffers].
 */
class StopCostReportOffersChatTool(
  private val service: CostReportShareService,
) : StudentScopedChatTool() {
  override val name: String = TOOL_NAME

  // Scoped to the turn's student and takes nothing, like its two report
  // siblings: the shared no-argument definition says so once.
  override val definition: JsonObject = noArgumentToolDefinition(TOOL_NAME, DESCRIPTION)

  override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    unknownFieldsReason(input, emptySet())?.let { return errorObject(it) }

    val outcome =
      service
        .stopOffers(studentId)
        .getOrElse { e ->
          logger.warn("tool [{}] opt-out record failed for student=[{}]", TOOL_NAME, studentId.value, e)
          return errorObject("cost report offer opt-out failed")
        }

    // Both cases end in the same true state — offers stopped — so `stopped`
    // is always true; `already_stopped` carries WHICH call this was as a typed
    // fact, and the statement is the sentence that fits it.
    val statement =
      when (outcome) {
        is StopCostReportOffersOutcome.Recorded -> STOPPED_STATEMENT
        StopCostReportOffersOutcome.AlreadyStopped -> ALREADY_STOPPED_STATEMENT
      }
    return buildJsonObject {
      putJsonObject(RESULT_KEY) {
        put(STOPPED_KEY, true)
        put(ALREADY_STOPPED_KEY, outcome == StopCostReportOffersOutcome.AlreadyStopped)
        put(ShareCostReportChatTool.STATEMENT_KEY, statement)
      }
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(StopCostReportOffersChatTool::class.java)

    const val TOOL_NAME = "stop_cost_report_offers"

    const val RESULT_KEY = "cost_report_offers"

    /** Present in every success shape, so the coach reads one key. */
    const val STOPPED_KEY = "stopped"

    /** True when "never" was already on file and this call wrote nothing. */
    const val ALREADY_STOPPED_KEY = "already_stopped"

    const val STOPPED_STATEMENT =
      "Noted, permanently: the coach will never suggest sharing the Family Cost Report again. " +
        "Nothing else changes - any link already shared stays live until the student revokes it, " +
        "and the student can still ask to share whenever they want."

    const val ALREADY_STOPPED_STATEMENT =
      "That was already noted: the coach never suggests sharing the Family Cost Report to this student. " +
        "Nothing new was recorded, and the student can still ask to share whenever they want."

    const val DESCRIPTION =
      "Permanently stop suggesting that the student share their Family Cost Report. " +
        "Only call this when the student says they never want the sharing suggestion again - " +
        "not for an ordinary no, which simply closes the topic for this conversation. " +
        "This changes nothing else: it does not revoke any link already shared, and the student " +
        "can still ask to share at any time - share_cost_report keeps working. " +
        "Safe to call twice; the result's already_stopped flag says whether it was already noted. There is no way to undo it, " +
        "so confirm plainly and move on."
  }
}
