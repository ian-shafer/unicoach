package ed.unicoach.email

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.email.dao.EmailSendsDao
import ed.unicoach.email.dao.NewEmailSend

class EmailService(
  private val database: Database,
  private val provider: EmailProvider,
  private val config: EmailConfig,
) {
  // Resolved once on first use and memoized for the service instance's lifetime
  // (config is load-once; a defaultFrom change requires reconstructing the
  // service). `by lazy` is thread-safe by default, which suffices for concurrent
  // suspend callers.
  private val resolvedFrom: ValidationResult<EmailAddress> by lazy { EmailAddress.create(config.defaultFrom) }

  suspend fun send(
    to: EmailAddress,
    subject: EmailSubject,
    body: EmailBody,
  ): Result<SentEmail> {
    val from =
      when (val resolved = resolvedFrom) {
        is ValidationResult.Valid -> resolved.value
        is ValidationResult.Invalid -> return Result.failure(EmailConfigException())
      }

    val outbound = OutboundEmail(from = from, to = to, subject = subject, body = body)

    return when (val outcome = provider.send(outbound)) {
      is ProviderResult.Sent -> recordSent(outbound, outcome)
      is ProviderResult.Rejected -> recordRejected(outbound, outcome)
      is ProviderResult.TransientFailure -> Result.failure(EmailDeliveryException(outcome.reason))
    }
  }

  private suspend fun recordSent(
    outbound: OutboundEmail,
    outcome: ProviderResult.Sent,
  ): Result<SentEmail> =
    insert(
      NewEmailSend(
        recipient = outbound.to,
        sender = outbound.from,
        subject = outbound.subject,
        body = outbound.body,
        status = EmailSendStatus.SENT,
        provider = provider.id,
        providerMessageId = outcome.providerMessageId,
        errorMessage = null,
      ),
    )

  private suspend fun recordRejected(
    outbound: OutboundEmail,
    outcome: ProviderResult.Rejected,
  ): Result<SentEmail> {
    val recorded =
      insert(
        NewEmailSend(
          recipient = outbound.to,
          sender = outbound.from,
          subject = outbound.subject,
          body = outbound.body,
          status = EmailSendStatus.REJECTED,
          provider = provider.id,
          providerMessageId = null,
          errorMessage = outcome.reason,
        ),
      )
    // A DB failure during recording is returned in preference to the rejection.
    return if (recorded.isFailure) recorded else Result.failure(EmailRejectedException(outcome.reason))
  }

  private suspend fun insert(newSend: NewEmailSend): Result<SentEmail> =
    runCatching {
      database.withConnection { session -> EmailSendsDao.insert(session, newSend) }
    }.fold(
      onSuccess = { it },
      onFailure = { Result.failure(it) },
    )
}
