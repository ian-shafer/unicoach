package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.db.models.CollegeSummary
import ed.unicoach.error.FieldError
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.CollegeSearchResponse
import ed.unicoach.rest.models.PublicCollegeSummary
import ed.unicoach.rest.rejectUnsupportedMethods
import ed.unicoach.rest.respondValidationFailed
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
 * The student-facing college search (RFC 137): `GET /api/v1/colleges?q=...`,
 * the name-to-id resolver behind the iOS add-college picker. Authenticated
 * session required, but deliberately **no student-profile gate** — searching a
 * public catalog is not an operation on "your college list", so the
 * `student_profile_required` refusal stays on the list routes.
 */
class CollegeRouteHandler(
  authService: AuthService,
  studentService: StudentService,
  private val collegeSearchService: CollegeSearchService,
  sessionConfig: SessionConfig,
) : CallerResolution by SessionCallerResolution(authService, studentService, sessionConfig) {
  fun registerRoutes(route: Route) {
    route.route("/api/v1/colleges") {
      get { handleSearch() }
      rejectUnsupportedMethods(HttpMethod.Get)
    }
  }

  private suspend fun RoutingContext.handleSearch() {
    resolveUser() ?: return respondUnauthorized()
    val query = validatedQuery() ?: return
    val limit = validatedLimit() ?: return
    respondSearchResults(collegeSearchService.searchByName(query, limit).getOrThrow())
  }

  /**
   * Validates `q`: trimmed, non-blank, within [CollegeSearchService.MAX_QUERY_LENGTH]
   * (the service owns the bound; the route's 400 references it). Responds 400
   * and yields null otherwise.
   */
  private suspend fun RoutingContext.validatedQuery(): String? {
    val query = call.request.queryParameters["q"]?.trim()
    if (query.isNullOrEmpty()) {
      respondValidationFailed(listOf(FieldError("q", "Missing or blank query")))
      return null
    }
    if (query.length > CollegeSearchService.MAX_QUERY_LENGTH) {
      respondValidationFailed(
        listOf(
          FieldError(
            "q",
            "Query must be at most ${CollegeSearchService.MAX_QUERY_LENGTH} characters (got ${query.length})",
          ),
        ),
      )
      return null
    }
    return query
  }

  /**
   * Validates `limit`: absent defaults to [DEFAULT_LIMIT]; a non-integer
   * responds 400 (naming the offending value) and yields null. Out-of-range
   * limits are clamped at the service boundary (the CollegeQuery convention),
   * not rejected here.
   */
  private suspend fun RoutingContext.validatedLimit(): Int? {
    val raw = call.request.queryParameters["limit"] ?: return DEFAULT_LIMIT
    val limit = raw.toIntOrNull()
    if (limit == null) {
      respondValidationFailed(listOf(FieldError("limit", "Non-integer limit: [$raw]")))
    }
    return limit
  }

  private suspend fun RoutingContext.respondSearchResults(summaries: List<CollegeSummary>) {
    call.respond(
      HttpStatusCode.OK,
      CollegeSearchResponse(
        summaries.map { PublicCollegeSummary(id = it.id.value, name = it.name, city = it.city, state = it.state) },
      ),
    )
  }

  companion object {
    const val DEFAULT_LIMIT = 20
  }
}
