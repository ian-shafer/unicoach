package ed.unicoach.db.dao

import ed.unicoach.common.money.Nanodollars
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.StudentLlmCost
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * Reads the per-student LLM cost off the `student_llm_cost` per-call attribution
 * spine (RFC 108). Stateless `object`, one [SqlSession] per call, transaction
 * boundaries owned by the caller. Both reads sum the frozen per-call cost and,
 * alongside it, count the two independent gap/estimate signals the total needs
 * to be read honestly (see [StudentLlmCost]).
 *
 * [lifetimeCost] is the free-tier meter (no window); [windowedCost] is the
 * subscription meter over `[periodStart, periodEnd)`.
 */
object StudentLlmCostDao {
  /**
   * The three-scalar cost aggregate for one student. `COALESCE(SUM, 0)` gives a
   * genuine `0` for a student with no attributed (or no costed) spend rather than
   * a NULL. `COUNT(*) FILTER` bounds the total: how many calls are uncosted, and
   * how many are default-priced estimates.
   */
  private const val AGGREGATE =
    "SELECT COALESCE(SUM(cost_nanodollars), 0) AS cost, " +
      "COUNT(*) FILTER (WHERE cost_nanodollars IS NULL) AS uncosted, " +
      "COUNT(*) FILTER (WHERE cost_is_estimated) AS estimated " +
      "FROM student_llm_cost WHERE student_id = ?"

  /** Lifetime total: every attributed call for [studentId], no time bound. */
  fun lifetimeCost(
    session: SqlSession,
    studentId: StudentId,
  ): Result<StudentLlmCost> =
    session.queryOne(
      AGGREGATE,
      bind = { it.setObject(1, studentId.value) },
      map = ::mapCost,
    )

  /**
   * Windowed total: attributed calls for [studentId] with
   * `created_at ∈ [periodStart, periodEnd)` (start inclusive, end exclusive).
   * Requires `periodStart < periodEnd` — reading an inverted or empty window as a
   * silent `(0, 0, 0)` is indistinguishable from a genuine no-spend read, which
   * `COALESCE` already provides for a real window.
   */
  fun windowedCost(
    session: SqlSession,
    studentId: StudentId,
    periodStart: Instant,
    periodEnd: Instant,
  ): Result<StudentLlmCost> {
    require(periodStart < periodEnd) { "periodStart [$periodStart] must be before periodEnd [$periodEnd]" }
    return session.queryOne(
      "$AGGREGATE AND created_at >= ? AND created_at < ?",
      bind = { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.setTimestamp(2, Timestamp.from(periodStart))
        stmt.setTimestamp(3, Timestamp.from(periodEnd))
      },
      map = ::mapCost,
    )
  }

  // PostgreSQL returns SUM(bigint) as numeric and COUNT(*) as bigint; the driver
  // narrows both to the Kotlin Long/Int the model declares via getLong/getInt.
  private fun mapCost(rs: ResultSet): StudentLlmCost =
    StudentLlmCost(
      costNanodollars = Nanodollars.of(rs.getLong("cost")),
      uncostedCalls = rs.getInt("uncosted"),
      estimatedCalls = rs.getInt("estimated"),
    )
}
