package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.CoachingUsage
import ed.unicoach.rest.models.CoachingUsageResponse
import ed.unicoach.rest.rejectUnsupportedMethods
import ed.unicoach.student.StudentService
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * `GET /api/v1/students/me/coaching-usage` (RFC 109): the student's own read of
 * the coaching meter, and the read a usage bar consumes.
 *
 * It reports the same [ed.unicoach.coaching.budget.Entitlement] the four turn
 * gates block on, so the bar and the block can never disagree. The email
 * verification gate covers the route automatically (403 `email_not_verified`).
 */
class CoachingUsageRouteHandler(
  authService: AuthService,
  studentService: StudentService,
  private val budgetService: BudgetService,
  sessionConfig: SessionConfig,
) : CallerResolution by SessionCallerResolution(authService, studentService, sessionConfig) {
  fun registerRoutes(route: Route) {
    route.route("/api/v1/students/me/coaching-usage") {
      get { handleGet() }
      rejectUnsupportedMethods(HttpMethod.Get)
    }
  }

  private suspend fun RoutingContext.handleGet() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()

    val entitlement = budgetService.entitlement(student.id).getOrThrow()
    call.respond(
      HttpStatusCode.OK,
      CoachingUsageResponse(CoachingUsage(usedPercent = entitlement.usedPercent, exhausted = entitlement.exhausted)),
    )
  }
}
