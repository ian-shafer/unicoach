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
 * The `share_cost_report` chat tool (RFC 155): the student's door to the Family
 * Cost Report — a tokenized web page a parent can open with no login and no
 * account. The tool returns the link so the coach can speak it, plus the plain
 * sentence about who can see it.
 *
 * Total by the [ed.unicoach.chat.ChatTool] contract: a malformed call or a failed
 * write returns a structured `{ "error": ... }` object, never a throw. A thin
 * adapter by design — minting, revocation and the link shape live in
 * [CostReportShareService].
 */
class ShareCostReportChatTool(
  private val service: CostReportShareService,
) : StudentScopedChatTool() {
  override val name: String = TOOL_NAME

  // The tool is scoped to the turn's student and takes nothing; the shared
  // no-argument definition says so once, for both report doors.
  override val definition: JsonObject = noArgumentToolDefinition(TOOL_NAME, DESCRIPTION)

  override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    unknownFieldsReason(input, emptySet())?.let { return errorObject(it) }

    val outcome =
      service
        .share(studentId)
        .getOrElse { e ->
          logger.warn("tool [{}] share link mint failed for student=[{}]", TOOL_NAME, studentId.value, e)
          return errorObject("cost report share failed")
        }

    // A pure router: each branch is one named step, one level down.
    return when (outcome) {
      is ShareCostReportOutcome.Link -> sharedLink(outcome)
      ShareCostReportOutcome.Unavailable -> sharingUnavailable()
    }
  }

  /** The link handed over, with the sentences that must ride with it every time. */
  private fun sharedLink(link: ShareCostReportOutcome.Link): JsonObject {
    // Minted now, or handed back for the second time? The coach says "same link
    // as before" rather than implying the old one has been replaced. BOTH wire
    // keys are decided by ONE exhaustive `when` over the three cases: written as
    // `!is Existing` they were an `else` in disguise, and a fourth Link case
    // would have shipped `newly_created = true` with nothing to stop it.
    val newlyCreated: Boolean
    val reissued: Boolean
    when (link) {
      is ShareCostReportOutcome.Existing -> {
        newlyCreated = false
        reissued = false
      }

      is ShareCostReportOutcome.Minted -> {
        newlyCreated = true
        reissued = false
      }

      is ShareCostReportOutcome.Reissued -> {
        newlyCreated = true
        reissued = true
      }
    }
    return buildJsonObject {
      putJsonObject(RESULT_KEY) {
        put(LINK_CREATED_KEY, true)
        put("url", link.url)
        put("newly_created", newlyCreated)
        put("live_report", LIVE_REPORT)
        put("who_can_see", WHO_CAN_SEE)
        put("previous_link_no_longer_works", reissued)
        if (reissued) put("previous_link_note", PREVIOUS_LINK_NOTE)
      }
    }
  }

  /**
   * A missing secret is a deployment state, not this student's fault, so it is a
   * RESULT rather than an error: the server still runs, the page still serves
   * links already shared, and nothing about this student's list or costs has
   * changed. Reporting it through the error channel put it beside a failed
   * write, and the model could not tell the two apart — one is "try again", the
   * other is "there is nothing to try".
   */
  private fun sharingUnavailable(): JsonObject =
    buildJsonObject {
      putJsonObject(RESULT_KEY) {
        put(LINK_CREATED_KEY, false)
        put(STATEMENT_KEY, UNAVAILABLE)
      }
    }

  companion object {
    private val logger = LoggerFactory.getLogger(ShareCostReportChatTool::class.java)

    const val TOOL_NAME = "share_cost_report"

    const val RESULT_KEY = "cost_report_share"

    /**
     * Whether this call produced a link at all. Present in BOTH shapes, so the
     * coach reads one key rather than inferring from a missing `url`.
     */
    const val LINK_CREATED_KEY = "link_created"

    /** The sentence the coach may say when no link was created. */
    const val STATEMENT_KEY = "statement"

    /** The honest description of the link's reach, spoken every time it is handed over. */
    const val WHO_CAN_SEE =
      "Anyone with this link can see the student's college list and its cost figures. " +
        "It needs no login. The student can revoke it at any time with revoke_cost_report_share."

    /** The page is not a document: the student's own list, whenever the parent opens it. */
    const val LIVE_REPORT = "This report is live - it updates as your student updates their list."

    /**
     * Said only after a secret rotation has killed the links issued before it.
     * Not the ordinary repeat-share case: asking twice returns the SAME link.
     */
    const val PREVIOUS_LINK_NOTE =
      "A link the student shared earlier no longer works and this one replaces it. " +
        "Tell the student, so anyone holding the old link is sent this one."

    /** The honest decline when no share-token secret is configured — a result, not an error. */
    const val UNAVAILABLE =
      "cost report sharing is not available right now: tell the student you cannot create a link at the moment, " +
        "and that nothing about their list or their costs has changed"

    // The ethos contract rides the tool description verbatim (RFC 155): value
    // before ask, never unasked, never a nudge.
    const val DESCRIPTION =
      "Create a link to the student's Family Cost Report - a web page a parent can open with no login, " +
        "showing the student's college list with tuition and fees, housing and food, the published price, " +
        "the likely price after a financial aid offer, merit practice and debt context. " +
        "Only call this when the student asks to share their costs with a parent or family member, " +
        "or accepts an offer to. Offer it only after a cost comparison has actually happened in the " +
        "conversation - never open with it, and never call it without the student asking or agreeing. " +
        "When you give the link, say plainly that anyone with the link can see it, that the report is live " +
        "and updates as the student updates their list, and that the student can revoke it at any time. " +
        "Asking again returns the same link, so a link already sent keeps working. " +
        "Do not push, do not repeat the offer, and do not treat declining as something to work around: " +
        "declining changes nothing about what you do next. " +
        "The page is live - it shows the student's list as it stands whenever it is opened."
  }
}
