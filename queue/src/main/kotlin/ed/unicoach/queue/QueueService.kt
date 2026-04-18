package ed.unicoach.queue

import ed.unicoach.db.Database
import ed.unicoach.error.ExceptionWrapper
import ed.unicoach.queue.dao.JobInsertResult
import ed.unicoach.queue.dao.JobsDao
import kotlinx.serialization.json.JsonObject

sealed interface EnqueueResult {
    data class Success(val job: Job) : EnqueueResult
    data class DatabaseFailure(val error: ExceptionWrapper) : EnqueueResult
}

/**
 * Thin facade over [JobsDao] exposing the public enqueue API.
 * All enqueued jobs start in SCHEDULED status.
 */
class QueueService(
    private val database: Database,
    private val jobsDao: JobsDao = JobsDao(),
) {
    fun enqueue(
        jobType: JobType,
        payload: JsonObject,
        maxAttempts: Int? = null,
        delay: kotlin.time.Duration? = null,
    ): EnqueueResult =
        database.withConnection { session ->
            when (val result = jobsDao.insert(session, NewJob(jobType, payload, maxAttempts, delay))) {
                is JobInsertResult.Success -> EnqueueResult.Success(result.job)
                is JobInsertResult.DatabaseFailure -> EnqueueResult.DatabaseFailure(result.error)
            }
        }
}
