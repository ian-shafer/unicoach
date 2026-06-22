package ed.unicoach.college

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.models.CollegeMatch
import ed.unicoach.db.models.CollegeQuery

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
   * zero-match query yields an empty list (a valid outcome), not a failure.
   */
  suspend fun search(query: CollegeQuery): Result<List<CollegeMatch>> {
    val clamped = query.copy(limit = query.limit.coerceIn(MIN_LIMIT, MAX_LIMIT))
    return database.withConnection { session -> CollegesDao.search(session, clamped) }
  }

  companion object {
    const val MIN_LIMIT = 1
    const val MAX_LIMIT = 25
  }
}
