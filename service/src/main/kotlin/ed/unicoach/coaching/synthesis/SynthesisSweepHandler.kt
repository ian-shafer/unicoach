package ed.unicoach.coaching.synthesis

import ed.unicoach.common.json.asJson
import ed.unicoach.db.Database
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.queue.EnqueueResult
import ed.unicoach.queue.JobHandler
import ed.unicoach.queue.JobResult
import ed.unicoach.queue.JobType
import ed.unicoach.queue.JobTypeConfig
import ed.unicoach.queue.QueueService
import ed.unicoach.queue.SynthesisPayload
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

/**
 * The daily dispatcher (RFC 97): the `JobHandler` for [JobType.SYNTHESIS_SWEEP],
 * enqueued once per day by the `:cron` scheduler. It enumerates active students
 * and fans out one [JobType.SYNTHESIZE_STUDENT] per student; the existing
 * [SynthesisHandler]/[SynthesisService] then reflect over each student, unchanged.
 *
 * The fan-out is best-effort: an enqueue failure for one student is logged and
 * the sweep continues, so one bad enqueue never strands the rest. `maxAttempts =
 * 1` because the next daily tick re-produces the sweep — next-tick re-production
 * is the retry, not queue backoff. `executionTimeout` (5m) is strictly less than
 * `lockDuration` (10m) so a slow sweep cannot outlive its queue lock and be
 * reclaimed mid-run.
 *
 * A duplicate `SYNTHESIZE_STUDENT` (from an overlapping sweep or a re-run) is a
 * cheap no-op: each is idempotent via the per-student advisory lock and the
 * synthesis freshness gate.
 */
class SynthesisSweepHandler(
  private val database: Database,
  private val queueService: QueueService,
) : JobHandler {
  private val logger = LoggerFactory.getLogger(SynthesisSweepHandler::class.java)

  override val jobType = JobType.SYNTHESIS_SWEEP

  override val config =
    JobTypeConfig(
      concurrency = 1,
      maxAttempts = 1,
      lockDuration = 10.minutes,
      executionTimeout = 5.minutes,
    )

  override suspend fun execute(payload: JsonObject): JobResult {
    val studentIds =
      database
        .withConnection { session -> StudentsDao.listActiveIds(session) }
        .getOrElse { error ->
          logger.warn("Synthesis sweep could not list active students", error)
          return JobResult.RetriableFailure("Failed to list active students: [${error.message}]", error)
        }

    var enqueued = 0
    for (studentId in studentIds) {
      val result =
        queueService.enqueue(
          jobType = JobType.SYNTHESIZE_STUDENT,
          payload = SynthesisPayload(studentId = studentId.asString).asJson(),
        )
      when (result) {
        is EnqueueResult.Success -> {
          enqueued++
        }

        is EnqueueResult.DatabaseFailure -> {
          // Best-effort: log and continue so one failed enqueue does not strand
          // the rest of the sweep. The next daily tick re-produces the sweep.
          logger.warn("Synthesis sweep failed to enqueue student [{}]", studentId.asString, result.error)
        }
      }
    }

    logger.info("Synthesis sweep enqueued [{}] of [{}] active students", enqueued, studentIds.size)
    return JobResult.Success
  }
}
