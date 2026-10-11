package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.coaching.SharingPermissionService
import ed.unicoach.db.models.SharingPermission
import ed.unicoach.db.models.SharingPermissionStatus
import ed.unicoach.error.FieldError
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.models.PublicSharingPermission
import ed.unicoach.rest.models.SharingPermissionStatusView
import ed.unicoach.rest.models.SharingPermissionsResponse
import ed.unicoach.rest.models.UpdateSharingPermissionRequest
import ed.unicoach.rest.rejectUnsupportedMethods
import ed.unicoach.rest.respondValidationFailed
import ed.unicoach.student.StudentService
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * The student's own door to their sharing permissions (RFC 248):
 * `GET /api/v1/students/me/sharing-permissions` and
 * `PUT /api/v1/students/me/sharing-permissions/{permission}`.
 *
 * A sibling of the intended-subjects route, on the same caller-resolution
 * ladder, and like it `GET` is always a 200: an answer is never absent, only
 * `not_asked`.
 */
class SharingPermissionRouteHandler(
  authService: AuthService,
  studentService: StudentService,
  private val sharingPermissionService: SharingPermissionService,
  sessionConfig: SessionConfig,
) : CallerResolution by SessionCallerResolution(authService, studentService, sessionConfig) {
  fun registerRoutes(route: Route) {
    route.route("/api/v1/students/me/sharing-permissions") {
      route("") {
        get { handleGet() }
        rejectUnsupportedMethods(HttpMethod.Get)
      }
      route("/{permission}") {
        put { handlePut() }
        rejectUnsupportedMethods(HttpMethod.Put)
      }
    }
  }

  private suspend fun RoutingContext.handleGet() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()
    val answers = sharingPermissionService.read(student.id).getOrThrow()
    call.respond(
      HttpStatusCode.OK,
      SharingPermissionsResponse(answers.map { (permission, status) -> publicPermission(permission, status) }),
    )
  }

  private suspend fun RoutingContext.handlePut() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()
    val permission = pathPermission() ?: return respondNoSuchPermission(call.parameters["permission"])
    val request = call.receive<UpdateSharingPermissionRequest>()
    val status = parseStatus(request.status) ?: return respondValidationFailed(listOf(statusError(request.status)))

    val stored = sharingPermissionService.set(student.id, permission, status).getOrThrow()
    call.respond(HttpStatusCode.OK, publicPermission(permission, stored))
  }

  /** The `{permission}` path segment as a known [SharingPermission]; null when it names none. */
  private fun RoutingContext.pathPermission(): SharingPermission? = call.parameters["permission"]?.let(SharingPermission::fromValue)

  private suspend fun RoutingContext.respondNoSuchPermission(raw: String?) =
    call.respond(
      HttpStatusCode.NotFound,
      ErrorResponse(
        ErrorCode.NOT_FOUND,
        "No such sharing permission [$raw]; known: ${SharingPermission.entries.joinToString(", ") { it.value }}",
      ),
    )

  /** A writable answer: `granted` or `refused`. `not_asked` is the absence of a row, never something a student writes. */
  private fun parseStatus(raw: String?): SharingPermissionStatus? = raw?.let(SharingPermissionStatus::fromValue)

  private fun statusError(raw: String?): FieldError =
    when (raw) {
      null -> {
        FieldError("status", "Required: one of ${writableStatuses()}")
      }

      SharingPermissionStatusView.NOT_ASKED.wire -> {
        FieldError(
          "status",
          "[${SharingPermissionStatusView.NOT_ASKED.wire}] cannot be written; " +
            "withdraw with [${SharingPermissionStatus.REFUSED.value}]",
        )
      }

      else -> {
        FieldError("status", "[$raw] is not one of ${writableStatuses()}")
      }
    }

  private fun writableStatuses(): String = SharingPermissionStatus.entries.joinToString(", ") { it.value }

  private fun publicPermission(
    permission: SharingPermission,
    status: SharingPermissionStatus?,
  ) = PublicSharingPermission(
    permission = permission.value,
    status = SharingPermissionStatusView.from(status),
  )
}
