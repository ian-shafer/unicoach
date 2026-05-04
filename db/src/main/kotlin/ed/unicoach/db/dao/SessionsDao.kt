package ed.unicoach.db.dao

import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.Session
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import ed.unicoach.error.ExceptionWrapper
import java.sql.ResultSet
import java.util.UUID

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

  private fun <T> executeSafely(block: () -> DaoResult<T>): DaoResult<T> =
    try {
      block()
    } catch (e: Exception) {
      classifyDatabaseError(e)
    }

  fun findByTokenHash(
    session: SqlSession,
    tokenHash: TokenHash,
  ): DaoResult<Session> =
    executeSafely {
      val sql = "SELECT * FROM sessions WHERE token_hash = ? AND is_revoked = false AND expires_at > NOW()"
      session.prepareStatement(sql).use { stmt ->
        stmt.setBytes(1, tokenHash.value)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) {
            return@executeSafely DaoResult.PermanentError.NotFound("Session not found or expired")
          }
          val foundHash = rs.getBytes("token_hash")
          if (!foundHash.contentEquals(tokenHash.value)) {
            return@executeSafely DaoResult.PermanentError.NotFound("Session hash mismatch")
          }
          DaoResult.Success(mapSession(rs))
        }
      }
    }

  fun create(
    session: SqlSession,
    newSession: NewSession,
  ): DaoResult<Session> =
    executeSafely {
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
            DaoResult.Success(mapSession(rs))
          } else {
            DaoResult.PermanentError.DatabaseError(ExceptionWrapper.from(RuntimeException("Insert succeeded but returning failed")))
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
  ): DaoResult<Session> =
    executeSafely {
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
            DaoResult.Success(mapSession(rs))
          } else {
            DaoResult.PermanentError.NotFound("Session could not be reminted either due to version mismatch or not found")
          }
        }
      }
    }

  fun extendExpiry(
    session: SqlSession,
    id: UUID,
    currentVersion: Int,
  ): DaoResult<Session> =
    executeSafely {
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
            DaoResult.Success(mapSession(rs))
          } else {
            DaoResult.PermanentError.NotFound("Session could not be extended either due to version mismatch or not found")
          }
        }
      }
    }

  fun revokeByTokenHash(
    session: SqlSession,
    tokenHash: TokenHash,
  ): DaoResult<Session> =
    executeSafely {
      val sql =
        """
        UPDATE sessions 
        SET version = version + 1, is_revoked = true
        WHERE token_hash = ? AND is_revoked = false
        RETURNING *
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        stmt.setBytes(1, tokenHash.value)

        stmt.executeQuery().use { rs ->
          if (rs.next()) {
            DaoResult.Success(mapSession(rs))
          } else {
            DaoResult.PermanentError.NotFound("Session not found or already revoked")
          }
        }
      }
    }

  fun expireZombieSessions(session: SqlSession): DaoResult<Unit> =
    executeSafely {
      val sql =
        """
        DELETE FROM sessions 
        WHERE expires_at < NOW() OR is_revoked = true
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        stmt.executeUpdate()
        DaoResult.Success(Unit)
      }
    }
}
