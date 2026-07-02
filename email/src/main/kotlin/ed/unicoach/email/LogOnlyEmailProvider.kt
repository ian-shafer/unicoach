package ed.unicoach.email

import org.slf4j.LoggerFactory
import java.util.UUID

// Records-but-does-not-transmit adapter. The real adapter (SES/SendGrid) is
// deferred to its own RFC and slots in behind EmailProvider.
class LogOnlyEmailProvider : EmailProvider {
  override val id: String = PROVIDER_ID

  private val logger = LoggerFactory.getLogger(LogOnlyEmailProvider::class.java)

  override suspend fun send(email: OutboundEmail): ProviderResult {
    val providerMessageId = UUID.randomUUID().toString()
    logger.info(
      "[{}] recorded outbound email from=[{}] to=[{}] subject=[{}] body=[{}]",
      PROVIDER_ID,
      email.from.value,
      email.to.value,
      email.subject.value,
      email.body.value,
    )
    return ProviderResult.Sent(providerMessageId)
  }

  companion object {
    const val PROVIDER_ID = "log"
  }
}
