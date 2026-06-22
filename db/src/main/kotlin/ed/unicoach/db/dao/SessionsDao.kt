package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.models.LoginMethod
import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.Session
import ed.unicoach.db.models.SessionId
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import java.sql.ResultSet
import java.util.UUID

object SessionsDao :
  Findable<Session, SessionId>,
  Listable<Session>,
  Creatable<NewSession, Session>,
  Destroyable<SessionId> {
  private fun mapSession(rs: ResultSet): Session {
    val id = SessionId(UUID.fromString(rs.getString("id")))
    val version = rs.getInt("version")
    val createdAt = rs.getInstant("created_at")
    val userIdStr = rs.getString("user_id")
    val userId = if (userIdStr != null) UserId(UUID.fromString(userIdStr)) else null
    // A genuine SQL NULL is an anonymous session (no login method). A non-null
    // value the enum cannot resolve is row corruption — surfaced as a
    // CorruptPersistedValueException (a PermanentError), never silently mapped to
    // null, which would make a corrupt row indistinguishable from an anonymous one.
    val rawLoginMethod = rs.getString("login_method")
    val loginMethod =
      if (rawLoginMethod == null) {
        null
      } else {
        LoginMethod.fromWire(rawLoginMethod)
          ?: throw CorruptPersistedValueException(
            rawLoginMethod,
            ValidationError.InvalidFormat(expected = "a known LoginMethod wire value"),
          )
      }
    val metadata = rs.getString("metadata")
    val userAgent = rs.getString("user_agent")
    val initialIp = rs.getString("initial_ip")
    val expiresAt = rs.getInstant("expires_at")

    return Session(
      id = id,
      version = version,
      createdAt = createdAt,
      userId = userId,
      loginMethod = loginMethod,
      metadata = metadata,
      userAgent = userAgent,
      initialIp = initialIp,
      expiresAt = expiresAt,
    )
  }

  override fun findById(
    session: SqlSession,
    id: SessionId,
  ): Result<Session> =
    session.queryOne(
      "SELECT * FROM sessions WHERE id = ?",
      bind = { it.setObject(1, id.value) },
      map = ::mapSession,
      onNoRow = { NotFoundException("Session not found") },
    )

  fun listByUser(
    session: SqlSession,
    userId: UserId,
    limit: Int,
    offset: Int,
  ): Result<List<Session>> {
    val sql =
      """
      SELECT * FROM sessions
      WHERE user_id = ?
      ORDER BY created_at DESC, id
      LIMIT ? OFFSET ?
      """.trimIndent()
    return session.queryList(
      sql,
      bind = { stmt ->
        stmt.setObject(1, userId.value)
        stmt.setInt(2, limit)
        stmt.setInt(3, offset)
      },
      map = ::mapSession,
    )
  }

  override fun list(
    session: SqlSession,
    limit: Int,
    offset: Int,
  ): Result<List<Session>> {
    val sql =
      """
      SELECT * FROM sessions
      ORDER BY created_at DESC, id
      LIMIT ? OFFSET ?
      """.trimIndent()
    return session.queryList(
      sql,
      bind = { stmt ->
        stmt.setInt(1, limit)
        stmt.setInt(2, offset)
      },
      map = ::mapSession,
    )
  }

  /**
   * Physical delete: `sessions` carries no `prevent_physical_delete` trigger, so
   * the row is removed outright (unlike soft-delete entities). A missing id is a
   * [NotFoundException].
   */
  override fun destroy(
    session: SqlSession,
    id: SessionId,
  ): Result<Unit> =
    session
      .execute(
        "DELETE FROM sessions WHERE id = ?",
        bind = { it.setObject(1, id.value) },
      ).mapCatching { affected ->
        if (affected == 0) throw NotFoundException("Session not found")
      }

  fun findByTokenHash(
    session: SqlSession,
    tokenHash: TokenHash,
  ): Result<Session> =
    try {
      val sql = "SELECT * FROM sessions WHERE token_hash = ? AND is_revoked = false AND expires_at > NOW()"
      session.prepareStatement(sql).use { stmt ->
        stmt.setBytes(1, tokenHash.value)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) {
            return Result.failure(NotFoundException("Session not found or expired"))
          }
          val foundHash = rs.getBytes("token_hash")
          if (!foundHash.contentEquals(tokenHash.value)) {
            return Result.failure(NotFoundException("Session hash mismatch"))
          }
          Result.success(mapSession(rs))
        }
      }
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  override fun create(
    session: SqlSession,
    input: NewSession,
  ): Result<Session> {
    val sql =
      """
      INSERT INTO sessions (user_id, login_method, token_hash, user_agent, initial_ip, metadata, expires_at)
      VALUES (?, ?, ?, ?, ?, ?::jsonb, NOW() + (${input.expiration.seconds} * INTERVAL '1 second'))
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        if (input.userId != null) stmt.setObject(1, input.userId.value) else stmt.setNull(1, java.sql.Types.OTHER)
        stmt.setStringOrNull(2, input.loginMethod?.wire)
        stmt.setBytes(3, input.tokenHash.value)
        stmt.setStringOrNull(4, input.userAgent)
        stmt.setStringOrNull(5, input.initialIp)
        stmt.setStringOrNull(6, input.metadata)
      },
      map = ::mapSession,
    )
  }

  fun remintToken(
    session: SqlSession,
    id: SessionId,
    currentVersion: Int,
    newUserId: UserId,
    newTokenHash: ByteArray,
    newExpirationSeconds: Long,
    newLoginMethod: LoginMethod,
  ): Result<Session> {
    val sql =
      """
      UPDATE sessions
      SET version = ?, user_id = ?, login_method = ?, token_hash = ?, expires_at = NOW() + (? * INTERVAL '1 second')
      WHERE id = ? AND version = ? AND is_revoked = false
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setInt(1, currentVersion + 1)
        stmt.setObject(2, newUserId.value)
        stmt.setString(3, newLoginMethod.wire)
        stmt.setBytes(4, newTokenHash)
        stmt.setLong(5, newExpirationSeconds)
        stmt.setObject(6, id.value)
        stmt.setInt(7, currentVersion)
      },
      map = ::mapSession,
      onNoRow = { NotFoundException("Session could not be reminted either due to version mismatch or not found") },
    )
  }

  fun extendExpiry(
    session: SqlSession,
    id: SessionId,
    currentVersion: Int,
  ): Result<Session> {
    val sql =
      """
      UPDATE sessions
      SET version = ?, expires_at = NOW() + INTERVAL '7 days'
      WHERE id = ? AND version = ? AND is_revoked = false
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setInt(1, currentVersion + 1)
        stmt.setObject(2, id.value)
        stmt.setInt(3, currentVersion)
      },
      map = ::mapSession,
      onNoRow = { NotFoundException("Session could not be extended either due to version mismatch or not found") },
    )
  }

  fun revokeByTokenHash(
    session: SqlSession,
    tokenHash: TokenHash,
  ): Result<Session> {
    val sql =
      """
      UPDATE sessions
      SET version = version + 1, is_revoked = true
      WHERE token_hash = ? AND is_revoked = false
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { it.setBytes(1, tokenHash.value) },
      map = ::mapSession,
      onNoRow = { NotFoundException("Session not found or already revoked") },
    )
  }

  fun expireZombieSessions(session: SqlSession): Result<Unit> =
    session
      .execute(
        """
        DELETE FROM sessions
        WHERE expires_at < NOW() OR is_revoked = true
        """.trimIndent(),
      ).map { }
}
