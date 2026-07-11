package ed.unicoach.email

import aws.sdk.kotlin.services.sesv2.model.AccountSuspendedException
import aws.sdk.kotlin.services.sesv2.model.BadRequestException
import aws.sdk.kotlin.services.sesv2.model.LimitExceededException
import aws.sdk.kotlin.services.sesv2.model.MailFromDomainNotVerifiedException
import aws.sdk.kotlin.services.sesv2.model.MessageRejected
import aws.sdk.kotlin.services.sesv2.model.SendEmailRequest
import aws.sdk.kotlin.services.sesv2.model.SendEmailResponse
import aws.sdk.kotlin.services.sesv2.model.SendingPausedException
import aws.sdk.kotlin.services.sesv2.model.TooManyRequestsException

// One recorded SES outcome: what the transport seam returns or throws for a
// single send(). The shapes are the real sesv2 SDK types the production
// SesEmailProvider maps against — SendEmailResponse for success, the concrete
// SDK exception classes for the Rejected / TransientFailure branches — never
// stand-in types (RFC 107). SesEmailProvider keys its mapping off these classes,
// so replaying anything else would exercise a different branch than production.
sealed interface SesOutcome {
  data class Response(
    val response: SendEmailResponse,
  ) : SesOutcome

  data class Throw(
    val error: Throwable,
  ) : SesOutcome
}

/**
 * A scripted, multi-response fake at the [SesSendOperation] seam. Each `send()`
 * dequeues the next [SesOutcome] in order and returns or throws it, and records
 * every received [SendEmailRequest] into [requests] so a test can assert the wire
 * request the real [SesEmailProvider] built (e.g. the verification link body).
 *
 * The script is strict: a `send()` past the end of [outcomes] throws, so an
 * unexpected extra send fails the test loudly.
 */
class ScriptedSesSendOperation(
  private val outcomes: List<SesOutcome>,
) : SesSendOperation {
  private val captured = mutableListOf<SendEmailRequest>()

  /** Requests received, one per `send()` call, in call order. */
  val requests: List<SendEmailRequest> get() = captured

  /**
   * The plain-text body of each received request, in call order. Exposed as a
   * String so consumers can assert the transmitted content (e.g. a verification
   * link) without depending on the SES SDK types, which stay encapsulated here.
   */
  val bodyTexts: List<String?> get() =
    captured.map {
      it.content
        ?.simple
        ?.body
        ?.text
        ?.data
    }

  override suspend fun send(request: SendEmailRequest): SendEmailResponse {
    val index = captured.size
    check(index < outcomes.size) {
      "ScriptedSesSendOperation exhausted: unexpected send() call #${index + 1}, script has ${outcomes.size}"
    }
    captured.add(request)
    return when (val outcome = outcomes[index]) {
      is SesOutcome.Response -> outcome.response
      is SesOutcome.Throw -> throw outcome.error
    }
  }
}

/** Single-outcome scripts and the recorded SES SDK exception shapes. */
object SesFixtures {
  /** A successful send returning [messageId]. */
  fun sent(messageId: String = "ses-message-id-001"): ScriptedSesSendOperation =
    ScriptedSesSendOperation(listOf(SesOutcome.Response(SendEmailResponse { this.messageId = messageId })))

  /** A permanent rejection: [MessageRejected] carrying [reason]. */
  fun rejected(reason: String = "message rejected by ses"): ScriptedSesSendOperation =
    ScriptedSesSendOperation(listOf(SesOutcome.Throw(MessageRejected { message = reason })))

  /** A transient failure: [TooManyRequestsException] carrying [reason]. */
  fun transient(reason: String = "too many requests"): ScriptedSesSendOperation =
    ScriptedSesSendOperation(listOf(SesOutcome.Throw(TooManyRequestsException { message = reason })))

  // Recorded SDK exception instances for the mapping unit tests, one per branch.
  fun messageRejected(message: String = "message rejected by ses") = MessageRejected { this.message = message }

  fun mailFromDomainNotVerified(message: String = "mail from not verified") = MailFromDomainNotVerifiedException { this.message = message }

  fun accountSuspended(message: String = "account suspended") = AccountSuspendedException { this.message = message }

  fun sendingPaused(message: String = "sending paused") = SendingPausedException { this.message = message }

  fun badRequest(message: String = "bad request") = BadRequestException { this.message = message }

  fun tooManyRequests(message: String = "too many requests") = TooManyRequestsException { this.message = message }

  fun limitExceeded(message: String = "limit exceeded") = LimitExceededException { this.message = message }
}

/** A no-op AutoCloseable recording whether it was closed, for provider lifecycle tests. */
class NoopCloseable : AutoCloseable {
  var closed = false
    private set

  override fun close() {
    closed = true
  }
}
