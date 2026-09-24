package ed.unicoach.web.render

import ed.unicoach.db.models.InviterRole
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.web.INVITE_PATH
import ed.unicoach.web.auth.ACCOUNT_PATH
import ed.unicoach.web.auth.HOME_PATH
import ed.unicoach.web.auth.REGISTER_PATH
import ed.unicoach.web.auth.SIGN_IN_PATH
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.SECTION
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.ul

/** Heading marker proving the invite page rendered (RFC 232). */
const val INVITE_HEADING = "You're invited"

/**
 * The one sentence the INVITER reads on their own link (RFC 232).
 *
 * `SupporterInviteService.accept` refuses a self-addressed invite, so offering the inviter an
 * accept button offers the one action that can only answer the branded 404 —
 * and that 404 reads, to the person who minted the link, as "your live
 * invitation is dead".
 */
const val INVITE_OWN_LINK_NOTICE =
  "This is your own invitation link. Share it with the person you want to connect with."

/**
 * WHO is reading the invite page, which is what decides the action it offers
 * (RFC 232): a stranger is pointed at the two signed-out doors, a signed-in
 * recipient gets the one-click accept, and the inviter is told it is their own
 * link rather than offered a button that cannot work for them.
 */
enum class InviteViewer {
  SIGNED_OUT,
  SIGNED_IN,
  INVITER,
}

/**
 * The ceiling statement the invite itself carries (brief 0014 D3, standing D12):
 * what linking gives and what it does not, written so a stranger reading a text
 * message can judge the ask before creating an account.
 */
private fun SECTION.inviteCeiling() {
  section("invite-ceiling") {
    h2 { +"What connecting lets you do" }
    ul {
      li { +"See the student's college list and key application deadlines." }
      li { +"See estimated college costs and financial-aid calculations." }
      li { +"Use your own coach workspace for financial-aid and timeline help." }
    }
    h2 { +"What stays private" }
    ul {
      li { +"Student coach conversations stay 100% private to the student. Transcripts are never shared." }
      li { +"Supporters cannot edit the student's college list, profile, or tasks — parents support, students direct." }
    }
  }
}

/**
 * The word the RECIPIENT reads.
 *
 * Separate from [SupporterRelationship.value], which is the token stored in the
 * `relationship` column and constrained by that table's CHECK: CLAUDE.md's
 * schema convention puts the human-readable label at the boundary, so a rename
 * of a stored token must not rewrite the first sentence a parent reads.
 * Exhaustive, so a new member cannot ship with no label.
 */
private fun SupporterRelationship.recipientLabel(): String =
  when (this) {
    SupporterRelationship.PARENT -> "parent"
    SupporterRelationship.GUARDIAN -> "guardian"
    SupporterRelationship.GRANDPARENT -> "grandparent"
    SupporterRelationship.SUPPORTER -> "supporter"
  }

/** One sentence stating who invited whom, in the relationship word brief 0014 chose. */
private fun invitationSentence(
  inviterName: String,
  inviterRole: InviterRole,
  relationship: SupporterRelationship,
): String =
  when (inviterRole) {
    InviterRole.STUDENT -> "$inviterName invited you to connect on unicoach as their ${relationship.recipientLabel()}."
    InviterRole.SUPPORTER -> "$inviterName — your ${relationship.recipientLabel()} — invited you to connect on unicoach."
  }

/**
 * The accept page (RFC 232). A signed-out recipient sees the ceiling and is
 * pointed at register/sign-in with the invite token carried along; a signed-in
 * recipient sees a one-click accept form. Declining is always an ordinary link
 * away, never a gate.
 */
suspend fun ApplicationCall.respondInvitePage(
  inviterName: String,
  inviterRole: InviterRole,
  relationship: SupporterRelationship,
  token: String,
  viewer: InviteViewer,
) {
  respondHtml {
    siteLayout(INVITE_HEADING, noindex = true) {
      section("invite") {
        h1 { +INVITE_HEADING }
        p { +invitationSentence(inviterName, inviterRole, relationship) }
        inviteCeiling()

        inviteActions(viewer, token)
      }
    }
  }
}

/** ROUTES by audience; each audience's controls are one named block of their own. */
private fun SECTION.inviteActions(
  viewer: InviteViewer,
  token: String,
) {
  when (viewer) {
    InviteViewer.INVITER -> ownLinkNotice()
    InviteViewer.SIGNED_IN -> acceptForm(token)
    InviteViewer.SIGNED_OUT -> signedOutDoors(token)
  }
}

/** The inviter is told it is their own link: `SupporterInviteService.accept` can only refuse them. */
private fun SECTION.ownLinkNotice() {
  p { +INVITE_OWN_LINK_NOTICE }
  p {
    a(href = ACCOUNT_PATH) { +"Back to your account" }
  }
}

private fun SECTION.acceptForm(token: String) {
  form(action = INVITE_PATH, method = FormMethod.post) {
    tokenField(token)
    button(type = ButtonType.submit) { +"Accept invitation" }
  }
  declineLink()
}

/** The two doors that carry the raw token onward, so the link is made the moment they authenticate. */
private fun SECTION.signedOutDoors(token: String) {
  p {
    a(href = pathCarryingToken(REGISTER_PATH, token)) { +"Create an account to accept" }
  }
  p {
    a(href = pathCarryingToken(SIGN_IN_PATH, token)) { +"Sign in to accept" }
  }
  declineLink()
}

/** Declining is an ordinary link away, never a gate (brief 0014 D12). */
private fun SECTION.declineLink() {
  p {
    a(href = HOME_PATH) { +"Decline" }
  }
}

/** Heading marker for the post-accept confirmation (RFC 232). */
const val INVITE_ACCEPTED_HEADING = "Invitation accepted"

/** The one confirmation a successful accept renders (RFC 232). */
const val INVITE_ACCEPTED_CONFIRMATION = "You are now connected. Welcome to unicoach."

/**
 * The one sentence a REFUSED accept renders on the `/register` and `/sign-in`
 * doors (RFC 232).
 *
 * ONE sentence for all three refusals — dead token, self-addressed invite, no
 * student profile — because `POST /invite` serves one body for all three too,
 * and a door that named which one fired would tell the holder of a dead link
 * that it was once real. Their account is made either way, which is why these
 * two doors say something rather than serving the branded 404 `/invite` serves.
 */
const val INVITE_NOT_ACCEPTED_NOTICE =
  "Your account is ready, but that invitation could not be accepted. It may have expired or already been used."
