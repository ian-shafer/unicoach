package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.coaching.moneyprofile.FieldUpdate
import ed.unicoach.coaching.moneyprofile.GetMoneyProfileResult
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.moneyprofile.MoneyProfileUpdate
import ed.unicoach.coaching.moneyprofile.UpsertMoneyProfileResult
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyProfile
import ed.unicoach.error.FieldError
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.models.MoneyProfileResponse
import ed.unicoach.rest.models.PublicMoneyProfile
import ed.unicoach.rest.models.UpdateMoneyProfileRequest
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
 * The student-facing money-profile surface (RFC 134), mirroring
 * [CollegeListRouteHandler]'s auth umbrella: `GET /money-profile` (200 with the
 * profile, 404 before the first write) and `PUT /money-profile` (idempotent
 * create-or-update of any subset of fields; per field value-or-declined-or-
 * clear).
 */
class MoneyProfileRouteHandler(
  authService: AuthService,
  studentService: StudentService,
  private val moneyProfileService: MoneyProfileService,
  sessionConfig: SessionConfig,
) : CallerResolution by SessionCallerResolution(authService, studentService, sessionConfig) {
  fun registerRoutes(route: Route) {
    route.route("/api/v1/students/me/money-profile") {
      get { handleGet() }
      put { handlePut() }
      rejectUnsupportedMethods(HttpMethod.Get, HttpMethod.Put)
    }
  }

  private suspend fun RoutingContext.handleGet() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()

    when (val outcome = moneyProfileService.getForStudent(student.id).getOrThrow()) {
      is GetMoneyProfileResult.Found -> {
        call.respond(HttpStatusCode.OK, MoneyProfileResponse(toPublicProfile(outcome.profile)))
      }

      GetMoneyProfileResult.NotFound -> {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorCode.NOT_FOUND, "No money profile yet"))
      }
    }
  }

  private suspend fun RoutingContext.handlePut() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()
    val request = call.receive<UpdateMoneyProfileRequest>()

    val update =
      when (val parsed = parseUpdate(request)) {
        is ParsedUpdate.Ok -> parsed.update
        is ParsedUpdate.Invalid -> return respondValidationFailed(parsed.fieldErrors)
      }

    when (val outcome = moneyProfileService.upsert(student.id, update).getOrThrow()) {
      is UpsertMoneyProfileResult.Success -> {
        call.respond(HttpStatusCode.OK, MoneyProfileResponse(toPublicProfile(outcome.profile)))
      }

      UpsertMoneyProfileResult.StudentNotFound -> {
        // The caller's student row vanished between resolution and the write; the
        // college-list convention for the same race is a 404.
        call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorCode.NOT_FOUND, "Owning student not found"))
      }
    }
  }

  /** The parse outcome for one PUT body: a typed [MoneyProfileUpdate] or the field errors. */
  private sealed interface ParsedUpdate {
    data class Ok(
      val update: MoneyProfileUpdate,
    ) : ParsedUpdate

    data class Invalid(
      val fieldErrors: List<FieldError>,
    ) : ParsedUpdate
  }

  /** Assembles the request-level parse from the two per-field parsers, pooling every [FieldError] into one 400. */
  private fun parseUpdate(request: UpdateMoneyProfileRequest): ParsedUpdate {
    val income = parseIncomeUpdate(request)
    val residency = parseResidencyUpdate(request)
    val errors = listOf(income, residency).filterIsInstance<FieldParse.Invalid>().flatMap { it.errors }
    if (errors.isNotEmpty()) return ParsedUpdate.Invalid(errors)
    // errors empty => neither parse is Invalid, so both field parses are Ok and the casts below cannot fail.
    return ParsedUpdate.Ok(
      MoneyProfileUpdate(
        income = (income as FieldParse.Ok<IncomeBand>).update,
        residency = (residency as FieldParse.Ok<String>).update,
      ),
    )
  }

  /** The parse outcome for one field: its [FieldUpdate] (null: untouched) or the field errors — the chat tool's shape. */
  private sealed interface FieldParse<out T> {
    data class Ok<T>(
      val update: FieldUpdate<T>?,
    ) : FieldParse<T>

    data class Invalid(
      val errors: List<FieldError>,
    ) : FieldParse<Nothing>
  }

  /** Income: value/declined/clear are mutually exclusive; a value must be a known [IncomeBand]; folds to one [FieldUpdate]. */
  private fun parseIncomeUpdate(request: UpdateMoneyProfileRequest): FieldParse<IncomeBand> {
    val errors = mutableListOf<FieldError>()
    val selections = listOf(request.incomeBand != null, request.incomeBandDeclined, request.incomeBandClear).count { it }
    if (selections > 1) {
      errors.add(FieldError("incomeBand", "At most one of incomeBand, incomeBandDeclined, incomeBandClear may be set"))
    }
    val band =
      request.incomeBand?.let { raw ->
        val parsed = IncomeBand.fromValue(raw)
        if (parsed == null) errors.add(FieldError("incomeBand", "Unknown income band value: $raw"))
        parsed
      }
    if (errors.isNotEmpty()) return FieldParse.Invalid(errors)
    return FieldParse.Ok(
      when {
        band != null -> FieldUpdate.Set(band)
        request.incomeBandDeclined -> FieldUpdate.Decline
        request.incomeBandClear -> FieldUpdate.Clear
        else -> null
      },
    )
  }

  /** Residency: value/declined/clear are mutually exclusive; a value must normalize to a USPS code; folds to one [FieldUpdate]. */
  private fun parseResidencyUpdate(request: UpdateMoneyProfileRequest): FieldParse<String> {
    val errors = mutableListOf<FieldError>()
    val selections = listOf(request.residencyState != null, request.residencyDeclined, request.residencyClear).count { it }
    if (selections > 1) {
      errors.add(FieldError("residencyState", "At most one of residencyState, residencyDeclined, residencyClear may be set"))
    }
    val normalized =
      request.residencyState?.let { raw ->
        val state = MoneyProfileService.parseResidencyState(raw)
        if (state == null) errors.add(FieldError("residencyState", "Must be a two-letter US state postal code, got: $raw"))
        state
      }
    if (errors.isNotEmpty()) return FieldParse.Invalid(errors)
    return FieldParse.Ok(
      when {
        normalized != null -> FieldUpdate.Set(normalized)
        request.residencyDeclined -> FieldUpdate.Decline
        request.residencyClear -> FieldUpdate.Clear
        else -> null
      },
    )
  }

  private fun toPublicProfile(profile: MoneyProfile): PublicMoneyProfile =
    PublicMoneyProfile(
      incomeBandStatus = profile.incomeBandStatus.value,
      incomeBand = profile.incomeBand?.value,
      residencyStatus = profile.residencyStatus.value,
      residencyState = profile.residencyState,
      version = profile.version,
      createdAt = profile.createdAt,
      updatedAt = profile.updatedAt,
    )
}
