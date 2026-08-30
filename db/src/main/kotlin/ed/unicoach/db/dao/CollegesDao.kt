package ed.unicoach.db.dao

import ed.unicoach.db.models.College
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeMatch
import ed.unicoach.db.models.CollegeProgram
import ed.unicoach.db.models.CollegeProgramId
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.CollegeSearchPage
import ed.unicoach.db.models.CollegeSummary
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeIndexBuild
import ed.unicoach.db.models.NewCollegeProgram
import ed.unicoach.db.models.Version
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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

  private fun mapCollege(rs: ResultSet): College =
    College(
      id = CollegeId(UUID.fromString(rs.getString("id"))),
      version = rs.getInt("version"),
      ipedsUnitId = rs.getInt("ipeds_unit_id"),
      opeid = rs.getString("opeid"),
      name = rs.getString("name"),
      city = rs.getString("city"),
      state = rs.getString("state"),
      region = rs.intOrNull("region"),
      locale = rs.intOrNull("locale"),
      latitude = rs.doubleOrNull("latitude"),
      longitude = rs.doubleOrNull("longitude"),
      control = rs.getInt("control"),
      undergradEnrollmentHeadcount = rs.intOrNull("undergrad_enrollment_headcount"),
      admissionRateShare = rs.doubleOrNull("admission_rate_share"),
      satAverageEquivalentScore = rs.intOrNull("sat_average_equivalent_score"),
      costOfAttendancePerYearUsd = rs.intOrNull("cost_of_attendance_per_year_usd"),
      netPricePerYearUsd = rs.intOrNull("net_price_per_year_usd"),
      netPricePerYearIncomeQ1Usd = rs.intOrNull("net_price_per_year_income_q1_usd"),
      netPricePerYearIncomeQ2Usd = rs.intOrNull("net_price_per_year_income_q2_usd"),
      netPricePerYearIncomeQ3Usd = rs.intOrNull("net_price_per_year_income_q3_usd"),
      netPricePerYearIncomeQ4Usd = rs.intOrNull("net_price_per_year_income_q4_usd"),
      netPricePerYearIncomeQ5Usd = rs.intOrNull("net_price_per_year_income_q5_usd"),
      tuitionAndFeesInStatePerYearUsd = rs.intOrNull("tuition_and_fees_in_state_per_year_usd"),
      tuitionAndFeesOutOfStatePerYearUsd = rs.intOrNull("tuition_and_fees_out_of_state_per_year_usd"),
      completionRate150pct4yrShare = rs.doubleOrNull("completion_rate_150pct_4yr_share"),
      medianEarnings10yAfterEntryUsd = rs.intOrNull("median_earnings_10y_after_entry_usd"),
      medianDebtAtCompletionUsd = rs.intOrNull("median_debt_at_completion_usd"),
      pellShare = rs.doubleOrNull("pell_share"),
      website = rs.getString("website"),
      aliases = rs.getStringList("aliases"),
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

  /** Maps a [searchByName] result row into the picker's [CollegeSummary] projection. */
  private fun mapSummary(rs: ResultSet): CollegeSummary =
    CollegeSummary(
      id = CollegeId(UUID.fromString(rs.getString("id"))),
      name = rs.getString("name"),
      city = rs.getString("city"),
      state = rs.getString("state"),
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
      ipedsUnitId = rs.getInt("ipeds_unit_id"),
      name = rs.getString("name"),
      city = rs.getString("city"),
      state = rs.getString("state"),
      control = rs.getInt("control"),
      locale = rs.intOrNull("locale"),
      undergradEnrollmentHeadcount = rs.intOrNull("undergrad_enrollment_headcount"),
      admissionRateShare = rs.doubleOrNull("admission_rate_share"),
      netPricePerYearUsd = rs.intOrNull("net_price_per_year_usd"),
      netPricePerYearIncomeQ1Usd = rs.intOrNull("net_price_per_year_income_q1_usd"),
      netPricePerYearIncomeQ2Usd = rs.intOrNull("net_price_per_year_income_q2_usd"),
      netPricePerYearIncomeQ3Usd = rs.intOrNull("net_price_per_year_income_q3_usd"),
      netPricePerYearIncomeQ4Usd = rs.intOrNull("net_price_per_year_income_q4_usd"),
      netPricePerYearIncomeQ5Usd = rs.intOrNull("net_price_per_year_income_q5_usd"),
      completionRate150pct4yrShare = rs.doubleOrNull("completion_rate_150pct_4yr_share"),
      medianEarnings10yAfterEntryUsd = rs.intOrNull("median_earnings_10y_after_entry_usd"),
      medianDebtAtCompletionUsd = rs.intOrNull("median_debt_at_completion_usd"),
      pellShare = rs.doubleOrNull("pell_share"),
      website = rs.getString("website"),
      programTitles = titles,
    )
  }

  // ---------------------------------------------------------------------------
  // Upserts (hand-rolled ON CONFLICT)
  // ---------------------------------------------------------------------------

  /**
   * Upserts a college on its natural key `ipeds_unit_id` (RFC 82). On conflict every
   * curated column is overwritten from [input]; `id` and `created_at` are
   * preserved and the `_03` trigger advances `updated_at`.
   *
   * The version bumps (`version = colleges.version + 1`) and a history row is
   * logged **only on a real content change** — the `DO UPDATE` carries a `WHERE`
   * comparing every curated column as a row-tuple with `IS DISTINCT FROM`, so
   * re-ingesting an unchanged row neither writes nor bumps. (A whole-row
   * `colleges IS DISTINCT FROM EXCLUDED` would be unconditionally true —
   * `EXCLUDED.id`/`version`/`created_at`/`updated_at` all differ — defeating the
   * no-op skip; the tuple compare fixes that.)
   *
   * When the `WHERE` is unsatisfied the `DO UPDATE` performs no write and
   * `RETURNING` yields zero rows; the `UNION ALL` arm then returns the existing
   * row. The conflict guarantees the row exists, so exactly one row is always
   * returned, preserving the one-row contract. The bound `ipeds_unit_id` parameter
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
          ipeds_unit_id, opeid, name, city, state, region, locale, latitude, longitude,
          control, undergrad_enrollment_headcount, admission_rate_share, sat_average_equivalent_score, cost_of_attendance_per_year_usd,
          net_price_per_year_usd, tuition_and_fees_in_state_per_year_usd, tuition_and_fees_out_of_state_per_year_usd, completion_rate_150pct_4yr_share,
          median_earnings_10y_after_entry_usd, pell_share, website, net_price_per_year_income_q1_usd, net_price_per_year_income_q2_usd,
          net_price_per_year_income_q3_usd, net_price_per_year_income_q4_usd, net_price_per_year_income_q5_usd, median_debt_at_completion_usd
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (ipeds_unit_id) DO UPDATE SET
          opeid = EXCLUDED.opeid,
          name = EXCLUDED.name,
          city = EXCLUDED.city,
          state = EXCLUDED.state,
          region = EXCLUDED.region,
          locale = EXCLUDED.locale,
          latitude = EXCLUDED.latitude,
          longitude = EXCLUDED.longitude,
          control = EXCLUDED.control,
          undergrad_enrollment_headcount = EXCLUDED.undergrad_enrollment_headcount,
          admission_rate_share = EXCLUDED.admission_rate_share,
          sat_average_equivalent_score = EXCLUDED.sat_average_equivalent_score,
          cost_of_attendance_per_year_usd = EXCLUDED.cost_of_attendance_per_year_usd,
          net_price_per_year_usd = EXCLUDED.net_price_per_year_usd,
          tuition_and_fees_in_state_per_year_usd = EXCLUDED.tuition_and_fees_in_state_per_year_usd,
          tuition_and_fees_out_of_state_per_year_usd = EXCLUDED.tuition_and_fees_out_of_state_per_year_usd,
          completion_rate_150pct_4yr_share = EXCLUDED.completion_rate_150pct_4yr_share,
          median_earnings_10y_after_entry_usd = EXCLUDED.median_earnings_10y_after_entry_usd,
          pell_share = EXCLUDED.pell_share,
          website = EXCLUDED.website,
          net_price_per_year_income_q1_usd = EXCLUDED.net_price_per_year_income_q1_usd,
          net_price_per_year_income_q2_usd = EXCLUDED.net_price_per_year_income_q2_usd,
          net_price_per_year_income_q3_usd = EXCLUDED.net_price_per_year_income_q3_usd,
          net_price_per_year_income_q4_usd = EXCLUDED.net_price_per_year_income_q4_usd,
          net_price_per_year_income_q5_usd = EXCLUDED.net_price_per_year_income_q5_usd,
          median_debt_at_completion_usd = EXCLUDED.median_debt_at_completion_usd,
          version = colleges.version + 1
        WHERE (
          colleges.opeid, colleges.name, colleges.city, colleges.state,
          colleges.region, colleges.locale, colleges.latitude, colleges.longitude,
          colleges.control, colleges.undergrad_enrollment_headcount, colleges.admission_rate_share,
          colleges.sat_average_equivalent_score, colleges.cost_of_attendance_per_year_usd, colleges.net_price_per_year_usd,
          colleges.tuition_and_fees_in_state_per_year_usd, colleges.tuition_and_fees_out_of_state_per_year_usd,
          colleges.completion_rate_150pct_4yr_share, colleges.median_earnings_10y_after_entry_usd, colleges.pell_share,
          colleges.website, colleges.net_price_per_year_income_q1_usd, colleges.net_price_per_year_income_q2_usd,
          colleges.net_price_per_year_income_q3_usd, colleges.net_price_per_year_income_q4_usd, colleges.net_price_per_year_income_q5_usd,
          colleges.median_debt_at_completion_usd, colleges.ipeds_unit_id
        ) IS DISTINCT FROM (
          EXCLUDED.opeid, EXCLUDED.name, EXCLUDED.city, EXCLUDED.state,
          EXCLUDED.region, EXCLUDED.locale, EXCLUDED.latitude, EXCLUDED.longitude,
          EXCLUDED.control, EXCLUDED.undergrad_enrollment_headcount, EXCLUDED.admission_rate_share,
          EXCLUDED.sat_average_equivalent_score, EXCLUDED.cost_of_attendance_per_year_usd, EXCLUDED.net_price_per_year_usd,
          EXCLUDED.tuition_and_fees_in_state_per_year_usd, EXCLUDED.tuition_and_fees_out_of_state_per_year_usd,
          EXCLUDED.completion_rate_150pct_4yr_share, EXCLUDED.median_earnings_10y_after_entry_usd, EXCLUDED.pell_share,
          EXCLUDED.website, EXCLUDED.net_price_per_year_income_q1_usd, EXCLUDED.net_price_per_year_income_q2_usd,
          EXCLUDED.net_price_per_year_income_q3_usd, EXCLUDED.net_price_per_year_income_q4_usd, EXCLUDED.net_price_per_year_income_q5_usd,
          EXCLUDED.median_debt_at_completion_usd, EXCLUDED.ipeds_unit_id
        )
        RETURNING *
      )
      SELECT * FROM up
      UNION ALL
      SELECT * FROM colleges WHERE ipeds_unit_id = ? AND NOT EXISTS (SELECT 1 FROM up)
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setInt(1, input.ipedsUnitId)
        stmt.setStringOrNull(2, input.opeid)
        stmt.setString(3, input.name)
        stmt.setString(4, input.city)
        stmt.setString(5, input.state)
        stmt.setIntOrNull(6, input.region)
        stmt.setIntOrNull(7, input.locale)
        stmt.setDoubleOrNull(8, input.latitude)
        stmt.setDoubleOrNull(9, input.longitude)
        stmt.setInt(10, input.control)
        stmt.setIntOrNull(11, input.undergradEnrollmentHeadcount)
        stmt.setDoubleOrNull(12, input.admissionRateShare)
        stmt.setIntOrNull(13, input.satAverageEquivalentScore)
        stmt.setIntOrNull(14, input.costOfAttendancePerYearUsd)
        stmt.setIntOrNull(15, input.netPricePerYearUsd)
        stmt.setIntOrNull(16, input.tuitionAndFeesInStatePerYearUsd)
        stmt.setIntOrNull(17, input.tuitionAndFeesOutOfStatePerYearUsd)
        stmt.setDoubleOrNull(18, input.completionRate150pct4yrShare)
        stmt.setIntOrNull(19, input.medianEarnings10yAfterEntryUsd)
        stmt.setDoubleOrNull(20, input.pellShare)
        stmt.setStringOrNull(21, input.website)
        stmt.setIntOrNull(22, input.netPricePerYearIncomeQ1Usd)
        stmt.setIntOrNull(23, input.netPricePerYearIncomeQ2Usd)
        stmt.setIntOrNull(24, input.netPricePerYearIncomeQ3Usd)
        stmt.setIntOrNull(25, input.netPricePerYearIncomeQ4Usd)
        stmt.setIntOrNull(26, input.netPricePerYearIncomeQ5Usd)
        stmt.setIntOrNull(27, input.medianDebtAtCompletionUsd)
        stmt.setInt(28, input.ipedsUnitId)
      },
      map = ::mapCollege,
      mapError = ::mapCollegeWriteError,
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
      mapError = ::mapCollegeWriteError,
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
   * The display names of the given college [ids], in no particular order —
   * [listByIds] projected to names. Used by the fit-lens read phase to render
   * its exclusion set (college-list + prior suggestions) into LLM call #1 by
   * name (bounded by list size, so the wider SELECT is immaterial). An empty
   * [ids] short-circuits to an empty list without a query.
   */
  fun listNamesByIds(
    session: SqlSession,
    ids: Collection<CollegeId>,
  ): Result<List<String>> = listByIds(session, ids).map { rows -> rows.map { it.name } }

  /**
   * The full [College] rows for the given [ids], in no particular order (the
   * caller re-orders; RFC 135's cost read joins them back to the student's
   * list entries). An empty [ids] short-circuits to an empty list without a
   * query; ids with no row are simply absent from the result.
   */
  fun listByIds(
    session: SqlSession,
    ids: Collection<CollegeId>,
  ): Result<List<College>> {
    if (ids.isEmpty()) return Result.success(emptyList())
    val placeholders = ids.joinToString(", ") { "?" }
    return session.queryList(
      "SELECT * FROM colleges WHERE id IN ($placeholders)",
      bind = { stmt ->
        ids.forEachIndexed { i, id -> stmt.setObject(i + 1, id.value) }
      },
      map = ::mapCollege,
    )
  }

  /**
   * Admin read surface (RFC 82): a page of colleges ordered by `name, ipeds_unit_id`.
   * `ipeds_unit_id` is unique, so the order is total/deterministic for count-free paging.
   */
  override fun list(
    session: SqlSession,
    limit: Int,
    offset: Int,
  ): Result<List<College>> =
    session.queryList(
      "SELECT * FROM colleges ORDER BY name, ipeds_unit_id LIMIT ? OFFSET ?",
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

  fun findByIpedsUnitId(
    session: SqlSession,
    ipedsUnitId: Int,
  ): Result<College?> =
    session
      .queryOne(
        "SELECT * FROM colleges WHERE ipeds_unit_id = ?",
        bind = { it.setInt(1, ipedsUnitId) },
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
   * `program_titles`; a `credentialLevel` alone joins without title aggregation).
   * Applies the [CollegeQuery.SortBy] ordering (see [orderBy]) and the
   * caller-supplied `LIMIT`, then runs the companion unclamped COUNT over the
   * same FROM/WHERE for [CollegeSearchPage.totalMatches] (RFC 139). Every value
   * is bound as a parameter — no filter value is interpolated into SQL text.
   */
  fun search(
    session: SqlSession,
    query: CollegeQuery,
  ): Result<CollegeSearchPage> {
    val binders = mutableListOf<(PreparedStatement, Int) -> Unit>()
    val wheres = mutableListOf<String>()
    val hasProgramFilter = query.cipPrefix != null || query.credentialLevel != null

    if (query.cipPrefix != null) {
      wheres += "p.cip_code LIKE ? || '%'"
      val prefix = query.cipPrefix
      binders += { stmt, i -> stmt.setString(i, prefix) }
    }
    query.credentialLevel?.let { level ->
      wheres += "p.credential_level = ?"
      binders += { stmt, i -> stmt.setInt(i, level.code) }
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
    query.minUndergradEnrollmentHeadcount?.let { min ->
      wheres += "c.undergrad_enrollment_headcount >= ?"
      binders += { stmt, i -> stmt.setInt(i, min) }
    }
    query.maxUndergradEnrollmentHeadcount?.let { max ->
      wheres += "c.undergrad_enrollment_headcount <= ?"
      binders += { stmt, i -> stmt.setInt(i, max) }
    }
    query.minAdmissionRateShare?.let { min ->
      wheres += "c.admission_rate_share >= ?"
      binders += { stmt, i -> stmt.setDouble(i, min) }
    }
    query.maxAdmissionRateShare?.let { max ->
      wheres += "c.admission_rate_share <= ?"
      binders += { stmt, i -> stmt.setDouble(i, max) }
    }
    query.maxNetPricePerYearUsd?.let { max ->
      wheres += "c.net_price_per_year_usd <= ?"
      binders += { stmt, i -> stmt.setInt(i, max) }
    }
    query.minCompletionRate150pct4yrShare?.let { min ->
      wheres += "c.completion_rate_150pct_4yr_share >= ?"
      binders += { stmt, i -> stmt.setDouble(i, min) }
    }

    val selectTitles =
      if (query.cipPrefix != null) {
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
        c.id, c.ipeds_unit_id, c.name, c.city, c.state, c.control, c.locale,
        c.undergrad_enrollment_headcount, c.admission_rate_share, c.net_price_per_year_usd, c.net_price_per_year_income_q1_usd,
        c.net_price_per_year_income_q2_usd, c.net_price_per_year_income_q3_usd, c.net_price_per_year_income_q4_usd, c.net_price_per_year_income_q5_usd,
        c.completion_rate_150pct_4yr_share, c.median_earnings_10y_after_entry_usd, c.median_debt_at_completion_usd, c.pell_share, c.website,
        $selectTitles
      FROM colleges c
      $join
      $whereClause
      GROUP BY c.id
      ORDER BY ${orderBy(query.sortBy)}
      LIMIT ?
      """.trimIndent()

    val matches =
      session
        .queryList(
          sql,
          bind = { stmt ->
            var idx = 1
            binders.forEach { b -> b(stmt, idx++) }
            stmt.setInt(idx, query.limit)
          },
          map = ::mapMatch,
        ).getOrElse { return Result.failure(it) }

    // The honest population count: same FROM/WHERE, no GROUP BY, no LIMIT. Two
    // statements on one connection; at ~6k rows the COUNT costs microseconds and
    // keeps the main query untouched.
    val countSql = "SELECT COUNT(DISTINCT c.id) AS total FROM colleges c $join $whereClause"
    return session
      .queryOne(
        countSql,
        bind = { stmt ->
          var idx = 1
          binders.forEach { b -> b(stmt, idx++) }
        },
        map = { rs -> rs.getInt("total") },
      ).map { total -> CollegeSearchPage(matches = matches, totalMatches = total) }
  }

  /**
   * The ORDER BY clause for a [CollegeQuery.SortBy] — a closed enum-to-constant
   * mapping (no caller text reaches SQL). A sort never filters: rows NULL on the
   * sort key sink (`NULLS LAST`), they do not vanish (brief 0004 D11); every
   * ordering ends with the `ipeds_unit_id ASC` tiebreak for a total, deterministic
   * order (`name` is not unique, so NAME_ASC needs the tiebreak too).
   */
  private fun orderBy(sortBy: CollegeQuery.SortBy): String =
    when (sortBy) {
      CollegeQuery.SortBy.ENROLLMENT_DESC -> "c.undergrad_enrollment_headcount DESC NULLS LAST"
      CollegeQuery.SortBy.ADMISSION_RATE_SHARE_ASC -> "c.admission_rate_share ASC NULLS LAST"
      CollegeQuery.SortBy.NET_PRICE_PER_YEAR_USD_ASC -> "c.net_price_per_year_usd ASC NULLS LAST"
      CollegeQuery.SortBy.COMPLETION_RATE_150PCT_4YR_SHARE_DESC -> "c.completion_rate_150pct_4yr_share DESC NULLS LAST"
      CollegeQuery.SortBy.NAME_ASC -> "c.name ASC NULLS LAST"
    } + ", c.ipeds_unit_id ASC"

  /**
   * Student-facing name search (RFC 137 boundary, RFC 146 matching). Three
   * mechanisms, each **exact** — there is no similarity score and no threshold
   * anywhere in this query:
   *
   * 1. **One keystroke** — the typo mechanism. The query is split into words by
   *    `college_search_words()` — the SAME function `college_name_words` is
   *    built from, so there is one word boundary in the system — and a college
   *    matches when EVERY query word is within one keystroke of SOME word of
   *    its search text: `one_keystroke_off()` (migration 0056) is optimal
   *    string alignment distance <= 1, i.e. one substitution, insertion,
   *    deletion, or adjacent transposition. Recall is then a theorem rather
   *    than a corpus statistic — a query formed by mistyping one key in each
   *    word of a name is by definition within one keystroke of each of those
   *    words. The quantifier is `for all` on purpose: `there exists` would let
   *    "colege" alone return every college in the corpus.
   * 2. **Substring** — the fragment mechanism: `search_text ILIKE '%…%'` over
   *    `college_search_text(name, aliases)` (the 0051 expression), so a short
   *    fragment ("Amh") and an alias fragment ("izzo") both match literally.
   * 3. **Aliases** — the nickname mechanism (RFC 139), which needs no code of
   *    its own: "Mizzou" is a curated alias, therefore a word of the search
   *    text, and so matches arm 1 exactly.
   *
   * This replaces RFC 139's two `pg_trgm` arms, whose `word_similarity` scored
   * the best-matching contiguous extent and so ranked Elmhurst University above
   * an absent Amherst College for the query "Amhurst". No threshold repairs
   * that, so the metric — and the extension — are gone (RFC 146).
   *
   * `nw.len BETWEEN length(qw) - 1 AND length(qw) + 1` is a **lossless**
   * prefilter by argument, not by measurement: one edit changes a string's
   * length by at most 1, so a word outside that band cannot be one keystroke
   * away. It exists to let `college_name_words_len_word_idx` prune.
   *
   * A query with no word at all (`"%%%"`, `"\\"`) yields an empty `words`
   * array, and the `cardinality(...) > 0` guard is what keeps that from
   * matching everything: "every query word matched" is vacuously true when
   * there are no query words. Such a query is left to the substring arm alone,
   * which is exactly the RFC 137 behaviour.
   *
   * The match is computed ONCE, in the `word_match` CTE: the minimum distance
   * from each query word to that college's name words. Membership is "every
   * query word has such a row" (`matched_words = cardinality(words)`) and the
   * rank key is the sum of those distances, so the predicate and the ranking
   * read the same numbers rather than each re-expanding the join — they cannot
   * drift, and the work is done once.
   *
   * Ranking: exact-prefix-of-name first (RFC 137 behaviour preserved), then two
   * explicit keys. The first is the CLASS — whether the row matched the
   * one-keystroke rule at all, i.e. every query word matched — so rows the rule
   * matched come before rows here only through the substring arm. It is a
   * boolean, which cannot collide with a distance the way an in-band penalty
   * can, so the separation holds at every query-word count rather than only at
   * one. The second is the summed per-word distance: an exact word contributes
   * 0 and a one-keystroke word 1, NULL when nothing matched (sorted last). Then
   * `undergrad_enrollment_headcount DESC NULLS LAST, name, ipeds_unit_id` as the deterministic
   * tail. The same definition as the predicate, summed; no weights, no magic
   * literal, nothing fitted. [limit] is clamped by the service boundary before
   * reaching here (the [search] convention).
   *
   * The raw/escaped split is load-bearing and positional (as it was under RFC
   * 139, for a different reason): the ILIKE arms take the ESCAPED query, so a
   * literal `%` in a school's name cannot act as a wildcard, while the
   * one-keystroke arm takes the RAW query and lets `college_search_words()`
   * split and lowercase it — `%`/`_`/`\` are not word characters, so they are
   * inert there rather than escaped. Swapping
   * one for the other silently changes what matches, and no test of a
   * metacharacter-free query would notice.
   */
  fun searchByName(
    session: SqlSession,
    query: String,
    limit: Int,
  ): Result<List<CollegeSummary>> {
    val escaped = escapeLikePattern(query)
    val sql =
      """
      WITH q(words) AS (SELECT college_search_words(?)),
      -- The one-keystroke match, computed ONCE per (college, query word): the
      -- minimum distance from that query word to any of that college's name
      -- words — 0 exact, 1 one keystroke, and no row at all when nothing is
      -- within one keystroke. Membership and ranking below both read THIS, so
      -- the predicate and the rank key cannot drift into disagreeing about
      -- what "matches" means. WITH ORDINALITY keeps a repeated query word
      -- repeated, which is what the rank sum counts.
      word_match AS (
        SELECT nw.college_id, qw.ord, min(CASE WHEN nw.word = qw.word THEN 0 ELSE 1 END) AS distance
        FROM q, unnest(q.words) WITH ORDINALITY AS qw(word, ord)
        JOIN college_name_words nw
          ON nw.len BETWEEN length(qw.word) - 1 AND length(qw.word) + 1
         AND one_keystroke_off(qw.word, nw.word)
        GROUP BY nw.college_id, qw.ord
      ),
      scored AS (
        SELECT college_id, count(*) AS matched_words, sum(distance) AS distance
        FROM word_match
        GROUP BY college_id
      )
      SELECT c.id, c.name, c.city, c.state
      FROM colleges c
        CROSS JOIN q
        LEFT JOIN scored s ON s.college_id = c.id
      WHERE (cardinality(q.words) > 0 AND s.matched_words = cardinality(q.words))
         OR college_search_text(c.name, c.aliases) ILIKE '%' || ? || '%'
      ORDER BY (c.name ILIKE ? || '%') DESC,
        -- Two explicit keys, not one number with a penalty folded into it.
        -- First the CLASS: did the row match the one-keystroke rule at all
        -- (every query word matched)? A boolean cannot collide with a distance,
        -- so a substring-only row can never tie or outrank a rule match however
        -- many words the query has. coalesce because a row with no word_match
        -- rows has no `scored` row at all.
        (coalesce(s.matched_words, 0) = cardinality(q.words)) DESC,
        -- Then, within a class, the summed per-word distance: 0 per exact word,
        -- 1 per one-keystroke word. NULL for a substring-only row that matched
        -- no query word, which sorts last — it is the least-explained match.
        s.distance ASC NULLS LAST,
        c.undergrad_enrollment_headcount DESC NULLS LAST, c.name, c.ipeds_unit_id
      LIMIT ?
      """.trimIndent()
    return session.queryList(
      sql,
      bind = { stmt ->
        // Parameter 1 is the RAW query: Postgres splits it with
        // `college_search_words`, the same function the stored words are built
        // from, so there is exactly ONE word boundary in the system. Bound,
        // never interpolated; `%`/`_`/`\\` are inert here because they are not
        // word characters, while the ILIKE arms below take the ESCAPED form.
        stmt.setString(1, query)
        stmt.setString(2, escaped)
        stmt.setString(3, escaped)
        stmt.setInt(4, limit)
      },
      map = ::mapSummary,
    )
  }

  /**
   * Escapes LIKE metacharacters so caller text matches literally (backslash
   * first, so it never re-escapes its own output). Backslash-as-escape is
   * Postgres's **implicit default** — the query above carries no `ESCAPE`
   * clause and relies on it (standard SQL would require `ESCAPE '\\'`
   * explicitly), so this helper and that default are one contract.
   */
  private fun escapeLikePattern(raw: String): String =
    raw
      .replace("\\", "\\\\")
      .replace("%", "\\%")
      .replace("_", "\\_")

  // ---------------------------------------------------------------------------
  // Derived name words (RFC 146)
  // ---------------------------------------------------------------------------

  /**
   * Rebuilds `college_name_words` wholesale and returns the number of rows
   * written (RFC 146). This is the ingest's `name-words` phase and the ONLY
   * writer of that derived table: it lives here, beside the other college
   * derived writes, rather than in a DAO of its own.
   *
   * `DELETE` + `INSERT … SELECT` inside the caller's one transaction, not
   * `TRUNCATE`: TRUNCATE takes ACCESS EXCLUSIVE and would block live search
   * readers for the length of the rebuild, while the DELETE leaves them on the
   * old snapshot until the commit flips them to the new one.
   *
   * The word set is `college_search_words(college_search_text(name, aliases))` —
   * the SAME function [searchByName] splits the user's query with, which is the
   * point: there is one splitter, so the stored words and the query words
   * cannot be cut differently. It drops the empty strings a leading, trailing
   * or doubled separator produces, and DISTINCT collapses a word repeated
   * across the name and its aliases into the single (college, word) row the
   * primary key allows. `len` is not written here: it is a generated column, so
   * the database derives it from `word` and the length prefilter cannot be lied
   * to.
   *
   * Wholesale is the complete story in production because `colleges` is written
   * only by the ingest, so phase 2 sees the finished snapshot. A test that
   * seeds `colleges` directly must call this itself; a per-row trigger stays
   * rejected (RFC 139's rows-first, derived-state-second rule).
   */
  fun rebuildNameWords(session: SqlSession): Result<Int> {
    session.execute("DELETE FROM college_name_words").getOrElse { return Result.failure(it) }
    val sql =
      """
      INSERT INTO college_name_words (college_id, word)
      SELECT DISTINCT c.id, w
      FROM colleges c,
        LATERAL unnest(college_search_words(college_search_text(c.name, c.aliases))) AS w
      """.trimIndent()
    val written = session.execute(sql).getOrElse { return Result.failure(it) }
    // ANALYZE inside the same transaction (it is permitted in a transaction
    // block; VACUUM is not): the table was just emptied and refilled, so the
    // planner's stats describe the previous build — or, on the very first
    // ingest into a new database, an empty table. That is the one case where
    // the length prefilter's btree would look pointless to the planner.
    session.execute("ANALYZE college_name_words").getOrElse { return Result.failure(it) }
    return Result.success(written)
  }

  // ---------------------------------------------------------------------------
  // Aliases + ingest provenance (RFC 139)
  // ---------------------------------------------------------------------------

  /** The outcome of one [updateAliases] call, tallied by the ingest summary. */
  enum class AliasUpdateOutcome {
    /** The row existed with a different alias set: written, version bumped. */
    APPLIED,

    /** The row existed with this exact alias set: nothing written, no bump. */
    UNCHANGED,

    /** No college carries this `ipeds_unit_id`: counted by the caller, never fatal. */
    UNKNOWN_IPEDS_UNIT_ID,
  }

  /**
   * Applies one curated alias entry (RFC 139), change-suppressed like the
   * Scorecard upsert: the UPDATE's `aliases IS DISTINCT FROM ?` arm means an
   * unchanged alias set writes nothing and bumps nothing (the suppressed no-op
   * UPDATE also never fires the history trigger). When zero rows update, a
   * companion existence probe splits [AliasUpdateOutcome.UNCHANGED] from
   * [AliasUpdateOutcome.UNKNOWN_IPEDS_UNIT_ID] so the ingest summary can count
   * unknown `ipeds_unit_id`s precisely.
   */
  fun updateAliases(
    session: SqlSession,
    ipedsUnitId: Int,
    aliases: List<String>,
  ): Result<AliasUpdateOutcome> =
    try {
      // The alias set is bound as one jsonb parameter and expanded to text[] by
      // Postgres, not built client-side with `Connection.createArrayOf`: the
      // [SqlSession] boundary deliberately withholds the pooled connection (its
      // commit/rollback guarantee), and reaching through a returned statement to
      // recover it would make that boundary advisory. It also removes the
      // `java.sql.Array` handle entirely, so there is no `finally { free() }`
      // that could replace the real SQLException with a cleanup one.
      val sql =
        """
        UPDATE colleges
        SET aliases = ARRAY(SELECT jsonb_array_elements_text(?::jsonb)), version = version + 1
        WHERE ipeds_unit_id = ?
          AND aliases IS DISTINCT FROM ARRAY(SELECT jsonb_array_elements_text(?::jsonb))
        """.trimIndent()
      val aliasesJson = JsonArray(aliases.map { JsonPrimitive(it) }).toString()
      val updated =
        session.prepareStatement(sql).use { stmt ->
          stmt.setString(1, aliasesJson)
          stmt.setInt(2, ipedsUnitId)
          stmt.setString(3, aliasesJson)
          stmt.executeUpdate()
        }
      if (updated > 0) {
        Result.success(AliasUpdateOutcome.APPLIED)
      } else {
        session.prepareStatement("SELECT 1 FROM colleges WHERE ipeds_unit_id = ?").use { stmt ->
          stmt.setInt(1, ipedsUnitId)
          stmt.executeQuery().use { rs ->
            Result.success(if (rs.next()) AliasUpdateOutcome.UNCHANGED else AliasUpdateOutcome.UNKNOWN_IPEDS_UNIT_ID)
          }
        }
      }
    } catch (e: SQLException) {
      Result.failure(mapCollegeWriteError(e))
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  /**
   * Every college's current `version` keyed by `ipeds_unit_id` (RFC 139): the
   * ingest's pre-load snapshot, so each Scorecard upsert outcome can be split
   * into inserted / changed / unchanged for the provenance build row. ~6k rows.
   */
  fun currentVersionsByIpedsUnitId(session: SqlSession): Result<Map<Int, Int>> =
    session
      .queryList(
        "SELECT ipeds_unit_id, version FROM colleges",
        bind = {},
        map = { rs -> rs.getInt("ipeds_unit_id") to rs.getInt("version") },
      ).map { it.toMap() }

  /**
   * Every `colleges` column [nonNullCounts] may count — the closed identifier
   * allowlist (RFC 139). SQL has no identifier binding, so the boundary is this
   * set: anything outside it never becomes SQL text.
   */
  val NON_NULL_COUNTABLE_COLUMNS: Set<String> =
    setOf(
      "opeid",
      "region",
      "locale",
      "latitude",
      "longitude",
      "undergrad_enrollment_headcount",
      "admission_rate_share",
      "sat_average_equivalent_score",
      "cost_of_attendance_per_year_usd",
      "net_price_per_year_usd",
      "net_price_per_year_income_q1_usd",
      "net_price_per_year_income_q2_usd",
      "net_price_per_year_income_q3_usd",
      "net_price_per_year_income_q4_usd",
      "net_price_per_year_income_q5_usd",
      "tuition_and_fees_in_state_per_year_usd",
      "tuition_and_fees_out_of_state_per_year_usd",
      "completion_rate_150pct_4yr_share",
      "median_earnings_10y_after_entry_usd",
      "median_debt_at_completion_usd",
      "pell_share",
      "website",
    )

  /**
   * Non-null counts over the given `colleges` columns in one SELECT (RFC 139):
   * the ingest change-summary's before/after axis. [columns] must be members of
   * [NON_NULL_COUNTABLE_COLUMNS] — they are interpolated as identifiers, not
   * bound, so an unknown name is rejected here rather than reaching SQL, and
   * every accepted name is emitted double-quoted.
   */
  fun nonNullCounts(
    session: SqlSession,
    columns: List<String>,
  ): Result<Map<String, Int>> {
    val unknown = columns.toSet() - NON_NULL_COUNTABLE_COLUMNS
    require(unknown.isEmpty()) {
      "nonNullCounts: unknown colleges column(s) ${unknown.sorted()}; allowed: ${NON_NULL_COUNTABLE_COLUMNS.sorted()}"
    }
    val select = columns.joinToString(", ") { """count("$it") AS "$it"""" }
    return session.queryOne(
      "SELECT $select FROM colleges",
      bind = {},
      map = { rs -> columns.associateWith { rs.getInt(it) } },
    )
  }

  /**
   * Inserts the one `college_index_build` provenance row a successful ingest
   * run ends with (RFC 139), returning its generated id. The JSON payloads
   * arrive structured and are serialized here, at the JDBC edge, by
   * [setJsonbOrNull] binding `?::jsonb` (the [ConvosDao]/[LlmCallsDao]
   * convention — the JDBC driver has no native jsonb binding).
   */
  fun insertIndexBuild(
    session: SqlSession,
    input: NewCollegeIndexBuild,
  ): Result<UUID> {
    val sql =
      """
      INSERT INTO college_index_build (
        started_at, finished_at, sources, rows_ingested, index_rows,
        change_summary, method_version
      )
      VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?)
      RETURNING id
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setTimestamp(1, java.sql.Timestamp.from(input.startedAt))
        stmt.setTimestamp(2, java.sql.Timestamp.from(input.finishedAt))
        stmt.setJsonbOrNull(3, input.sources)
        stmt.setJsonbOrNull(4, input.rowsIngested)
        stmt.setIntOrNull(5, input.indexRows)
        stmt.setJsonbOrNull(6, input.changeSummary)
        stmt.setInt(7, input.methodVersion)
      },
      map = { rs -> UUID.fromString(rs.getString("id")) },
      mapError = ::mapCollegeWriteError,
    )
  }
}
