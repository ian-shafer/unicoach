package ed.unicoach.db.dao

import ed.unicoach.db.models.AccountToken
import ed.unicoach.db.models.AccountTokenId
import ed.unicoach.db.models.AccountTokenType
import ed.unicoach.db.models.NewAccountToken
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.util.UUID

/**
 * Single-use tokens mailed to an account's address (RFC 276). Only the SHA-256
 * hash is persisted; the raw token rides only in the email link. Every read and
 * claim takes the [AccountTokenType], so a token minted for one type cannot be
 * spent on another.
 */
object AccountTokensDao : Creatable<NewAccountToken, AccountToken> {
  /**
   * What makes a token spendable, over `account_tokens t`, `users u` and
   * `users_versions uv`. Compares ADDRESSES, not version numbers: a rename or a
   * verification also bumps `users.version` and must not kill a link, while a
   * link in a mailbox the account has left must be dead however the address
   * changed. A missing version row matches nothing, so this fails closed.
   */
  private val SPENDABLE =
    """
    t.token_hash = ? AND t.type = ?
      AND t.consumed_at IS NULL AND t.expires_at > NOW()
      AND u.id = t.user_id AND u.deleted_at IS NULL
      AND uv.id = u.id AND uv.version = t.user_version AND uv.email = u.email
    """.trimIndent()

  private fun mapToken(rs: ResultSet): AccountToken {
    val type = rs.getString("type")
    return AccountToken(
      id = AccountTokenId(UUID.fromString(rs.getString("id"))),
      userId = UserId(UUID.fromString(rs.getString("user_id"))),
      type = AccountTokenType.fromValue(type) ?: throw SQLException("Persisted account_tokens.type is not a valid enum value: \"$type\""),
      userVersion = rs.getInt("user_version"),
      expiresAt = rs.getInstant("expires_at"),
      consumedAt = rs.getInstantOrNull("consumed_at"),
      createdAt = rs.getInstant("created_at"),
    )
  }

  private fun bindKey(
    stmt: PreparedStatement,
    type: AccountTokenType,
    tokenHash: TokenHash,
  ) {
    stmt.setBytes(1, tokenHash.value)
    stmt.setString(2, type.value)
  }

  override fun create(
    session: SqlSession,
    input: NewAccountToken,
  ): Result<AccountToken> {
    val sql =
      """
      INSERT INTO account_tokens (user_id, type, user_version, token_hash, expires_at)
      VALUES (?, ?, ?, ?, ?)
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setObject(1, input.userId.value)
        stmt.setString(2, input.type.value)
        stmt.setInt(3, input.userVersion)
        stmt.setBytes(4, input.tokenHash.value)
        stmt.setTimestamp(5, Timestamp.from(input.expiresAt))
      },
      map = ::mapToken,
    )
  }

  /**
   * Compare-and-swap claim: stamps `consumed_at` on the spendable token matching
   * [tokenHash] and returns it, or yields [NotFoundException] when none matches.
   * Exactly one concurrent caller can win the row.
   *
   * It first locks the owning `users` row, which the caller's transaction then
   * holds. A plain `UPDATE ... FROM users` reads that row without locking it, so
   * an address change could commit after the claim's check and the caller would
   * go on to mark the NEW address verified on the strength of a link mailed to
   * the old one. The wait is a blocking one, not `findByIdForUpdate`'s `NOWAIT`:
   * the loser of two simultaneous claims must see "already used", not a fault.
   */
  fun claim(
    session: SqlSession,
    type: AccountTokenType,
    tokenHash: TokenHash,
  ): Result<AccountToken> {
    lockOwner(session, type, tokenHash).onFailure { return Result.failure(it) }
    return session.mutateReturning(
      """
      UPDATE account_tokens t SET consumed_at = NOW()
        FROM users u, users_versions uv
       WHERE $SPENDABLE
      RETURNING t.*
      """.trimIndent(),
      bind = { bindKey(it, type, tokenHash) },
      map = ::mapToken,
      onNoRow = { NotFoundException() },
    )
  }

  /** Locks the `users` row owning the token; a hash matching nothing locks nothing and is not an error here. */
  private fun lockOwner(
    session: SqlSession,
    type: AccountTokenType,
    tokenHash: TokenHash,
  ): Result<Unit?> =
    session
      .queryOne(
        """
        SELECT 1 FROM users u
          JOIN account_tokens t ON t.user_id = u.id
         WHERE t.token_hash = ? AND t.type = ?
           FOR UPDATE OF u
        """.trimIndent(),
        bind = { bindKey(it, type, tokenHash) },
        map = { },
      ).orNullOnNotFound()

  /**
   * The token [claim] would win right now, changing nothing, or
   * [NotFoundException] when it would win none. The side-effect-free read
   * behind the mailed `GET`, so a mail scanner fetching the link cannot spend it.
   */
  fun findSpendable(
    session: SqlSession,
    type: AccountTokenType,
    tokenHash: TokenHash,
  ): Result<AccountToken> =
    session.queryOne(
      "SELECT t.* FROM account_tokens t, users u, users_versions uv WHERE $SPENDABLE",
      bind = { bindKey(it, type, tokenHash) },
      map = ::mapToken,
      onNoRow = { NotFoundException() },
    )

  /**
   * Reads a token by hash in any state so a failed [claim] can be classified.
   * An unknown hash, or one minted for another type, yields [NotFoundException].
   */
  fun findByTokenHash(
    session: SqlSession,
    type: AccountTokenType,
    tokenHash: TokenHash,
  ): Result<AccountToken> =
    session.queryOne(
      "SELECT * FROM account_tokens WHERE token_hash = ? AND type = ?",
      bind = { bindKey(it, type, tokenHash) },
      map = ::mapToken,
      onNoRow = { NotFoundException() },
    )

  /**
   * Stamps `consumed_at` on every still-unconsumed token of [type] for [userId]
   * and returns the affected count; already-consumed rows are left untouched.
   */
  fun consumeAllForUser(
    session: SqlSession,
    type: AccountTokenType,
    userId: UserId,
  ): Result<Int> =
    session.execute(
      "UPDATE account_tokens SET consumed_at = NOW() WHERE user_id = ? AND type = ? AND consumed_at IS NULL",
      bind = {
        it.setObject(1, userId.value)
        it.setString(2, type.value)
      },
    )
}
