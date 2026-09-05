package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.PublicVocabulary
import ed.unicoach.rest.models.PublicVocabularyEntry
import ed.unicoach.rest.models.VocabulariesResponse
import ed.unicoach.rest.rejectUnsupportedMethods
import ed.unicoach.student.StudentService
import ed.unicoach.vocabulary.PublishedVocabularies
import ed.unicoach.vocabulary.VocabularyService
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * The served vocabularies (RFC 165): `GET /api/v1/vocabularies`, the closed
 * lists a client renders instead of transcribing.
 *
 * Authenticated session required, and deliberately **no student-profile gate** —
 * the [CollegeRouteHandler] precedent, whose reasoning transfers verbatim:
 * reading a published vocabulary is not an operation on your money profile, and
 * the screen that needs the picker is exactly the screen the caller has no
 * profile on yet.
 *
 * A supplier fault (a residency code with no `us_states` row) is thrown, not
 * swallowed: `StatusPages` answers 500 rather than the route serving a
 * vocabulary smaller than the set the write path accepts.
 */
class VocabularyRouteHandler(
  authService: AuthService,
  studentService: StudentService,
  private val vocabularyService: VocabularyService,
  sessionConfig: SessionConfig,
) : CallerResolution by SessionCallerResolution(authService, studentService, sessionConfig) {
  fun registerRoutes(route: Route) {
    route.route("/api/v1/vocabularies") {
      get { handleGet() }
      rejectUnsupportedMethods(HttpMethod.Get)
    }
  }

  private suspend fun RoutingContext.handleGet() {
    resolveUser() ?: return respondUnauthorized()
    call.respond(HttpStatusCode.OK, toResponse(vocabularyService.publishedVocabularies().getOrThrow()))
  }

  /**
   * The domain document as the wire shows it: vocabularies keyed by name, and
   * each entry's declared extras flattened into the entry object by
   * [PublicVocabularyEntry]'s any-getter.
   */
  private fun toResponse(published: PublishedVocabularies): VocabulariesResponse =
    VocabulariesResponse(
      version = published.version,
      vocabularies =
        published.vocabularies.associateTo(LinkedHashMap()) { vocabulary ->
          vocabulary.name to
            PublicVocabulary(
              vocabulary.entries.map { PublicVocabularyEntry(value = it.value, label = it.label, extras = it.extras) },
            )
        },
    )
}
