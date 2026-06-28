package ed.unicoach.db.dao

import ed.unicoach.db.models.College
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeMatch
import ed.unicoach.db.models.CollegeProgram
import ed.unicoach.db.models.CollegeProgramId
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeProgram
import ed.unicoach.db.models.Version
import org.postgresql.util.PSQLException
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.UUID

/**
 * Data-access layer over the college reference tables (RFC 67): `colleges` and
 * `college_programs`.
 *
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned by
 * the caller (same shape as [ConvosDao]). The upsert methods are hand-rolled
 * `INSERT ... ON CONFLICT ... DO UPDATE`: no generic upsert helper exists in the
 * codebase, where DAOs use typed `Creatable`/`insertReturning` helpers.
 *
 * `colleges` is versioned (RFC 82) via a trigger-managed `version` that the
 * upsert bumps on a real content change, recording each change in
 * `colleges_versions`. The bump is not an optimistic-concurrency guard: there is
 * no client-supplied version; the upsert sets `version = colleges.version + 1`
 * from the current row inside the statement. `college_programs` remains
 * unversioned (out of scope), so its upsert carries no version column.
 */
object CollegesDao :
  Findable<College, CollegeId>,
  Listable<College>,
  VersionHistory<CollegeId, Version<College>> {
  // ---------------------------------------------------------------------------
  // Row mappers
  // ---------------------------------------------------------------------------

  // Nullable JDBC reads use the getInt/getDouble + wasNull() idiom (per
  // ConvosDao); these scoped helpers keep the mappers terse without touching the
  // shared SqlSessionQueries scaffolding.
  private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

  private fun ResultSet.doubleOrNull(column: String): Double? = getDouble(column).takeUnless { wasNull() }

  /**
   * Reads a SQL `text[]` column into a Kotlin list, freeing the JDBC [java.sql.Array]
   * handle afterward (it holds driver-side resources). A NULL array collapses to an
   * empty list.
   */
  private fun ResultSet.getStringList(column: String): List<String> {
    val arr = getArray(column) ?: return emptyList()
    try {
      @Suppress("UNCHECKED_CAST")
      return (arr.array as Array<String?>).filterNotNull()
    } finally {
      arr.free()
    }
  }

  /** Binds a nullable Double, NULL as `Types.DOUBLE` (the Double sibling of [setIntOrNull]). */
  private fun PreparedStatement.setDoubleOrNull(
    index: Int,
    value: Double?,
  ) {
    if (value != null) setDouble(index, value) else setNull(index, Types.DOUBLE)
  }

  private fun mapCollege(rs: ResultSet): College =
    College(
      id = CollegeId(UUID.fromString(rs.getString("id"))),
      version = rs.getInt("version"),
      unitId = rs.getInt("unit_id"),
      opeid = rs.getString("opeid"),
      name = rs.getString("name"),
      city = rs.getString("city"),
      state = rs.getString("state"),
      region = rs.intOrNull("region"),
      locale = rs.intOrNull("locale"),
      latitude = rs.doubleOrNull("latitude"),
      longitude = rs.doubleOrNull("longitude"),
      control = rs.getInt("control"),
      undergradEnrollment = rs.intOrNull("undergrad_enrollment"),
      admissionRate = rs.doubleOrNull("admission_rate"),
      satAvg = rs.intOrNull("sat_avg"),
      costAttendance = rs.intOrNull("cost_attendance"),
      netPrice = rs.intOrNull("net_price"),
      tuitionInState = rs.intOrNull("tuition_in_state"),
      tuitionOutState = rs.intOrNull("tuition_out_state"),
      graduationRate = rs.doubleOrNull("graduation_rate"),
      medianEarnings = rs.intOrNull("median_earnings"),
      pctPell = rs.doubleOrNull("pct_pell"),
      website = rs.getString("website"),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
    )

  private fun mapProgram(rs: ResultSet): CollegeProgram =
    CollegeProgram(
      id = CollegeProgramId(UUID.fromString(rs.getString("id"))),
      collegeId = CollegeId(UUID.fromString(rs.getString("college_id"))),
      cipCode = rs.getString("cip_code"),
      cipTitle = rs.getString("cip_title"),
      credentialLevel = rs.getInt("credential_level"),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
    )

  /**
   * Maps a [search] result row. The scalar columns are read here; the
   * `program_titles` SQL ARRAY is read via JDBC `getArray` (it cannot be read as
   * a typed scalar). A NULL array — possible when the program JOIN is absent or
   * `array_agg` saw no rows — collapses to an empty list.
   */
  private fun mapMatch(rs: ResultSet): CollegeMatch {
    val titles = rs.getStringList("program_titles")
    return CollegeMatch(
      id = CollegeId(UUID.fromString(rs.getString("id"))),
      unitId = rs.getInt("unit_id"),
      name = rs.getString("name"),
      city = rs.getString("city"),
      state = rs.getString("state"),
      control = rs.getInt("control"),
      locale = rs.intOrNull("locale"),
      undergradEnrollment = rs.intOrNull("undergrad_enrollment"),
      admissionRate = rs.doubleOrNull("admission_rate"),
      netPrice = rs.intOrNull("net_price"),
      graduationRate = rs.doubleOrNull("graduation_rate"),
      medianEarnings = rs.intOrNull("median_earnings"),
      pctPell = rs.doubleOrNull("pct_pell"),
      website = rs.getString("website"),
      programTitles = titles,
    )
  }

  // ---------------------------------------------------------------------------
  // Upserts (hand-rolled ON CONFLICT)
  // ---------------------------------------------------------------------------

  /**
   * Upserts a college on its natural key `unit_id` (RFC 82). On conflict every
   * curated column is overwritten from [input]; `id` and `created_at` are
   * preserved and the `_03` trigger advances `updated_at`.
   *
   * The version bumps (`version = colleges.version + 1`) and a history row is
   * logged **only on a real content change** — the `DO UPDATE` carries a `WHERE`
   * comparing the 21 curated columns as a row-tuple with `IS DISTINCT FROM`, so
   * re-ingesting an unchanged row neither writes nor bumps. (A whole-row
   * `colleges IS DISTINCT FROM EXCLUDED` would be unconditionally true —
   * `EXCLUDED.id`/`version`/`created_at`/`updated_at` all differ — defeating the
   * no-op skip; the tuple compare fixes that.)
   *
   * When the `WHERE` is unsatisfied the `DO UPDATE` performs no write and
   * `RETURNING` yields zero rows; the `UNION ALL` arm then returns the existing
   * row. The conflict guarantees the row exists, so exactly one row is always
   * returned, preserving the one-row contract. The bound `unit_id` parameter
   * appears twice (INSERT VALUES and the UNION arm).
   */
  fun upsert(
    session: SqlSession,
    input: NewCollege,
  ): Result<College> {
    val sql =
      """
      WITH up AS (
        INSERT INTO colleges (
          unit_id, opeid, name, city, state, region, locale, latitude, longitude,
          control, undergrad_enrollment, admission_rate, sat_avg, cost_attendance,
          net_price, tuition_in_state, tuition_out_state, graduation_rate,
          median_earnings, pct_pell, website
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (unit_id) DO UPDATE SET
          opeid = EXCLUDED.opeid,
          name = EXCLUDED.name,
          city = EXCLUDED.city,
          state = EXCLUDED.state,
          region = EXCLUDED.region,
          locale = EXCLUDED.locale,
          latitude = EXCLUDED.latitude,
          longitude = EXCLUDED.longitude,
          control = EXCLUDED.control,
          undergrad_enrollment = EXCLUDED.undergrad_enrollment,
          admission_rate = EXCLUDED.admission_rate,
          sat_avg = EXCLUDED.sat_avg,
          cost_attendance = EXCLUDED.cost_attendance,
          net_price = EXCLUDED.net_price,
          tuition_in_state = EXCLUDED.tuition_in_state,
          tuition_out_state = EXCLUDED.tuition_out_state,
          graduation_rate = EXCLUDED.graduation_rate,
          median_earnings = EXCLUDED.median_earnings,
          pct_pell = EXCLUDED.pct_pell,
          website = EXCLUDED.website,
          version = colleges.version + 1
        WHERE (
          colleges.opeid, colleges.name, colleges.city, colleges.state,
          colleges.region, colleges.locale, colleges.latitude, colleges.longitude,
          colleges.control, colleges.undergrad_enrollment, colleges.admission_rate,
          colleges.sat_avg, colleges.cost_attendance, colleges.net_price,
          colleges.tuition_in_state, colleges.tuition_out_state,
          colleges.graduation_rate, colleges.median_earnings, colleges.pct_pell,
          colleges.website, colleges.unit_id
        ) IS DISTINCT FROM (
          EXCLUDED.opeid, EXCLUDED.name, EXCLUDED.city, EXCLUDED.state,
          EXCLUDED.region, EXCLUDED.locale, EXCLUDED.latitude, EXCLUDED.longitude,
          EXCLUDED.control, EXCLUDED.undergrad_enrollment, EXCLUDED.admission_rate,
          EXCLUDED.sat_avg, EXCLUDED.cost_attendance, EXCLUDED.net_price,
          EXCLUDED.tuition_in_state, EXCLUDED.tuition_out_state,
          EXCLUDED.graduation_rate, EXCLUDED.median_earnings, EXCLUDED.pct_pell,
          EXCLUDED.website, EXCLUDED.unit_id
        )
        RETURNING *
      )
      SELECT * FROM up
      UNION ALL
      SELECT * FROM colleges WHERE unit_id = ? AND NOT EXISTS (SELECT 1 FROM up)
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setInt(1, input.unitId)
        stmt.setStringOrNull(2, input.opeid)
        stmt.setString(3, input.name)
        stmt.setString(4, input.city)
        stmt.setString(5, input.state)
        stmt.setIntOrNull(6, input.region)
        stmt.setIntOrNull(7, input.locale)
        stmt.setDoubleOrNull(8, input.latitude)
        stmt.setDoubleOrNull(9, input.longitude)
        stmt.setInt(10, input.control)
        stmt.setIntOrNull(11, input.undergradEnrollment)
        stmt.setDoubleOrNull(12, input.admissionRate)
        stmt.setIntOrNull(13, input.satAvg)
        stmt.setIntOrNull(14, input.costAttendance)
        stmt.setIntOrNull(15, input.netPrice)
        stmt.setIntOrNull(16, input.tuitionInState)
        stmt.setIntOrNull(17, input.tuitionOutState)
        stmt.setDoubleOrNull(18, input.graduationRate)
        stmt.setIntOrNull(19, input.medianEarnings)
        stmt.setDoubleOrNull(20, input.pctPell)
        stmt.setStringOrNull(21, input.website)
        stmt.setInt(22, input.unitId)
      },
      map = ::mapCollege,
      mapError = ::mapCollegeError,
    )
  }

  /**
   * Upserts a program on its natural key `(college_id, cip_code,
   * credential_level)`. On conflict `cip_title` is overwritten; `id` and
   * `created_at` are preserved and the `_03` trigger advances `updated_at`.
   */
  fun upsertProgram(
    session: SqlSession,
    input: NewCollegeProgram,
  ): Result<CollegeProgram> {
    val sql =
      """
      INSERT INTO college_programs (college_id, cip_code, cip_title, credential_level)
      VALUES (?, ?, ?, ?)
      ON CONFLICT (college_id, cip_code, credential_level) DO UPDATE SET
        cip_title = EXCLUDED.cip_title
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setObject(1, input.collegeId.value)
        stmt.setString(2, input.cipCode)
        stmt.setString(3, input.cipTitle)
        stmt.setInt(4, input.credentialLevel)
      },
      map = ::mapProgram,
      mapError = ::mapCollegeError,
    )
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /** Admin read surface (RFC 82): a single college by surface id, [NotFoundException] on no row. */
  override fun findById(
    session: SqlSession,
    id: CollegeId,
  ): Result<College> =
    session.queryOne(
      "SELECT * FROM colleges WHERE id = ?",
      bind = { it.setObject(1, id.value) },
      map = ::mapCollege,
    )

  /**
   * Admin read surface (RFC 82): a page of colleges ordered by `name, unit_id`.
   * `unit_id` is unique, so the order is total/deterministic for count-free paging.
   */
  override fun list(
    session: SqlSession,
    limit: Int,
    offset: Int,
  ): Result<List<College>> =
    session.queryList(
      "SELECT * FROM colleges ORDER BY name, unit_id LIMIT ? OFFSET ?",
      bind = {
        it.setInt(1, limit)
        it.setInt(2, offset)
      },
      map = ::mapCollege,
    )

  /**
   * Admin read surface (RFC 82): a college's full version history, ascending by
   * version. Unpaged — one college's history is bounded by the number of ingests
   * that changed that single row.
   */
  override fun listVersions(
    session: SqlSession,
    id: CollegeId,
  ): Result<List<Version<College>>> =
    session.queryList(
      "SELECT * FROM colleges_versions WHERE id = ? ORDER BY version",
      bind = { it.setObject(1, id.value) },
      map = { Version(mapCollege(it)) },
    )

  fun findByUnitId(
    session: SqlSession,
    unitId: Int,
  ): Result<College?> =
    session
      .queryOne(
        "SELECT * FROM colleges WHERE unit_id = ?",
        bind = { it.setInt(1, unitId) },
        map = ::mapCollege,
      ).fold(
        onSuccess = { Result.success(it) },
        onFailure = { if (it is NotFoundException) Result.success(null) else Result.failure(it) },
      )

  /**
   * Structured filtering over the typed columns. Builds a parameterized SELECT,
   * appending one `AND` clause per non-null filter; joins `college_programs` only
   * when `cipPrefix` is set (matching `cip_code LIKE prefix || '%'` so 2/4/6-digit
   * prefixes all resolve, and aggregating the matched titles into
   * `program_titles`). Applies the deterministic
   * `ORDER BY undergrad_enrollment DESC NULLS LAST, unit_id ASC` and the
   * caller-supplied `LIMIT`. Every value is bound as a parameter — no filter value
   * is interpolated into SQL text.
   */
  fun search(
    session: SqlSession,
    query: CollegeQuery,
  ): Result<List<CollegeMatch>> {
    val binders = mutableListOf<(PreparedStatement, Int) -> Unit>()
    val wheres = mutableListOf<String>()
    val hasProgramFilter = query.cipPrefix != null

    if (query.cipPrefix != null) {
      wheres += "p.cip_code LIKE ? || '%'"
      val prefix = query.cipPrefix
      binders += { stmt, i -> stmt.setString(i, prefix) }
    }
    query.states?.let { states ->
      if (states.isNotEmpty()) {
        wheres += "c.state IN (${states.joinToString(", ") { "?" }})"
        states.forEach { s -> binders += { stmt, i -> stmt.setString(i, s) } }
      }
    }
    query.region?.let { region ->
      wheres += "c.region = ?"
      binders += { stmt, i -> stmt.setInt(i, region) }
    }
    query.locales?.let { locales ->
      if (locales.isNotEmpty()) {
        wheres += "c.locale IN (${locales.joinToString(", ") { "?" }})"
        locales.forEach { l -> binders += { stmt, i -> stmt.setInt(i, l) } }
      }
    }
    query.control?.let { control ->
      if (control.isNotEmpty()) {
        wheres += "c.control IN (${control.joinToString(", ") { "?" }})"
        control.forEach { ctrl -> binders += { stmt, i -> stmt.setInt(i, ctrl) } }
      }
    }
    query.minUndergradEnrollment?.let { min ->
      wheres += "c.undergrad_enrollment >= ?"
      binders += { stmt, i -> stmt.setInt(i, min) }
    }
    query.maxUndergradEnrollment?.let { max ->
      wheres += "c.undergrad_enrollment <= ?"
      binders += { stmt, i -> stmt.setInt(i, max) }
    }
    query.minAdmissionRate?.let { min ->
      wheres += "c.admission_rate >= ?"
      binders += { stmt, i -> stmt.setDouble(i, min) }
    }
    query.maxAdmissionRate?.let { max ->
      wheres += "c.admission_rate <= ?"
      binders += { stmt, i -> stmt.setDouble(i, max) }
    }
    query.maxNetPrice?.let { max ->
      wheres += "c.net_price <= ?"
      binders += { stmt, i -> stmt.setInt(i, max) }
    }
    query.minGraduationRate?.let { min ->
      wheres += "c.graduation_rate >= ?"
      binders += { stmt, i -> stmt.setDouble(i, min) }
    }

    val selectTitles =
      if (hasProgramFilter) {
        "array_agg(DISTINCT p.cip_title) AS program_titles"
      } else {
        "ARRAY[]::text[] AS program_titles"
      }
    val join = if (hasProgramFilter) "JOIN college_programs p ON p.college_id = c.id" else ""
    val whereClause = if (wheres.isEmpty()) "" else "WHERE ${wheres.joinToString(" AND ")}"

    // limit is positional and always last; bound below after the filter binders.
    val sql =
      """
      SELECT
        c.id, c.unit_id, c.name, c.city, c.state, c.control, c.locale,
        c.undergrad_enrollment, c.admission_rate, c.net_price, c.graduation_rate,
        c.median_earnings, c.pct_pell, c.website,
        $selectTitles
      FROM colleges c
      $join
      $whereClause
      GROUP BY c.id
      ORDER BY c.undergrad_enrollment DESC NULLS LAST, c.unit_id ASC
      LIMIT ?
      """.trimIndent()

    return session.queryList(
      sql,
      bind = { stmt ->
        var idx = 1
        binders.forEach { b -> b(stmt, idx++) }
        stmt.setInt(idx, query.limit)
      },
      map = ::mapMatch,
    )
  }

  // ---------------------------------------------------------------------------
  // Error mapping
  // ---------------------------------------------------------------------------

  /**
   * Maps write-path SQLSTATEs: `23503` (FK — a program referencing an absent
   * college) to [NotFoundException]; `23505`/`23514` (unique/check) to
   * [ConstraintViolationException], populated with the violated constraint name
   * and the server DETAIL line so a caller can bucket by constraint and surface
   * the failing key without parsing log text. Everything else routes through the
   * shared [mapDatabaseError], which classifies transient SQLSTATEs.
   */
  private fun mapCollegeError(e: SQLException): Exception =
    when (e.sqlState) {
      "23503" -> NotFoundException("Referenced college not found")
      "23505", "23514" -> {
        val serverError = (e as? PSQLException)?.serverErrorMessage
        ConstraintViolationException(e, serverError?.constraint, serverError?.detail)
      }
      else -> mapDatabaseError(e)
    }
}
