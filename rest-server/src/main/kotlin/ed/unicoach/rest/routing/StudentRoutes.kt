package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.db.models.Student
import ed.unicoach.db.models.StudentVersionId
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.error.FieldError
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.CreateStudentRequest
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.models.PublicStudent
import ed.unicoach.rest.models.StudentResponse
import ed.unicoach.rest.models.UpdateStudentRequest
import ed.unicoach.rest.rejectUnsupportedMethods
import ed.unicoach.student.CreateStudentResult
import ed.unicoach.student.DeleteStudentResult
import ed.unicoach.student.StudentService
import ed.unicoach.student.UpdateStudentResult
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private fun toPublicStudent(student: Student): PublicStudent =
  PublicStudent(
    id = student.id.value,
    expectedHighSchoolGraduationDate = student.expectedHighSchoolGraduationDate.toIso(),
    version = student.versionId.value,
    createdAt = student.createdAt,
    updatedAt = student.updatedAt,
  )

private suspend fun RoutingContext.respondUnauthorized() {
  call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", "Not authenticated"))
}

private suspend fun RoutingContext.respondStudentNotFound() {
  call.respond(HttpStatusCode.NotFound, ErrorResponse("STUDENT_NOT_FOUND", "No student profile for the current user"))
}

private suspend fun RoutingContext.respondValidationFailure(fieldErrors: List<FieldError>) {
  call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Invalid student parameters", fieldErrors))
}

private fun RoutingContext.tokenFrom(sessionConfig: SessionConfig): String? = call.request.cookies[sessionConfig.cookieName]

class StudentRouteHandler(
  private val authService: AuthService,
  private val studentService: StudentService,
  private val sessionConfig: SessionConfig,
) {
  fun registerRoutes(route: Route) {
    route.route("/api/v1/students") {
      route("") {
        post { handleCreate() }
        rejectUnsupportedMethods(HttpMethod.Post)
      }
      route("/me") {
        get { handleGet() }
        patch { handleUpdate() }
        delete { handleDelete() }
        rejectUnsupportedMethods(HttpMethod.Get, HttpMethod.Patch, HttpMethod.Delete)
      }
    }
  }

  private suspend fun RoutingContext.resolveUser(): User? {
    val token = tokenFrom(sessionConfig) ?: return null
    val tokenHash = TokenHash.fromRawToken(token)
    return authService.getCurrentUser(tokenHash).getOrThrow()
  }

  private suspend fun RoutingContext.handleCreate() {
    val user = resolveUser()
    if (user == null) {
      respondUnauthorized()
      return
    }

    val request = call.receive<CreateStudentRequest>()
    when (val outcome = studentService.createStudent(user.id, request.expectedHighSchoolGraduationDate).getOrThrow()) {
      is CreateStudentResult.Success -> {
        call.respond(HttpStatusCode.Created, StudentResponse(toPublicStudent(outcome.student)))
      }

      is CreateStudentResult.ValidationFailure -> {
        respondValidationFailure(outcome.fieldErrors)
      }

      is CreateStudentResult.AlreadyExists -> {
        call.respond(HttpStatusCode.Conflict, ErrorResponse("STUDENT_ALREADY_EXISTS", "A student profile already exists"))
      }
    }
  }

  private suspend fun RoutingContext.handleGet() {
    val user = resolveUser()
    if (user == null) {
      respondUnauthorized()
      return
    }

    val student = studentService.getStudentForUser(user.id).getOrThrow()
    if (student == null) {
      respondStudentNotFound()
    } else {
      call.respond(HttpStatusCode.OK, StudentResponse(toPublicStudent(student)))
    }
  }

  private suspend fun RoutingContext.handleUpdate() {
    val user = resolveUser()
    if (user == null) {
      respondUnauthorized()
      return
    }

    val request = call.receive<UpdateStudentRequest>()
    val outcome =
      studentService
        .updateStudent(
          userId = user.id,
          expectedVersion = StudentVersionId(request.version),
          graduationDateIso = request.expectedHighSchoolGraduationDate,
        ).getOrThrow()

    when (outcome) {
      is UpdateStudentResult.Success -> {
        call.respond(HttpStatusCode.OK, StudentResponse(toPublicStudent(outcome.student)))
      }

      is UpdateStudentResult.ValidationFailure -> {
        respondValidationFailure(outcome.fieldErrors)
      }

      is UpdateStudentResult.NotFound -> {
        respondStudentNotFound()
      }

      is UpdateStudentResult.VersionConflict -> {
        call.respond(HttpStatusCode.Conflict, ErrorResponse("VERSION_CONFLICT", "Student was modified concurrently"))
      }
    }
  }

  private suspend fun RoutingContext.handleDelete() {
    val token = tokenFrom(sessionConfig)
    if (token == null) {
      respondUnauthorized()
      return
    }
    val tokenHash = TokenHash.fromRawToken(token)
    val user = authService.getCurrentUser(tokenHash).getOrThrow()
    if (user == null) {
      respondUnauthorized()
      return
    }

    when (studentService.deleteStudentAndAccount(user.id, tokenHash).getOrThrow()) {
      is DeleteStudentResult.Success -> {
        clearSessionCookie()
        call.respond(HttpStatusCode.NoContent)
      }

      is DeleteStudentResult.NotFound -> {
        respondStudentNotFound()
      }
    }
  }

  private fun RoutingContext.clearSessionCookie() {
    call.response.cookies.append(
      name = sessionConfig.cookieName,
      value = "",
      domain = sessionConfig.cookieDomain,
      path = "/",
      secure = sessionConfig.cookieSecure,
      httpOnly = true,
      maxAge = 0L,
      extensions = mapOf("SameSite" to "Strict"),
    )
  }
}
