package ed.unicoach.db.dao

import ed.unicoach.db.models.TableErasure
import ed.unicoach.db.models.UserId
import java.sql.SQLException

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
 */
object AccountErasureDao {
  private const val NO_SUCH_ACCOUNT = "erase_account_exists"
  private const val ACCOUNT_LIVE = "erase_account_closed"

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
