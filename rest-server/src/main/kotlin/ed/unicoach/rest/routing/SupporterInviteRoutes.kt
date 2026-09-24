package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.family.AcceptInviteOutcome
import ed.unicoach.family.MintedInvite
import ed.unicoach.family.SupporterInviteService
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.AcceptSupporterInviteRequest
import ed.unicoach.rest.models.AcceptSupporterInviteResponse
import ed.unicoach.rest.models.AcceptSupporterInviteStatus
import ed.unicoach.rest.models.CreateSupporterInviteRequest
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.models.SupporterInviteResponse
import ed.unicoach.rest.models.relationshipOrDefault
import ed.unicoach.rest.rejectUnsupportedMethods
import ed.unicoach.student.StudentService
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** The mint door (RFC 232). */
const val SUPPORTER_INVITES_PATH = "/api/v1/supporter-invites"

/**
 * The accept door: the one path `configureEmailVerificationGate` exempts
 * (correction X8). Derived from the mount rather than retyped there, so moving
 * the route is a compile error instead of a silently re-armed deadlock.
 */
const val SUPPORTER_INVITE_ACCEPT_SEGMENT = "/accept"

const val SUPPORTER_INVITE_ACCEPT_PATH = "$SUPPORTER_INVITES_PATH$SUPPORTER_INVITE_ACCEPT_SEGMENT"

/**
 * The two REST doors of the supporter invite (RFC 232): mint an invite link
 * (`POST /api/v1/supporter-invites`) and accept one
 * (`POST /api/v1/supporter-invites/accept`).
 *
 * The accept door is the one `EmailVerificationGate` exempts (correction X8), so
 * a new parent or student can accept before verifying their email. This slice's
 * only acceptance surface is public-web, where the recipient opens the shared
 * link; no iOS client calls this door yet.
 */
class SupporterInviteRoutes(
  authService: AuthService,
  studentService: StudentService,
  sessionConfig: SessionConfig,
  private val supporterInviteService: SupporterInviteService,
) : CallerResolution by SessionCallerResolution(authService, studentService, sessionConfig) {
  fun registerRoutes(route: Route) {
    route.route(SUPPORTER_INVITES_PATH) {
      post { handleCreate() }
      rejectUnsupportedMethods(HttpMethod.Post)
      route(SUPPORTER_INVITE_ACCEPT_SEGMENT) {
        post { handleAccept() }
        rejectUnsupportedMethods(HttpMethod.Post)
      }
    }
  }

  private suspend fun RoutingContext.handleCreate() {
    val user = resolveUser() ?: return respondUnauthorized()

    val body = call.receive<CreateSupporterInviteRequest>()
    val relationship = body.relationshipOrDefault() ?: return respondUnreadableRelationship(body.relationship)

    val minted = supporterInviteService.create(user.id, relationship).getOrThrow()
    call.respond(HttpStatusCode.Created, minted.toResponse())
  }

  /**
   * The one refusal the mint door makes, naming BOTH the word it could not read
   * and the vocabulary it reads: the legal set alone leaves an integrator unable
   * to tell a typo from a case difference from a trailing space.
   */
  private suspend fun RoutingContext.respondUnreadableRelationship(presented: String?) {
    call.respond(
      HttpStatusCode.BadRequest,
      ErrorResponse(
        ErrorCode.VALIDATION_ERROR,
        "relationship [$presented] must be one of: ${SupporterRelationship.entries.joinToString { it.value }}",
      ),
    )
  }

  private suspend fun RoutingContext.handleAccept() {
    val user = resolveUser() ?: return respondUnauthorized()

    val request = call.receive<AcceptSupporterInviteRequest>()
    val outcome = supporterInviteService.accept(request.token, user.id).getOrThrow()
    respondAcceptOutcome(outcome)
  }

  /** ROUTES only: each branch is one named responder, so no altitude but routing lives here. */
  private suspend fun RoutingContext.respondAcceptOutcome(outcome: AcceptInviteOutcome) {
    when (outcome) {
      is AcceptInviteOutcome.Success -> respondInviteAccepted(outcome)
      is AcceptInviteOutcome.InvalidToken -> respondInviteNotFound()
      AcceptInviteOutcome.SelfLinkProhibited -> respondSelfLinkProhibited()
      AcceptInviteOutcome.StudentProfileRequired -> respondStudentProfileRequired()
    }
  }

  private suspend fun RoutingContext.respondInviteAccepted(outcome: AcceptInviteOutcome.Success) {
    call.respond(
      HttpStatusCode.OK,
      AcceptSupporterInviteResponse(
        status = AcceptSupporterInviteStatus.ACCEPTED,
        linkId =
          outcome.link.id.value
            .toString(),
      ),
    )
  }

  /** ONE body for a token that is unknown, expired, consumed or revoked: no enumeration oracle. */
  private suspend fun RoutingContext.respondInviteNotFound() {
    call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorCode.NOT_FOUND, "No such invite"))
  }

  private suspend fun RoutingContext.respondSelfLinkProhibited() {
    call.respond(HttpStatusCode.Forbidden, ErrorResponse(ErrorCode.FORBIDDEN, "You cannot accept your own invite"))
  }

  private fun MintedInvite.toResponse(): SupporterInviteResponse =
    SupporterInviteResponse(
      id = id.value.toString(),
      inviteUrl = inviteUrl,
      inviterRole = inviterRole.value,
      relationship = relationship.value,
      expiresAt = expiresAt,
    )
}
