package ed.unicoach.email

import aws.sdk.kotlin.services.sesv2.model.SendEmailRequest
import aws.sdk.kotlin.services.sesv2.model.SendEmailResponse

// Narrow seam over SesV2Client.sendEmail. The factory adapts the real client; tests
// supply a lambda. This makes SesEmailProvider's mapping logic unit-testable without
// the ~40-method SesV2Client interface, a mocking framework, or a network.
fun interface SesSendOperation {
  suspend fun send(request: SendEmailRequest): SendEmailResponse
}
