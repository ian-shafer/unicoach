package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.models.SubscriptionStatusView
import ed.unicoach.rest.models.SubscriptionVerifyRequest
import ed.unicoach.rest.models.SubscriptionVerifyResponse
import ed.unicoach.rest.models.SubscriptionView
import ed.unicoach.rest.rejectUnsupportedMethods
import ed.unicoach.subscriptions.SubscriptionService
import ed.unicoach.subscriptions.VerifyResult
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
 * `POST /api/v1/subscriptions/verify` (RFC 110): the app posts the StoreKit 2
 * signed transaction; [SubscriptionService] owns the flow and this handler only
 * maps its arms. The email verification gate covers the route automatically
 * (403 `email_not_verified`).
 *
 * The delegated seam is [CallerAuthentication] and NOT [CallerResolution], since
 * RFC 244: billing is per ACCOUNT, so `409 student_profile_required` is not an
 * answer this route can give any more — a parent may pay for their own account,
 * and telling them to go and make a student profile first was the blocker this
 * slice removes. As on the supporter doors, the split keeps that refusal off the
 * class rather than off a comment.
 */
class SubscriptionRouteHandler(
  authService: AuthService,
  private val subscriptionService: SubscriptionService,
  sessionConfig: SessionConfig,
) : CallerAuthentication by SessionCallerAuthentication(authService, sessionConfig) {
  private val logger = LoggerFactory.getLogger(SubscriptionRouteHandler::class.java)

  fun registerRoutes(route: Route) {
    route.route("/api/v1/subscriptions/verify") {
      post { handleVerify() }
      rejectUnsupportedMethods(HttpMethod.Post)
    }
  }

  private suspend fun RoutingContext.handleVerify() {
    val user = resolveUser() ?: return respondUnauthorized()

    val request = call.receive<SubscriptionVerifyRequest>()
    val outcome = subscriptionService.verify(user.id, request.signedTransaction).getOrThrow()
    respondVerifyOutcome(outcome)
  }

  private suspend fun RoutingContext.respondVerifyOutcome(outcome: VerifyResult) {
    when (outcome) {
      is VerifyResult.Verified -> respondVerified(outcome)
      is VerifyResult.ValidationFailure -> respondValidationFailure(outcome)
      is VerifyResult.UnknownTransaction -> respondUnknownTransaction()
      is VerifyResult.UnknownProduct -> respondUnknownProduct(outcome)
      is VerifyResult.OwnedByOtherAccount -> respondOwnedByOtherAccount()
      is VerifyResult.AppStoreUnavailable -> respondAppStoreUnavailable()
    }
  }

  private suspend fun RoutingContext.respondVerified(outcome: VerifyResult.Verified) {
    call.respond(
      HttpStatusCode.OK,
      SubscriptionVerifyResponse(
        SubscriptionView(
          status = SubscriptionStatusView.from(outcome.subscription.status),
          productId = outcome.subscription.productId,
          currentPeriodEnd = outcome.subscription.periodEnd,
        ),
      ),
    )
  }

  private suspend fun RoutingContext.respondValidationFailure(outcome: VerifyResult.ValidationFailure) {
    call.respond(
      HttpStatusCode.BadRequest,
      ErrorResponse(ErrorCode.VALIDATION_FAILED, "Invalid signed transaction", outcome.fieldErrors),
    )
  }

  private suspend fun RoutingContext.respondUnknownTransaction() {
    call.respond(
      HttpStatusCode.NotFound,
      ErrorResponse(ErrorCode.SUBSCRIPTION_NOT_FOUND, "The App Store knows no such transaction"),
    )
  }

  // Config drift, not a client error: Apple verified a product this box
  // cannot map to a budget. The client sees a plain 500; the log names it.
  private suspend fun RoutingContext.respondUnknownProduct(outcome: VerifyResult.UnknownProduct) {
    logger.error("Verified App Store product [${outcome.productId}] has no configured subscription plan")
    call.respond(
      HttpStatusCode.InternalServerError,
      ErrorResponse(ErrorCode.INTERNAL_ERROR, "Internal server error"),
    )
  }

  /**
   * The shared-Apple-ID refusal, unchanged in code and status (RFC 244 §2): one
   * Apple ID cannot buy the same subscription twice, so a second unicoach account
   * signed in to it is handed the first account's transaction. Coverage between the
   * two was deliberately not built; the way out is a different Apple ID, which the
   * app now says. [SubscriptionService] counts each occurrence
   * (`subscription_apple_id_already_bound`), and that count is what would justify
   * building coverage later.
   */
  private suspend fun RoutingContext.respondOwnedByOtherAccount() {
    call.respond(
      HttpStatusCode.Conflict,
      ErrorResponse(ErrorCode.SUBSCRIPTION_OWNED_BY_OTHER_ACCOUNT, "This subscription is bound to another account"),
    )
  }

  private suspend fun RoutingContext.respondAppStoreUnavailable() {
    call.respond(
      HttpStatusCode.ServiceUnavailable,
      ErrorResponse(ErrorCode.SERVICE_UNAVAILABLE, "Subscription verification is temporarily unavailable"),
    )
  }
}
