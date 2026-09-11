package ed.unicoach.db.dao

import java.sql.PreparedStatement

/**
 * A [SqlSession] that records the SQL of every statement it prepares, so "one
 * query for the whole answer, never one per row" is an ASSERTION rather than a
 * test name. Every statement is delegated to the real session it wraps, so the
 * read under test still runs against the real database and the count is taken
 * from the read that actually happened.
 *
 * It lives in `:db`'s test fixtures rather than in one suite because the
 * batching claim it exists to prove is made by every batched DAO read, and it
 * was already typed out twice -- once in `:db`'s own `CollegeIpedsDaoTest` and
 * once, KDoc included, in `:service`'s `CoachingTestDb`. Two decorators over
 * one interface is two places for "what counts as a prepared statement" to be
 * answered, and the assertions that read them are about the same contract.
 *
 * [prepared] is the SQL in preparation ORDER, so a failure can name the
 * statement that should not be there rather than only the count that grew. It
 * is a READ-ONLY copy over a private buffer: the recording is this decorator's
 * own state, and handing out the live `MutableList` would let a caller append
 * to or clear the very log the batching assertions read -- a fixture shared by
 * two modules is exactly where that would go unnoticed.
 */
open class CountingSqlSession(
  private val delegate: SqlSession,
) : SqlSession {
  private val statements: MutableList<String> = mutableListOf()

  val prepared: List<String> get() = statements.toList()

  override fun prepareStatement(sql: String): PreparedStatement {
    statements += sql
    return delegate.prepareStatement(sql)
  }
}
