package ed.unicoach.db.dao

import ed.unicoach.db.models.SoftDeleteScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Instant

/*
 * Shared query/mutate execution scaffolding. These `SqlSession` extensions own
 * the try/prepare/execute/map envelope, the OCC existence-probe dance, and the
 * JDBC null/JSON binding all DAOs would otherwise re-implement. They receive a
 * `SqlSession` (which exposes only `prepareStatement`), so transaction
 * boundaries remain owned by `Database.withConnection`.
 */

/** SELECT yielding exactly one row, or [NotFoundException] (via [onNoRow]) on no row. */
internal fun <T> SqlSession.queryOne(
  sql: String,
  bind: (PreparedStatement) -> Unit,
  map: (ResultSet) -> T,
  onNoRow: () -> Exception = { NotFoundException() },
): Result<T> =
  try {
    prepareStatement(sql).use { stmt ->
      bind(stmt)
      stmt.executeQuery().use { rs ->
        if (rs.next()) {
          Result.success(map(rs))
        } else {
          Result.failure(onNoRow())
        }
      }
    }
  } catch (e: Exception) {
    Result.failure(mapDatabaseError(e))
  }

/** SELECT yielding N rows mapped into a list. */
internal fun <T> SqlSession.queryList(
  sql: String,
  bind: (PreparedStatement) -> Unit,
  map: (ResultSet) -> T,
): Result<List<T>> =
  try {
    prepareStatement(sql).use { stmt ->
      bind(stmt)
      stmt.executeQuery().use { rs ->
        val rows = mutableListOf<T>()
        while (rs.next()) {
          rows.add(map(rs))
        }
        Result.success(rows)
      }
    }
  } catch (e: Exception) {
    Result.failure(mapDatabaseError(e))
  }

/**
 * INSERT/UPDATE ... RETURNING *. On a returned row → `success(map(row))`. On 0
 * rows → `failure(onNoRow())`. [mapError] discriminates SQLSTATE (defaulting to
 * [mapDatabaseError]); callers whose WHERE can match nothing pass their specific
 * [NotFoundException] message via [onNoRow], while inserts whose RETURNING
 * always yields a row keep the default.
 */
internal fun <T> SqlSession.mutateReturning(
  sql: String,
  bind: (PreparedStatement) -> Unit,
  map: (ResultSet) -> T,
  mapError: (SQLException) -> Exception = ::mapDatabaseError,
  onNoRow: () -> Exception = { NotFoundException() },
): Result<T> =
  try {
    prepareStatement(sql).use { stmt ->
      bind(stmt)
      stmt.executeQuery().use { rs ->
        if (rs.next()) {
          Result.success(map(rs))
        } else {
          Result.failure(onNoRow())
        }
      }
    }
  } catch (e: SQLException) {
    Result.failure(mapError(e))
  } catch (e: Exception) {
    Result.failure(mapDatabaseError(e))
  }

/** A write returning its affected-row count. */
internal fun SqlSession.execute(
  sql: String,
  bind: (PreparedStatement) -> Unit = {},
): Result<Int> =
  try {
    prepareStatement(sql).use { stmt ->
      bind(stmt)
      Result.success(stmt.executeUpdate())
    }
  } catch (e: Exception) {
    Result.failure(mapDatabaseError(e))
  }

/**
 * Runs an OCC `UPDATE ... WHERE id = ? AND version = ? RETURNING *`. On a
 * returned row → success. On 0 rows → probes `SELECT 1 FROM <table> WHERE id = ?`
 * and fails with [ConcurrentModificationException] when the row exists, else
 * [NotFoundException]. The probed column is immaterial (existence-only).
 */
internal fun <T> SqlSession.occUpdate(
  table: String,
  sql: String,
  bind: (PreparedStatement) -> Unit,
  idValue: Any,
  map: (ResultSet) -> T,
  mapError: (SQLException) -> Exception = ::mapDatabaseError,
): Result<T> =
  try {
    prepareStatement(sql).use { stmt ->
      bind(stmt)
      stmt.executeQuery().use { rs ->
        if (rs.next()) {
          Result.success(map(rs))
        } else {
          prepareStatement("SELECT 1 FROM $table WHERE id = ?").use { probe ->
            probe.setObject(1, idValue)
            probe.executeQuery().use { probeRs ->
              if (probeRs.next()) {
                Result.failure(ConcurrentModificationException())
              } else {
                Result.failure(NotFoundException())
              }
            }
          }
        }
      }
    }
  } catch (e: SQLException) {
    Result.failure(mapError(e))
  } catch (e: Exception) {
    Result.failure(mapDatabaseError(e))
  }

// Generic column-map mutation helpers

/**
 * A closure binding one parameter at a positional index. Callers use the
 * existing JDBC helpers ([setStringOrNull], [setIntOrNull], [setJsonbOrNull],
 * `setObject`, …) inside the closure, so each value carries its own
 * type-specific binding semantics. Column names paired with these closures are
 * fixed DAO identifiers, never caller data.
 */
internal typealias Bind = (PreparedStatement, Int) -> Unit

/**
 * Generates `INSERT INTO $table (<cols>) VALUES (?, …) RETURNING *` from the
 * ordered [columns] map and delegates to [mutateReturning]. Column names are
 * fixed DAO identifiers, never caller data; only the bound values vary.
 */
internal fun <T> SqlSession.insertReturning(
  table: String,
  columns: Map<String, Bind>,
  map: (ResultSet) -> T,
  mapError: (SQLException) -> Exception = ::mapDatabaseError,
): Result<T> {
  val names = columns.keys.joinToString(", ")
  val placeholders = columns.keys.joinToString(", ") { "?" }
  val sql = "INSERT INTO $table ($names) VALUES ($placeholders) RETURNING *"
  val binds = columns.values.toList()
  return mutateReturning(
    sql,
    bind = { stmt -> binds.forEachIndexed { i, b -> b(stmt, i + 1) } },
    map = map,
    mapError = mapError,
  )
}

/**
 * Generates `UPDATE $table SET <col>=?, … WHERE id=? [AND version=?] RETURNING *`.
 *
 * - [currentVersion] `null` → delegates to [mutateReturning] (NotFound on 0 rows).
 * - [currentVersion] non-null → prepends `version=currentVersion+1` to the SET
 *   clause, appends `AND version=?` to the WHERE, and delegates to [occUpdate]
 *   (ConcurrentModification probe on 0 rows).
 *
 * Column names are fixed DAO identifiers, never caller data.
 */
internal fun <T> SqlSession.updateColumnsReturning(
  table: String,
  id: Any,
  currentVersion: Int?,
  columns: Map<String, Bind>,
  map: (ResultSet) -> T,
  mapError: (SQLException) -> Exception = ::mapDatabaseError,
): Result<T> {
  val columnBinds = columns.values.toList()

  if (currentVersion == null) {
    val setClause = columns.keys.joinToString(", ") { "$it = ?" }
    val sql = "UPDATE $table SET $setClause WHERE id = ? RETURNING *"
    return mutateReturning(
      sql,
      bind = { stmt ->
        var idx = 1
        columnBinds.forEach { b -> b(stmt, idx++) }
        stmt.setObject(idx, id)
      },
      map = map,
      mapError = mapError,
    )
  }

  val setClause = (listOf("version = ?") + columns.keys.map { "$it = ?" }).joinToString(", ")
  val sql = "UPDATE $table SET $setClause WHERE id = ? AND version = ? RETURNING *"
  return occUpdate(
    table = table,
    sql = sql,
    bind = { stmt ->
      var idx = 1
      stmt.setInt(idx++, currentVersion + 1)
      columnBinds.forEach { b -> b(stmt, idx++) }
      stmt.setObject(idx++, id)
      stmt.setInt(idx, currentVersion)
    },
    idValue = id,
    map = map,
    mapError = mapError,
  )
}

/**
 * Generates a soft-delete toggle `UPDATE $table SET [version=?,]
 * deleted_at=[NOW()/NULL] WHERE id=? …  RETURNING *`.
 *
 * - [deleted] `true` → `SET deleted_at = NOW()`; `false` → `SET deleted_at = NULL`.
 * - [currentVersion] `null` → non-OCC: appends `AND deleted_at IS [NOT] NULL`
 *   and delegates to [mutateReturning] (NotFound on 0 rows — whether the id is
 *   absent or the row is already in the target state).
 * - [currentVersion] non-null → OCC: appends `AND version=?`, increments the
 *   version, and delegates to [occUpdate] (ConcurrentModification probe on 0 rows).
 */
internal fun <T> SqlSession.softDeleteReturning(
  table: String,
  id: Any,
  currentVersion: Int?,
  deleted: Boolean,
  map: (ResultSet) -> T,
  mapError: (SQLException) -> Exception = ::mapDatabaseError,
): Result<T> {
  val deletedAtClause = if (deleted) "deleted_at = NOW()" else "deleted_at = NULL"

  if (currentVersion == null) {
    // Target-state guard: deleting requires a currently-active row, undeleting a
    // currently-deleted one. A 0-row result (absent id or already-in-target-state)
    // collapses to NotFound.
    val stateGuard = if (deleted) "deleted_at IS NULL" else "deleted_at IS NOT NULL"
    val sql = "UPDATE $table SET $deletedAtClause WHERE id = ? AND $stateGuard RETURNING *"
    return mutateReturning(
      sql,
      bind = { stmt -> stmt.setObject(1, id) },
      map = map,
      mapError = mapError,
    )
  }

  val sql =
    "UPDATE $table SET version = ?, $deletedAtClause WHERE id = ? AND version = ? RETURNING *"
  return occUpdate(
    table = table,
    sql = sql,
    bind = { stmt ->
      stmt.setInt(1, currentVersion + 1)
      stmt.setObject(2, id)
      stmt.setInt(3, currentVersion)
    },
    idValue = id,
    map = map,
    mapError = mapError,
  )
}

// JDBC binding/reading helpers

/** Binds a nullable String, NULL as `Types.VARCHAR`. */
internal fun PreparedStatement.setStringOrNull(
  index: Int,
  value: String?,
) {
  if (value != null) setString(index, value) else setNull(index, Types.VARCHAR)
}

/** Binds a nullable Int, NULL as `Types.INTEGER`. */
internal fun PreparedStatement.setIntOrNull(
  index: Int,
  value: Int?,
) {
  if (value != null) setInt(index, value) else setNull(index, Types.INTEGER)
}

/** Binds a nullable [JsonElement] into a `?::jsonb` slot, NULL as `Types.OTHER`. */
internal fun PreparedStatement.setJsonbOrNull(
  index: Int,
  value: JsonElement?,
) {
  if (value != null) setString(index, value.toString()) else setNull(index, Types.OTHER)
}

internal fun ResultSet.getInstant(column: String): Instant = getTimestamp(column).toInstant()

internal fun ResultSet.getInstantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()

internal fun ResultSet.getJsonbOrNull(column: String): JsonElement? = getString(column)?.let { Json.parseToJsonElement(it) }

// Read-time soft-delete predicate (fixed SQL fragment, no caller data)

/**
 * Fixed SQL fragment selecting rows by their soft-delete column. No caller data
 * is interpolated — [column] is a fixed identifier supplied by the DAO.
 *
 * - [SoftDeleteScope.ACTIVE] → `<column> IS NULL`
 * - [SoftDeleteScope.DELETED] → `<column> IS NOT NULL`
 * - [SoftDeleteScope.ALL] → `TRUE`
 */
internal fun SoftDeleteScope.predicate(column: String = "deleted_at"): String =
  when (this) {
    SoftDeleteScope.ACTIVE -> "$column IS NULL"
    SoftDeleteScope.DELETED -> "$column IS NOT NULL"
    SoftDeleteScope.ALL -> "TRUE"
  }
