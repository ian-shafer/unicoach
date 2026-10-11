package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.coaching.collegelist.SharedCollegeListEntry
import ed.unicoach.db.models.StudentId
import ed.unicoach.error.FieldError
import ed.unicoach.family.SubjectRead
import ed.unicoach.family.SubjectReadService
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.models.SharedCollegeListResponse
import ed.unicoach.rest.models.toResponse
import ed.unicoach.rest.pathId
import ed.unicoach.rest.rejectUnsupportedMethods
import ed.unicoach.rest.respondValidationFailed
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** The path parameter a subject-addressed route names its student by (RFC 250). */
const val STUDENT_ID_PARAM = "studentId"

/**
 * A linked adult's read of a student's college list. Ktor matches the
 * constant `me` before a parameter, so `/api/v1/students/me/college-list`
 * keeps going to the student's own handler.
 */
const val SUBJECT_COLLEGE_LIST_PATH = "/api/v1/students/{$STUDENT_ID_PARAM}/college-list"

/**
 * The routes that name a student other than the caller's own (RFC 250). Every
 * one is a GET over [SubjectReadService], which checks a live link on every
 * call.
 *
 * The delegated seam is [CallerAuthentication], not [CallerResolution]: the
 * caller is a linked adult, who has no student profile, so
 * `student_profile_required` is not this door's to send.
 *
 * Every refusal is ONE `404 student_not_found` with one fixed body, so the
 * door cannot be used to learn whether a student exists or why a link failed.
 */
class SubjectRoutes(
  authService: AuthService,
  sessionConfig: SessionConfig,
  private val subjectReadService: SubjectReadService,
) : CallerAuthentication by SessionCallerAuthentication(authService, sessionConfig) {
  fun registerRoutes(route: Route) {
    route.route(SUBJECT_COLLEGE_LIST_PATH) {
      get { handleCollegeList() }
      rejectUnsupportedMethods(HttpMethod.Get)
    }
  }

  private suspend fun RoutingContext.handleCollegeList() {
    val user = resolveUser() ?: return respondUnauthorized()
    val studentId = pathStudentId() ?: return respondNotAStudentId()

    when (val read = subjectReadService.collegeList(user.id, studentId).getOrThrow()) {
      is SubjectRead.Found -> respondCollegeList(read.value)
      SubjectRead.NotFound -> respondNoSuchStudent()
    }
  }

  private suspend fun RoutingContext.respondCollegeList(entries: List<SharedCollegeListEntry>) {
    call.respond(HttpStatusCode.OK, SharedCollegeListResponse(entries.map { it.toResponse() }))
  }

  private fun RoutingContext.pathStudentId(): StudentId? = pathId(STUDENT_ID_PARAM, ::StudentId)

  /** `400` — a malformed id names no student, so saying so reveals nothing. */
  private suspend fun RoutingContext.respondNotAStudentId() {
    respondValidationFailed(listOf(FieldError(STUDENT_ID_PARAM, "Not a student id: [${call.pathParameters[STUDENT_ID_PARAM]}]")))
  }

  private suspend fun RoutingContext.respondNoSuchStudent() {
    call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorCode.STUDENT_NOT_FOUND, "No such student"))
  }
}
