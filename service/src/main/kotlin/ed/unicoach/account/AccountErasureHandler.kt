package ed.unicoach.account

import ed.unicoach.common.json.deserialize
import ed.unicoach.db.models.UserId
import ed.unicoach.queue.JobHandler
import ed.unicoach.queue.JobResult
import ed.unicoach.queue.JobType
import ed.unicoach.queue.JobTypeConfig
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * The [JobHandler] for [JobType.ERASE_ACCOUNT] (RFC 267 §4): erases the account
 * its close scheduled, through [AccountErasureService.eraseClose], which also
 * enqueues the confirmation email in the same transaction.
 *
 * An account reopened, or closed again since, is not an error: the job did
 * what it was for (nothing) and the later close has its own job. An unknown
 * account or an unreadable payload will never erase, so it dead-letters at
 * once; any other failure is retried and then dead-lettered, which leaves the
 * account closed and un-erased -- the walk rolled back whole -- and logs the
 * worker's dead-letter alert, the operator's cue to re-run the admin door.
 */
class AccountErasureHandler(
  private val accountErasureService: AccountErasureService,
) : JobHandler {
  private val logger = LoggerFactory.getLogger(AccountErasureHandler::class.java)

  override val jobType = JobType.ERASE_ACCOUNT

  // One at a time: erasures are rare and each locks one account's rows for a
  // whole walk. executionTimeout is strictly less than lockDuration so a slow
  // walk cannot outlive its queue lock.
  override val config =
    JobTypeConfig(
      concurrency = 1,
      maxAttempts = 3,
      lockDuration = 5.minutes,
      executionTimeout = 2.minutes,
    )

  override suspend fun execute(payload: JsonObject): JobResult {
    val (userId, closedAt) =
      parse(payload).getOrElse { e ->
        logger.warn("Discarding erase job with malformed payload [{}]", payload, e)
        return JobResult.PermanentFailure("Malformed payload: [${e::class.simpleName}]: [${e.message}]")
      }
    return accountErasureService.eraseClose(userId, closedAt).fold(
      onSuccess = { fold(it, userId) },
      onFailure = { JobResult.RetriableFailure("Erasure of account [${userId.asString}] failed: [${it.message}]", it) },
    )
  }

  private fun parse(payload: JsonObject): Result<Pair<UserId, Instant>> =
    runCatching {
      val job = payload.deserialize<AccountErasurePayload>()
      // An offset, not only "Z": the backfill's timestamps are written in the
      // migrating session's time zone.
      UserId(UUID.fromString(job.userId)) to OffsetDateTime.parse(job.closedAt).toInstant()
    }

  private fun fold(
    outcome: CloseErasureOutcome,
    userId: UserId,
  ): JobResult =
    when (outcome) {
      is ErasureOutcome.Erased, ErasureOutcome.AlreadyErased -> {
        JobResult.Success
      }

      ErasureOutcome.NotClosed -> {
        logger.info("Erase job for account [{}] did nothing: the account was reopened", userId.asString)
        JobResult.Success
      }

      CloseErasureOutcome.CloseSuperseded -> {
        logger.info("Erase job for account [{}] did nothing: it was closed again, and that close has its own job", userId.asString)
        JobResult.Success
      }

      ErasureOutcome.NotFound -> {
        JobResult.PermanentFailure("No account [${userId.asString}] to erase")
      }
    }
}
