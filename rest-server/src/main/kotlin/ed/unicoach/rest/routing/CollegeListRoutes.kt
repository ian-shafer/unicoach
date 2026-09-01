package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.coaching.collegelist.AddToListResult
import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.coaching.collegelist.GetEntryResult
import ed.unicoach.coaching.collegelist.RemoveEntryResult
import ed.unicoach.coaching.collegelist.UpdateEntryResult
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.CollegeListEntryId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.Observation
import ed.unicoach.db.models.ObservationId
import ed.unicoach.error.FieldError
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.CollegeListEntryResponse
import ed.unicoach.rest.models.CollegeListResponse
import ed.unicoach.rest.models.CreateCollegeListEntryRequest
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.models.ObservationSummary
import ed.unicoach.rest.models.PublicCollegeListEntry
import ed.unicoach.rest.models.UpdateCollegeListEntryRequest
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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

class CollegeListRouteHandler(
  authService: AuthService,
  studentService: StudentService,
  private val collegeListService: CollegeListService,
  sessionConfig: SessionConfig,
) : CallerResolution by SessionCallerResolution(authService, studentService, sessionConfig) {
  fun registerRoutes(route: Route) {
    route.route("/api/v1/students/me/college-list") {
      route("") {
        post { handleCreate() }
        get { handleList() }
        rejectUnsupportedMethods(HttpMethod.Post, HttpMethod.Get)
      }
      route("/{entryId}") {
        get { handleGet() }
        patch { handleUpdate() }
        delete { handleDelete() }
        rejectUnsupportedMethods(HttpMethod.Get, HttpMethod.Patch, HttpMethod.Delete)
      }
    }
  }

  private suspend fun RoutingContext.respondNotFound(
    message: String = "No such college list entry",
    fieldErrors: List<FieldError>? = null,
  ) {
    call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorCode.NOT_FOUND, message, fieldErrors))
  }

  /** The `observationId` field-error convention used for a cited observation that is absent or not owned by the caller. */
  private fun observationNotFoundFieldErrors(observationId: ObservationId): List<FieldError> =
    listOf(FieldError("observationId", "No such observation: ${observationId.value}"))

  private suspend fun RoutingContext.respondVersionConflict() {
    call.respond(HttpStatusCode.Conflict, ErrorResponse(ErrorCode.VERSION_CONFLICT, "College list entry was modified concurrently"))
  }

  /**
   * The override this request asks for. An absent plan is `null` and not an
   * error -- on an update that is the client clearing the override back to the
   * family's usual plan (RFC 152 D2a) -- while a value no [LivingArrangement]
   * names is handed to [onUnknown], which never returns.
   *
   * One home, so create and update cannot word one refusal two ways, and each
   * handler still returns its own 400.
   */
  private inline fun mapLivingPlan(
    raw: String?,
    onUnknown: (FieldError) -> Nothing,
  ): LivingArrangement? =
    if (raw == null) {
      null
    } else {
      LivingArrangement.fromValue(raw) ?: onUnknown(FieldError("livingPlan", "Unknown living plan value: [$raw]"))
    }

  private fun RoutingContext.pathEntryId(): CollegeListEntryId? {
    val raw = call.parameters["entryId"] ?: return null
    return runCatching { CollegeListEntryId(java.util.UUID.fromString(raw)) }.getOrNull()
  }

  // ---------------------------------------------------------------------------
  // Handlers
  // ---------------------------------------------------------------------------

  private suspend fun RoutingContext.handleCreate() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()
    val request = call.receive<CreateCollegeListEntryRequest>()

    val status = CollegeListEntryStatus.fromValue(request.status)
    if (status == null) {
      return respondValidationFailed(listOf(FieldError("status", "Unknown status value")))
    }
    val livingPlan = mapLivingPlan(request.livingPlan) { return respondValidationFailed(listOf(it)) }

    val outcome =
      collegeListService
        .addToList(
          studentId = student.id,
          collegeId = CollegeId(request.collegeId),
          status = status,
          reasons = request.reasons,
          livingPlan = livingPlan,
          observationIds = request.observationIds.map { ObservationId(it) },
        ).getOrThrow()

    when (outcome) {
      is AddToListResult.Success -> {
        val name = resolveName(outcome.entry)
        call.respond(HttpStatusCode.Created, CollegeListEntryResponse(toPublicEntry(outcome.entry, outcome.supportingObservations, name)))
      }

      is AddToListResult.CollegeNotFound -> {
        respondNotFound("No such college")
      }

      is AddToListResult.AlreadyOnList -> {
        call.respond(HttpStatusCode.Conflict, ErrorResponse(ErrorCode.CONFLICT, "College is already on the list"))
      }

      is AddToListResult.InvalidReasons -> {
        respondValidationFailed(listOf(FieldError("reasons", "Reasons must be non-empty and at most 2048 characters")))
      }

      is AddToListResult.ObservationNotFound -> {
        respondNotFound("No such observation", observationNotFoundFieldErrors(outcome.observationId))
      }
    }
  }

  private suspend fun RoutingContext.handleList() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()

    val entries = collegeListService.listForStudent(student.id).getOrThrow()
    val names = resolveNames(entries.map { it.entry })
    call.respond(
      HttpStatusCode.OK,
      CollegeListResponse(entries.zip(names) { row, name -> toPublicEntry(row.entry, row.supportingObservations, name) }),
    )
  }

  private suspend fun RoutingContext.handleGet() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()
    val entryId = pathEntryId() ?: return respondNotFound()

    when (val outcome = collegeListService.getForStudent(student.id, entryId).getOrThrow()) {
      is GetEntryResult.Found -> {
        val name = resolveName(outcome.entry)
        call.respond(HttpStatusCode.OK, CollegeListEntryResponse(toPublicEntry(outcome.entry, outcome.supportingObservations, name)))
      }

      GetEntryResult.NotFound -> {
        respondNotFound()
      }
    }
  }

  private suspend fun RoutingContext.handleUpdate() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()
    val entryId = pathEntryId() ?: return respondNotFound()
    val request = call.receive<UpdateCollegeListEntryRequest>()

    val status = CollegeListEntryStatus.fromValue(request.status)
    if (status == null) {
      return respondValidationFailed(listOf(FieldError("status", "Unknown status value")))
    }
    val livingPlan = mapLivingPlan(request.livingPlan) { return respondValidationFailed(listOf(it)) }

    val outcome =
      collegeListService
        .updateEntry(
          studentId = student.id,
          entryId = entryId,
          expectedVersion = request.version,
          status = status,
          reasons = request.reasons,
          livingPlan = livingPlan,
          addObservationIds = request.addObservationIds.map { ObservationId(it) },
        ).getOrThrow()

    when (outcome) {
      is UpdateEntryResult.Success -> {
        val name = resolveName(outcome.entry)
        call.respond(HttpStatusCode.OK, CollegeListEntryResponse(toPublicEntry(outcome.entry, outcome.supportingObservations, name)))
      }

      is UpdateEntryResult.NotFound -> {
        respondNotFound()
      }

      is UpdateEntryResult.VersionConflict -> {
        respondVersionConflict()
      }

      is UpdateEntryResult.InvalidReasons -> {
        respondValidationFailed(listOf(FieldError("reasons", "Reasons must be non-empty and at most 2048 characters")))
      }

      is UpdateEntryResult.ObservationNotFound -> {
        respondNotFound("No such observation", observationNotFoundFieldErrors(outcome.observationId))
      }
    }
  }

  private suspend fun RoutingContext.handleDelete() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()
    val entryId = pathEntryId() ?: return respondNotFound()

    val versionRaw = call.request.queryParameters["version"]
    val version = versionRaw?.toIntOrNull()
    if (version == null) {
      return respondValidationFailed(listOf(FieldError("version", "Missing or non-integer version")))
    }

    when (collegeListService.removeFromList(student.id, entryId, version).getOrThrow()) {
      is RemoveEntryResult.Success -> {
        call.respond(HttpStatusCode.NoContent)
      }

      is RemoveEntryResult.NotFound -> {
        respondNotFound()
      }

      is RemoveEntryResult.VersionConflict -> {
        respondVersionConflict()
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Projections
  // ---------------------------------------------------------------------------

  /**
   * Resolves the display name for each of [entries]' colleges (RFC 137),
   * index-aligned with [entries]: one batch read for the whole response,
   * whatever its size. A college id that resolves to no name is a broken FK
   * invariant (`ON DELETE RESTRICT`; colleges are never deleted), so the
   * thrown error surfaces as a 500 rather than a fabricated placeholder name.
   */
  private suspend fun resolveNames(entries: List<CollegeListEntry>): List<String> {
    val names = collegeListService.listNames(entries.map { it.collegeId }).getOrThrow()
    return entries.map { entry ->
      checkNotNull(names[entry.collegeId]) { "No college row for [${entry.collegeId.value}] (broken FK invariant)" }
    }
  }

  /** The single-entry handlers' fetch: resolves the one name [resolveNames] proves present. */
  private suspend fun resolveName(entry: CollegeListEntry): String = resolveNames(listOf(entry)).single()

  private fun toPublicEntry(
    entry: CollegeListEntry,
    observations: List<Observation>,
    collegeName: String,
  ): PublicCollegeListEntry =
    PublicCollegeListEntry(
      id = entry.id.value,
      collegeId = entry.collegeId.value,
      collegeName = collegeName,
      status = entry.status.value,
      reasons = entry.reasons,
      livingPlan = entry.livingPlan?.value,
      version = entry.version,
      createdAt = entry.createdAt,
      updatedAt = entry.updatedAt,
      supportingObservations =
        observations.map { observation ->
          ObservationSummary(id = observation.id.value, quote = observation.quote, utteredAt = observation.utteredAt)
        },
    )
}
