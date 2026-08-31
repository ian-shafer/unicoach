package ed.unicoach.college

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SearchIndexNotBuiltException
import ed.unicoach.db.dao.mapDatabaseError
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.CollegeSearchOutcome
import ed.unicoach.db.models.CollegeSummary
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.sql.SQLException

/**
 * Orchestrates structured college retrieval over the `db` module's
 * [CollegesDao]. Constructor-DI sibling of the coaching service: it owns the
 * connection boundary and the result-cap clamp, delegating all filtering and
 * ranking to the DAO's SQL.
 *
 * The only domain rule applied here is clamping the caller's `limit` into
 * [MIN_LIMIT]`..`[MAX_LIMIT]; everything else is passed through to the DAO.
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
   * [MAX_QUERY_LENGTH] is a failure. A zero-match query yields an empty list,
   * not a failure.
   */
  suspend fun searchByName(
    query: String,
    limit: Int,
  ): Result<List<CollegeSummary>> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return Result.success(emptyList())
    if (trimmed.length > MAX_QUERY_LENGTH) {
      return Result.failure(
        IllegalArgumentException("query must be at most [$MAX_QUERY_LENGTH] characters (got [${trimmed.length}])"),
      )
    }
    val clamped = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
    return handleFailures { database.withConnection { session -> CollegesDao.searchByName(session, trimmed, clamped) } }
      .onFailure { error ->
        if (error is SearchIndexNotBuiltException) {
          logger.error(
            "college name search ran against an UNBUILT college_search_index -- " +
              "run the ingest's `search-index` phase (`bin/ingest-colleges`); until then the picker " +
              "can find nothing, and is refused rather than answered with a false zero",
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
   */
  private suspend fun <T> handleFailures(block: suspend () -> Result<T>): Result<T> =
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: SQLException) {
      Result.failure(mapDatabaseError(e))
    } catch (e: Exception) {
      Result.failure(e)
    }

  companion object {
    private val logger = LoggerFactory.getLogger(CollegeSearchService::class.java)

    const val MIN_LIMIT = 1
    const val MAX_LIMIT = 25

    /** The `q` boundary for [searchByName], referenced by the REST route's 400 validation. */
    const val MAX_QUERY_LENGTH = 100
  }
}
