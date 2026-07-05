package ed.unicoach.cron.dao

import ed.unicoach.cron.PeriodicJob
import ed.unicoach.cron.PeriodicJobName
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.queue.JobType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId

/**
 * Raw-JDBC DAO over `periodic_jobs`, mirroring `JobsDao` in `:queue`: an
 * `executeSafely` envelope, a private [mapRow], and the `was_updated` UNION idiom
 * ([mapUpdateResult]) that discriminates a hit from a miss on a by-name write.
 *
 * (The shared `queryOne`/`queryList`/`mutateReturning` helpers in `:db`'s
 * `SqlSessionQueries` are `internal` to that module and so unreachable here, as
 * they are from `:queue`'s `JobsDao`; both DAOs hand-roll the same small
 * envelope rather than widening `:db`'s API surface or relocating the DAO — the
 * latter explicitly rejected by RFC 97.)
 *
 * The scheduler holds the row lock for the remainder of its transaction after
 * [claimDue], so [advance] and the enqueue commit atomically with the claim.
 */
class PeriodicJobsDao {
  private fun <T> executeSafely(
    onError: (Exception) -> T,
    block: () -> T,
  ): T =
    try {
      block()
    } catch (e: Exception) {
      onError(e)
    }

  /**
   * Reconstructs a [PeriodicJob] from a row. A row that cannot be reconstructed —
   * an unknown `job_type` or a malformed `payload` — throws a
   * [PeriodicJobRowCorruptException] carrying the row `name` plus the offending
   * field/value, so a `list()` over many rows still pins down the corrupt one
   * (the bare `error()`/`SerializationException` the naive path throws names
   * neither). `executeSafely` folds the throw into the DAO's `DatabaseFailure`.
   */
  private fun mapRow(rs: ResultSet): PeriodicJob {
    val name = PeriodicJobName(rs.getString("name"))

    val jobTypeStr = rs.getString("job_type")
    val jobType =
      JobType.fromValue(jobTypeStr)
        ?: throw PeriodicJobRowCorruptException(name, "job_type", jobTypeStr)

    val payloadStr = rs.getString("payload")
    val payload: JsonObject =
      try {
        Json.decodeFromString(payloadStr)
      } catch (e: SerializationException) {
        throw PeriodicJobRowCorruptException(name, "payload", payloadStr, e)
      }

    val lastRunAt: Timestamp? = rs.getTimestamp("last_run_at")

    return PeriodicJob(
      name = name,
      jobType = jobType,
      payload = payload,
      schedule = rs.getString("schedule"),
      timezone = ZoneId.of(rs.getString("timezone")),
      nextRunAt = rs.getTimestamp("next_run_at").toInstant(),
      lastRunAt = lastRunAt?.toInstant(),
      enabled = rs.getBoolean("enabled"),
      createdAt = rs.getTimestamp("created_at").toInstant(),
      updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )
  }

  /**
   * Locks and returns one due, enabled row (`next_run_at <= NOW()`), cheapest
   * next-fire first, plus the database clock (`NOW()`). Uses
   * `FOR UPDATE SKIP LOCKED` so concurrent schedulers never claim the same row;
   * the caller holds the row lock for the remainder of its transaction.
   *
   * `NOW()` is selected in the same statement so the scheduler computes the next
   * fire against the database clock, not the host wall clock (Invariant 1).
   */
  fun claimDue(session: SqlSession): ClaimDueResult =
    executeSafely(ClaimDueResult::DatabaseFailure) {
      val sql =
        """
        SELECT *, NOW() AS db_now
        FROM periodic_jobs
        WHERE enabled AND next_run_at <= NOW()
        ORDER BY next_run_at
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        stmt.executeQuery().use { rs ->
          if (!rs.next()) {
            return@executeSafely ClaimDueResult.NoneDue
          }
          // Read the DB clock before mapRow, so a corrupt locked row can still be
          // parked against the DB clock (not the host wall clock). A reconstruction
          // failure is a distinct Corrupt outcome — the row is locked and real, it
          // just cannot be run — so the scheduler parks it and keeps draining rather
          // than folding it into the generic DatabaseFailure back-off (which would
          // leave the row cheapest-due and starve every healthy row behind it).
          val dbNow = rs.getTimestamp("db_now").toInstant()
          try {
            ClaimDueResult.Claimed(mapRow(rs), dbNow)
          } catch (e: PeriodicJobRowCorruptException) {
            ClaimDueResult.Corrupt(e.name, dbNow, e)
          }
        }
      }
    }

  /**
   * Advances `next_run_at` to [nextRunAt] and sets `last_run_at = NOW()` for a
   * claimed, **healthy** (reconstructable) row that was just dispatched, so the
   * returned [PeriodicUpdateResult.Success] reconstructs and carries the row.
   *
   * Unlike [setEnabled] and [park], this keeps reconstructing the `RETURNING` row:
   * it only ever runs on an already-reconstructed healthy claimed row (the caller
   * holds the row lock from [claimDue]), so [mapRow] cannot re-throw here.
   */
  fun advance(
    session: SqlSession,
    name: PeriodicJobName,
    nextRunAt: Instant,
  ): PeriodicUpdateResult =
    executeSafely(PeriodicUpdateResult::DatabaseFailure) {
      val sql = updateReturningWasUpdated("SET next_run_at = ?, last_run_at = NOW()")
      session.prepareStatement(sql).use { stmt ->
        stmt.setTimestamp(1, Timestamp.from(nextRunAt))
        stmt.setString(2, name.value)
        stmt.setString(3, name.value)
        mapUpdateResult(stmt, name)
      }
    }

  /**
   * Parks a claimed row by moving `next_run_at` to [retryAt] **without** stamping
   * `last_run_at` — nothing ran. The scheduler calls this on an unparseable
   * schedule OR an unreconstructable (corrupt `job_type`/`payload`) row so the
   * misconfigured row drops out of the due window for a cooldown instead of
   * remaining the cheapest-due row every tick and starving healthy rows (RFC 97
   * "inert, not fatal"). Returns a [ParkResult] whose `Success` carries no
   * reconstructed row: park must succeed on a corrupt row, so it confirms the
   * write from the `was_updated` flag alone and never calls `mapRow` (which would
   * re-throw the corruption park exists to quarantine).
   */
  fun park(
    session: SqlSession,
    name: PeriodicJobName,
    retryAt: Instant,
  ): ParkResult =
    executeSafely(ParkResult::DatabaseFailure) {
      val sql = updateReturningWasUpdated("SET next_run_at = ?")
      session.prepareStatement(sql).use { stmt ->
        stmt.setTimestamp(1, Timestamp.from(retryAt))
        stmt.setString(2, name.value)
        stmt.setString(3, name.value)
        if (wasUpdated(stmt)) ParkResult.Success else ParkResult.NotFound(name)
      }
    }

  /**
   * Sets `enabled` for a row by name — the admin toggle's write path, and the
   * operator's remedy for a corrupt row (disable/quarantine it). Idempotent:
   * setting an already-`enabled` row re-`Success`es with a no-op write.
   *
   * Confirms the write from the `was_updated` flag alone and never calls [mapRow]
   * (the same [ParkResult]-style approach [park] uses), so a disable succeeds on a
   * **corrupt** row — reconstructing the `RETURNING` row would re-throw the very
   * corruption the operator is quarantining. Hence its `Success` carries no
   * reconstructed [PeriodicJob].
   */
  fun setEnabled(
    session: SqlSession,
    name: PeriodicJobName,
    enabled: Boolean,
  ): SetEnabledResult =
    executeSafely(SetEnabledResult::DatabaseFailure) {
      val sql = updateReturningWasUpdated("SET enabled = ?")
      session.prepareStatement(sql).use { stmt ->
        stmt.setBoolean(1, enabled)
        stmt.setString(2, name.value)
        stmt.setString(3, name.value)
        if (wasUpdated(stmt)) SetEnabledResult.Success else SetEnabledResult.NotFound(name)
      }
    }

  /**
   * Runs [stmt]'s `was_updated` UNION query and reads the flag alone: `true` iff the
   * by-name UPDATE hit a row. The corrupt-safe counterpart to [mapUpdateResult] —
   * it never calls [mapRow], so [park] and [setEnabled] can both confirm a write
   * against a **corrupt** row (reconstructing the `RETURNING` row would re-throw the
   * corruption they exist to quarantine). Each caller maps the boolean to its own
   * hit/miss result type.
   */
  private fun wasUpdated(stmt: PreparedStatement): Boolean = stmt.executeQuery().use { rs -> rs.next() && rs.getBoolean("was_updated") }

  /**
   * Builds the `was_updated` UNION query for a by-name write: the given [setClause]
   * runs its `UPDATE … WHERE name = ? RETURNING *`, then a `false`-tagged probe of
   * the same name discriminates a hit from a miss. [setClause] is a fixed DAO
   * fragment (never caller data); the two `name` binds are positional params 2 & 3.
   */
  private fun updateReturningWasUpdated(setClause: String): String =
    """
    WITH updated AS (
        UPDATE periodic_jobs
        $setClause
        WHERE name = ?
        RETURNING *
    )
    SELECT true AS was_updated, u.* FROM updated u
    UNION ALL
    SELECT false AS was_updated, p.* FROM periodic_jobs p
    WHERE p.name = ? AND NOT EXISTS (SELECT 1 FROM updated)
    """.trimIndent()

  /**
   * Runs [stmt] and maps the `was_updated` UNION result: a `true` row is a
   * [PeriodicUpdateResult.Success]; no row is a [PeriodicUpdateResult.NotFound].
   * (`was_updated = false` can only appear when the row exists but the UPDATE
   * matched nothing, which the by-name WHERE never produces, so it collapses to
   * NotFound alongside the empty case.)
   */
  private fun mapUpdateResult(
    stmt: PreparedStatement,
    name: PeriodicJobName,
  ): PeriodicUpdateResult =
    stmt.executeQuery().use { rs ->
      if (rs.next() && rs.getBoolean("was_updated")) {
        PeriodicUpdateResult.Success(mapRow(rs))
      } else {
        PeriodicUpdateResult.NotFound(name)
      }
    }

  /**
   * Lists all rows, cheapest next-fire first. Admin read. Degrades per-row: a row
   * that cannot be reconstructed ([PeriodicJobRowCorruptException]) is collected as
   * a [CorruptRow] rather than folding the whole call into [PeriodicListResult.DatabaseFailure],
   * so one corrupt row cannot hide every healthy row from the admin list. Only a
   * genuine DB/connection fault (the query or a `ResultSet` read failing) yields
   * `DatabaseFailure`.
   */
  fun list(session: SqlSession): PeriodicListResult =
    executeSafely(PeriodicListResult::DatabaseFailure) {
      val sql = "SELECT * FROM periodic_jobs ORDER BY next_run_at"
      session.prepareStatement(sql).use { stmt ->
        stmt.executeQuery().use { rs ->
          val jobs = mutableListOf<PeriodicJob>()
          val corruptRows = mutableListOf<CorruptRow>()
          while (rs.next()) {
            try {
              jobs.add(mapRow(rs))
            } catch (e: PeriodicJobRowCorruptException) {
              corruptRows.add(CorruptRow(e.name, e.field, e.value, e))
            }
          }
          PeriodicListResult.Success(jobs, corruptRows)
        }
      }
    }

  /**
   * Finds a single row by name. Admin read. A found-but-unreconstructable row
   * ([PeriodicJobRowCorruptException]) is a distinct [PeriodicFindResult.Corrupt]
   * outcome (mirroring [ClaimDueResult.Corrupt]) carrying the row name + cause, so
   * the admin detail path can report a corrupt row instead of a whole-page
   * `DatabaseFailure`.
   */
  fun findByName(
    session: SqlSession,
    name: PeriodicJobName,
  ): PeriodicFindResult =
    executeSafely(PeriodicFindResult::DatabaseFailure) {
      val sql = "SELECT * FROM periodic_jobs WHERE name = ?"
      session.prepareStatement(sql).use { stmt ->
        stmt.setString(1, name.value)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) {
            return@executeSafely PeriodicFindResult.NotFound(name)
          }
          try {
            PeriodicFindResult.Success(mapRow(rs))
          } catch (e: PeriodicJobRowCorruptException) {
            PeriodicFindResult.Corrupt(e.name, e)
          }
        }
      }
    }
}
