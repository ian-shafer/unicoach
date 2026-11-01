package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.models.LoginMethod
import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.Session
import ed.unicoach.db.models.SessionId
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import ed.unicoach.db.session.SessionMetadata
import ed.unicoach.db.session.SessionMetadataJson
import org.postgresql.util.PSQLException
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

object SessionsDao :
  Findable<Session, SessionId>,
  Listable<Session>,
  Creatable<NewSession, Session>,
  Destroyable<SessionId> {
  /** The column's size CHECK: the cap's one home (RFC 265). */
  private const val METADATA_SIZE_CHECK = "sessions_metadata_size_check"

  /**
   * A `metadata` write's SQLSTATE mapping: the size CHECK becomes
   * [SessionMetadataTooLargeException], told apart from any other `23514` by its
   * constraint name, and carrying the row ([id], `null` for an INSERT) and the
   * size of the [printed] value it refused; everything else keeps the DAO's
   * default mapping.
   */
  private fun mapMetadataWriteError(
    e: SQLException,
    id: SessionId?,
    printed: String?,
  ): Exception {
    val serverError = (e as? PSQLException)?.serverErrorMessage
    return if (e.sqlState == SQLSTATE_CHECK_VIOLATION && serverError?.constraint == METADATA_SIZE_CHECK) {
      SessionMetadataTooLargeException(METADATA_SIZE_CHECK, id, printed?.toByteArray(Charsets.UTF_8)?.size ?: 0)
    } else {
      mapDatabaseError(e)
    }
  }

  /**
   * The live-row predicate of a metadata write, stated once: the
   * UPDATE filters on it, and the existence probe that tells a version race
   * from a revoked row asks the same question. Two copies could drift apart,
   * and a probe looser than its UPDATE would retry a row it can never write.
   */
  private const val LIVE_SESSION = "is_revoked = false"

  /** The marker a corrupt `metadata` cell's exception carries in place of the cell. */
  private const val WITHHELD_METADATA = "<withheld: sessions.metadata is pii.jsonb>"

  /**
   * The stored metadata as a [SessionMetadata]. SQL `NULL` is the default
   * instance: proto3 has no other way to say "nothing set", and every row
   * minted before RFC 265 holds `NULL`.
   *
   * Anything the strict parser refuses is row corruption (D-E). The raw value is
   * WITHHELD from the exception: the column is `pii.jsonb`, a draft holds what a
   * family typed, and the CSRF token is a secret. So is the parser's own
   * message, which can quote the offending JSON: the cause is chained as a
   * [WithheldMessageException], which keeps the parser exception's type and
   * stack and drops its text. The row id is what finds the cell.
   */
  private fun mapMetadata(
    raw: String?,
    id: SessionId,
  ): SessionMetadata {
    if (raw == null) return SessionMetadata.getDefaultInstance()
    return try {
      SessionMetadataJson.parse(raw)
    } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
      throw CorruptPersistedValueException(
        WITHHELD_METADATA,
        ValidationError.InvalidFormat(expected = "canonical proto3 JSON of a SessionMetadata"),
        location = "sessions.metadata id=[${id.value}]",
        cause = WithheldMessageException(e),
      )
    }
  }

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
    val metadata = mapMetadata(rs.getString("metadata"), id)
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
    val metadata = SessionMetadataJson.printOrNull(input.metadata)
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        if (input.userId != null) stmt.setObject(1, input.userId.value) else stmt.setNull(1, java.sql.Types.OTHER)
        stmt.setStringOrNull(2, input.loginMethod?.wire)
        stmt.setBytes(3, input.tokenHash.value)
        stmt.setStringOrNull(4, input.userAgent)
        stmt.setStringOrNull(5, input.initialIp)
        stmt.setStringOrNull(6, metadata)
      },
      map = ::mapSession,
      mapError = { mapMetadataWriteError(it, null, metadata) },
    )
  }

  /**
   * Rebinds a live session to [newUserId] under a new token. [newMetadata]
   * REPLACES the row's metadata rather than keeping it: the row is changing
   * hands, and the previous holder's form drafts and CSRF token are not the new
   * holder's to inherit (RFC 265).
   */
  fun remintToken(
    session: SqlSession,
    id: SessionId,
    currentVersion: Int,
    newUserId: UserId,
    newTokenHash: ByteArray,
    newExpirationSeconds: Long,
    newLoginMethod: LoginMethod,
    newMetadata: SessionMetadata,
  ): Result<Session> {
    val sql =
      """
      UPDATE sessions
      SET version = ?, user_id = ?, login_method = ?, token_hash = ?, metadata = ?::jsonb,
          expires_at = NOW() + (? * INTERVAL '1 second')
      WHERE id = ? AND version = ? AND is_revoked = false
      RETURNING *
      """.trimIndent()
    val metadata = SessionMetadataJson.printOrNull(newMetadata)
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setInt(1, currentVersion + 1)
        stmt.setObject(2, newUserId.value)
        stmt.setString(3, newLoginMethod.wire)
        stmt.setBytes(4, newTokenHash)
        stmt.setStringOrNull(5, metadata)
        stmt.setLong(6, newExpirationSeconds)
        stmt.setObject(7, id.value)
        stmt.setInt(8, currentVersion)
      },
      map = ::mapSession,
      mapError = { mapMetadataWriteError(it, id, metadata) },
      onNoRow = { NotFoundException("Session could not be reminted either due to version mismatch or not found") },
    )
  }

  /**
   * Replaces a live session's [metadata] at [currentVersion] (RFC 265).
   *
   * It never touches `expires_at`, so saving a draft never keeps a session
   * alive. It bumps `version` like `extendExpiry` does, so the two serialize
   * through the row's optimistic version rather than one overwriting the other.
   *
   * Failures: [SessionMetadataTooLargeException] when the size CHECK refuses the
   * value; [ConcurrentModificationException] when the row is live at another
   * version; [NotFoundException] when it is revoked or gone.
   */
  fun writeMetadata(
    session: SqlSession,
    id: SessionId,
    currentVersion: Int,
    metadata: SessionMetadata,
  ): Result<Session> {
    val sql =
      """
      UPDATE sessions
      SET version = ?, metadata = ?::jsonb
      WHERE id = ? AND version = ? AND $LIVE_SESSION
      RETURNING *
      """.trimIndent()
    val printed = SessionMetadataJson.printOrNull(metadata)
    return session.occUpdate(
      table = "sessions",
      sql = sql,
      bind = { stmt ->
        stmt.setInt(1, currentVersion + 1)
        stmt.setStringOrNull(2, printed)
        stmt.setObject(3, id.value)
        stmt.setInt(4, currentVersion)
      },
      idValue = id.value,
      map = ::mapSession,
      mapError = { mapMetadataWriteError(it, id, printed) },
      liveCondition = LIVE_SESSION,
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

  /**
   * Revokes every live session [userId] holds, on every device (RFC 252), and
   * returns how many it revoked. A closed account's sessions already fail to
   * resolve, because session resolution reads the user as ACTIVE; revoking
   * them makes the session table say so too.
   */
  fun revokeAllForUser(
    session: SqlSession,
    userId: UserId,
  ): Result<Int> =
    session.execute(
      """
      UPDATE sessions
      SET version = version + 1, is_revoked = true
      WHERE user_id = ? AND is_revoked = false
      """.trimIndent(),
    ) { it.setObject(1, userId.value) }

  fun expireZombieSessions(session: SqlSession): Result<Unit> =
    session
      .execute(
        """
        DELETE FROM sessions
        WHERE expires_at < NOW() OR is_revoked = true
        """.trimIndent(),
      ).map { }
}
