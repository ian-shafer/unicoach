package ed.unicoach.auth

import com.google.protobuf.ByteString
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.LoginMethod
import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.db.session.SessionMetadata
import ed.unicoach.util.TokenGenerator
import java.time.Duration

/** The one definition of what a new session is, shared by every service that signs someone in. */
class SessionMinter(
  private val tokenGenerator: TokenGenerator,
) {
  /**
   * Revokes a live old-cookie session (mirroring `login`) and mints a fresh
   * session bound to [user] with the given [loginMethod], in [session]'s
   * transaction. Returns the raw token for the cookie.
   */
  fun mint(
    session: SqlSession,
    user: User,
    loginMethod: LoginMethod,
    oldCookieToken: String?,
    sessionExpiration: Duration,
    userAgent: String?,
    initialIp: String?,
  ): String {
    if (oldCookieToken != null) {
      val oldHash = TokenHash.fromRawToken(oldCookieToken)
      val revoke = SessionsDao.revokeByTokenHash(session, oldHash)
      val revokeError = revoke.exceptionOrNull()
      if (revokeError != null && revokeError !is NotFoundException) {
        throw revokeError
      }
    }

    val newToken = tokenGenerator.generateToken()
    val newHash = TokenHash.fromRawToken(newToken)
    SessionsDao
      .create(
        session,
        NewSession(
          userId = user.id,
          tokenHash = newHash,
          userAgent = userAgent,
          initialIp = initialIp,
          metadata = newMetadata(),
          expiration = sessionExpiration,
          loginMethod = loginMethod,
        ),
      ).getOrThrow()
    return newToken
  }

  /**
   * A new session's first metadata: a fresh CSRF synchronizer token and nothing
   * else (RFC 265 D-F, brief 0013 D22).
   *
   * Minted WITH the session, in the same INSERT, so there is no window in which
   * a live session lacks one and no lazy first write a concurrent request could
   * race. Every session gets one, including `rest-server`'s, because the row is
   * one row whichever surface it is later used from.
   */
  fun newMetadata(): SessionMetadata =
    SessionMetadata
      .newBuilder()
      .setCsrfToken(ByteString.copyFrom(tokenGenerator.generateBytes()))
      .build()
}
