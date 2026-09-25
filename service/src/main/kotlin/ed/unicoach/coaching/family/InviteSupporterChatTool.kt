package ed.unicoach.coaching.family

import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.noArgumentToolDefinition
import ed.unicoach.coaching.report.ShareCostReportChatTool
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.family.MintedInvite
import ed.unicoach.family.StudentMintOutcome
import ed.unicoach.family.SupporterInviteService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * The `invite_supporter` chat tool (RFC 240): the student's door, in chat, to
 * connecting a parent or supporter PROPERLY — their own account and their own
 * coach workspace, not the read-only Family Cost Report page.
 *
 * Total by the [ed.unicoach.chat.ChatTool] contract: a malformed call or a failed
 * write returns a structured `{ "error": ... }` object, never a throw, and a
 * student this server cannot mint for is a RESULT the coach can speak. A thin
 * adapter by design — the token, the link and the expiry live in
 * [SupporterInviteService] (RFC 232).
 *
 * Every ask mints a NEW link, and that is not the [ed.unicoach.coaching.report.ShareCostReportChatTool]
 * behaviour by oversight. `supporter_invites` stores only a SHA-256 hash of the
 * token, so a link already handed over is unrecoverable by construction; and
 * several live invites at once are legal by design (RFC 232 §2 — a student with
 * two parents invites both). So asking twice is asking for a second link, not a
 * repeat of the first.
 */
class InviteSupporterChatTool(
  private val service: SupporterInviteService,
) : StudentScopedChatTool() {
  override val name: String = TOOL_NAME

  // Scoped to the turn's student and takes nothing: one more caller of the
  // shared no-argument definition, so every argument-free tool advertises one
  // schema shape.
  override val definition: JsonObject = noArgumentToolDefinition(TOOL_NAME, DESCRIPTION)

  override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    unknownFieldsReason(input, emptySet())?.let { return errorObject(it) }

    // MINTED_RELATIONSHIP and not an argument: no door states a relationship,
    // and a model asked to guess one would be inventing a fact about a family.
    val outcome =
      service
        .createForStudent(studentId, MINTED_RELATIONSHIP)
        .getOrElse { e ->
          // The URL carries a live single-use credential, so the log names the
          // student and nothing else.
          logger.warn("tool [{}] supporter invite mint failed for student=[{}]", TOOL_NAME, studentId.value, e)
          return errorObject("supporter invite failed")
        }

    return when (outcome) {
      is StudentMintOutcome.Minted -> {
        invitedSupporter(outcome.invite)
      }

      // The coach hears ONE sentence for both reasons; the reason itself is
      // recorded, because a closed account is an expected event and a student id
      // with no row is a routing defect somebody has to fix.
      is StudentMintOutcome.NoEndpoint -> {
        logger.warn(
          "tool [{}] no student endpoint to invite from: student=[{}] reason=[{}]",
          TOOL_NAME,
          studentId.value,
          outcome.reason,
        )
        invitingUnavailable()
      }
    }
  }

  /** The link handed over, with the sentences that must ride with it every time. */
  private fun invitedSupporter(minted: MintedInvite): JsonObject =
    buildJsonObject {
      putJsonObject(RESULT_KEY) {
        put(LINK_CREATED_KEY, true)
        // The raw token is never a field of its own and is never logged: it
        // rides inside this URL, and a second copy of it is a second place it
        // can leak from.
        put(URL_KEY, minted.inviteUrl)
        put(EXPIRES_AT_KEY, minted.expiresAt.toString())
        put(ONE_PERSON_ONE_USE_KEY, ONE_PERSON_ONE_USE)
        put(WHAT_CONNECTING_GIVES_KEY, WHAT_CONNECTING_GIVES)
        put(WHAT_STAYS_PRIVATE_KEY, WHAT_STAYS_PRIVATE)
      }
    }

  /**
   * This student has no open endpoint to invite from — a closed or missing
   * student row. A RESULT and not an error, on [ed.unicoach.coaching.report.ShareCostReportChatTool]'s
   * precedent: an error sits beside a failed write, and the model cannot tell
   * "try again" from "there is nothing to try".
   */
  private fun invitingUnavailable(): JsonObject = noLinkCreated(RESULT_KEY, UNAVAILABLE)

  companion object {
    private val logger = LoggerFactory.getLogger(InviteSupporterChatTool::class.java)

    const val TOOL_NAME = "invite_supporter"

    const val RESULT_KEY = "supporter_invite"

    /** The date the link dies, which the coach must be able to say out loud. */
    const val EXPIRES_AT_KEY = "expires_at"

    /** The single-use warning's key, said every time a link is handed over. */
    const val ONE_PERSON_ONE_USE_KEY = "one_person_one_use"

    /** The ceiling's public half. */
    const val WHAT_CONNECTING_GIVES_KEY = "what_connecting_gives"

    /** The ceiling's private half. */
    const val WHAT_STAYS_PRIVATE_KEY = "what_stays_private"

    /**
     * The word an invite carries when nobody stated one (RFC 240 decision 4),
     * read from its one home [SupporterRelationship.UNSTATED] rather than named
     * again here: the REST door mints the same value on an absent field, and the
     * `/invite` page renders THAT value as "parent or supporter", so a door that
     * spelled its own word would leave the recipient's sentence untrue.
     */
    val MINTED_RELATIONSHIP = SupporterRelationship.UNSTATED

    /** Said every time, because a link for one person that is spread to two fails silently for the second. */
    const val ONE_PERSON_ONE_USE =
      "This link is for ONE person and works once. " +
        "Tell the student to send it to just that person, and that it stops working after the date above. " +
        "If they want to connect someone else as well, call $TOOL_NAME again for a second link."

    /**
     * The same ceiling `InvitePage.inviteCeiling` shows the recipient (RFC 232's
     * landed copy), RESTATED for speech — not that page's own strings. The page
     * renders three HTML bullets to a stranger; this is one sentence a coach
     * says to a student.
     *
     * Nothing binds the two: `inviteCeiling` is private and lives in the
     * `public-web` module, which `service` does not depend on. So this is a
     * second hand-written home for one claim, and the day they diverge is the
     * day a student is told something the recipient's page contradicts. Reword
     * either one and reword the other (RFC 240 §3, open item).
     */
    const val WHAT_CONNECTING_GIVES =
      "Connecting gives the parent or supporter their own unicoach account and their own coach workspace, " +
        "where they can see the student's college list, key application deadlines, estimated costs and " +
        "financial-aid calculations. The student can end one supporter's access without ending anyone else's."

    /** The other half of the ceiling, and the half a family will ask about first. */
    const val WHAT_STAYS_PRIVATE =
      "The parent or supporter never reads the student's coach conversations - transcripts are never shared - " +
        "and cannot change the student's college list, profile or tasks."

    /** The honest decline when this student has no open endpoint to invite from — a result, not an error. */
    const val UNAVAILABLE =
      "connecting a parent is not available right now: tell the student you cannot create an invitation at the " +
        "moment, and that nothing about their account has changed"

    // The ethos contract rides the tool description verbatim (RFC 155's rule,
    // RFC 240's offer): only on the student's ask or agreement, never a nudge,
    // and a decline changes nothing.
    const val DESCRIPTION =
      "Create a private invitation link the student sends to one parent or supporter, so that person gets " +
        "their own unicoach account and their own coach workspace. " +
        "This is a different thing from ${ShareCostReportChatTool.TOOL_NAME}, " +
        "which is a read-only page and no account. " +
        "Only call this when the student asks to connect a parent or supporter, or accepts an offer to. " +
        "Never open a conversation with it, and never call it without the student asking or agreeing. " +
        "Each link is for ONE person, works once, and stops working after the date the result gives - " +
        "so say that date when you hand the link over, and call this again for a second person. " +
        "When you give the link, say plainly what connecting gives: their own account and their own coach " +
        "workspace, the student's college list, deadlines, costs and aid calculations, and that the student " +
        "can end one supporter's access without ending anyone else's. " +
        "Say plainly what it does not give: the parent never reads the student's coach conversations, and " +
        "cannot change the student's college list, profile or tasks. " +
        "Do not push, do not repeat the offer, and do not treat declining as something to work around: " +
        "declining changes nothing about what you do next. " +
        "A student who asks is never refused, and a student may invite more than one person."
  }
}
