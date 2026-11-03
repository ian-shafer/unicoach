package ed.unicoach.queue

import ed.unicoach.db.Database
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.mapDatabaseError
import ed.unicoach.queue.dao.JobInsertResult
import ed.unicoach.queue.dao.JobsDao
import kotlinx.serialization.json.JsonObject

sealed interface EnqueueResult {
  data class Success(
    val job: Job,
  ) : EnqueueResult

  class DatabaseFailure(
    val error: Exception,
  ) : EnqueueResult
}

/**
 * Thin facade over [JobsDao] exposing the public enqueue API.
 * All enqueued jobs start in SCHEDULED status.
 *
 * `final`: this is a production request-path facade, not a substitution seam.
 * Tests that need an enqueue to fail inject a [JobsDao] whose `insert` returns
 * [JobInsertResult.DatabaseFailure] (which this maps to
 * [EnqueueResult.DatabaseFailure]) via the [jobsDao] constructor parameter,
 * rather than subclassing the facade.
 */
class QueueService(
  private val database: Database,
  private val jobsDao: JobsDao = JobsDao(),
) {
  suspend fun enqueue(
    jobType: JobType,
    payload: JsonObject,
    maxAttempts: Int? = null,
    delay: kotlin.time.Duration? = null,
  ): EnqueueResult =
    database.withConnection { session ->
      enqueue(session, jobType, payload, maxAttempts, delay)
    }

  /**
   * Session-threaded enqueue: inserts the job on the caller's open [SqlSession] so
   * it commits and rolls back with the caller's transaction. Use this when the
   * enqueue is a *required* side-effect that must be atomic with the request's
   * own database work; the connection-owning overload above stays for best-effort
   * fire-and-forget callers.
   */
  fun enqueue(
    session: SqlSession,
    jobType: JobType,
    payload: JsonObject,
    maxAttempts: Int? = null,
    delay: kotlin.time.Duration? = null,
  ): EnqueueResult =
    when (val result = jobsDao.insert(session, NewJob(jobType, payload, maxAttempts, delay))) {
      is JobInsertResult.Success -> EnqueueResult.Success(result.job)
      is JobInsertResult.DatabaseFailure -> EnqueueResult.DatabaseFailure(result.error)
    }

  /**
   * The REQUIRED enqueue (ASYNC_WORK.md rule 2): the session-threaded [enqueue],
   * whose failure is thrown, so the caller's transaction rolls back with it. It
   * is thrown mapped as every DAO maps one ([mapDatabaseError]): a classified
   * `DaoException` with a fixed message, the driver's own exception -- whose
   * text can quote the failing row, payload included -- kept only as its cause.
   * The one home of that rule; a caller that wants to name its own context on
   * the failure catches and wraps it.
   */
  fun enqueueOrThrow(
    session: SqlSession,
    jobType: JobType,
    payload: JsonObject,
    maxAttempts: Int? = null,
    delay: kotlin.time.Duration? = null,
  ): Job =
    when (val result = enqueue(session, jobType, payload, maxAttempts, delay)) {
      is EnqueueResult.Success -> result.job
      is EnqueueResult.DatabaseFailure -> throw mapDatabaseError(result.error)
    }
}
