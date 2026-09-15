package ed.unicoach.coaching

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CodebooksDao
import ed.unicoach.db.dao.mapDatabaseError
import ed.unicoach.db.models.IntendedSubjectName

/**
 * The subject taxonomy as a browsable, searchable LIST (RFC 205 D29/D41) —
 * what the picker and the chat tool both resolve a student's words against.
 *
 * It serves OUR subjects, not the federal file. A student answers at our grain,
 * so `slug` is the identity the client sends back and `name` is the only thing
 * anyone reads (D39). DEPRECATED subjects are excluded by
 * [CodebooksDao.searchSubjects] — a subject the taxonomy file no longer carries
 * is offered to nobody.
 *
 * No synonym is stored and none is invented (D15). A term that is not a
 * published subject name simply matches nothing, and the caller says "that is
 * not a subject on the list" rather than quietly offering something near it. A
 * curated synonym aid is a later decision.
 */
class SubjectVocabularyService(
  private val database: Database,
) {
  /**
   * The list, optionally filtered by [query] and capped at [limit].
   *
   * [query] is NULL for the whole list, in name order. That is a deliberate
   * divergence from `GET /api/v1/colleges`, which requires its `q`: this corpus
   * is closed and small, so a picker that shows nothing until the student types
   * is simply a worse picker. A blank-but-PRESENT query is a caller error and
   * the route refuses it before it gets here.
   */
  suspend fun list(
    query: String? = null,
    limit: Int = MAX_LIMIT,
  ): Result<List<IntendedSubjectName>> =
    try {
      database.withConnection { session ->
        CodebooksDao
          .searchSubjects(session, query, limit.coerceIn(MIN_LIMIT, MAX_LIMIT))
          .map { rows -> rows.map { IntendedSubjectName(slug = it.slug, name = it.name) } }
      }
    } catch (e: Exception) {
      // Every arm in this class maps, for the reason given in
      // [IntendedSubjectsService.replace]: the intended-subject invariants are
      // DEFERRABLE INITIALLY DEFERRED triggers that raise at COMMIT, inside
      // `withConnection` and past the point any DAO mapper can see them. An
      // unmapped `PSQLException` is neither PermanentError nor TransientError,
      // so a retryable failure would be answered as a flat 500.
      Result.failure(mapDatabaseError(e))
    }

  /**
   * Resolves [slugs] to names, DEPRECATED rows included — the read that answers
   * "is every slug this student sent a real subject?" and, for the ones that
   * are, what they are called.
   *
   * A slug with no `subjects` row at all is simply absent from the returned map,
   * which is how the caller tells an unknown slug from a known one without a
   * second query.
   */
  suspend fun namesFor(slugs: Collection<String>): Result<Map<String, String>> =
    try {
      database.withConnection { session -> CodebooksDao.subjectNames(session, slugs) }
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  /** The slugs among [slugs] that are ACTIVE — the ones a student may newly choose. */
  suspend fun activeSlugs(slugs: Collection<String>): Result<Set<String>> =
    try {
      database.withConnection { session ->
        CodebooksDao.subjects(session).map { active -> active.map { it.slug }.toSet().intersect(slugs.toSet()) }
      }
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  companion object {
    /**
     * The clamp, 1..200.
     *
     * `GET /api/v1/colleges` clamps at 25 because its corpus is 6,273 rows and
     * no picker shows them all. This corpus is a closed authored list of a
     * couple of hundred, so the whole of it IS a reasonable page and the default
     * is the whole list. The bound is a bound on this route, not a measurement
     * of the file: it does not change when the file does.
     */
    const val MIN_LIMIT: Int = 1
    const val MAX_LIMIT: Int = 200

    /**
     * The longest `q` this route accepts, the `CollegeSearchService`
     * MAX_QUERY_LENGTH convention: a bound the route's 400 can reference by
     * name, so the refusal names the observed length instead of a magic number.
     */
    const val MAX_QUERY_LENGTH: Int = 100
  }
}
