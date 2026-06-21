package ed.unicoach.auth

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.dao.VerificationTokensDao
import ed.unicoach.db.models.NewVerificationToken
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserId
import ed.unicoach.email.EmailBody
import ed.unicoach.email.EmailService
import ed.unicoach.email.EmailSubject
import ed.unicoach.util.TokenGenerator
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Backend for email verification: issuance inside the caller's transaction,
 * best-effort post-commit delivery, single-use verify, and idempotent resend.
 * Token storage mirrors sessions — only the SHA-256 hash is persisted; the raw
 * token rides only in the email link.
 */
class EmailVerificationService(
  private val database: Database,
  private val emailService: EmailService,
  private val tokenGenerator: TokenGenerator,
  private val config: EmailVerificationConfig,
) {
  private val logger = LoggerFactory.getLogger(EmailVerificationService::class.java)

  /**
   * Generates a raw token, inserts its hash + expiry inside the caller's
   * transaction (atomic with the surrounding work), and returns the raw token for
   * post-commit delivery.
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
   * Post-commit, best-effort delivery: builds the verify link, constructs a
   * fixed-literal subject/body, and sends via [EmailService]. Any failure
   * (invalid subject/body construction or a provider rejection) folds into a
   * logged `Result.failure` without throwing — registration/resend already
   * persisted the token transactionally.
   */
  suspend fun sendVerificationEmail(
    to: EmailAddress,
    rawToken: String,
  ): Result<Unit> {
    val link = "${config.verifyUrlBase}?token=$rawToken"
    val subject =
      when (val s = EmailSubject.create("Verify your email address")) {
        is ValidationResult.Valid -> s.value
        is ValidationResult.Invalid -> return logAndFail("verification email subject construction failed: ${s.error}")
      }
    val body =
      when (
        val b =
          EmailBody.create(
            "Welcome to Unicoach. Confirm your email address by visiting:\n\n$link\n\n" +
              "If you did not create this account, you can ignore this message.",
          )
      ) {
        is ValidationResult.Valid -> b.value
        is ValidationResult.Invalid -> return logAndFail("verification email body construction failed: ${b.error}")
      }

    return emailService
      .send(to, subject, body)
      .fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { error ->
          logger.warn("verification email send failed for [{}]: {}", to.value, error.message)
          Result.failure(error)
        },
      )
  }

  private fun logAndFail(message: String): Result<Unit> {
    logger.warn(message)
    return Result.failure(IllegalStateException(message))
  }

  /**
   * Verifies an email in its own transaction: a compare-and-swap consume of the
   * token, then [UsersDao.markEmailVerified], then burning sibling tokens. A
   * zero-row consume is classified via [VerificationTokensDao.findByTokenHash]
   * into [VerifyEmailResult.InvalidToken] / [VerifyEmailResult.Expired] /
   * [VerifyEmailResult.AlreadyConsumed].
   */
  suspend fun verify(rawToken: String): Result<VerifyEmailResult> =
    runCatching {
      database.withConnection { session ->
        val tokenHash = TokenHash.fromRawToken(rawToken)
        val consumed = VerificationTokensDao.consume(session, tokenHash)
        if (consumed.isFailure) {
          val ex = consumed.exceptionOrNull()
          if (ex is NotFoundException) {
            return@withConnection classifyFailedConsume(session, tokenHash)
          }
          throw ex ?: RuntimeException("verify-email consume failed")
        }

        val token = consumed.getOrThrow()
        val user = UsersDao.markEmailVerified(session, token.userId).getOrThrow()
        VerificationTokensDao.consumeAllForUser(session, token.userId).getOrThrow()
        VerifyEmailResult.Success(user)
      }
    }

  private fun classifyFailedConsume(
    session: SqlSession,
    tokenHash: TokenHash,
  ): VerifyEmailResult {
    val found = VerificationTokensDao.findByTokenHash(session, tokenHash)
    if (found.isFailure) {
      val ex = found.exceptionOrNull()
      if (ex is NotFoundException) {
        return VerifyEmailResult.InvalidToken
      }
      throw ex ?: RuntimeException("verify-email token classification failed")
    }
    val token = found.getOrThrow()
    return when {
      token.consumedAt != null -> VerifyEmailResult.AlreadyConsumed
      !token.expiresAt.isAfter(Instant.now()) -> VerifyEmailResult.Expired
      else -> VerifyEmailResult.InvalidToken
    }
  }

  /**
   * Resends verification in its own transaction: a no-op for an already-verified
   * user, otherwise burns outstanding tokens and issues a fresh one, then sends
   * the email best-effort post-commit.
   */
  suspend fun resend(user: User): Result<ResendResult> {
    if (user.emailVerifiedAt != null) {
      return Result.success(ResendResult.AlreadyVerified)
    }

    val rawToken =
      runCatching {
        database.withConnection { session ->
          VerificationTokensDao.consumeAllForUser(session, user.id).getOrThrow()
          issueToken(session, user.id).getOrThrow()
        }
      }.getOrElse { return Result.failure(it) }

    // Best-effort delivery; a send failure does not undo the issued token.
    sendVerificationEmail(user.email, rawToken)
    return Result.success(ResendResult.Sent)
  }
}
