package ed.unicoach.db.dao

import ed.unicoach.db.models.TableErasure
import ed.unicoach.db.models.UserId
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant

/** How an account's `users.deleted_at` stands against the close an erase job was scheduled for (RFC 267 §4). */
enum class CloseMatch {
  /** No `users` row has the id. */
  NO_ACCOUNT,

  /** Not closed: reopened since. */
  LIVE,

  /** Closed by exactly that close. */
  THIS_CLOSE,

  /** Closed again since, by a close that has its own job. */
  ANOTHER_CLOSE,
}

/**
 * The door to `erase_account` (RFC 266 §3, erasure-functions.sql): erases one
 * closed account in the caller's transaction and answers what it did, per
 * table. The walk itself is in the schema; this object only calls it and
 * routes its two refusals by the constraint name the function raises them
 * with, never by the sentence:
 *
 * - `erase_account_exists` -> [NotFoundException]: no `users` row has the id;
 * - `erase_account_closed` -> [AccountNotClosedException]: the account is live.
 *
 * Any other failure is returned as the database failure it was. A re-run on an
 * erased account succeeds with an empty list.
 *
 * The steps beside it (RFC 267 §4) are what the erase transaction does before
 * the walk: [lock] first, then plain reads. They read `users` and
 * `deleted_users` directly because they answer for an erased account too,
 * which every [UsersDao] read excludes. An absent account is an answer of each
 * read, not a failure. The confirmation's address is not one of them: the
 * account is not yet erased when it is read, so it is read as a [UsersDao]
 * user.
 */
object AccountErasureDao {
  private const val NO_SUCH_ACCOUNT = "erase_account_exists"
  private const val ACCOUNT_LIVE = "erase_account_closed"

  /**
   * Locks the account's `users` row for the rest of the caller's transaction,
   * as the walk does; false when there is no such row. Taken FIRST, in its own
   * statement, so a concurrent erasure of the same account has either
   * committed -- and every later statement, on a fresh snapshot, sees what it
   * wrote -- or not begun.
   */
  fun lock(
    session: SqlSession,
    userId: UserId,
  ): Result<Boolean> =
    read {
      session.prepareStatement("SELECT 1 FROM users WHERE id = ? FOR UPDATE").use { stmt ->
        stmt.setObject(1, userId.value)
        stmt.executeQuery().use { rs -> rs.next() }
      }
    }

  /**
   * Whether the account has been erased: a walk is one transaction, so a
   * `deleted_users` row means erased whole. False when there is no account.
   */
  fun isErased(
    session: SqlSession,
    userId: UserId,
  ): Result<Boolean> =
    read {
      session.prepareStatement("SELECT EXISTS (SELECT 1 FROM deleted_users WHERE id = ?)").use { stmt ->
        stmt.setObject(1, userId.value)
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getBoolean(1)
        }
      }
    }

  /**
   * How the account's close stands against [closedAt], the close an erase job
   * was scheduled for, compared in SQL so the precision is Postgres's own.
   */
  fun closedAt(
    session: SqlSession,
    userId: UserId,
    closedAt: Instant,
  ): Result<CloseMatch> =
    read {
      session.prepareStatement("SELECT deleted_at IS NOT NULL, deleted_at = ? FROM users WHERE id = ?").use { stmt ->
        stmt.setTimestamp(1, Timestamp.from(closedAt))
        stmt.setObject(2, userId.value)
        stmt.executeQuery().use { rs ->
          when {
            !rs.next() -> CloseMatch.NO_ACCOUNT
            !rs.getBoolean(1) -> CloseMatch.LIVE
            rs.getBoolean(2) -> CloseMatch.THIS_CLOSE
            else -> CloseMatch.ANOTHER_CLOSE
          }
        }
      }
    }

  fun erase(
    session: SqlSession,
    userId: UserId,
  ): Result<List<TableErasure>> =
    try {
      session.prepareStatement("SELECT table_name, moved, erased, deleted FROM erase_account(?)").use { stmt ->
        stmt.setObject(1, userId.value)
        stmt.executeQuery().use { rs ->
          Result.success(
            buildList {
              while (rs.next()) {
                add(TableErasure(rs.getString("table_name"), rs.getLong("moved"), rs.getLong("erased"), rs.getLong("deleted")))
              }
            },
          )
        }
      }
    } catch (e: SQLException) {
      Result.failure(mapRefusal(e) ?: mapDatabaseError(e))
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  /** Runs one read; a failure is the database failure it was (a [DaoException] passes through as itself). */
  private fun <T> read(block: () -> T): Result<T> =
    try {
      Result.success(block())
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  private fun mapRefusal(e: SQLException): Exception? {
    if (e.sqlState != SQLSTATE_RAISE_EXCEPTION) return null
    val serverError = (e as? org.postgresql.util.PSQLException)?.serverErrorMessage
    return when (val constraint = serverError?.constraint) {
      NO_SUCH_ACCOUNT -> NotFoundException(serverError.message ?: "No such account", e, constraint, serverError.detail)
      ACCOUNT_LIVE -> AccountNotClosedException(serverError.message, e, constraint, serverError.detail)
      else -> null
    }
  }
}
