package ed.unicoach.coaching.fitlens

import ed.unicoach.common.json.asJson
import ed.unicoach.db.Database
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.queue.EnqueueResult
import ed.unicoach.queue.FitLensPayload
import ed.unicoach.queue.JobHandler
import ed.unicoach.queue.JobResult
import ed.unicoach.queue.JobType
import ed.unicoach.queue.JobTypeConfig
import ed.unicoach.queue.QueueService
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

/**
 * The weekly fit-lens dispatcher (RFC 98), a sibling of
 * [ed.unicoach.coaching.synthesis.SynthesisSweepHandler]: the `JobHandler` for
 * [JobType.FIT_LENS_SWEEP], enqueued once per week by the `:cron` scheduler. It
 * enumerates active students and fans out one [JobType.FIT_LENS] per student; the
 * [FitLensHandler]/[FitLensService] then run one student each.
 *
 * The fan-out is best-effort: an enqueue failure for one student is logged and
 * the sweep continues, so one bad enqueue never strands the rest. `maxAttempts =
 * 1` because the next weekly tick re-produces the sweep. `executionTimeout` (5m)
 * is strictly less than `lockDuration` (10m) so a slow sweep cannot outlive its
 * queue lock and be reclaimed mid-run.
 */
class FitLensSweepHandler(
  private val database: Database,
  private val queueService: QueueService,
) : JobHandler {
  private val logger = LoggerFactory.getLogger(FitLensSweepHandler::class.java)

  override val jobType = JobType.FIT_LENS_SWEEP

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
          logger.warn("Fit-lens sweep could not list active students", error)
          return JobResult.RetriableFailure("Failed to list active students: [${error.message}]", error)
        }

    var enqueued = 0
    for (studentId in studentIds) {
      val result =
        queueService.enqueue(
          jobType = JobType.FIT_LENS,
          payload = FitLensPayload(studentId = studentId.asString).asJson(),
        )
      when (result) {
        is EnqueueResult.Success -> {
          enqueued++
        }

        is EnqueueResult.DatabaseFailure -> {
          // Best-effort: log and continue so one failed enqueue does not strand
          // the rest of the sweep. The next weekly tick re-produces the sweep.
          logger.warn("Fit-lens sweep failed to enqueue student [{}]", studentId.asString, result.error)
        }
      }
    }

    logger.info("Fit-lens sweep enqueued [{}] of [{}] active students", enqueued, studentIds.size)
    return JobResult.Success
  }
}
