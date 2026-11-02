package ed.unicoach.db.dao

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Positional-parameter JDBC for tests that write or read rows no DAO exposes:
 * every argument is bound with `setObject`, in order.
 *
 * Beside the row fixtures because they and the suites that use them kept a
 * private copy of these few lines each (RFC 264); a copy that binds one
 * argument differently is a test that seeds a row nobody asked for.
 *
 * Every operation takes either a raw [Connection] or a pooled [SqlSession]
 * (`database.withConnection`). All either needs to do is prepare a statement,
 * so each pair of overloads hands that one capability to a shared body.
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
  ): Int = executeOn({ connection.prepareStatement(it) }, sql, args)

  /** [execute] on a pooled session. */
  fun execute(
    session: SqlSession,
    sql: String,
    vararg args: Any?,
  ): Int = executeOn({ session.prepareStatement(it) }, sql, args)

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
  ): T = singleOn({ connection.prepareStatement(it) }, sql, args, read)

  /** [single] on a pooled session. */
  fun <T> single(
    session: SqlSession,
    sql: String,
    vararg args: Any?,
    read: (ResultSet) -> T,
  ): T = singleOn({ session.prepareStatement(it) }, sql, args, read)

  /**
   * Runs a count query (`SELECT count(*) ...`) and answers its one value.
   * Like [single], a result of no row or more than one throws, naming the SQL
   * and its bound arguments.
   */
  fun count(
    connection: Connection,
    sql: String,
    vararg args: Any?,
  ): Int = singleOn({ connection.prepareStatement(it) }, sql, args, ::firstInt)

  /** [count] on a pooled session. */
  fun count(
    session: SqlSession,
    sql: String,
    vararg args: Any?,
  ): Int = singleOn({ session.prepareStatement(it) }, sql, args, ::firstInt)

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
  ): T? = singleOrNullOn({ connection.prepareStatement(it) }, sql, args, read)

  /** [singleOrNull] on a pooled session. */
  fun <T : Any> singleOrNull(
    session: SqlSession,
    sql: String,
    vararg args: Any?,
    read: (ResultSet) -> T,
  ): T? = singleOrNullOn({ session.prepareStatement(it) }, sql, args, read)

  /**
   * Runs a query and reads every row it answers with [read], in the order the
   * query returns them; no row is an empty list.
   */
  fun <T> list(
    connection: Connection,
    sql: String,
    vararg args: Any?,
    read: (ResultSet) -> T,
  ): List<T> = listOn({ connection.prepareStatement(it) }, sql, args, read)

  /** [list] on a pooled session. */
  fun <T> list(
    session: SqlSession,
    sql: String,
    vararg args: Any?,
    read: (ResultSet) -> T,
  ): List<T> = listOn({ session.prepareStatement(it) }, sql, args, read)

  private fun executeOn(
    prepare: (String) -> PreparedStatement,
    sql: String,
    args: Array<out Any?>,
  ): Int =
    prepare(sql).use { stmt ->
      bind(stmt, args)
      stmt.executeUpdate()
    }

  private fun <T> singleOn(
    prepare: (String) -> PreparedStatement,
    sql: String,
    args: Array<out Any?>,
    read: (ResultSet) -> T,
  ): T = atMostOne(prepare, sql, args, "exactly one", read) { error(unexpected("exactly one", sql, args, "none")) }

  private fun <T : Any> singleOrNullOn(
    prepare: (String) -> PreparedStatement,
    sql: String,
    args: Array<out Any?>,
    read: (ResultSet) -> T,
  ): T? = atMostOne(prepare, sql, args, "at most one", read) { null }

  private fun <T> listOn(
    prepare: (String) -> PreparedStatement,
    sql: String,
    args: Array<out Any?>,
    read: (ResultSet) -> T,
  ): List<T> =
    prepare(sql).use { stmt ->
      bind(stmt, args)
      stmt.executeQuery().use { rs ->
        buildList { while (rs.next()) add(read(rs)) }
      }
    }

  /**
   * The one query loop behind [single] and [singleOrNull]: they differ only in
   * what an empty result answers, [none], and the [expected] row count named in
   * the error.
   */
  private inline fun <T> atMostOne(
    prepare: (String) -> PreparedStatement,
    sql: String,
    args: Array<out Any?>,
    expected: String,
    read: (ResultSet) -> T,
    none: () -> T,
  ): T =
    prepare(sql).use { stmt ->
      bind(stmt, args)
      stmt.executeQuery().use { rs ->
        if (!rs.next()) return none()
        val value = read(rs)
        check(!rs.next()) { unexpected(expected, sql, args, "more") }
        value
      }
    }

  private fun firstInt(rs: ResultSet): Int = rs.getInt(1)

  private fun unexpected(
    expected: String,
    sql: String,
    args: Array<out Any?>,
    got: String,
  ) = "expected $expected row from [$sql] with args ${args.toList()}, got $got"

  private fun bind(
    stmt: PreparedStatement,
    args: Array<out Any?>,
  ) {
    args.forEachIndexed { i, arg -> stmt.setObject(i + 1, arg) }
  }
}
