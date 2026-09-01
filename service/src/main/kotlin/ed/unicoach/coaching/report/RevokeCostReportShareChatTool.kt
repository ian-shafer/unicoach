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
 * The `revoke_cost_report_share` chat tool (RFC 155): the student's off switch
 * for the Family Cost Report link. Revoking kills every link the student has
 * ever sent, because at most one is ever live.
 *
 * Safe to call twice: nothing live is an ordinary outcome the result states,
 * not an error. Total by the [ed.unicoach.chat.ChatTool] contract.
 */
class RevokeCostReportShareChatTool(
  private val service: CostReportShareService,
) : StudentScopedChatTool() {
  override val name: String = TOOL_NAME

  // No input at all, including no student id: [StudentScopedChatTool] scopes the
  // call to the turn's student, so the model can never revoke another student's
  // link by naming one.
  override val definition: JsonObject = noArgumentToolDefinition(TOOL_NAME, DESCRIPTION)

  override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    unknownFieldsReason(input, emptySet())?.let { return errorObject(it) }

    val outcome =
      service
        .revoke(studentId)
        .getOrElse { e ->
          logger.warn("tool [{}] share revoke failed for student=[{}]", TOOL_NAME, studentId.value, e)
          return errorObject("cost report share revoke failed")
        }

    // The wire boolean is DERIVED from the case at this edge, where a boolean is
    // what the coach reads. The service keeps the revoked row and its stamp.
    val wasLive =
      when (outcome) {
        is RevokeCostReportOutcome.Revoked -> true
        RevokeCostReportOutcome.NothingLive -> false
      }

    return buildJsonObject {
      putJsonObject(RESULT_KEY) {
        put("revoked", wasLive)
        put(ShareCostReportChatTool.STATEMENT_KEY, if (wasLive) REVOKED_STATEMENT else NOTHING_LIVE_STATEMENT)
      }
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(RevokeCostReportShareChatTool::class.java)

    const val TOOL_NAME = "revoke_cost_report_share"

    /** The SAME result key its sibling answers under: two doors, one wire vocabulary, stated once. */
    const val RESULT_KEY = ShareCostReportChatTool.RESULT_KEY

    const val REVOKED_STATEMENT =
      "Every cost report link the student has shared is now dead. Anyone opening one sees a page not found."

    const val NOTHING_LIVE_STATEMENT = "There was no live cost report link to revoke."

    const val DESCRIPTION =
      "Revoke the student's Family Cost Report link. Call this when the student asks to stop sharing " +
        "their costs. Revoking kills every link the student has ever shared, immediately - anyone " +
        "opening one sees a page not found. It is safe to call when nothing is shared; the result says so. " +
        "The student can create a new link later by asking."
  }
}
