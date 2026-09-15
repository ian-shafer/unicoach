package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.coaching.SubjectVocabularyService
import ed.unicoach.db.models.IntendedSubjectName
import ed.unicoach.error.FieldError
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.PublicSubject
import ed.unicoach.rest.models.SubjectListResponse
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
 * The subject picker's list: `GET /api/v1/subjects?q=...` (RFC 205 D29/D41).
 *
 * Follows [CollegeRouteHandler] exactly — authenticated session required, and
 * deliberately NO student-profile gate, for the reason that route already
 * states: reading a closed published list is not an operation on "your" data.
 *
 * It is a route of its own rather than an entry in `/api/v1/vocabularies`
 * because that document is uncached, unpaged and refetched on every screen
 * appearance, and RFC 165 excludes this taxonomy from served vocabularies by
 * rule.
 *
 * TWO deliberate divergences from the college route, stated because they are
 * divergences:
 *  - `q` is OPTIONAL. The corpus is a closed authored list of a couple of
 *    hundred subjects — browsable — and a picker that shows nothing until the
 *    student types is a worse picker. A blank-but-PRESENT `q` is still a 400, as
 *    it is there: an empty string is a caller defect, not a request for
 *    everything.
 *  - `limit` defaults to the WHOLE list and clamps 1..200, where the college
 *    route clamps 1..25, because its corpus is thousands of rows and this one is
 *    not.
 */
class SubjectsRouteHandler(
  authService: AuthService,
  studentService: StudentService,
  private val subjectVocabularyService: SubjectVocabularyService,
  sessionConfig: SessionConfig,
) : CallerResolution by SessionCallerResolution(authService, studentService, sessionConfig) {
  fun registerRoutes(route: Route) {
    route.route("/api/v1/subjects") {
      get { handleList() }
      rejectUnsupportedMethods(HttpMethod.Get)
    }
  }

  private suspend fun RoutingContext.handleList() {
    resolveUser() ?: return respondUnauthorized()
    val query = validatedQuery() ?: return
    val limit = validatedLimit() ?: return
    respondSubjects(subjectVocabularyService.list(query.value, limit).getOrThrow())
  }

  /**
   * `q` absent is the whole list; `q` present carries a trimmed, non-blank,
   * bounded term. The two are different requests and [ParsedQuery] keeps them
   * so — a null String would collapse "no filter" and "the filter is empty" into
   * one value, and the second of those is a 400.
   */
  private data class ParsedQuery(
    val value: String?,
  )

  private suspend fun RoutingContext.validatedQuery(): ParsedQuery? {
    val raw = call.request.queryParameters["q"] ?: return ParsedQuery(null)
    val query = raw.trim()
    if (query.isEmpty()) {
      respondValidationFailed(listOf(FieldError("q", "Blank query; omit q entirely for the whole list")))
      return null
    }
    if (query.length > SubjectVocabularyService.MAX_QUERY_LENGTH) {
      respondValidationFailed(
        listOf(
          FieldError(
            "q",
            "Query must be at most ${SubjectVocabularyService.MAX_QUERY_LENGTH} characters (got ${query.length})",
          ),
        ),
      )
      return null
    }
    return ParsedQuery(query)
  }

  /**
   * `limit` absent is the whole list; a non-integer is a 400 naming the
   * offending value. An out-of-range limit is CLAMPED at the service boundary
   * rather than rejected — the college route's convention — so `0` and `9999`
   * both serve a list instead of an error.
   */
  private suspend fun RoutingContext.validatedLimit(): Int? {
    val raw = call.request.queryParameters["limit"] ?: return SubjectVocabularyService.MAX_LIMIT
    val limit = raw.toIntOrNull()
    if (limit == null) {
      respondValidationFailed(listOf(FieldError("limit", "Non-integer limit: [$raw]")))
    }
    return limit
  }

  private suspend fun RoutingContext.respondSubjects(subjects: List<IntendedSubjectName>) {
    call.respond(
      HttpStatusCode.OK,
      SubjectListResponse(subjects.map { PublicSubject(slug = it.slug, name = it.name) }),
    )
  }
}
