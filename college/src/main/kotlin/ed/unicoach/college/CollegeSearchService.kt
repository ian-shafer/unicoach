package ed.unicoach.college

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.CollegeSearchPage
import ed.unicoach.db.models.CollegeSummary

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
   * zero-match query yields an empty page (a valid outcome), not a failure.
   * `credentialLevel` narrows the program join, so it is rejected without a
   * `cipPrefix` to join on (RFC 139) — validated here so every caller inherits
   * the boundary, not just the chat tool.
   */
  suspend fun search(query: CollegeQuery): Result<CollegeSearchPage> {
    if (query.credentialLevel != null && query.cipPrefix == null) {
      return Result.failure(
        IllegalArgumentException(
          "credentialLevel [${query.credentialLevel}] requires a cipPrefix program filter, but cipPrefix was absent",
        ),
      )
    }
    val clamped = query.copy(limit = query.limit.coerceIn(MIN_LIMIT, MAX_LIMIT))
    return database.withConnection { session -> CollegesDao.search(session, clamped) }
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
    return database.withConnection { session -> CollegesDao.searchByName(session, trimmed, clamped) }
  }

  companion object {
    const val MIN_LIMIT = 1
    const val MAX_LIMIT = 25

    /** The `q` boundary for [searchByName], referenced by the REST route's 400 validation. */
    const val MAX_QUERY_LENGTH = 100
  }
}
