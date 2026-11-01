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
  /**
   * Runs one INSERT, UPDATE or DELETE and answers the number of rows it
   * affected, for a test that asserts the write landed.
   */
  fun execute(
    connection: Connection,
    sql: String,
    vararg args: Any?,
  ): Int =
    connection.prepareStatement(sql).use { stmt ->
      bind(stmt, args)
      stmt.executeUpdate()
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
  ): T = atMostOne(connection, sql, args, "exactly one", read) { error(unexpected("exactly one", sql, args, "none")) }

  /**
   * Runs a query that answers one row or none, and reads the row with [read];
   * null is "no row", which is why [read] cannot itself answer null. More than
   * one row is a broken fixture, not an answer, so it throws naming the SQL and
   * its bound arguments.
   */
  fun <T : Any> singleOrNull(
    connection: Connection,
    sql: String,
    vararg args: Any?,
    read: (ResultSet) -> T,
  ): T? = atMostOne(connection, sql, args, "at most one", read) { null }

  /**
   * The one query loop behind [single] and [singleOrNull]: they differ only in
   * what an empty result answers, [none], and the [expected] row count named in
   * the error.
   */
  private inline fun <T> atMostOne(
    connection: Connection,
    sql: String,
    args: Array<out Any?>,
    expected: String,
    read: (ResultSet) -> T,
    none: () -> T,
  ): T =
    connection.prepareStatement(sql).use { stmt ->
      bind(stmt, args)
      stmt.executeQuery().use { rs ->
        if (!rs.next()) return none()
        val value = read(rs)
        check(!rs.next()) { unexpected(expected, sql, args, "more") }
        value
      }
    }

  private fun unexpected(
    expected: String,
    sql: String,
    args: Array<out Any?>,
    got: String,
  ) = "expected $expected row from [$sql] with args ${args.toList()}, got $got"

  private fun bind(
    stmt: java.sql.PreparedStatement,
    args: Array<out Any?>,
  ) {
    args.forEachIndexed { i, arg -> stmt.setObject(i + 1, arg) }
  }
}
