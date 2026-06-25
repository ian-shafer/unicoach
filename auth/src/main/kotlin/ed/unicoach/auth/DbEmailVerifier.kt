package ed.unicoach.auth

import ed.unicoach.db.Database
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.dao.VerificationTokensDao
import ed.unicoach.db.models.TokenHash
import java.time.Instant

/**
 * The database-backed [EmailVerifier]: the single-use verify-email consume,
 * lifted verbatim from `service`'s `EmailVerificationService.verify`. Token
 * storage mirrors sessions — only the SHA-256 hash is persisted; the raw token
 * rides only in the email link. Depends on `db` alone (no email/queue/chat
 * graph), so any first-party service can compose it in-process.
 */
class DbEmailVerifier(
  private val database: Database,
) : EmailVerifier {
  /**
   * Verifies an email in its own transaction: a compare-and-swap consume of the
   * token, then [UsersDao.markEmailVerified], then burning sibling tokens. A
   * zero-row consume is classified via [VerificationTokensDao.findByTokenHash]
   * into [VerifyEmailResult.InvalidToken] / [VerifyEmailResult.Expired] /
   * [VerifyEmailResult.AlreadyConsumed].
   */
  override suspend fun verify(rawToken: String): Result<VerifyEmailResult> =
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
}
