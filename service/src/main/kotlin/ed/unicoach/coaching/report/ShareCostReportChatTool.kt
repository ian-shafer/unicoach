package ed.unicoach.coaching.report

import ed.unicoach.coaching.SharingPermissionCopy
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.UpdateSharingPermissionChatTool
import ed.unicoach.coaching.noArgumentToolDefinition
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IntendedSubjectAnswer
import ed.unicoach.db.models.SharingPermission
import ed.unicoach.db.models.SharingPermissionStatus
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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
 * write returns a structured `{ "error": ... }` object, never a throw. Minting,
 * revocation and the link shape live in [CostReportShareService]; the one
 * decision made here is what the result says about the student's subjects
 * ([SubjectsConsent], RFC 251).
 *
 * The reach sentence is TRUE FOR THE STUDENT'S STATE (RFC 251): with a grant of
 * [SharingPermission.INTENDED_SUBJECTS] the page also shows the subjects, so
 * the sentence says so. Both answers are read BEFORE a link is minted. A failed
 * permission read is a failed share: a reach sentence this tool cannot state
 * truthfully is not a link handed over with a guess beside it. A failed subjects
 * read decides only the ask, so the link is minted without `your_subjects`.
 */
class ShareCostReportChatTool(
  private val service: CostReportShareService,
  private val readPermissions: suspend (StudentId) -> Result<Map<SharingPermission, SharingPermissionStatus?>>,
  private val readSubjects: suspend (StudentId) -> Result<IntendedSubjectAnswer>,
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

    val subjectsPermission = subjectsPermissionOf(studentId).getOrElse { return errorObject("cost report share failed") }
    val consent = subjectsConsentOf(subjectsPermission, subjectsOf(studentId))

    val outcome =
      service
        .share(studentId)
        .getOrElse { e ->
          logger.warn("tool [{}] share link mint failed for student=[{}]", TOOL_NAME, studentId.value, e)
          return errorObject("cost report share failed")
        }

    // A pure router: each branch is one named step, one level down.
    return when (outcome) {
      is ShareCostReportOutcome.Link -> sharedLink(outcome, subjectsPermission, consent)
      ShareCostReportOutcome.Unavailable -> sharingUnavailable()
    }
  }

  /**
   * The link handed over, with the sentences that must ride with it every time.
   *
   * [subjectsPermission] is the student's answer, null when never asked, and
   * decides only the reach sentence; [consent] is what the result says about
   * the student's subjects, already classified by [subjectsConsentOf].
   */
  private fun sharedLink(
    link: ShareCostReportOutcome.Link,
    subjectsPermission: SharingPermissionStatus?,
    consent: SubjectsConsent,
  ): JsonObject {
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
        put(URL_KEY, link.url)
        put("newly_created", newlyCreated)
        put("live_report", LIVE_REPORT)
        put("who_can_see", whoCanSeeOf(subjectsPermission))
        put("previous_link_no_longer_works", reissued)
        if (reissued) put("previous_link_note", PREVIOUS_LINK_NOTE)
        putYourSubjects(consent)
      }
    }
  }

  /** The `your_subjects` field: absent with nothing to show, the ask while never answered, else the answer. */
  private fun JsonObjectBuilder.putYourSubjects(consent: SubjectsConsent) {
    when (consent) {
      SubjectsConsent.NothingToShow -> {
        Unit
      }

      SubjectsConsent.Ask -> {
        putJsonObject(YOUR_SUBJECTS_KEY) {
          put(PERMISSION_KEY, SharingPermissionStatus.NOT_ASKED_WIRE)
          put(ASK_KEY, SharingPermissionCopy.CONSENT_WORDS)
        }
      }

      is SubjectsConsent.Answered -> {
        putJsonObject(YOUR_SUBJECTS_KEY) { put(PERMISSION_KEY, consent.status.value) }
      }
    }
  }

  /** The student's answer about showing their subjects, null when never asked; a failed read is logged here. */
  private suspend fun subjectsPermissionOf(studentId: StudentId): Result<SharingPermissionStatus?> =
    readPermissions(studentId)
      .map { it[SharingPermission.INTENDED_SUBJECTS] }
      .onFailure { e -> logger.warn("tool [{}] sharing permission read failed for student=[{}]", TOOL_NAME, studentId.value, e) }

  /** The student's intended-subjects answer; a failed read is logged here and classified by [subjectsConsentOf]. */
  private suspend fun subjectsOf(studentId: StudentId): Result<IntendedSubjectAnswer> =
    readSubjects(studentId)
      .onFailure { e -> logger.warn("tool [{}] intended subjects read failed for student=[{}]", TOOL_NAME, studentId.value, e) }

  /**
   * What the share result says about the student's subjects (RFC 251).
   * `your_subjects` rides only when there ARE stored subjects -- with none, the
   * answer changes nothing on the page -- and carries the ask only while the
   * student has never answered, so the store, not the model's memory, is what
   * makes the coach ask once.
   */
  private sealed interface SubjectsConsent {
    /** No stored subjects, or they could not be read: the answer changes nothing, so nothing rides. */
    data object NothingToShow : SubjectsConsent

    /** Subjects stored, never asked: the coach asks, in the consent words. */
    data object Ask : SubjectsConsent

    /** Subjects stored and answered. */
    data class Answered(
      val status: SharingPermissionStatus,
    ) : SubjectsConsent
  }

  /**
   * The one classifier of the two reads. Every [AnswerStatus] is named, so a new
   * status is a compile error here rather than a silent "nothing to show"; a
   * failed subjects read is [SubjectsConsent.NothingToShow], deciding only the ask.
   */
  private fun subjectsConsentOf(
    permission: SharingPermissionStatus?,
    subjects: Result<IntendedSubjectAnswer>,
  ): SubjectsConsent {
    val answered =
      subjects.fold(
        onSuccess = { answer ->
          when (answer.status) {
            AnswerStatus.ANSWERED -> true

            // No subject is stored, so a yes would change nothing on the page.
            AnswerStatus.UNANSWERED, AnswerStatus.DECLINED, AnswerStatus.UNDECIDED -> false
          }
        },
        onFailure = { false },
      )
    return when {
      !answered -> SubjectsConsent.NothingToShow
      permission == null -> SubjectsConsent.Ask
      else -> SubjectsConsent.Answered(permission)
    }
  }

  /** The reach sentence: only a grant adds the subjects; `null` is never asked, which shows nothing. */
  private fun whoCanSeeOf(subjectsPermission: SharingPermissionStatus?): String =
    when (subjectsPermission) {
      SharingPermissionStatus.GRANTED -> WHO_CAN_SEE_WITH_SUBJECTS
      SharingPermissionStatus.REFUSED, null -> WHO_CAN_SEE
    }

  /**
   * A missing secret is a deployment state, not this student's fault, so it is a
   * RESULT rather than an error: the server still runs, the page still serves
   * links already shared, and nothing about this student's list or costs has
   * changed. Reporting it through the error channel put it beside a failed
   * write, and the model could not tell the two apart — one is "try again", the
   * other is "there is nothing to try".
   */
  private fun sharingUnavailable(): JsonObject = noLinkCreated(RESULT_KEY, UNAVAILABLE)

  companion object {
    private val logger = LoggerFactory.getLogger(ShareCostReportChatTool::class.java)

    const val TOOL_NAME = "share_cost_report"

    const val RESULT_KEY = "cost_report_share"

    /** What every reach sentence ends with: no login, and the tool that takes the link back. */
    private const val NO_LOGIN_AND_REVOKE =
      "It needs no login. The student can revoke it at any time with ${RevokeCostReportShareChatTool.TOOL_NAME}."

    /** The honest description of the link's reach, spoken every time it is handed over. */
    const val WHO_CAN_SEE =
      "Anyone with this link can see the student's college list and its cost figures. $NO_LOGIN_AND_REVOKE"

    /** [WHO_CAN_SEE] for a student who has said yes to showing their subjects (RFC 251). */
    const val WHO_CAN_SEE_WITH_SUBJECTS =
      "Anyone with this link can see the student's college list and its cost figures, and what the student " +
        "wants to study, with what graduates of those subjects earn and owe. $NO_LOGIN_AND_REVOKE"

    /** The student's answer about showing their subjects, present only when they have stored subjects. */
    const val YOUR_SUBJECTS_KEY = "your_subjects"

    /** `not_asked`, `granted` or `refused`, inside [YOUR_SUBJECTS_KEY]. */
    const val PERMISSION_KEY = "permission"

    /** The words to ask in, present only while the student has never answered. */
    const val ASK_KEY = "ask"

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
        "the likely price after a financial aid offer, merit practice, and what its graduates borrowed. " +
        "Only call this when the student asks to share their costs with a parent or family member, " +
        "or accepts an offer to. Offer it only after a cost comparison has actually happened in the " +
        "conversation - never open with it, and never call it without the student asking or agreeing. " +
        "When you give the link, say plainly that anyone with the link can see it, that the report is live " +
        "and updates as the student updates their list, and that the student can revoke it at any time. " +
        "Asking again returns the same link, so a link already sent keeps working. " +
        "If the result carries $YOUR_SUBJECTS_KEY with an $ASK_KEY, ask the student once, in those words, " +
        "whether people they share with may see their subjects, and record the answer with " +
        "${UpdateSharingPermissionChatTool.TOOL_NAME}; never raise it again after a no. " +
        "Do not push, do not repeat the offer, and do not treat declining as something to work around: " +
        "declining changes nothing about what you do next. " +
        "The page is live - it shows the student's list as it stands whenever it is opened."
  }
}
