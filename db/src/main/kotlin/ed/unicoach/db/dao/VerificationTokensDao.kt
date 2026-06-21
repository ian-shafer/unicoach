package ed.unicoach.db.dao

import ed.unicoach.db.models.NewVerificationToken
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import ed.unicoach.db.models.VerificationToken
import ed.unicoach.db.models.VerificationTokenId
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

/**
 * Single-use email-verification credential store. Built on the [Creatable]
 * capability for create; the remaining operations are concrete methods. Modeled
 * on [SessionsDao] (a hashed credential): only the SHA-256 hash is persisted; the
 * raw token rides only in the email link.
 */
object VerificationTokensDao : Creatable<NewVerificationToken, VerificationToken> {
  private fun mapToken(rs: ResultSet): VerificationToken =
    VerificationToken(
      id = VerificationTokenId(UUID.fromString(rs.getString("id"))),
      userId = UserId(UUID.fromString(rs.getString("user_id"))),
      expiresAt = rs.getInstant("expires_at"),
      consumedAt = rs.getInstantOrNull("consumed_at"),
      createdAt = rs.getInstant("created_at"),
    )

  override fun create(
    session: SqlSession,
    input: NewVerificationToken,
  ): Result<VerificationToken> {
    val sql =
      """
      INSERT INTO verification_tokens (user_id, token_hash, expires_at)
      VALUES (?, ?, ?)
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setObject(1, input.userId.value)
        stmt.setBytes(2, input.tokenHash.value)
        stmt.setTimestamp(3, Timestamp.from(input.expiresAt))
      },
      map = ::mapToken,
    )
  }

  /**
   * Compare-and-swap claim: atomically stamps `consumed_at` on the single
   * unconsumed, unexpired token matching [tokenHash] and returns the claimed row.
   * When no row matches (unknown hash, already consumed, or expired) it yields
   * [NotFoundException] — the same no-row convention [SessionsDao] uses. Exactly
   * one concurrent caller can win the row; the rest see zero rows.
   */
  fun consume(
    session: SqlSession,
    tokenHash: TokenHash,
  ): Result<VerificationToken> {
    val sql =
      """
      UPDATE verification_tokens
      SET consumed_at = NOW()
      WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > NOW()
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { it.setBytes(1, tokenHash.value) },
      map = ::mapToken,
      onNoRow = { NotFoundException() },
    )
  }

  /**
   * Reads a token by hash in any state (consumed/expired included) so a failed
   * [consume] can be classified. An unknown hash yields [NotFoundException].
   */
  fun findByTokenHash(
    session: SqlSession,
    tokenHash: TokenHash,
  ): Result<VerificationToken> =
    session.queryOne(
      "SELECT * FROM verification_tokens WHERE token_hash = ?",
      bind = { it.setBytes(1, tokenHash.value) },
      map = ::mapToken,
      onNoRow = { NotFoundException() },
    )

  /**
   * Stamps `consumed_at` on every still-unconsumed token for [userId] and returns
   * the affected count; already-consumed rows are left untouched.
   */
  fun consumeAllForUser(
    session: SqlSession,
    userId: UserId,
  ): Result<Int> =
    session.execute(
      "UPDATE verification_tokens SET consumed_at = NOW() WHERE user_id = ? AND consumed_at IS NULL",
      bind = { it.setObject(1, userId.value) },
    )
}
