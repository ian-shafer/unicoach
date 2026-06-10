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
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SesEmailProviderTest {
  @Test
  fun `id is the wire identity ses`() {
    val provider = SesEmailProvider(SesSendOperation { SendEmailResponse {} }, NoopCloseable())
    assertEquals("ses", provider.id)
  }

  @Test
  fun `seam returning a messageId maps to Sent with that providerMessageId`() =
    runTest {
      val provider = SesEmailProvider(SesSendOperation { SendEmailResponse { messageId = "m-1" } }, NoopCloseable())

      val result = provider.send(outbound())

      assertTrue(result is ProviderResult.Sent)
      assertEquals("m-1", result.providerMessageId)
    }

  @Test
  fun `MessageRejected maps to Rejected carrying the exception message`() =
    runTest {
      val provider = throwingProvider(MessageRejected { message = "message rejected by ses" })

      val result = provider.send(outbound())

      assertTrue(result is ProviderResult.Rejected)
      assertEquals("message rejected by ses", result.reason)
    }

  @Test
  fun `MailFromDomainNotVerifiedException maps to Rejected`() =
    runTest {
      val provider = throwingProvider(MailFromDomainNotVerifiedException { message = "mail from not verified" })

      val result = provider.send(outbound())

      assertTrue(result is ProviderResult.Rejected)
      assertEquals("mail from not verified", result.reason)
    }

  @Test
  fun `AccountSuspendedException maps to Rejected`() =
    runTest {
      val provider = throwingProvider(AccountSuspendedException { message = "account suspended" })

      val result = provider.send(outbound())

      assertTrue(result is ProviderResult.Rejected)
      assertEquals("account suspended", result.reason)
    }

  @Test
  fun `SendingPausedException maps to Rejected`() =
    runTest {
      val provider = throwingProvider(SendingPausedException { message = "sending paused" })

      val result = provider.send(outbound())

      assertTrue(result is ProviderResult.Rejected)
      assertEquals("sending paused", result.reason)
    }

  @Test
  fun `BadRequestException maps to Rejected`() =
    runTest {
      val provider = throwingProvider(BadRequestException { message = "bad request" })

      val result = provider.send(outbound())

      assertTrue(result is ProviderResult.Rejected)
      assertEquals("bad request", result.reason)
    }

  @Test
  fun `TooManyRequestsException maps to TransientFailure`() =
    runTest {
      val provider = throwingProvider(TooManyRequestsException { message = "too many requests" })

      val result = provider.send(outbound())

      assertTrue(result is ProviderResult.TransientFailure)
      assertEquals("too many requests", result.reason)
    }

  @Test
  fun `LimitExceededException maps to TransientFailure`() =
    runTest {
      val provider = throwingProvider(LimitExceededException { message = "limit exceeded" })

      val result = provider.send(outbound())

      assertTrue(result is ProviderResult.TransientFailure)
      assertEquals("limit exceeded", result.reason)
    }

  @Test
  fun `a generic transport-like throwable maps to TransientFailure via the catch-all`() =
    runTest {
      val provider = throwingProvider(RuntimeException("connection reset"))

      val result = provider.send(outbound())

      assertTrue(result is ProviderResult.TransientFailure)
      assertEquals("connection reset", result.reason)
    }

  @Test
  fun `the built SendEmailRequest carries from, a single to, and UTF-8 subject and body`() =
    runTest {
      var captured: SendEmailRequest? = null
      val provider =
        SesEmailProvider(
          SesSendOperation { request ->
            captured = request
            SendEmailResponse { messageId = "m-1" }
          },
          NoopCloseable(),
        )

      provider.send(
        OutboundEmail(
          from = address("from@unicoach.app"),
          to = address("to@example.com"),
          subject = subject("Sübject"),
          body = body("Bödy"),
        ),
      )

      val request = requireNotNull(captured)
      assertEquals("from@unicoach.app", request.fromEmailAddress)
      assertEquals(listOf("to@example.com"), request.destination?.toAddresses)
      val message = requireNotNull(request.content?.simple)
      assertEquals("Sübject", message.subject?.data)
      assertEquals("UTF-8", message.subject?.charset)
      assertEquals("Bödy", message.body?.text?.data)
      assertEquals("UTF-8", message.body?.text?.charset)
    }

  @Test
  fun `close closes the injected backing resource`() {
    val resource = NoopCloseable()
    val provider = SesEmailProvider(SesSendOperation { SendEmailResponse {} }, resource)

    provider.close()

    assertTrue(resource.closed)
  }

  private fun throwingProvider(error: Throwable): SesEmailProvider = SesEmailProvider(SesSendOperation { throw error }, NoopCloseable())

  private fun outbound(): OutboundEmail =
    OutboundEmail(
      from = address("from@unicoach.app"),
      to = address("to@example.com"),
      subject = subject("Hello"),
      body = body("World"),
    )

  private fun address(value: String): EmailAddress = (EmailAddress.create(value) as ValidationResult.Valid).value

  private fun subject(value: String): EmailSubject = (EmailSubject.create(value) as ValidationResult.Valid).value

  private fun body(value: String): EmailBody = (EmailBody.create(value) as ValidationResult.Valid).value

  private class NoopCloseable : AutoCloseable {
    var closed = false

    override fun close() {
      closed = true
    }
  }
}
