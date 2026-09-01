package ed.unicoach.college

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SearchIndexNotBuiltException
import ed.unicoach.db.dao.mapDatabaseError
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.CollegeSearchOutcome
import ed.unicoach.db.models.CollegeSimilarityOutcome
import ed.unicoach.db.models.CollegeSummary
import ed.unicoach.db.models.SimilarityAnchorOutcome
import ed.unicoach.db.models.SimilarityQuery
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.sql.SQLException

/**
 * The rejection [CollegeSearchService.searchByName] answers with when the
 * caller's words are longer than [CollegeSearchService.MAX_QUERY_LENGTH] — a
 * REJECTED INPUT, never a failed search.
 *
 * It carries the two NUMBERS and no model-facing prose: the sentence a chat
 * tool shows is composed at the boundary that speaks to the model (the same
 * move RFC 150 made when it took the program-filter refusal string out of the
 * DAO and left `CollegeSearchTool.refusalSentence` to word it). The
 * `IllegalArgumentException` message stays a complete sentence for every
 * caller — the REST route and the logs — that has nothing to compose with.
 *
 * It is its OWN type so a caller can tell this rejection from any other
 * `IllegalArgumentException`: [CollegeSearchService.handleFailures] catches
 * `Exception` around the DAO and the JDBC driver, so a supertype test would
 * report a driver fault to the caller as "your words were wrong". It still
 * EXTENDS `IllegalArgumentException` so nothing that already maps that type —
 * `GET /api/v1/colleges?q=` among them — changes behaviour.
 */
class QueryTooLongException(
  val maxLength: Int,
  val actualLength: Int,
) : IllegalArgumentException("query must be at most [$maxLength] characters (got [$actualLength])")

/**
 * Orchestrates structured college retrieval over the `db` module's
 * [CollegesDao]. Constructor-DI sibling of the coaching service: it owns the
 * connection boundary and the result-cap clamp, delegating all filtering and
 * ranking to the DAO's SQL.
 *
 * The only domain rule applied here is clamping the caller's `limit` into
 * [MIN_LIMIT]`..`[MAX_LIMIT]; everything else is passed through to the DAO.
 *
 * **The module convention (RFC 154 D-F).** Three clauses, stated here because
 * this is where a caller adding a fourth way to find a college is standing:
 *
 * - college SEARCH — structured or by name — goes through this service, over
 *   `college_search_index`. Every consumer takes one of these two doors, and a
 *   new finder is a new caller HERE, never a new query somewhere else. The
 *   consumers are deliberately NOT listed: they live in modules this one cannot
 *   see, so any list written here is stale the moment one is added;
 * - point-reads by `id` / `ipeds_unit_id` stay on `colleges`, which holds the
 *   facts. Admin's unfiltered browse is such a read, not a search (D-D);
 * - ingest and versioning WRITE `colleges` and rebuild the index from it.
 */
class CollegeSearchService(
  private val database: Database,
) {
  /**
   * Runs [query] after clamping its `limit` to [MIN_LIMIT]`..`[MAX_LIMIT]. A
   * zero-match query yields an empty page (a valid outcome), not a failure; a
   * program word the loaded vocabulary cannot expand comes back as
   * [CollegeSearchOutcome.UnresolvableProgramFilter]; and a database that has
   * been migrated but never ingested comes back as
   * [CollegeSearchOutcome.IndexNotBuilt] rather than as a page of zero matches.
   * `Result.failure` here means the database failed — and now actually does,
   * because [handleFailures] catches the raw JDBC `withConnection` throws
   * AROUND the DAO's `Result`.
   *
   * The `credentialLevel`-requires-`cipPrefix` pairing rule is GONE with the
   * field itself (RFC 150 D53), so the clamp is once again the only domain rule
   * this boundary applies.
   */
  suspend fun search(query: CollegeQuery): Result<CollegeSearchOutcome> {
    val clamped = query.copy(limit = query.limit.coerceIn(MIN_LIMIT, MAX_LIMIT))
    return handleFailures { database.withConnection { session -> CollegesDao.search(session, clamped) } }
      .onSuccess { outcome ->
        // Loud, on every call, because the state is invisible from the answer:
        // an unbuilt index is an operator fact, not a user one.
        if (outcome is CollegeSearchOutcome.IndexNotBuilt) {
          logger.error(
            "college search ran against an UNBUILT college_search_index: no rows and no build row -- " +
              "run the ingest's `search-index` phase (`bin/ingest-colleges`); until then every search " +
              "is refused rather than answered with a false zero",
          )
        }
      }
  }

  /**
   * Student-facing name search (RFC 137): [CollegesDao.searchByName] behind
   * the same connection boundary and `limit` clamp as [search]. The service
   * owns the query boundary so every caller inherits it, not just the REST
   * route: [query] is trimmed, a blank query is an empty success (nothing can
   * match nothing — never an unbounded scan), and a query longer than
   * [MAX_QUERY_LENGTH] is a failure carrying [QueryTooLongException] — a
   * PUBLISHED type, asked about through [rejectedInput], not an unstated
   * implementation choice a caller has to type-test for itself. A zero-match
   * query yields an empty list, not a failure.
   */
  suspend fun searchByName(
    query: String,
    limit: Int,
  ): Result<List<CollegeSummary>> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return Result.success(emptyList())
    if (trimmed.length > MAX_QUERY_LENGTH) {
      return Result.failure(QueryTooLongException(maxLength = MAX_QUERY_LENGTH, actualLength = trimmed.length))
    }
    val clamped = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
    return handleFailures { database.withConnection { session -> CollegesDao.searchByName(session, trimmed, clamped) } }
      .onFailure { error ->
        if (error is SearchIndexNotBuiltException) {
          // The throwable is passed, not just the sentence: the type, the stack
          // and any cause are the only record of this state anywhere in the
          // system -- every consumer above renders it as a fixed refusal.
          logger.error(
            "college name search ran against an UNBUILT college_search_index -- " +
              "run the ingest's `search-index` phase (`bin/ingest-colleges`); until then the picker " +
              "can find nothing, and is refused rather than answered with a false zero",
            error,
          )
        }
      }
  }

  /**
   * Whether [error] — a failure from [searchByName] — means the search index
   * has never been built, rather than that the query itself failed.
   *
   * The answer lives HERE because the fact is `:db`'s
   * ([ed.unicoach.db.dao.SearchIndexNotBuiltException]) and this service is the
   * boundary that owns what `:db` hands up. A caller that type-tested the
   * exception class itself would be reaching through this boundary for a
   * detail it should not have to know; a caller ASKS instead, and keeps working
   * if the DAO ever reports the state some other way.
   */
  fun isIndexNotBuilt(error: Throwable): Boolean = error is SearchIndexNotBuiltException

  /**
   * The REJECTED-INPUT rejection inside [error] — a failure from
   * [searchByName] — or `null` when [error] is anything else, meaning the
   * SEARCH failed rather than the caller's words being refused.
   *
   * The answer lives HERE for the same reason [isIndexNotBuilt] does: this
   * service is the boundary that owns what `:db` hands up, and only it knows
   * which failures are its own rules speaking. A caller that tested
   * `error is IllegalArgumentException` instead would be answering a different
   * question, because [handleFailures] catches `Exception` around the DAO and
   * the JDBC driver: any `IllegalArgumentException` from down there would be
   * reported to the caller as a fault in the words it wrote.
   *
   * It returns the rejection rather than a boolean so the caller can WORD it
   * from the numbers ([QueryTooLongException.maxLength],
   * [QueryTooLongException.actualLength]) instead of re-parsing a sentence.
   */
  fun rejectedInput(error: Throwable): QueryTooLongException? = error as? QueryTooLongException

  /**
   * The anchor of a "schools like X" query (RFC 153 D63), behind the same
   * connection boundary as [search].
   *
   * [SimilarityAnchorOutcome.NoSuchCollege] means this database has no college
   * with that id — a refusal the boundary above states in words, never a page
   * of peers for a school nobody named — and
   * [SimilarityAnchorOutcome.IndexNotBuilt] is the DEPLOYMENT state stated
   * separately, so an unbuilt index is never answered as "that school does not
   * exist".
   */
  suspend fun findSimilarityAnchor(id: CollegeId): Result<SimilarityAnchorOutcome> =
    handleFailures { database.withConnection { session -> CollegesDao.findSimilarityAnchor(session, id) } }
      .onSuccess { outcome ->
        if (outcome is SimilarityAnchorOutcome.IndexNotBuilt) {
          logger.error(
            "a similar-college anchor was resolved against an UNBUILT college_search_index -- " +
              "run the ingest's `search-index` phase (`bin/ingest-colleges`); until then the anchor " +
              "is refused rather than reported as a college this database does not hold",
          )
        }
      }

  /**
   * Runs [query] after clamping its `limit` to [MIN_LIMIT]`..`[MAX_SIMILAR_LIMIT]
   * (RFC 153 D70: a peer list is read, not scrolled), behind the same
   * `withConnection` + [mapDatabaseError] boundary as [search].
   *
   * The clamp is the only domain rule applied here; the axis drops, the weight
   * clamp and the anchor-relative expansions all happen before a
   * [SimilarityQuery] can exist, and the ranking itself is SQL.
   */
  suspend fun findSimilar(query: SimilarityQuery): Result<CollegeSimilarityOutcome> {
    val clamped = query.copy(filters = query.filters.copy(limit = query.limit.coerceIn(MIN_LIMIT, MAX_SIMILAR_LIMIT)))
    return handleFailures { database.withConnection { session -> CollegesDao.findSimilar(session, clamped) } }
      .onSuccess { outcome ->
        if (outcome is CollegeSimilarityOutcome.IndexNotBuilt) {
          logger.error(
            "similar-college search ran against an UNBUILT college_search_index: no rows and no build row -- " +
              "run the ingest's `search-index` phase (`bin/ingest-colleges`); until then every peer list " +
              "is refused rather than answered with a false zero",
          )
        }
      }
  }

  /**
   * Runs [block] and keeps EVERY failure inside the [Result] both entry points
   * promise.
   *
   * [ed.unicoach.db.Database.withConnection] throws raw JDBC — a pool timeout,
   * a failed commit, a failed rollback — around the enveloped `Result` the DAO
   * returns, so `Result.failure` did not in fact mean "the database failed": a
   * transient blip escaped as an exception instead. The tool boundary's
   * retryable `search_failed` shape is built from a failure, never from a
   * throw, so a blip reached the model as a hard failure. A `SQLException` is
   * mapped through the DAO's own [mapDatabaseError], so a transient SQLSTATE
   * keeps its [ed.unicoach.error.TransientError] trait here exactly as it does
   * inside the DAO. A [CancellationException] is not this function's to
   * report — structured concurrency must see it.
   *
   * Every failure is LOGGED here, once, with the throwable itself.
   */
  private suspend fun <T> handleFailures(block: suspend () -> Result<T>): Result<T> {
    val result =
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: SQLException) {
        Result.failure(mapDatabaseError(e))
      } catch (e: Exception) {
        Result.failure(e)
      }
    // The tool boundary answers a failure with a JSON envelope built from
    // `message` alone and never THROWS, so the coaching funnel's catch never
    // runs: this is the only place the exception type, the SQLSTATE, the stack
    // and the cause chain can still be seen. One statement here covers every
    // caller of this boundary, which four call sites of their own would not.
    return result.onFailure { error -> logger.error("college retrieval failed", error) }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(CollegeSearchService::class.java)

    const val MIN_LIMIT = 1
    const val MAX_LIMIT = 25

    /**
     * The result cap for [findSimilar] (RFC 153 D70), lower than [MAX_LIMIT] on
     * purpose: a peer list is a handful of schools a student reads, not a page
     * they scroll.
     */
    const val MAX_SIMILAR_LIMIT = 10

    /** The `q` boundary for [searchByName], referenced by the REST route's 400 validation. */
    const val MAX_QUERY_LENGTH = 100
  }
}
