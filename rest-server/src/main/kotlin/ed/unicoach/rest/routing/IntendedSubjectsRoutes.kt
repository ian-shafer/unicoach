package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.coaching.IntendedSubjectsChoice
import ed.unicoach.coaching.IntendedSubjectsService
import ed.unicoach.coaching.IntendedSubjectsUpdate
import ed.unicoach.coaching.UpdateIntendedSubjectsResult
import ed.unicoach.coaching.intendedSubjectsChoice
import ed.unicoach.coaching.literalTrueViolations
import ed.unicoach.db.dao.IntendedSubjectsDao
import ed.unicoach.db.models.IntendedSubjectAnswer
import ed.unicoach.error.FieldError
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.IntendedSubjectsResponse
import ed.unicoach.rest.models.PublicIntendedSubjects
import ed.unicoach.rest.models.PublicSubject
import ed.unicoach.rest.models.UpdateIntendedSubjectsRequest
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
 * The screen door for what a student wants to study (RFC 205 D17):
 * `GET`/`PUT /api/v1/students/me/intended-subjects`.
 *
 * A SIBLING of the money-profile route, not an extension of it (D37) — the money
 * profile is the MONEY profile, and what a student wants to study is not money.
 * It reuses that route's caller-resolution ladder and its "at most one of these
 * keys" validation idiom (`count { it } > 1` folded into one pooled 400).
 *
 * ONE deliberate divergence: there is no 404. The money route 404s because a
 * money-profile ROW is genuinely absent before the first write; here the answer
 * is never absent, only `unanswered`, and `unanswered` is a thing this route can
 * state. So `GET` is always a 200.
 */
class IntendedSubjectsRouteHandler(
  authService: AuthService,
  studentService: StudentService,
  private val intendedSubjectsService: IntendedSubjectsService,
  sessionConfig: SessionConfig,
) : CallerResolution by SessionCallerResolution(authService, studentService, sessionConfig) {
  fun registerRoutes(route: Route) {
    route.route("/api/v1/students/me/intended-subjects") {
      get { handleGet() }
      put { handlePut() }
      rejectUnsupportedMethods(HttpMethod.Get, HttpMethod.Put)
    }
  }

  private suspend fun RoutingContext.handleGet() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()
    respondAnswer(intendedSubjectsService.getForStudent(student.id).getOrThrow())
  }

  private suspend fun RoutingContext.handlePut() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()
    val request = call.receive<UpdateIntendedSubjectsRequest>()

    val update =
      when (val parsed = parseUpdate(request)) {
        is ParsedUpdate.Ok -> parsed.update
        is ParsedUpdate.Invalid -> return respondValidationFailed(parsed.fieldErrors)
      }

    when (val outcome = intendedSubjectsService.replace(student.id, update).getOrThrow()) {
      is UpdateIntendedSubjectsResult.Success -> respondAnswer(outcome.answer)
      is UpdateIntendedSubjectsResult.Rejected -> respondValidationFailed(outcome.reasons.map(::toFieldError))
    }
  }

  /** The parse outcome for one PUT body: a typed [IntendedSubjectsUpdate] or the field errors. */
  private sealed interface ParsedUpdate {
    data class Ok(
      val update: IntendedSubjectsUpdate,
    ) : ParsedUpdate

    data class Invalid(
      val fieldErrors: List<FieldError>,
    ) : ParsedUpdate
  }

  /**
   * At most one of the three keys, and at least one — decided by
   * [intendedSubjectsChoice], the rule the chat tool reads the same call by, so
   * the two doors cannot disagree about what an answer is. Only the 400 body is
   * this route's own.
   *
   * An empty `subjects` ARRAY is a different thing entirely and is allowed: it
   * is the D17 case, and the service turns it into `undecided`.
   *
   * What the three keys are counted BY is presence, not truth, so `declined` or
   * `undecided` sent as `false` is refused here by
   * [ed.unicoach.coaching.literalTrueViolations] — the chat tool's rule, read
   * rather than re-stated — instead of being dropped and the rest of the body
   * served.
   */
  private fun parseUpdate(request: UpdateIntendedSubjectsRequest): ParsedUpdate {
    // Presence, not truth: a sent `false` is a surplus key, and dropping it
    // silently is how this door used to accept a body the chat tool refused.
    // Checked BEFORE the choice, as the chat tool checks it, so the same
    // malformed shape gets the same verdict at both doors.
    val surplus = literalTrueViolations("declined" to request.declined, "undecided" to request.undecided)
    if (surplus.isNotEmpty()) {
      return ParsedUpdate.Invalid(
        surplus.map { field -> FieldError(field, "Must be true when present; omit $field entirely otherwise") },
      )
    }

    return when (
      val choice =
        intendedSubjectsChoice(request.subjects, declined = request.declined == true, undecided = request.undecided == true)
    ) {
      is IntendedSubjectsChoice.Subjects -> {
        ParsedUpdate.Ok(IntendedSubjectsUpdate.Subjects(choice.subjects))
      }

      is IntendedSubjectsChoice.Stated -> {
        ParsedUpdate.Ok(choice.update)
      }

      IntendedSubjectsChoice.Several -> {
        ParsedUpdate.Invalid(
          listOf(FieldError("subjects", "At most one of subjects, declined, undecided may be set")),
        )
      }

      IntendedSubjectsChoice.None -> {
        ParsedUpdate.Invalid(
          listOf(FieldError("subjects", "Exactly one of subjects, declined, undecided must be set")),
        )
      }
    }
  }

  /**
   * One refusal as one [FieldError], each naming the input it is about.
   *
   * The unknown-slug sentence is the one the RFC fixes in words (D15): a term
   * that is not a subject is refused WITH A REASON rather than silently mapped
   * to something near it, because no synonym is stored and none is invented.
   */
  private fun toFieldError(reason: UpdateIntendedSubjectsResult.Reason): FieldError =
    when (reason) {
      is UpdateIntendedSubjectsResult.Reason.TooMany -> {
        FieldError(
          "subjects",
          "At most ${IntendedSubjectsDao.MAX_SUBJECTS} subjects may be stored (got ${reason.submitted})",
        )
      }

      is UpdateIntendedSubjectsResult.Reason.NotASubject -> {
        FieldError("subjects", "[${reason.slug}] is not a subject on the list")
      }

      is UpdateIntendedSubjectsResult.Reason.NoLongerOffered -> {
        FieldError("subjects", "[${reason.slug}] is no longer offered and is not already stored")
      }

      is UpdateIntendedSubjectsResult.Reason.Duplicate -> {
        FieldError("subjects", "[${reason.slug}] was sent more than once")
      }
    }

  private suspend fun RoutingContext.respondAnswer(answer: IntendedSubjectAnswer) {
    call.respond(
      HttpStatusCode.OK,
      IntendedSubjectsResponse(
        PublicIntendedSubjects(
          status = answer.status.value,
          subjects = answer.subjects.map { PublicSubject(slug = it.slug, name = it.name) },
        ),
      ),
    )
  }
}
