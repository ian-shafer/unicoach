package ed.unicoach.db.dao

import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.Session
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import ed.unicoach.error.ExceptionWrapper
import java.sql.ResultSet
import java.util.UUID

sealed interface SessionFindResult {
  data class Success(
    val session: Session,
  ) : SessionFindResult

  data class NotFound(
    val message: String,
  ) : SessionFindResult

  data class DatabaseFailure(
    val error: ExceptionWrapper,
  ) : SessionFindResult
}

sealed interface SessionCreateResult {
  data class Success(
    val session: Session,
  ) : SessionCreateResult

  data class DatabaseFailure(
    val error: ExceptionWrapper,
  ) : SessionCreateResult
}

sealed interface SessionUpdateResult {
  data class Success(
    val session: Session,
  ) : SessionUpdateResult

  data class NotFound(
    val message: String,
  ) : SessionUpdateResult

  data class DatabaseFailure(
    val error: ExceptionWrapper,
  ) : SessionUpdateResult
}

sealed interface SessionDeleteResult {
  data object Success : SessionDeleteResult

  data class DatabaseFailure(
    val error: ExceptionWrapper,
  ) : SessionDeleteResult
}

object SessionsDao {
  private fun mapSession(rs: ResultSet): Session {
    val id = UUID.fromString(rs.getString("id"))
    val version = rs.getInt("version")
    val createdAt = rs.getTimestamp("created_at").toInstant()
    val userIdStr = rs.getString("user_id")
    val userId = if (userIdStr != null) UserId(UUID.fromString(userIdStr)) else null
    val metadata = rs.getString("metadata")
    val userAgent = rs.getString("user_agent")
    val initialIp = rs.getString("initial_ip")
    val expiresAt = rs.getTimestamp("expires_at").toInstant()

    return Session(
      id = id,
      version = version,
      createdAt = createdAt,
      userId = userId,
      metadata = metadata,
      userAgent = userAgent,
      initialIp = initialIp,
      expiresAt = expiresAt,
    )
  }

  private fun <T> executeSafely(
    onError: (ExceptionWrapper) -> T,
    block: () -> T,
  ): T =
    try {
      block()
    } catch (e: Exception) {
      onError(ExceptionWrapper.from(e))
    }

  fun findByTokenHash(
    session: SqlSession,
    tokenHash: TokenHash,
  ): SessionFindResult =
    executeSafely(SessionFindResult::DatabaseFailure) {
      val sql = "SELECT * FROM sessions WHERE token_hash = ? AND is_revoked = false AND expires_at > NOW()"
      session.prepareStatement(sql).use { stmt ->
        stmt.setBytes(1, tokenHash.value)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) {
            return@executeSafely SessionFindResult.NotFound("Session not found or expired")
          }
          val foundHash = rs.getBytes("token_hash")
          if (!foundHash.contentEquals(tokenHash.value)) {
            return@executeSafely SessionFindResult.NotFound("Session hash mismatch")
          }
          SessionFindResult.Success(mapSession(rs))
        }
      }
    }

  fun create(
    session: SqlSession,
    newSession: NewSession,
  ): SessionCreateResult =
    executeSafely(SessionCreateResult::DatabaseFailure) {
      val sql =
        """
        INSERT INTO sessions (user_id, token_hash, user_agent, initial_ip, metadata, expires_at)
        VALUES (?, ?, ?, ?, ?::jsonb, NOW() + (${newSession.expiration.seconds} * INTERVAL '1 second'))
        RETURNING *
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        if (newSession.userId != null) stmt.setObject(1, newSession.userId.value) else stmt.setNull(1, java.sql.Types.OTHER)
        stmt.setBytes(2, newSession.tokenHash.value)
        if (newSession.userAgent != null) stmt.setString(3, newSession.userAgent) else stmt.setNull(3, java.sql.Types.VARCHAR)
        if (newSession.initialIp != null) stmt.setString(4, newSession.initialIp) else stmt.setNull(4, java.sql.Types.VARCHAR)
        if (newSession.metadata != null) stmt.setString(5, newSession.metadata) else stmt.setNull(5, java.sql.Types.VARCHAR)

        stmt.executeQuery().use { rs ->
          if (rs.next()) {
            SessionCreateResult.Success(mapSession(rs))
          } else {
            SessionCreateResult.DatabaseFailure(ExceptionWrapper.from(RuntimeException("Insert succeeded but returning failed")))
          }
        }
      }
    }

  fun remintToken(
    session: SqlSession,
    id: UUID,
    currentVersion: Int,
    newUserId: UserId,
    newTokenHash: ByteArray,
    newExpirationSeconds: Long,
  ): SessionUpdateResult =
    executeSafely(SessionUpdateResult::DatabaseFailure) {
      val sql =
        """
        UPDATE sessions 
        SET version = ?, user_id = ?, token_hash = ?, expires_at = NOW() + (? * INTERVAL '1 second')
        WHERE id = ? AND version = ? AND is_revoked = false
        RETURNING *
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        val nextVersion = currentVersion + 1
        stmt.setInt(1, nextVersion)
        stmt.setObject(2, newUserId.value)
        stmt.setBytes(3, newTokenHash)
        stmt.setLong(4, newExpirationSeconds)
        stmt.setObject(5, id)
        stmt.setInt(6, currentVersion)

        stmt.executeQuery().use { rs ->
          if (rs.next()) {
            SessionUpdateResult.Success(mapSession(rs))
          } else {
            SessionUpdateResult.NotFound("Session could not be reminted either due to version mismatch or not found")
          }
        }
      }
    }

  fun extendExpiry(
    session: SqlSession,
    id: UUID,
    currentVersion: Int,
  ): SessionUpdateResult =
    executeSafely(SessionUpdateResult::DatabaseFailure) {
      val sql =
        """
        UPDATE sessions 
        SET version = ?, expires_at = NOW() + INTERVAL '7 days'
        WHERE id = ? AND version = ? AND is_revoked = false
        RETURNING *
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        val nextVersion = currentVersion + 1
        stmt.setInt(1, nextVersion)
        stmt.setObject(2, id)
        stmt.setInt(3, currentVersion)

        stmt.executeQuery().use { rs ->
          if (rs.next()) {
            SessionUpdateResult.Success(mapSession(rs))
          } else {
            SessionUpdateResult.NotFound("Session could not be updated either due to version mismatch or not found")
          }
        }
      }
    }

  fun expireZombieSessions(session: SqlSession): SessionDeleteResult =
    executeSafely(SessionDeleteResult::DatabaseFailure) {
      val sql =
        """
        DELETE FROM sessions 
        WHERE expires_at < NOW() OR is_revoked = true
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        stmt.executeUpdate()
        SessionDeleteResult.Success
      }
    }
}
