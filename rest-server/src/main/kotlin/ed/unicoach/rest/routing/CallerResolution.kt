package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.db.models.Student
import ed.unicoach.db.models.User
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.auth.resolveCaller
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.student.StudentService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext

/**
 * The preamble every student-scoped route shares: resolve the caller's user,
 * resolve their student profile, and answer the two standard refusals when
 * either is absent.
 *
 * Route handlers acquire it by delegation — `class XRouteHandler(...) :
 * CallerResolution by SessionCallerResolution(authService, studentService,
 * sessionConfig)` — so the members stay callable unqualified from a
 * [RoutingContext] handler body. One definition means a change to the 401 or
 * 409 wire shape lands in every route at once, rather than having to be
 * remembered per handler.
 */
interface CallerResolution {
  /** The authenticated caller, or `null` when the request carries no live session. */
  suspend fun RoutingContext.resolveUser(): User?

  /** [user]'s student profile, or `null` when they have not created one. */
  suspend fun RoutingContext.resolveStudent(user: User): Student?

  /** 401 `unauthorized` — the answer to an unresolved caller. */
  suspend fun RoutingContext.respondUnauthorized()

  /** 409 `student_profile_required` — the answer to a caller with no student profile. */
  suspend fun RoutingContext.respondStudentProfileRequired()
}

/** [CallerResolution] backed by the session cookie ([resolveCaller]) and [StudentService]. */
class SessionCallerResolution(
  private val authService: AuthService,
  private val studentService: StudentService,
  private val sessionConfig: SessionConfig,
) : CallerResolution {
  override suspend fun RoutingContext.resolveUser(): User? = call.resolveCaller(authService, sessionConfig)?.user

  override suspend fun RoutingContext.resolveStudent(user: User): Student? = studentService.getStudentForUser(user.id).getOrThrow()

  override suspend fun RoutingContext.respondUnauthorized() {
    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorCode.UNAUTHORIZED, "Not authenticated"))
  }

  override suspend fun RoutingContext.respondStudentProfileRequired() {
    call.respond(HttpStatusCode.Conflict, ErrorResponse(ErrorCode.STUDENT_PROFILE_REQUIRED, "A student profile is required"))
  }
}
