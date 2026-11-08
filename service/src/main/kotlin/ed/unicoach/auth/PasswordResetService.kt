package ed.unicoach.auth

import ed.unicoach.common.json.asJson
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.dao.AccountTokensDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.dao.recordEmailVerified
import ed.unicoach.db.models.AccountTokenType
import ed.unicoach.db.models.LoginMethod
import ed.unicoach.db.models.NewAccountToken
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserId
import ed.unicoach.email.EmailAccountKey
import ed.unicoach.email.EmailJobPayload
import ed.unicoach.email.EmailTemplate
import ed.unicoach.queue.EnqueueResult
import ed.unicoach.queue.JobType
import ed.unicoach.queue.QueueService
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.TokenGenerator
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Password reset by mailed link (RFC 276). It belongs to the account, not to a
 * role: nothing here reads `students` or `supporters`.
 *
 * [afterVerified] runs inside the transaction in which a reset marks an address
 * verified, the same hook `AuthService` and `DbEmailVerifier` take for the same
 * moment. It is required, not defaulted, so no process that builds this service
 * can forget it.
 */
class PasswordResetService(
  private val database: Database,
  private val queueService: QueueService,
  private val tokenGenerator: TokenGenerator,
  private val argon2Hasher: Argon2Hasher,
  private val sessionMinter: SessionMinter,
  private val config: PasswordResetConfig,
  private val afterVerified: (SqlSession, UserId) -> Unit,
) {
  private val logger = LoggerFactory.getLogger(PasswordResetService::class.java)

  /**
   * Mails a reset link when [email] is a live account, burning that account's
   * earlier links, and answers [PasswordResetRequest.Submitted] whether or not it
   * is one. The outcome is logged and never the address.
   */
  suspend fun request(email: String): Result<PasswordResetRequest> =
    when (val address = EmailAddress.create(email)) {
      is ValidationResult.Invalid -> Result.success(PasswordResetRequest.InvalidEmail(address.error))
      is ValidationResult.Valid -> issueLinkFor(address.value)
    }

  private suspend fun issueLinkFor(address: EmailAddress): Result<PasswordResetRequest> =
    database
      .withConnectionCatching { session -> issueLink(session, address) }
      .map { sent ->
        logger.info("password reset requested: outcome=[{}]", if (sent) "link sent" else "no live account")
        PasswordResetRequest.Submitted
      }

  /** Whether [address] is a live account and a link is now on its way; the token and its job commit together (RFC 96). */
  private fun issueLink(
    session: SqlSession,
    address: EmailAddress,
  ): Boolean {
    val user =
      UsersDao.findByEmail(session, address).getOrElse { failure ->
        if (failure is NotFoundException) return false
        throw failure
      }
    AccountTokensDao.consumeAllForUser(session, TOKEN_TYPE, user.id).getOrThrow()
    val rawToken = storeNewToken(session, user)
    enqueueEmail(session, user, rawToken)
    return true
  }

  /** Persists a fresh token for [user]; returns the raw value, which exists only for the email. */
  private fun storeNewToken(
    session: SqlSession,
    user: User,
  ): String {
    val rawToken = tokenGenerator.generateToken()
    AccountTokensDao
      .create(
        session,
        NewAccountToken(
          userId = user.id,
          type = TOKEN_TYPE,
          userVersion = user.version,
          tokenHash = TokenHash.fromRawToken(rawToken),
          expiresAt = Instant.now().plus(config.tokenTtl),
        ),
      ).getOrThrow()
    return rawToken
  }

  private fun enqueueEmail(
    session: SqlSession,
    user: User,
    rawToken: String,
  ) {
    val payload =
      EmailJobPayload(
        to = user.email.value,
        template = EmailTemplate.PASSWORD_RESET,
        context = PasswordResetEmailContext(resetToken = rawToken).asJson(),
        account = EmailAccountKey.Live(user.id),
      )
    when (val result = queueService.enqueue(session, JobType.SEND_EMAIL, payload.asJson())) {
      is EnqueueResult.Success -> Unit
      is EnqueueResult.DatabaseFailure -> throw result.error
    }
  }

  /** What [rawToken] can do right now, changing nothing: a mail scanner fetching the link cannot spend it. */
  suspend fun inspect(rawToken: String): Result<ResetLink> {
    val tokenHash = TokenHash.fromRawToken(rawToken)
    return database.withConnectionCatching { session ->
      AccountTokensDao.findSpendable(session, TOKEN_TYPE, tokenHash).fold(
        onSuccess = { ResetLink.Usable },
        onFailure = { failure ->
          if (failure !is NotFoundException) throw failure
          classifyDead(session, tokenHash)
        },
      )
    }
  }

  /**
   * Sets a new password from a usable link and signs the person in. A rule-breaking
   * password leaves the token unspent. Argon2 runs before the transaction, because
   * it is slow and must not hold a connection.
   */
  suspend fun reset(
    rawToken: String,
    newPassword: String,
    oldCookieToken: String?,
    sessionExpiration: Duration,
    userAgent: String?,
    initialIp: String?,
  ): Result<PasswordResetResult> {
    when (val link = inspect(rawToken).getOrElse { return Result.failure(it) }) {
      ResetLink.Usable -> Unit
      is ResetLink.Dead -> return Result.success(PasswordResetResult.Dead(link))
    }
    val violations = passwordViolations(newPassword)
    if (violations.isNotEmpty()) return Result.success(PasswordResetResult.Rejected(violations))

    val passwordHash = hash(newPassword).getOrElse { return Result.failure(it) }
    val tokenHash = TokenHash.fromRawToken(rawToken)

    return database.withConnectionCatching { session ->
      val token =
        AccountTokensDao.claim(session, TOKEN_TYPE, tokenHash).getOrElse { failure ->
          if (failure !is NotFoundException) throw failure
          return@withConnectionCatching PasswordResetResult.Dead(classifyDead(session, tokenHash))
        }
      val changed = UsersDao.setPassword(session, token.userId, passwordHash).getOrThrow()
      SessionsDao.revokeAllForUser(session, token.userId).getOrThrow()
      AccountTokensDao.consumeAllForUser(session, TOKEN_TYPE, token.userId).getOrThrow()
      val user = markVerifiedOnce(session, changed)
      val sessionToken =
        sessionMinter.mint(session, user, LoginMethod.PASSWORD, oldCookieToken, sessionExpiration, userAgent, initialIp)
      PasswordResetResult.Done(user, sessionToken)
    }
  }

  private suspend fun hash(password: String): Result<PasswordHash> =
    try {
      when (val created = PasswordHash.create(argon2Hasher.hash(password))) {
        is ValidationResult.Valid -> Result.success(created.value)
        is ValidationResult.Invalid -> Result.failure(IllegalStateException("Argon2 produced a rejected password hash: ${created.error}"))
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * Marks the address verified: the link proved the inbox exactly as a
   * verification link does. Skipped for a verified address, because
   * [afterVerified] must not run twice for one address.
   */
  private fun markVerifiedOnce(
    session: SqlSession,
    user: User,
  ): User = if (user.emailVerifiedAt == null) recordEmailVerified(session, user.id, afterVerified) else user

  /**
   * Why [tokenHash] cannot be spent, recorded here because this is where the row
   * is in hand. The log names the row and its owner; the token is a live
   * credential and never appears.
   */
  private fun classifyDead(
    session: SqlSession,
    tokenHash: TokenHash,
  ): ResetLink.Dead {
    val token =
      AccountTokensDao.findByTokenHash(session, TOKEN_TYPE, tokenHash).getOrElse { failure ->
        if (failure !is NotFoundException) throw failure
        val unknown = ResetLink.Dead.NotValid(ResetLink.Dead.NotValidReason.UNKNOWN_TOKEN)
        logger.info("password reset link dead: outcome=[{}]", unknown)
        return unknown
      }
    val dead =
      when {
        token.consumedAt != null -> ResetLink.Dead.AlreadyUsed
        !token.expiresAt.isAfter(Instant.now()) -> ResetLink.Dead.Expired
        else -> ResetLink.Dead.NotValid(ResetLink.Dead.NotValidReason.OWNER_CHANGED)
      }
    logger.info(
      "password reset link dead: outcome=[{}] tokenId=[{}] userId=[{}] consumedAt=[{}] expiresAt=[{}]",
      dead,
      token.id.asString,
      token.userId.asString,
      token.consumedAt,
      token.expiresAt,
    )
    return dead
  }

  private companion object {
    val TOKEN_TYPE = AccountTokenType.PASSWORD_RESET
  }
}
