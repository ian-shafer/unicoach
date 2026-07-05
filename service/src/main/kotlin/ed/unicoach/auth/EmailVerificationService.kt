package ed.unicoach.auth

import ed.unicoach.common.json.asJson
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.db.Database
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.VerificationTokensDao
import ed.unicoach.db.models.NewVerificationToken
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserId
import ed.unicoach.email.EmailJobPayload
import ed.unicoach.email.EmailTemplate
import ed.unicoach.queue.EnqueueResult
import ed.unicoach.queue.JobType
import ed.unicoach.queue.QueueService
import ed.unicoach.util.TokenGenerator
import java.time.Instant

/**
 * Backend for email verification: issuance and the verification-email enqueue,
 * both inside the caller's transaction (RFC 96 — a required enqueue is part of
 * the request transaction), single-use verify, and idempotent resend. Token
 * storage mirrors sessions — only the SHA-256 hash is persisted; the raw token
 * rides only in the enqueued job's context and, once transmitted, the email link.
 *
 * This service builds only the intent (an [EmailJobPayload]); it performs no
 * rendering and never transmits — the `queue-worker`'s [ed.unicoach.email.EmailSendHandler]
 * resolves the renderer and sends.
 */
class EmailVerificationService(
  private val database: Database,
  private val queueService: QueueService,
  private val tokenGenerator: TokenGenerator,
  private val config: EmailVerificationConfig,
) {
  /**
   * Generates a raw token, inserts its hash + expiry inside the caller's
   * transaction (atomic with the surrounding work), and returns the raw token so
   * the caller can enqueue the verification email inside the same transaction.
   */
  fun issueToken(
    session: SqlSession,
    userId: UserId,
  ): Result<String> {
    val rawToken = tokenGenerator.generateToken()
    val tokenHash = TokenHash.fromRawToken(rawToken)
    val expiresAt = Instant.now().plus(config.tokenTtl)
    val inserted =
      VerificationTokensDao.create(
        session,
        NewVerificationToken(userId = userId, tokenHash = tokenHash, expiresAt = expiresAt),
      )
    return inserted.map { rawToken }
  }

  /**
   * Enqueues a [JobType.SEND_EMAIL] job carrying the verification intent on the
   * caller's open transaction (RFC 96). The job commits and rolls back with the
   * surrounding work: if the enqueue fails the transaction aborts and the request
   * fails. Builds the [VerificationEmailContext] with the raw token and serializes
   * an [EmailJobPayload]; the worker renders the link and transmits. Performs no
   * rendering.
   */
  fun enqueue(
    session: SqlSession,
    to: EmailAddress,
    rawToken: String,
  ): Result<Unit> {
    val payload =
      EmailJobPayload(
        to = to.value,
        template = EmailTemplate.EMAIL_VERIFICATION,
        context = VerificationEmailContext(verifyToken = rawToken).asJson(),
      )
    return when (val result = queueService.enqueue(session, JobType.SEND_EMAIL, payload.asJson())) {
      is EnqueueResult.Success -> Result.success(Unit)
      is EnqueueResult.DatabaseFailure -> Result.failure(result.error)
    }
  }

  /**
   * Resends verification in its own transaction: a no-op for an already-verified
   * user, otherwise burns outstanding tokens, issues a fresh one, and enqueues the
   * verification email — all atomic. A failed enqueue aborts the whole
   * transaction, so no token persists without its job.
   */
  suspend fun resend(user: User): Result<ResendResult> {
    if (user.emailVerifiedAt != null) {
      return Result.success(ResendResult.AlreadyVerified)
    }

    return runCatching {
      database.withConnection { session ->
        VerificationTokensDao.consumeAllForUser(session, user.id).getOrThrow()
        val rawToken = issueToken(session, user.id).getOrThrow()
        enqueue(session, user.email, rawToken).getOrThrow()
        ResendResult.Sent
      }
    }
  }
}
