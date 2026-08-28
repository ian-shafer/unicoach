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
      netPriceQ1 = rs.intOrNull("net_price_q1"),
      netPriceQ2 = rs.intOrNull("net_price_q2"),
      netPriceQ3 = rs.intOrNull("net_price_q3"),
      netPriceQ4 = rs.intOrNull("net_price_q4"),
      netPriceQ5 = rs.intOrNull("net_price_q5"),
      tuitionInState = rs.intOrNull("tuition_in_state"),
      tuitionOutState = rs.intOrNull("tuition_out_state"),
      graduationRate = rs.doubleOrNull("graduation_rate"),
      medianEarnings = rs.intOrNull("median_earnings"),
      medianDebt = rs.intOrNull("median_debt"),
      pctPell = rs.doubleOrNull("pct_pell"),
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
      unitId = rs.getInt("unit_id"),
      name = rs.getString("name"),
      city = rs.getString("city"),
      state = rs.getString("state"),
      control = rs.getInt("control"),
      locale = rs.intOrNull("locale"),
      undergradEnrollment = rs.intOrNull("undergrad_enrollment"),
      admissionRate = rs.doubleOrNull("admission_rate"),
      netPrice = rs.intOrNull("net_price"),
      netPriceQ1 = rs.intOrNull("net_price_q1"),
      netPriceQ2 = rs.intOrNull("net_price_q2"),
      netPriceQ3 = rs.intOrNull("net_price_q3"),
      netPriceQ4 = rs.intOrNull("net_price_q4"),
      netPriceQ5 = rs.intOrNull("net_price_q5"),
      graduationRate = rs.doubleOrNull("graduation_rate"),
      medianEarnings = rs.intOrNull("median_earnings"),
      medianDebt = rs.intOrNull("median_debt"),
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
   * comparing every curated column as a row-tuple with `IS DISTINCT FROM`, so
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
          median_earnings, pct_pell, website, net_price_q1, net_price_q2,
          net_price_q3, net_price_q4, net_price_q5, median_debt
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
          net_price_q1 = EXCLUDED.net_price_q1,
          net_price_q2 = EXCLUDED.net_price_q2,
          net_price_q3 = EXCLUDED.net_price_q3,
          net_price_q4 = EXCLUDED.net_price_q4,
          net_price_q5 = EXCLUDED.net_price_q5,
          median_debt = EXCLUDED.median_debt,
          version = colleges.version + 1
        WHERE (
          colleges.opeid, colleges.name, colleges.city, colleges.state,
          colleges.region, colleges.locale, colleges.latitude, colleges.longitude,
          colleges.control, colleges.undergrad_enrollment, colleges.admission_rate,
          colleges.sat_avg, colleges.cost_attendance, colleges.net_price,
          colleges.tuition_in_state, colleges.tuition_out_state,
          colleges.graduation_rate, colleges.median_earnings, colleges.pct_pell,
          colleges.website, colleges.net_price_q1, colleges.net_price_q2,
          colleges.net_price_q3, colleges.net_price_q4, colleges.net_price_q5,
          colleges.median_debt, colleges.unit_id
        ) IS DISTINCT FROM (
          EXCLUDED.opeid, EXCLUDED.name, EXCLUDED.city, EXCLUDED.state,
          EXCLUDED.region, EXCLUDED.locale, EXCLUDED.latitude, EXCLUDED.longitude,
          EXCLUDED.control, EXCLUDED.undergrad_enrollment, EXCLUDED.admission_rate,
          EXCLUDED.sat_avg, EXCLUDED.cost_attendance, EXCLUDED.net_price,
          EXCLUDED.tuition_in_state, EXCLUDED.tuition_out_state,
          EXCLUDED.graduation_rate, EXCLUDED.median_earnings, EXCLUDED.pct_pell,
          EXCLUDED.website, EXCLUDED.net_price_q1, EXCLUDED.net_price_q2,
          EXCLUDED.net_price_q3, EXCLUDED.net_price_q4, EXCLUDED.net_price_q5,
          EXCLUDED.median_debt, EXCLUDED.unit_id
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
        stmt.setIntOrNull(22, input.netPriceQ1)
        stmt.setIntOrNull(23, input.netPriceQ2)
        stmt.setIntOrNull(24, input.netPriceQ3)
        stmt.setIntOrNull(25, input.netPriceQ4)
        stmt.setIntOrNull(26, input.netPriceQ5)
        stmt.setIntOrNull(27, input.medianDebt)
        stmt.setInt(28, input.unitId)
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
        c.id, c.unit_id, c.name, c.city, c.state, c.control, c.locale,
        c.undergrad_enrollment, c.admission_rate, c.net_price, c.net_price_q1,
        c.net_price_q2, c.net_price_q3, c.net_price_q4, c.net_price_q5,
        c.graduation_rate, c.median_earnings, c.median_debt, c.pct_pell, c.website,
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
   * ordering ends with the `unit_id ASC` tiebreak for a total, deterministic
   * order (`name` is not unique, so NAME_ASC needs the tiebreak too).
   */
  private fun orderBy(sortBy: CollegeQuery.SortBy): String =
    when (sortBy) {
      CollegeQuery.SortBy.ENROLLMENT_DESC -> "c.undergrad_enrollment DESC NULLS LAST"
      CollegeQuery.SortBy.ADMISSION_RATE_ASC -> "c.admission_rate ASC NULLS LAST"
      CollegeQuery.SortBy.NET_PRICE_ASC -> "c.net_price ASC NULLS LAST"
      CollegeQuery.SortBy.GRADUATION_RATE_DESC -> "c.graduation_rate DESC NULLS LAST"
      CollegeQuery.SortBy.NAME_ASC -> "c.name ASC NULLS LAST"
    } + ", c.unit_id ASC"

  /**
   * Student-facing fuzzy name search (RFC 137 boundary, RFC 139 matching): a
   * three-arm OR over `search_text = college_search_text(name, aliases)` (the
   * 0051 IMMUTABLE expression the trigram GIN index is built on):
   *
   * 1. `search_text % ?` — whole-string trigram similarity at
   *    [SIMILARITY_THRESHOLD]: catches typos of full-ish names ("Amhurst
   *    Colege").
   * 2. `? <% search_text` — word similarity at [WORD_SIMILARITY_THRESHOLD]: catches
   *    fragments and nicknames ("Mizzou", "UMass Amherst"), which score far too
   *    low on whole-string similarity against a long search text. Verified
   *    empirically against the real dataset: the `%` arm alone finds nothing
   *    for "Mizzou"; this arm scores it 1.0.
   * 3. `search_text ILIKE '%'||?||'%'` — the escaped-literal substring arm,
   *    kept on merit for short fragments ("Amh") that trigram thresholds miss;
   *    ranging over the search text (not bare `name`) means a short alias
   *    fragment ("Miz") also matches.
   *
   * All three arms range over the indexed expression, so the whole OR is
   * served by `colleges_search_text_trgm_idx` (`gin_trgm_ops` supports `%`,
   * `<%`, and `ILIKE`) — no arm forces a seq scan. The trgm arms take the raw
   * trimmed query (trigrams ignore LIKE metacharacters); only the ILIKE arms
   * take the escaped one, so `%`/`_`/`\` still match literally there.
   *
   * Ranking: exact-prefix-of-name first (RFC 137 behaviour preserved), then
   * `word_similarity(?, search_text)` DESC (chosen over `similarity()` for the
   * same fragment reason as arm 2), then `undergrad_enrollment DESC NULLS
   * LAST, name, unit_id` as the deterministic tail. [limit] is clamped by the
   * service boundary before reaching here (the [search] convention).
   *
   * The two trigram bounds are owned here, not inherited: every call first
   * pins [SIMILARITY_THRESHOLD] and [WORD_SIMILARITY_THRESHOLD] with `SET
   * LOCAL` in the caller's transaction, so what search returns cannot drift
   * with a server- or role-level `pg_trgm.*` default between dev, CI and RDS.
   * `SET LOCAL` reverts at commit, so no other query on the pooled connection
   * sees them, and the operators stay index-backed (the thresholds are read by
   * the same `gin_trgm_ops` operators, not written into the predicate).
   */
  fun searchByName(
    session: SqlSession,
    query: String,
    limit: Int,
  ): Result<List<CollegeSummary>> {
    pinTrigramThresholds(session).getOrElse { return Result.failure(it) }
    val escaped = escapeLikePattern(query)
    val sql =
      """
      SELECT id, name, city, state
      FROM colleges
      WHERE college_search_text(name, aliases) % ?
         OR ? <% college_search_text(name, aliases)
         OR college_search_text(name, aliases) ILIKE '%' || ? || '%'
      ORDER BY (name ILIKE ? || '%') DESC,
        word_similarity(?, college_search_text(name, aliases)) DESC,
        undergrad_enrollment DESC NULLS LAST, name, unit_id
      LIMIT ?
      """.trimIndent()
    return session.queryList(
      sql,
      bind = { stmt ->
        // The raw/escaped split is load-bearing and positional: the two trigram
        // arms (1, 2) and the similarity ORDER BY (5) take the RAW query, because
        // trigrams treat `%`/`_` as ordinary characters; only the ILIKE arms
        // (3, 4) take the ESCAPED one, so a literal `%` in a school's name
        // cannot act as a wildcard. Swapping a raw for an escaped binding here
        // silently changes what matches, and no test of a metacharacter-free
        // query would notice.
        stmt.setString(1, query)
        stmt.setString(2, query)
        stmt.setString(3, escaped)
        stmt.setString(4, escaped)
        stmt.setString(5, query)
        stmt.setInt(6, limit)
      },
      map = ::mapSummary,
    )
  }

  /**
   * The `%` arm's whole-string trigram bound (RFC 139). Postgres' own default
   * value, but owned here rather than inherited: [searchByName] pins it per
   * call so recall is a property of this code, not of cluster config.
   */
  const val SIMILARITY_THRESHOLD = 0.3

  /**
   * The `<%` arm's word-similarity bound (RFC 139), pinned per call for the
   * same reason as [SIMILARITY_THRESHOLD]. This is the arm nicknames match on
   * ("Mizzou"), so a drifted server default would silently change what
   * students find.
   */
  const val WORD_SIMILARITY_THRESHOLD = 0.6

  /**
   * Pins the two `pg_trgm` thresholds for the remainder of the caller's
   * transaction. `SET LOCAL` is transaction-scoped, so it neither leaks onto
   * the pooled connection nor requires a cluster/database-level `ALTER`; the
   * values are compile-time constants, never caller text.
   */
  private fun pinTrigramThresholds(session: SqlSession): Result<Unit> =
    try {
      session
        .prepareStatement(
          "SET LOCAL pg_trgm.similarity_threshold = $SIMILARITY_THRESHOLD",
        ).use { it.execute() }
      session
        .prepareStatement(
          "SET LOCAL pg_trgm.word_similarity_threshold = $WORD_SIMILARITY_THRESHOLD",
        ).use { it.execute() }
      Result.success(Unit)
    } catch (e: SQLException) {
      Result.failure(mapDatabaseError(e))
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
  // Aliases + ingest provenance (RFC 139)
  // ---------------------------------------------------------------------------

  /** The outcome of one [updateAliases] call, tallied by the ingest summary. */
  enum class AliasUpdateOutcome {
    /** The row existed with a different alias set: written, version bumped. */
    APPLIED,

    /** The row existed with this exact alias set: nothing written, no bump. */
    UNCHANGED,

    /** No college carries this `unit_id`: counted by the caller, never fatal. */
    UNKNOWN_UNIT_ID,
  }

  /**
   * Applies one curated alias entry (RFC 139), change-suppressed like the
   * Scorecard upsert: the UPDATE's `aliases IS DISTINCT FROM ?` arm means an
   * unchanged alias set writes nothing and bumps nothing (the suppressed no-op
   * UPDATE also never fires the history trigger). When zero rows update, a
   * companion existence probe splits [AliasUpdateOutcome.UNCHANGED] from
   * [AliasUpdateOutcome.UNKNOWN_UNIT_ID] so the ingest summary can count
   * unknown `unit_id`s precisely.
   */
  fun updateAliases(
    session: SqlSession,
    unitId: Int,
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
        WHERE unit_id = ?
          AND aliases IS DISTINCT FROM ARRAY(SELECT jsonb_array_elements_text(?::jsonb))
        """.trimIndent()
      val aliasesJson = JsonArray(aliases.map { JsonPrimitive(it) }).toString()
      val updated =
        session.prepareStatement(sql).use { stmt ->
          stmt.setString(1, aliasesJson)
          stmt.setInt(2, unitId)
          stmt.setString(3, aliasesJson)
          stmt.executeUpdate()
        }
      if (updated > 0) {
        Result.success(AliasUpdateOutcome.APPLIED)
      } else {
        session.prepareStatement("SELECT 1 FROM colleges WHERE unit_id = ?").use { stmt ->
          stmt.setInt(1, unitId)
          stmt.executeQuery().use { rs ->
            Result.success(if (rs.next()) AliasUpdateOutcome.UNCHANGED else AliasUpdateOutcome.UNKNOWN_UNIT_ID)
          }
        }
      }
    } catch (e: SQLException) {
      Result.failure(mapCollegeError(e))
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  /**
   * Every college's current `version` keyed by `unit_id` (RFC 139): the
   * ingest's pre-load snapshot, so each Scorecard upsert outcome can be split
   * into inserted / changed / unchanged for the provenance build row. ~6k rows.
   */
  fun currentVersionsByUnitId(session: SqlSession): Result<Map<Int, Int>> =
    session
      .queryList(
        "SELECT unit_id, version FROM colleges",
        bind = {},
        map = { rs -> rs.getInt("unit_id") to rs.getInt("version") },
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
      "undergrad_enrollment",
      "admission_rate",
      "sat_avg",
      "cost_attendance",
      "net_price",
      "net_price_q1",
      "net_price_q2",
      "net_price_q3",
      "net_price_q4",
      "net_price_q5",
      "tuition_in_state",
      "tuition_out_state",
      "graduation_rate",
      "median_earnings",
      "median_debt",
      "pct_pell",
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
      mapError = ::mapCollegeError,
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
      "23503" -> {
        NotFoundException("Referenced college not found")
      }

      "23505", "23514" -> {
        val serverError = (e as? PSQLException)?.serverErrorMessage
        ConstraintViolationException(e, serverError?.constraint, serverError?.detail)
      }

      else -> {
        mapDatabaseError(e)
      }
    }
}
