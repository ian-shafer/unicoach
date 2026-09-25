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
 * The caller's IDENTITY, and the one refusal that needs no student profile.
 *
 * Separate from [CallerResolution] because a route about SUPPORTERS (RFC 241)
 * needs the session and nothing else: `student_profile_required` tells a caller
 * to go and make the wrong row, so a supporter route must not merely decline to
 * send it — it must not have it to send.
 */
interface CallerAuthentication {
  /** The authenticated caller, or `null` when the request carries no live session. */
  suspend fun RoutingContext.resolveUser(): User?

  /** 401 `unauthorized` — the answer to an unresolved caller. */
  suspend fun RoutingContext.respondUnauthorized()
}

/**
 * The preamble every student-scoped route shares: [CallerAuthentication] plus
 * the STUDENT half — resolve the caller's student profile, and answer the 409
 * when it is absent.
 *
 * Route handlers acquire it by delegation — `class XRouteHandler(...) :
 * CallerResolution by SessionCallerResolution(authService, studentService,
 * sessionConfig)` — so the members stay callable unqualified from a
 * [RoutingContext] handler body. One definition means a change to the 401 or
 * 409 wire shape lands in every route at once, rather than having to be
 * remembered per handler.
 */
interface CallerResolution : CallerAuthentication {
  /** [user]'s student profile, or `null` when they have not created one. */
  suspend fun RoutingContext.resolveStudent(user: User): Student?

  /** 409 `student_profile_required` — the answer to a caller with no student profile. */
  suspend fun RoutingContext.respondStudentProfileRequired()
}

/** [CallerAuthentication] backed by the session cookie ([resolveCaller]). */
class SessionCallerAuthentication(
  private val authService: AuthService,
  private val sessionConfig: SessionConfig,
) : CallerAuthentication {
  override suspend fun RoutingContext.resolveUser(): User? = call.resolveCaller(authService, sessionConfig)?.user

  override suspend fun RoutingContext.respondUnauthorized() {
    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorCode.UNAUTHORIZED, "Not authenticated"))
  }
}

/** [CallerResolution] backed by the session cookie ([resolveCaller]) and [StudentService]. */
class SessionCallerResolution(
  authService: AuthService,
  private val studentService: StudentService,
  sessionConfig: SessionConfig,
) : CallerResolution,
  CallerAuthentication by SessionCallerAuthentication(authService, sessionConfig) {
  override suspend fun RoutingContext.resolveStudent(user: User): Student? = studentService.getStudentForUser(user.id).getOrThrow()

  override suspend fun RoutingContext.respondStudentProfileRequired() {
    call.respond(HttpStatusCode.Conflict, ErrorResponse(ErrorCode.STUDENT_PROFILE_REQUIRED, "A student profile is required"))
  }
}
