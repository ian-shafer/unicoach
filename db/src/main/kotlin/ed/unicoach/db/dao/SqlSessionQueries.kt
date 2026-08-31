package ed.unicoach.db.dao

import ed.unicoach.db.models.SoftDeleteScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.time.LocalDate

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

/**
 * `SELECT $columns FROM $table WHERE $keyColumn IN (?, …)` over [keys] -- the one
 * home for expanding a collection into positional parameters.
 *
 * The placeholder string and the binder are generated TOGETHER, from the same
 * de-duplicated list, so no DAO can put them out of step or restate the 1-based
 * index rule for itself. [bindKey] gives each key its own JDBC type.
 *
 * The WHERE clause is this function's OWN and takes no fragment from a caller.
 * It briefly did: a `String?` spliced in unparenthesised and AND-ed at top
 * level, allowlisted only by a sentence in this KDoc -- a blank one emitted a
 * dangling `AND`, and an `OR` inside one would have bound looser than that
 * `AND` and silently widened the key filter. Neither would have been an error.
 * A caller that wants fewer rows filters the mapped rows in Kotlin, where no
 * string reaches SQL at all.
 *
 * Table and column names are fixed DAO identifiers, never caller data; only the
 * keys are bound. An empty [keys] short-circuits without a query, because
 * `IN ()` is not valid SQL and a query for nothing has nothing to answer.
 */
internal fun <K, T> SqlSession.queryListWhereIn(
  table: String,
  columns: String,
  keyColumn: String,
  keys: Collection<K>,
  bindKey: (PreparedStatement, Int, K) -> Unit,
  map: (ResultSet) -> T,
): Result<List<T>> {
  val distinct = keys.distinct()
  if (distinct.isEmpty()) return Result.success(emptyList())
  val placeholders = distinct.joinToString(", ") { "?" }
  return queryList(
    "SELECT $columns FROM $table WHERE $keyColumn IN ($placeholders)",
    bind = { stmt -> distinct.forEachIndexed { i, key -> bindKey(stmt, i + 1, key) } },
    map = map,
  )
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
 * The SQL fragment a [jsonbArrayBinder] parameter is read through: one jsonb
 * bind expanded to `text[]` inside Postgres.
 *
 * It lives here, beside [Bind] and the `set*OrNull` helpers, because it is one
 * half of a primitive: the fragment and the binder that feeds it are only
 * correct together, and both `CollegesDao` and `CodebooksDao` need them.
 */
internal const val TEXT_ARRAY_PARAM = "ARRAY(SELECT jsonb_array_elements_text(?::jsonb))"

/**
 * A set of strings bound as ONE jsonb parameter and expanded to `text[]` by
 * Postgres — the other half of [TEXT_ARRAY_PARAM]. Client-side
 * `Connection.createArrayOf` is not reachable from a [SqlSession]: the boundary
 * deliberately withholds the pooled connection. This shape also removes the
 * `java.sql.Array` handle that would otherwise need a `finally { free() }`.
 */
internal fun jsonbArrayBinder(values: Collection<String>): Bind {
  val json = JsonArray(values.map { JsonPrimitive(it) }).toString()
  return { stmt, i -> stmt.setString(i, json) }
}

/** Binds one non-null `text` value. */
internal fun stringBinder(value: String): Bind = { stmt, i -> stmt.setString(i, value) }

/** Binds one non-null `integer` value. */
internal fun intBinder(value: Int): Bind = { stmt, i -> stmt.setInt(i, value) }

/** Binds one non-null `double precision` value. */
internal fun doubleBinder(value: Double): Bind = { stmt, i -> stmt.setDouble(i, value) }

/** Binds one non-null `boolean` value. */
internal fun booleanBinder(value: Boolean): Bind = { stmt, i -> stmt.setBoolean(i, value) }

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
 * Per-row disposition of a change-detecting upsert ([upsertDetectingChange]).
 */
enum class UpsertOutcome {
  /** No row existed for the natural key; a new one was inserted. */
  INSERTED,

  /** A row existed and at least one column value changed. */
  CHANGED,

  /** A row existed and every column matched; nothing was written. */
  UNCHANGED,
}

/**
 * Generates `INSERT INTO $table (<keys>, <cols>) VALUES (?, …) ON CONFLICT
 * (<keys>) DO UPDATE SET <col> = EXCLUDED.<col>, … WHERE (<cols>) IS DISTINCT
 * FROM (EXCLUDED.<cols>)` and reports the per-row [UpsertOutcome], so a
 * re-ingest of an unchanged row writes nothing and does not advance
 * `updated_at`.
 *
 * The disposition rides back as a raw tri-state rather than a name: `xmax = 0`
 * is a fresh INSERT, a returned row with `xmax <> 0` is a real UPDATE, and zero
 * returned rows (the `IS DISTINCT FROM` guard suppressed the write) comes back
 * as the NULL row and is [UpsertOutcome.UNCHANGED]. The enum is derived here,
 * in Kotlin, so renaming a member cannot desynchronise it from a SQL literal.
 *
 * Column names are fixed DAO identifiers, never caller data; only the bound
 * values vary.
 */
internal fun SqlSession.upsertDetectingChange(
  table: String,
  keyColumns: Map<String, Bind>,
  columns: Map<String, Bind>,
  mapError: (SQLException) -> Exception = ::mapDatabaseError,
): Result<UpsertOutcome> {
  val all = keyColumns + columns
  val sql =
    """
    WITH up AS (
      INSERT INTO $table (${all.keys.joinToString(", ")})
      VALUES (${all.keys.joinToString(", ") { "?" }})
      ON CONFLICT (${keyColumns.keys.joinToString(", ")}) DO UPDATE SET
        ${columns.keys.joinToString(", ") { "$it = EXCLUDED.$it" }}
      WHERE (${columns.keys.joinToString(", ") { "$table.$it" }})
        IS DISTINCT FROM (${columns.keys.joinToString(", ") { "EXCLUDED.$it" }})
      RETURNING (xmax = 0) AS inserted
    )
    SELECT inserted FROM up
    UNION ALL
    SELECT NULL::boolean WHERE NOT EXISTS (SELECT 1 FROM up)
    """.trimIndent()
  val binds = all.values.toList()
  return mutateReturning(
    sql,
    bind = { stmt -> binds.forEachIndexed { i, b -> b(stmt, i + 1) } },
    map = { rs ->
      val inserted = rs.getBoolean("inserted")
      when {
        rs.wasNull() -> UpsertOutcome.UNCHANGED
        inserted -> UpsertOutcome.INSERTED
        else -> UpsertOutcome.CHANGED
      }
    },
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

/** Binds a nullable Long, NULL as `Types.BIGINT`. */
internal fun PreparedStatement.setLongOrNull(
  index: Int,
  value: Long?,
) {
  if (value != null) setLong(index, value) else setNull(index, Types.BIGINT)
}

/** Binds a nullable Double, NULL as `Types.DOUBLE`. */
internal fun PreparedStatement.setDoubleOrNull(
  index: Int,
  value: Double?,
) {
  if (value != null) setDouble(index, value) else setNull(index, Types.DOUBLE)
}

/** Binds a nullable [LocalDate], NULL as `Types.DATE`. */
internal fun PreparedStatement.setDateOrNull(
  index: Int,
  value: LocalDate?,
) {
  if (value != null) setObject(index, value) else setNull(index, Types.DATE)
}

/** Binds a nullable Boolean, NULL as `Types.BOOLEAN`. */
internal fun PreparedStatement.setBooleanOrNull(
  index: Int,
  value: Boolean?,
) {
  if (value != null) setBoolean(index, value) else setNull(index, Types.BOOLEAN)
}

/** Binds a nullable [JsonElement] into a `?::jsonb` slot, NULL as `Types.OTHER`. */
internal fun PreparedStatement.setJsonbOrNull(
  index: Int,
  value: JsonElement?,
) {
  if (value != null) setString(index, value.toString()) else setNull(index, Types.OTHER)
}

/** Reads a nullable INTEGER column (the `getInt` + `wasNull` JDBC idiom). */
internal fun ResultSet.getIntOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

/**
 * Reads a SQL `text[]` column into a Kotlin list, freeing the JDBC
 * [java.sql.Array] handle afterward (it holds driver-side resources). A NULL
 * array collapses to an empty list.
 */
internal fun ResultSet.getStringList(column: String): List<String> {
  val arr = getArray(column) ?: return emptyList()
  try {
    @Suppress("UNCHECKED_CAST")
    return (arr.array as Array<String?>).filterNotNull()
  } finally {
    arr.free()
  }
}

internal fun ResultSet.getInstant(column: String): Instant = getTimestamp(column).toInstant()

internal fun ResultSet.getInstantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()

internal fun ResultSet.getJsonbOrNull(column: String): JsonElement? = getString(column)?.let { Json.parseToJsonElement(it) }

/**
 * The read-path convention for a find-or-null query: a [NotFoundException] from
 * [queryOne] is the absence of a row, not a fault, and becomes `success(null)`.
 * Every other failure propagates untouched.
 */
internal fun <T> Result<T>.orNullOnNotFound(): Result<T?> =
  fold(
    onSuccess = { Result.success(it) },
    onFailure = { if (it is NotFoundException) Result.success(null) else Result.failure(it) },
  )

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
