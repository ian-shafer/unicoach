package ed.unicoach.auth

import ed.unicoach.db.dao.SqlSession
import ed.unicoach.queue.NewJob
import ed.unicoach.queue.dao.JobInsertResult
import ed.unicoach.queue.dao.JobsDao

/**
 * A [JobsDao] whose `insert` always reports a database failure. Injected into a
 * real [ed.unicoach.queue.QueueService] to drive the enqueue-failure/rollback
 * paths (RFC 96) through the production `EnqueueResult.DatabaseFailure` mapping —
 * without subclassing the [ed.unicoach.queue.QueueService] facade itself. Shared
 * by the auth-service tests that exercise the required-enqueue rollback.
 */
class FailingJobsDao : JobsDao() {
  override fun insert(
    session: SqlSession,
    newJob: NewJob,
  ): JobInsertResult = JobInsertResult.DatabaseFailure(RuntimeException("enqueue failed"))
}
