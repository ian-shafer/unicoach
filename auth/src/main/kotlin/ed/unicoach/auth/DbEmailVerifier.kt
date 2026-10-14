package ed.unicoach.auth

import ed.unicoach.db.Database
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.VerificationTokensDao
import ed.unicoach.db.dao.recordEmailVerified
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import java.time.Instant

/**
 * The database-backed [EmailVerifier]: the single-use verify-email consume,
 * lifted verbatim from `service`'s `EmailVerificationService.verify`. Token
 * storage mirrors sessions — only the SHA-256 hash is persisted; the raw token
 * rides only in the email link. Depends on `db` alone (no email/queue/chat
 * graph), so any first-party service can compose it in-process.
 *
 * [afterVerified] runs inside the verifying transaction, after the address is
 * marked verified (RFC 254): a parent's pending request to connect stays
 * invisible to the student until the parent has proved their address, and this
 * is the moment that proof arrives. It is a REQUIRED argument, not a default,
 * so neither process that verifies can silently forget it — and a function, so
 * `:auth` takes no dependency on `:service`.
 */
class DbEmailVerifier(
  private val database: Database,
  private val afterVerified: (SqlSession, UserId) -> Unit,
) : EmailVerifier {
  /**
   * Verifies an email in its own transaction: a compare-and-swap consume of the
   * token, then [recordEmailVerified] — mark, burn sibling tokens, hook. A
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
        VerifyEmailResult.Success(recordEmailVerified(session, token.userId, afterVerified))
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
