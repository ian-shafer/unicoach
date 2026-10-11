package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.db.models.SupporterStudentLinkId
import ed.unicoach.family.SupporterService
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.toResponse
import ed.unicoach.rest.pathId
import ed.unicoach.rest.rejectUnsupportedMethods
import ed.unicoach.student.StudentService
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** The student's list of the adults connected to them (RFC 249). */
const val STUDENT_SUPPORTERS_PATH = "/api/v1/students/me/supporters"

const val STUDENT_SUPPORTER_LINK_PATH = "$STUDENT_SUPPORTERS_PATH/{$LINK_ID_PARAM}"

/**
 * The student's side of a link (RFC 249): read who is connected and what they
 * can see (`GET /api/v1/students/me/supporters`), and end one adult's access
 * (`DELETE /api/v1/students/me/supporters/{linkId}`).
 *
 * [CallerResolution], because both are about the caller's STUDENT profile: a
 * caller without one gets `409 student_profile_required`, as on every other
 * `/students/me` route.
 */
class StudentSupporterRouteHandler(
  authService: AuthService,
  studentService: StudentService,
  private val supporterService: SupporterService,
  sessionConfig: SessionConfig,
) : CallerResolution by SessionCallerResolution(authService, studentService, sessionConfig) {
  fun registerRoutes(route: Route) {
    route.route(STUDENT_SUPPORTERS_PATH) {
      get { handleList() }
      rejectUnsupportedMethods(HttpMethod.Get)
    }
    route.route(STUDENT_SUPPORTER_LINK_PATH) {
      delete { handleDisconnect() }
      rejectUnsupportedMethods(HttpMethod.Delete)
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
}
