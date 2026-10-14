package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.db.models.SupporterLinkRequestId
import ed.unicoach.db.models.SupporterStudentLinkId
import ed.unicoach.error.FieldError
import ed.unicoach.family.ApproveRequestOutcome
import ed.unicoach.family.DeclineRequestOutcome
import ed.unicoach.family.SupporterLinkRequestService
import ed.unicoach.family.SupporterService
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.models.toResponse
import ed.unicoach.rest.pathId
import ed.unicoach.rest.rejectUnsupportedMethods
import ed.unicoach.rest.respondValidationFailed
import ed.unicoach.student.StudentService
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory

/** The student's list of the adults connected to them (RFC 249). */
const val STUDENT_SUPPORTERS_PATH = "/api/v1/students/me/supporters"

const val STUDENT_SUPPORTER_LINK_PATH = "$STUDENT_SUPPORTERS_PATH/{$LINK_ID_PARAM}"

/** The path parameter both request doors (RFC 254) name a request by. */
const val REQUEST_ID_PARAM = "requestId"

/** One parent's pending request to connect, as the student acts on it (RFC 254). */
const val STUDENT_SUPPORTER_REQUEST_PATH = "/api/v1/students/me/supporter-requests/{$REQUEST_ID_PARAM}"

const val STUDENT_SUPPORTER_REQUEST_APPROVE_PATH = "$STUDENT_SUPPORTER_REQUEST_PATH/approve"

const val STUDENT_SUPPORTER_REQUEST_DECLINE_PATH = "$STUDENT_SUPPORTER_REQUEST_PATH/decline"

/**
 * The student's side of a link (RFC 249): read who is connected and what they
 * can see (`GET /api/v1/students/me/supporters`), and end one adult's access
 * (`DELETE /api/v1/students/me/supporters/{linkId}`). Since RFC 254 the list
 * also carries pending requests to connect, and the student approves or
 * declines one (`POST .../supporter-requests/{requestId}/approve|decline`).
 *
 * [CallerResolution], because all of them are about the caller's STUDENT
 * profile: a caller without one gets `409 student_profile_required`, as on every
 * other `/students/me` route.
 */
class StudentSupporterRouteHandler(
  authService: AuthService,
  studentService: StudentService,
  private val supporterService: SupporterService,
  private val linkRequestService: SupporterLinkRequestService,
  sessionConfig: SessionConfig,
) : CallerResolution by SessionCallerResolution(authService, studentService, sessionConfig) {
  private val logger = LoggerFactory.getLogger(StudentSupporterRouteHandler::class.java)

  fun registerRoutes(route: Route) {
    route.route(STUDENT_SUPPORTERS_PATH) {
      get { handleList() }
      rejectUnsupportedMethods(HttpMethod.Get)
    }
    route.route(STUDENT_SUPPORTER_LINK_PATH) {
      delete { handleDisconnect() }
      rejectUnsupportedMethods(HttpMethod.Delete)
    }
    route.route(STUDENT_SUPPORTER_REQUEST_APPROVE_PATH) {
      post { handleApprove() }
      rejectUnsupportedMethods(HttpMethod.Post)
    }
    route.route(STUDENT_SUPPORTER_REQUEST_DECLINE_PATH) {
      post { handleDecline() }
      rejectUnsupportedMethods(HttpMethod.Post)
    }
  }

  private suspend fun RoutingContext.handleList() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()

    call.respond(HttpStatusCode.OK, supporterService.supportersOf(student.id).getOrThrow().toResponse())
  }

  private suspend fun RoutingContext.handleDisconnect() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()

    respondDisconnect(
      pathId(LINK_ID_PARAM, ::SupporterStudentLinkId),
    ) { linkId -> supporterService.disconnectAsStudent(student.id, linkId) }
  }

  /** `200` with the adult now connected, the same shape the list carries. */
  private suspend fun RoutingContext.handleApprove() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()
    val requestId = pathId(REQUEST_ID_PARAM, ::SupporterLinkRequestId) ?: return respondNotARequestId()

    when (val outcome = linkRequestService.approve(student.id, requestId).getOrThrow()) {
      is ApproveRequestOutcome.Approved -> {
        call.respond(HttpStatusCode.OK, outcome.supporter.toResponse())
      }

      is ApproveRequestOutcome.NotFound -> {
        logger.debug("approve found nothing to act on: requestId=[{}] reason=[{}]", requestId.asString, outcome.reason)
        respondRequestNotFound()
      }
    }
  }

  /** `204`; nothing is sent to anybody. */
  private suspend fun RoutingContext.handleDecline() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()
    val requestId = pathId(REQUEST_ID_PARAM, ::SupporterLinkRequestId) ?: return respondNotARequestId()

    when (linkRequestService.decline(student.id, requestId).getOrThrow()) {
      DeclineRequestOutcome.Declined -> call.respond(HttpStatusCode.NoContent)
      DeclineRequestOutcome.NotFound -> respondRequestNotFound()
    }
  }

  private suspend fun RoutingContext.respondNotARequestId() =
    respondValidationFailed(listOf(FieldError(REQUEST_ID_PARAM, "Not a request id: [${call.pathParameters[REQUEST_ID_PARAM]}]")))

  /**
   * ONE answer for every request the student cannot act on (see
   * [ApproveRequestOutcome.NotFound]), so the doors cannot probe ids.
   */
  private suspend fun RoutingContext.respondRequestNotFound() =
    call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorCode.SUPPORTER_REQUEST_NOT_FOUND, "No such request"))
}
