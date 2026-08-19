package ed.unicoach.rest.routing

import ed.unicoach.appstore.AppleNotification
import ed.unicoach.appstore.AppleNotificationOutcome
import ed.unicoach.appstore.AppleNotificationVerifier
import ed.unicoach.common.json.asJson
import ed.unicoach.queue.EnqueueResult
import ed.unicoach.queue.JobType
import ed.unicoach.queue.QueueService
import ed.unicoach.queue.SubscriptionRefreshPayload
import ed.unicoach.rest.models.AppleNotificationRequest
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.rejectUnsupportedMethods
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory

/**
 * `POST /api/v1/subscriptions/apple-notifications` (RFC 112): App Store Server
 * Notifications V2, from Apple.
 *
 * No [CallerResolution] delegation, unlike every other route handler here —
 * there is no session to resolve. The notification's own JWS signature IS the
 * authentication, and [AppleNotificationVerifier] is where that is decided.
 *
 * The notification is a TRIGGER, not a state carrier: this handler enqueues one
 * [JobType.REFRESH_SUBSCRIPTION] naming the transaction and returns. The worker
 * re-reads authoritative state from Apple, which is what makes duplicate and
 * out-of-order deliveries no-ops and needs no change when Apple adds a type.
 *
 * `ASYNC_WORK.md`'s required-enqueue rule holds with nothing to spell out: the
 * request has no other database work, so the enqueue IS the request's
 * transaction, and the request fails if it fails.
 */
class AppleNotificationRouteHandler(
  private val appleNotificationVerifier: AppleNotificationVerifier,
  private val queueService: QueueService,
) {
  private val logger = LoggerFactory.getLogger(AppleNotificationRouteHandler::class.java)

  fun registerRoutes(route: Route) {
    route.route(PATH) {
      post { handleNotification() }
      rejectUnsupportedMethods(HttpMethod.Post)
    }
  }

  private suspend fun RoutingContext.handleNotification() {
    val request = call.receive<AppleNotificationRequest>()
    when (val outcome = appleNotificationVerifier.read(request.signedPayload).getOrThrow()) {
      is AppleNotificationOutcome.Verified -> respondVerified(outcome.notification)
      is AppleNotificationOutcome.Untrusted -> respondUntrusted(outcome)
      is AppleNotificationOutcome.ForeignTarget -> respondForeignTarget(outcome)
    }
  }

  private suspend fun RoutingContext.respondVerified(notification: AppleNotification) {
    val originalTransactionId = notification.originalTransactionId ?: return respondNoTransactionNamed(notification)
    when (val enqueued = enqueueRefresh(notification, originalTransactionId)) {
      is EnqueueResult.Success -> respondEnqueued()
      is EnqueueResult.DatabaseFailure -> respondEnqueueFailure(enqueued, notification, originalTransactionId)
    }
  }

  /**
   * Apple's TEST notification and the summary-bearing types name no
   * transaction. Nothing to refresh, and nothing wrong.
   */
  private suspend fun RoutingContext.respondNoTransactionNamed(notification: AppleNotification) {
    logger.info(
      "Apple notification [{}] of type [{}] names no transaction; nothing enqueued",
      notification.notificationUuid,
      notification.notificationType,
    )
    call.respond(HttpStatusCode.OK)
  }

  /** Apple reads only the status code, so the acknowledgement carries no body. */
  private suspend fun RoutingContext.respondEnqueued() {
    call.respond(HttpStatusCode.OK)
  }

  /**
   * Apple retries a non-2xx for roughly three days, so failing loudly is how
   * the notification survives a database outage.
   */
  private suspend fun RoutingContext.respondEnqueueFailure(
    enqueued: EnqueueResult.DatabaseFailure,
    notification: AppleNotification,
    originalTransactionId: String,
  ) {
    logger.error(
      "Failed to enqueue a refresh for subscription [{}] named by Apple notification [{}]",
      originalTransactionId,
      notification.notificationUuid,
      enqueued.error,
    )
    call.respond(HttpStatusCode.InternalServerError, ErrorResponse(ErrorCode.INTERNAL_ERROR, "Internal server error"))
  }

  /** Builds the [SubscriptionRefreshPayload] and enqueues one [JobType.REFRESH_SUBSCRIPTION] job for it. */
  private suspend fun enqueueRefresh(
    notification: AppleNotification,
    originalTransactionId: String,
  ): EnqueueResult {
    val payload =
      SubscriptionRefreshPayload(
        originalTransactionId = originalTransactionId,
        notificationUuid = notification.notificationUuid,
        notificationType = notification.notificationType,
        subtype = notification.subtype,
      )
    return queueService.enqueue(JobType.REFRESH_SUBSCRIPTION, payload.asJson())
  }

  /**
   * The one outcome an attacker can provoke at will, so its message is a fixed
   * string and the reason stays in the log. 401 rather than 400 because it is an
   * authentication failure: nothing about these bytes says they came from Apple.
   */
  private suspend fun RoutingContext.respondUntrusted(outcome: AppleNotificationOutcome.Untrusted) {
    logger.warn("Rejected an unauthenticated App Store notification: [{}]", outcome.reason)
    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorCode.UNAUTHORIZED, "Unauthorized"))
  }

  /**
   * Apple-signed, but for another bundle or another App Store environment. App
   * Store Connect holds separate Production and Sandbox notification URLs, so in
   * normal operation these never cross — a crossing is misconfiguration or an
   * attack, and the operator should see it.
   */
  private suspend fun RoutingContext.respondForeignTarget(outcome: AppleNotificationOutcome.ForeignTarget) {
    when (outcome) {
      is AppleNotificationOutcome.ForeignTarget.WrongBundle -> {
        logger.error(
          "Refused an App Store notification targeting bundle [{}]; this box serves [{}]",
          outcome.targeted,
          outcome.served,
        )
      }

      is AppleNotificationOutcome.ForeignTarget.WrongEnvironment -> {
        logger.error(
          "Refused an App Store notification from the [{}] App Store; this box serves [{}]",
          outcome.targeted,
          outcome.served,
        )
      }
    }
    call.respond(
      HttpStatusCode.BadRequest,
      ErrorResponse(ErrorCode.VALIDATION_FAILED, "The notification is not for this App Store target"),
    )
  }

  companion object {
    /**
     * The endpoint's path. Named here because two gates in `rest-server.conf`
     * restate it and two suites assert against it — the route, the client-key
     * allowlist entry, and the request-size override are one decision.
     */
    const val PATH = "/api/v1/subscriptions/apple-notifications"
  }
}
