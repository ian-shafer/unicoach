package ed.unicoach.email

import aws.sdk.kotlin.services.sesv2.model.AccountSuspendedException
import aws.sdk.kotlin.services.sesv2.model.BadRequestException
import aws.sdk.kotlin.services.sesv2.model.Body
import aws.sdk.kotlin.services.sesv2.model.Content
import aws.sdk.kotlin.services.sesv2.model.Destination
import aws.sdk.kotlin.services.sesv2.model.EmailContent
import aws.sdk.kotlin.services.sesv2.model.LimitExceededException
import aws.sdk.kotlin.services.sesv2.model.MailFromDomainNotVerifiedException
import aws.sdk.kotlin.services.sesv2.model.Message
import aws.sdk.kotlin.services.sesv2.model.MessageRejected
import aws.sdk.kotlin.services.sesv2.model.SendEmailRequest
import aws.sdk.kotlin.services.sesv2.model.SendingPausedException
import aws.sdk.kotlin.services.sesv2.model.TooManyRequestsException

// Transmitting EmailProvider backed by Amazon SES (sesv2). It maps the SES
// SendEmail outcome onto the ProviderResult variants and owns the backing
// SesV2Client's lifecycle via AutoCloseable.
class SesEmailProvider(
  private val ses: SesSendOperation,
  // Backing SesV2Client; closed by close().
  private val resource: AutoCloseable,
) : EmailProvider,
  AutoCloseable {
  // The wire identity names the delivery mechanism (like RFC 34's "log"), not the
  // class; written verbatim to the ledger `provider` column.
  override val id: String = PROVIDER_ID

  override suspend fun send(email: OutboundEmail): ProviderResult {
    val request = buildRequest(email)
    return try {
      val response = ses.send(request)
      ProviderResult.Sent(response.messageId)
    } catch (e: MessageRejected) {
      ProviderResult.Rejected(e.permanentReason())
    } catch (e: MailFromDomainNotVerifiedException) {
      ProviderResult.Rejected(e.permanentReason())
    } catch (e: AccountSuspendedException) {
      ProviderResult.Rejected(e.permanentReason())
    } catch (e: SendingPausedException) {
      ProviderResult.Rejected(e.permanentReason())
    } catch (e: BadRequestException) {
      ProviderResult.Rejected(e.permanentReason())
    } catch (e: TooManyRequestsException) {
      ProviderResult.TransientFailure(e.transientReason())
    } catch (e: LimitExceededException) {
      ProviderResult.TransientFailure(e.transientReason())
    } catch (e: Throwable) {
      // Catch-all: transport/timeout, 5xx, or an unrecognized SesV2Exception. For
      // transactional email, retrying an unknown error (the queue bounds attempts)
      // is safer than silently dropping a deliverable message.
      ProviderResult.TransientFailure(e.transientReason())
    }
  }

  // Closes the backing SesV2Client. The only explicitly-set credentials provider is
  // StaticCredentialsProvider (in-memory, nothing closeable); the default chain is
  // owned and closed by the SDK.
  override fun close() {
    resource.close()
  }

  private fun buildRequest(email: OutboundEmail): SendEmailRequest =
    SendEmailRequest {
      fromEmailAddress = email.from.value
      destination =
        Destination {
          toAddresses = listOf(email.to.value)
        }
      content =
        EmailContent {
          simple =
            Message {
              // SES defaults unset charsets to 7-bit ASCII, which would mangle the
              // non-ASCII subjects/bodies the value classes admit.
              subject =
                Content {
                  data = email.subject.value
                  charset = CHARSET_UTF_8
                }
              body =
                Body {
                  text =
                    Content {
                      data = email.body.value
                      charset = CHARSET_UTF_8
                    }
                }
            }
        }
    }

  // `reason` carries the SES exception message verbatim.
  private fun Throwable.permanentReason(): String = message ?: this::class.simpleName.orEmpty()

  private fun Throwable.transientReason(): String = message ?: this::class.simpleName.orEmpty()

  companion object {
    const val PROVIDER_ID = "ses"
    private const val CHARSET_UTF_8 = "UTF-8"
  }
}
