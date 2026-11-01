package ed.unicoach.db.dao

import java.sql.Connection
import java.sql.ResultSet

/**
 * Positional-parameter JDBC for tests that write or read a row no DAO exposes:
 * every argument is bound with `setObject`, in order.
 *
 * Beside the row fixtures because they and the suites that use them kept a
 * private copy of these few lines each (RFC 264); a copy that binds one
 * argument differently is a test that seeds a row nobody asked for.
 */
object RawSql {
  /** Runs one INSERT, UPDATE or DELETE. */
  fun execute(
    connection: Connection,
    sql: String,
    vararg args: Any?,
  ) {
    connection.prepareStatement(sql).use { stmt ->
      bind(stmt, args)
      stmt.executeUpdate()
    }
  }

  /**
   * Runs a query that must answer exactly one row, and reads it with [read].
   * No row, or more than one, is a broken fixture, not an answer, so it throws
   * naming the SQL and its bound arguments.
   */
  fun <T> single(
    connection: Connection,
    sql: String,
    vararg args: Any?,
    read: (ResultSet) -> T,
  ): T =
    connection.prepareStatement(sql).use { stmt ->
      bind(stmt, args)
      stmt.executeQuery().use { rs ->
        check(rs.next()) { "expected exactly one row from [$sql] with args ${args.toList()}, got none" }
        val value = read(rs)
        check(!rs.next()) { "expected exactly one row from [$sql] with args ${args.toList()}, got more" }
        value
      }
    }

  private fun bind(
    stmt: java.sql.PreparedStatement,
    args: Array<out Any?>,
  ) {
    args.forEachIndexed { i, arg -> stmt.setObject(i + 1, arg) }
  }
}
