package ed.unicoach.subscriptions

import ed.unicoach.common.json.deserialize
import ed.unicoach.queue.JobHandler
import ed.unicoach.queue.JobResult
import ed.unicoach.queue.JobType
import ed.unicoach.queue.JobTypeConfig
import ed.unicoach.queue.SubscriptionRefreshPayload
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The [JobHandler] for [JobType.REFRESH_SUBSCRIPTION] (RFC 112): one Apple read
 * and one write, per App Store Server Notification the webhook accepted.
 *
 * A [Result.failure] from [SubscriptionService.refresh] is rethrown rather than
 * classified here, so the worker's default handles it and the root cause reaches
 * the worker log unaltered — the [ed.unicoach.email.EmailSendHandler] posture.
 */
class SubscriptionRefreshHandler(
  private val subscriptionService: SubscriptionService,
) : JobHandler {
  private val logger = LoggerFactory.getLogger(SubscriptionRefreshHandler::class.java)

  override val jobType = JobType.REFRESH_SUBSCRIPTION

  // A refresh is one short network call and one write, and distinct jobs are
  // independent, so several run in parallel. executionTimeout is strictly less
  // than lockDuration so a slow Apple call cannot outlive its queue lock.
  override val config =
    JobTypeConfig(
      concurrency = 4,
      maxAttempts = 5,
      lockDuration = 2.minutes,
      executionTimeout = 30.seconds,
    )

  override suspend fun execute(payload: JsonObject): JobResult {
    val job =
      try {
        payload.deserialize<SubscriptionRefreshPayload>()
      } catch (e: Exception) {
        // No transaction or notification id survives an unparseable payload; log it raw.
        logger.warn("Discarding subscription refresh job with malformed payload [{}]", payload, e)
        // The dead-lettered job's error_message is all an operator without log
        // access sees, so it names the exception type as well as its message.
        return JobResult.PermanentFailure("Malformed payload: [${e::class.simpleName}]: [${e.message}]")
      }

    return fold(subscriptionService.refresh(job.originalTransactionId).getOrThrow(), job)
  }

  /**
   * Maps one refresh outcome to its queue verdict. The notification identifiers
   * are threaded in so a dead-lettered job's `job_attempts.error_message` names
   * the delivery that produced it without joining back to `jobs.payload`.
   */
  private fun fold(
    outcome: RefreshResult,
    job: SubscriptionRefreshPayload,
  ): JobResult =
    when (outcome) {
      is RefreshResult.Refreshed -> {
        JobResult.Success
      }

      // Not a failure: nobody has verified this subscription yet, and the
      // webhook never binds one. The student's next /verify does.
      is RefreshResult.NotBound -> {
        logger.info(
          "Notification [{}] of type [{}] named unbound subscription [{}]; nothing refreshed",
          job.notificationUuid,
          job.notificationType,
          job.originalTransactionId,
        )
        JobResult.Success
      }

      // Config drift: Apple sells a product this box cannot budget. Retrying
      // cannot fix a missing plan entry, so it dead-letters immediately.
      is RefreshResult.UnknownProduct -> {
        val message =
          "Subscription [${job.originalTransactionId}] is for product [${outcome.productId}], which has no configured plan"
        logger.error(message)
        JobResult.PermanentFailure(message)
      }

      // Apple sent the notification, so Apple's own API should know the
      // subscription; a disagreement is worth retrying rather than discarding.
      is RefreshResult.UnknownTransaction -> {
        JobResult.RetriableFailure(
          "App Store does not know subscription [${job.originalTransactionId}] named by notification [${job.notificationUuid}]",
        )
      }

      // Apple knows a subscription, just not the one asked about — so the
      // failure names what it answered with, not "never heard of it".
      is RefreshResult.MismatchedTransaction -> {
        JobResult.RetriableFailure(
          "App Store answered for subscription [${outcome.answered}] when notification [${job.notificationUuid}] " +
            "named [${outcome.expected}]",
        )
      }

      is RefreshResult.AppStoreUnavailable -> {
        JobResult.RetriableFailure(
          "App Store unavailable refreshing subscription [${job.originalTransactionId}]: [${outcome.reason}]",
          outcome.cause,
        )
      }
    }
}
