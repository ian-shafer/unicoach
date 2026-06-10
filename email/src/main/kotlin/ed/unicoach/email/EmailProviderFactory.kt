package ed.unicoach.email

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.sesv2.SesV2Client

// Selector mapping the config `provider` string to a concrete EmailProvider. The
// module stays unwired: no production main() calls this; it is exercised by tests
// only, mirroring RFC 34's stance that production construction is the queue RFC's
// responsibility.
object EmailProviderFactory {
  fun fromConfig(config: EmailConfig): Result<EmailProvider> =
    when (config.provider) {
      LogOnlyEmailProvider.PROVIDER_ID -> Result.success(LogOnlyEmailProvider())
      SesEmailProvider.PROVIDER_ID -> Result.success(sesProvider(config.ses))
      else -> Result.failure(IllegalArgumentException("unknown email.provider [${config.provider}]"))
    }

  // Constructs a SesV2Client from the SES config — explicit region, plus static
  // credentials only when BOTH keys are present, else the AWS default credential
  // provider chain (env vars, IAM instance/task role). The client's lifetime
  // transfers to the returned SesEmailProvider (closed via its close()).
  private fun sesProvider(ses: SesConfig): SesEmailProvider {
    val client =
      SesV2Client {
        region = ses.region
        staticCredentials(ses)?.let { credentialsProvider = it }
      }
    return SesEmailProvider(SesSendOperation { request -> client.sendEmail(request) }, client)
  }

  private fun staticCredentials(ses: SesConfig): StaticCredentialsProvider? {
    val accessKeyId = ses.accessKeyId ?: return null
    val secretAccessKey = ses.secretAccessKey ?: return null
    return StaticCredentialsProvider {
      this.accessKeyId = accessKeyId
      this.secretAccessKey = secretAccessKey
    }
  }
}
