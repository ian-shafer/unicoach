package ed.unicoach.db.dao

import ed.unicoach.common.money.Nanodollars
import ed.unicoach.db.models.LlmCostAggregate
import ed.unicoach.db.models.UserId
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * Reads LLM cost off the `account_llm_cost` spine (RFC 244), which re-keys the
 * student spine (RFC 108, `student_llm_cost`) and the supporter spine
 * (`supporter_llm_cost`) onto the ACCOUNT and unions them. Stateless `object`, one
 * [SqlSession] per call, transaction boundaries owned by the caller.
 *
 * It is the ONLY cost read production makes, because the entitlement subject is
 * an account: every account pays for itself, and one human may hold BOTH a
 * `students` row and a `supporters` row — that person has ONE subscription, so
 * their spend on both sides is ONE total. Neither owner-side view is rewritten, so
 * a student's number is the number it always was: `supporter_llm_costs` has no
 * writer yet, so the union's second arm is empty for every account alive today.
 *
 * Both owner-side views are read THROUGH this one door, so the projection, the
 * window and the reading policy below exist once rather than once per spine. A
 * per-ROLE read arrives with the caller that needs one — `family/06` attributing
 * a parent's own calls is the first candidate — and not before.
 */
object AccountLlmCostDao {
  /** The projection over the account spine, keyed by `user_id`. */
  private const val AGGREGATE = "$LLM_COST_AGGREGATE_PROJECTION FROM account_llm_cost WHERE user_id = ?"

  /** Lifetime total: every attributed call for [userId], no time bound — the free-tier meter. */
  fun lifetimeCost(
    session: SqlSession,
    userId: UserId,
  ): Result<LlmCostAggregate> =
    session.queryOne(
      AGGREGATE,
      bind = { it.setObject(1, userId.value) },
      map = ::mapLlmCostAggregate,
    )

  /**
   * Windowed total — the subscription meter: attributed calls for [userId] with
   * `created_at ∈ [periodStart, periodEnd)` (start inclusive, end exclusive).
   * Requires `periodStart < periodEnd` ([requireLlmCostWindow]).
   */
  fun windowedCost(
    session: SqlSession,
    userId: UserId,
    periodStart: Instant,
    periodEnd: Instant,
  ): Result<LlmCostAggregate> {
    requireLlmCostWindow(periodStart, periodEnd)
    return session.queryOne(
      "$AGGREGATE$LLM_COST_WINDOW",
      bind = { stmt ->
        stmt.setObject(1, userId.value)
        bindLlmCostWindow(stmt, 2, periodStart, periodEnd)
      },
      map = ::mapLlmCostAggregate,
    )
  }
}

/**
 * What an [LlmCostAggregate] read MEANS, for the one cost read production makes
 * ([AccountLlmCostDao]). The three scalars and the reading policy they encode:
 * - `COALESCE(SUM, 0)`: a subject with no attributed (or no costed) spend reads a
 *   genuine `0`, never a NULL.
 * - `uncosted`: how many attributed calls carry no cost at all, so a reader knows
 *   the total is an UNDER-count. Their contribution to the sum is `0`.
 * - `estimated`: how many were default-priced, a deliberate OVER-estimate that IS
 *   in the sum.
 *
 * The `FROM <view> WHERE <key> = ?` around it is the DAO's own, because the view
 * and its key column are the only part a spine does not share. Both owner-side
 * views are reached through `account_llm_cost`'s union, so this policy is stated
 * once and no second copy of these three lines can come to disagree about what a
 * total means — the defect RFC 108 avoided by putting the policy in its one DAO,
 * kept here for the same reason.
 */
internal const val LLM_COST_AGGREGATE_PROJECTION =
  "SELECT COALESCE(SUM(cost_nanodollars), 0) AS cost, " +
    "COUNT(*) FILTER (WHERE cost_nanodollars IS NULL) AS uncosted, " +
    "COUNT(*) FILTER (WHERE cost_is_estimated) AS estimated"

/**
 * The `[periodStart, periodEnd)` bound a windowed read appends — start inclusive,
 * end exclusive, on the RESPONSE's `created_at` (every cost view projects it from
 * `llm_responses`), so a period means the same thing on both sides of the house.
 *
 * It opens with `AND` because the caller's WHERE has already named its subject.
 */
internal const val LLM_COST_WINDOW = " AND created_at >= ? AND created_at < ?"

/**
 * Requires `periodStart < periodEnd`. Reading an inverted or empty window as a
 * silent `(0, 0, 0)` is indistinguishable from a genuine no-spend read, which
 * `COALESCE` already provides for a real window.
 */
internal fun requireLlmCostWindow(
  periodStart: Instant,
  periodEnd: Instant,
) = require(periodStart < periodEnd) { "periodStart [$periodStart] must be before periodEnd [$periodEnd]" }

/** Binds [LLM_COST_WINDOW]'s two parameters from [firstIndex]. */
internal fun bindLlmCostWindow(
  stmt: PreparedStatement,
  firstIndex: Int,
  periodStart: Instant,
  periodEnd: Instant,
) {
  stmt.setTimestamp(firstIndex, Timestamp.from(periodStart))
  stmt.setTimestamp(firstIndex + 1, Timestamp.from(periodEnd))
}

/**
 * Reads [LLM_COST_AGGREGATE_PROJECTION]'s three columns back.
 *
 * PostgreSQL returns `SUM(bigint)` as `numeric` and `COUNT(*)` as `bigint`; the
 * driver narrows both to the Kotlin `Long`/`Int` the model declares via
 * `getLong`/`getInt`.
 */
internal fun mapLlmCostAggregate(rs: ResultSet): LlmCostAggregate =
  LlmCostAggregate(
    costNanodollars = Nanodollars.of(rs.getLong("cost")),
    uncostedCalls = rs.getInt("uncosted"),
    estimatedCalls = rs.getInt("estimated"),
  )
