package ed.unicoach.db.dao

import ed.unicoach.db.models.College
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeMatch
import ed.unicoach.db.models.CollegeProgram
import ed.unicoach.db.models.CollegeProgramId
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.CollegeSearchOutcome
import ed.unicoach.db.models.CollegeSearchPage
import ed.unicoach.db.models.CollegeSummary
import ed.unicoach.db.models.InstitutionControl
import ed.unicoach.db.models.InstitutionSector
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeIndexBuild
import ed.unicoach.db.models.NewCollegeProgram
import ed.unicoach.db.models.Version
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.UUID

/**
 * The DEFAULT searchable universe (RFC 150 D52/D56) — ONE home for its three
 * axes, over `college_search_index`.
 *
 * It was written twice and the copies disagreed: the percentile pass ranked
 * over a corpus that still contained the administrative units the default
 * search drops, so a percentile described a population no student searches.
 * Both readers now take the same words. [CollegesDao.search] uses one [Axis] at
 * a time, because a caller may override any of them; the percentile pass, which
 * has no caller to override it, takes [sql] whole.
 *
 * Each axis RENDERS itself — under a table alias, and where a caller may invert
 * it, negated. It used to be three finished predicate strings that a reader
 * rewrote: the alias was pasted on the front and `NOT ` before that. Both only
 * work while an axis happens to be shaped like a bare column, so
 * `NOT is_four_year IS NOT FALSE` and `i.sector IS DISTINCT FROM ...` were one
 * edit apart — a predicate silently meaning something else, in SQL no compiler
 * reads. Nothing here is caller text — the sector word is [InstitutionSector]'s
 * own constant — so no axis carries a bind parameter at all.
 */
private object DefaultUniverse {
  /**
   * One axis of the default universe: the column it reads and the predicate it
   * states about that column, both rendered by the axis itself.
   *
   * [negated] exists only where a caller may state the opposite; an axis with
   * no negation cannot be asked for one, because there is no correct text to
   * return and a wrong one would compile.
   */
  class Axis(
    private val column: String,
    private val predicate: (String) -> String,
    private val negation: ((String) -> String)? = null,
  ) {
    /**
     * The predicate, reading [column] through [prefix] — the table alias, empty
     * when the index is the only table in scope, `i.` when it is joined.
     */
    fun sql(prefix: String = ""): String = predicate("$prefix$column")

    /** The OPPOSITE of [sql], for the one axis a caller may invert. */
    fun negated(prefix: String = ""): String = (negation ?: error("[$column] is not an invertible universe axis"))("$prefix$column")
  }

  /** A closed school is not a school a student can apply to. */
  val ACTIVE = Axis("is_active", { it }, { "NOT $it" })

  /** An unknown LEVEL is INCLUDED; only a school KNOWN to be two-year is out. */
  val FOUR_YEAR = Axis("is_four_year", { "$it IS NOT FALSE" })

  /**
   * A university system's central office is not one of its campuses. An unknown
   * sector is kept, which is what `IS DISTINCT FROM` buys over `<>`.
   */
  val NOT_ADMINISTRATIVE =
    Axis("sector", { "$it IS DISTINCT FROM '${InstitutionSector.ADMINISTRATIVE_UNIT.value}'" })

  /** Every axis, in the order [sql] states them. */
  private val AXES = listOf(ACTIVE, FOUR_YEAR, NOT_ADMINISTRATIVE)

  /** All three axes at once, for a reader that cannot override any of them. */
  fun sql(prefix: String = ""): String = AXES.joinToString(" AND ") { it.sql(prefix) }
}

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
  private fun ResultSet.getStringList(column: String): List<String> = getStringListOrNull(column) ?: emptyList()

  /**
   * [getStringList] where a SQL NULL is a FACT rather than an absence: null
   * back, not an empty list. `program_titles` is the one such column — a page
   * with no program filter reports nothing about programs, which is a different
   * statement from "no program matched".
   */
  private fun ResultSet.getStringListOrNull(column: String): List<String>? {
    val arr = getArray(column) ?: return null
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
      housingAndFoodOnCampusPerYearUsd = rs.intOrNull("housing_and_food_on_campus_per_year_usd"),
      housingAndFoodOffCampusPerYearUsd = rs.intOrNull("housing_and_food_off_campus_per_year_usd"),
      booksAndSuppliesPerYearUsd = rs.intOrNull("books_and_supplies_per_year_usd"),
      otherExpensesOnCampusPerYearUsd = rs.intOrNull("other_expenses_on_campus_per_year_usd"),
      otherExpensesOffCampusPerYearUsd = rs.intOrNull("other_expenses_off_campus_per_year_usd"),
      otherExpensesWithFamilyPerYearUsd = rs.intOrNull("other_expenses_with_family_per_year_usd"),
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
    val titles = rs.getStringListOrNull("program_titles")
    return CollegeMatch(
      id = CollegeId(UUID.fromString(rs.getString("id"))),
      ipedsUnitId = rs.getInt("ipeds_unit_id"),
      name = rs.getString("name"),
      city = rs.getString("city"),
      state = rs.getString("state"),
      control = rs.getString("control"),
      region = rs.getString("region"),
      locale = rs.getString("locale"),
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
      ipedsSurveyYear = rs.intOrNull("ipeds_survey_year"),
      programsCensusSurveyYear = rs.intOrNull("programs_census_survey_year"),
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
          net_price_per_year_income_q3_usd, net_price_per_year_income_q4_usd, net_price_per_year_income_q5_usd, median_debt_at_completion_usd,
          housing_and_food_on_campus_per_year_usd, housing_and_food_off_campus_per_year_usd, books_and_supplies_per_year_usd,
          other_expenses_on_campus_per_year_usd, other_expenses_off_campus_per_year_usd, other_expenses_with_family_per_year_usd
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
          housing_and_food_on_campus_per_year_usd = EXCLUDED.housing_and_food_on_campus_per_year_usd,
          housing_and_food_off_campus_per_year_usd = EXCLUDED.housing_and_food_off_campus_per_year_usd,
          books_and_supplies_per_year_usd = EXCLUDED.books_and_supplies_per_year_usd,
          other_expenses_on_campus_per_year_usd = EXCLUDED.other_expenses_on_campus_per_year_usd,
          other_expenses_off_campus_per_year_usd = EXCLUDED.other_expenses_off_campus_per_year_usd,
          other_expenses_with_family_per_year_usd = EXCLUDED.other_expenses_with_family_per_year_usd,
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
          colleges.median_debt_at_completion_usd,
          colleges.housing_and_food_on_campus_per_year_usd, colleges.housing_and_food_off_campus_per_year_usd,
          colleges.books_and_supplies_per_year_usd, colleges.other_expenses_on_campus_per_year_usd,
          colleges.other_expenses_off_campus_per_year_usd, colleges.other_expenses_with_family_per_year_usd,
          colleges.ipeds_unit_id
        ) IS DISTINCT FROM (
          EXCLUDED.opeid, EXCLUDED.name, EXCLUDED.city, EXCLUDED.state,
          EXCLUDED.region, EXCLUDED.locale, EXCLUDED.latitude, EXCLUDED.longitude,
          EXCLUDED.control, EXCLUDED.undergrad_enrollment_headcount, EXCLUDED.admission_rate_share,
          EXCLUDED.sat_average_equivalent_score, EXCLUDED.cost_of_attendance_per_year_usd, EXCLUDED.net_price_per_year_usd,
          EXCLUDED.tuition_and_fees_in_state_per_year_usd, EXCLUDED.tuition_and_fees_out_of_state_per_year_usd,
          EXCLUDED.completion_rate_150pct_4yr_share, EXCLUDED.median_earnings_10y_after_entry_usd, EXCLUDED.pell_share,
          EXCLUDED.website, EXCLUDED.net_price_per_year_income_q1_usd, EXCLUDED.net_price_per_year_income_q2_usd,
          EXCLUDED.net_price_per_year_income_q3_usd, EXCLUDED.net_price_per_year_income_q4_usd, EXCLUDED.net_price_per_year_income_q5_usd,
          EXCLUDED.median_debt_at_completion_usd,
          EXCLUDED.housing_and_food_on_campus_per_year_usd, EXCLUDED.housing_and_food_off_campus_per_year_usd,
          EXCLUDED.books_and_supplies_per_year_usd, EXCLUDED.other_expenses_on_campus_per_year_usd,
          EXCLUDED.other_expenses_off_campus_per_year_usd, EXCLUDED.other_expenses_with_family_per_year_usd,
          EXCLUDED.ipeds_unit_id
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
        // The ordinal is the CURSOR's, never a literal: a column added below is
        // one line, and the trailing `WHERE ipeds_unit_id = ?` cannot drift out
        // of step with the placeholder list above it. Hand-counted indices were
        // exactly what the old comment here admitted to -- "every column added
        // above shifts it" -- with nothing but a second count to keep them true.
        var ordinal = 0

        fun next(): Int = ++ordinal

        stmt.setInt(next(), input.ipedsUnitId)
        stmt.setStringOrNull(next(), input.opeid)
        stmt.setString(next(), input.name)
        stmt.setString(next(), input.city)
        stmt.setString(next(), input.state)
        stmt.setIntOrNull(next(), input.region)
        stmt.setIntOrNull(next(), input.locale)
        stmt.setDoubleOrNull(next(), input.latitude)
        stmt.setDoubleOrNull(next(), input.longitude)
        stmt.setInt(next(), input.control)
        stmt.setIntOrNull(next(), input.undergradEnrollmentHeadcount)
        stmt.setDoubleOrNull(next(), input.admissionRateShare)
        stmt.setIntOrNull(next(), input.satAverageEquivalentScore)
        stmt.setIntOrNull(next(), input.costOfAttendancePerYearUsd)
        stmt.setIntOrNull(next(), input.netPricePerYearUsd)
        stmt.setIntOrNull(next(), input.tuitionAndFeesInStatePerYearUsd)
        stmt.setIntOrNull(next(), input.tuitionAndFeesOutOfStatePerYearUsd)
        stmt.setDoubleOrNull(next(), input.completionRate150pct4yrShare)
        stmt.setIntOrNull(next(), input.medianEarnings10yAfterEntryUsd)
        stmt.setDoubleOrNull(next(), input.pellShare)
        stmt.setStringOrNull(next(), input.website)
        stmt.setIntOrNull(next(), input.netPricePerYearIncomeQ1Usd)
        stmt.setIntOrNull(next(), input.netPricePerYearIncomeQ2Usd)
        stmt.setIntOrNull(next(), input.netPricePerYearIncomeQ3Usd)
        stmt.setIntOrNull(next(), input.netPricePerYearIncomeQ4Usd)
        stmt.setIntOrNull(next(), input.netPricePerYearIncomeQ5Usd)
        stmt.setIntOrNull(next(), input.medianDebtAtCompletionUsd)
        stmt.setIntOrNull(next(), input.housingAndFoodOnCampusPerYearUsd)
        stmt.setIntOrNull(next(), input.housingAndFoodOffCampusPerYearUsd)
        stmt.setIntOrNull(next(), input.booksAndSuppliesPerYearUsd)
        stmt.setIntOrNull(next(), input.otherExpensesOnCampusPerYearUsd)
        stmt.setIntOrNull(next(), input.otherExpensesOffCampusPerYearUsd)
        stmt.setIntOrNull(next(), input.otherExpensesWithFamilyPerYearUsd)
        // The UNION ALL arm's own `WHERE ipeds_unit_id = ?` -- positionally last.
        stmt.setInt(next(), input.ipedsUnitId)
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
   * Structured filtering over `college_search_index` (RFC 150 D53/D60).
   *
   * **Filtering, sorting and counting touch the index and nothing else.** Every
   * `WHERE` clause the vocabulary can build, every unknown-count `FILTER` arm
   * and every `ORDER BY` key resolves inside one table with no join at all —
   * the hot path that reads ~6,300 rows to find 142 got narrower, not wider.
   * The `college_programs` join and the twelve `colleges` filter clauses this
   * function used to carry are DELETED, not kept alongside.
   *
   * **Only the returned page reaches the source of truth.** After `LIMIT`, at
   * most 25 rows join back to `colleges` for the payload (city, the money and
   * outcome fields, website), to `college_ipeds` for its `survey_year`, and to
   * a LATERAL over `college_programs_census`/`cip_codes` for the matched
   * program titles and the census vintage. Sixteen duplicated columns would
   * have been sixteen chances for two tables to disagree.
   *
   * **No code-to-word step remains.** `control`, `region`, `locale` and the
   * attribute slugs come off the index already in the vocabulary the result
   * speaks (D61), so every value bound here is the word the model said.
   *
   * The count is ONE statement of `FILTER` aggregates (D55): the total, plus
   * one `excluded_unknown` arm per supplied filter over a nullable column,
   * evaluated against the DEFAULT UNIVERSE rather than against the other
   * filters — so the number answers "how many schools could not be judged on
   * this axis" and not an order-dependent residue. Not N extra round trips.
   *
   * Every value is bound as a parameter — no filter value is interpolated into
   * SQL text.
   */
  fun search(
    session: SqlSession,
    query: CollegeQuery,
  ): Result<CollegeSearchOutcome> {
    // An UNBUILT index answers everything with zero, and a zero is the one
    // answer that cannot be told apart from a real one (RFC 150). Checked
    // BEFORE the query, so nothing downstream has to interpret an empty page.
    if (!isSearchIndexBuilt(session).getOrElse { return Result.failure(it) }) {
      return Result.success(CollegeSearchOutcome.IndexNotBuilt)
    }

    // The program filter is expanded to a real 6-digit code set BEFORE anything
    // is matched, so "5116" — a CIP series the 2023 vocabulary does not carry —
    // is a named REFUSAL rather than a silent empty result (D54). The refusal is
    // a successful outcome: the database did not fail, the word did.
    val programCodes =
      when (val expansion = expandProgramCodes(session, query).getOrElse { return Result.failure(it) }) {
        is ProgramExpansion.Unresolvable -> return Result.success(expansion.refusal)
        is ProgramExpansion.Codes -> expansion.programs
      }

    val plan = createSearchPlan(query, programCodes)

    val matches =
      listMatches(session, query, plan, programCodes.matchedCodes).getOrElse { return Result.failure(it) }

    return countPage(session, plan, matches)
  }

  /**
   * The whole predicate of one search, as ONE value both statements consume.
   *
   * Everything the `WHERE` of the page query and the `FILTER` arms of the count
   * query are built from lives here — nothing else in this file may write a
   * search predicate. That is the point: the two statements used to be handed
   * a clause string and a separate binder list, and the rule that the second
   * had to stay in step with the first was written only in comments. Adding a
   * filter to one statement and not the other, or binding in a different order,
   * compiled and returned wrong answers. Now [SearchPlan] emits both the text
   * and the binds from the same list, so they cannot disagree.
   */
  private fun createSearchPlan(
    query: CollegeQuery,
    programCodes: ExpandedPrograms,
  ): SearchPlan {
    val universe = mutableListOf<String>()

    // The default universe is a default, not a wall (D56): all three axes are
    // overridable per call, and every one of them is [DefaultUniverse]'s own
    // words rather than a second copy of them. An overriding caller states a
    // boolean, so no clause here binds a parameter.
    query.isActive?.let { active ->
      universe += if (active) DefaultUniverse.ACTIVE.sql() else DefaultUniverse.ACTIVE.negated()
    }
    // Only the DEFAULT belongs in the universe. An EXPLICIT level is an ordinary
    // filter over a nullable column, so it reports how many colleges it could
    // not judge instead of silently reading unknown as "no" (D55) — see below.
    if (query.isFourYear == null) universe += DefaultUniverse.FOUR_YEAR.sql()
    if (!query.includeAdministrativeUnits) universe += DefaultUniverse.NOT_ADMINISTRATIVE.sql()

    val filters = mutableListOf<IndexFilter>()

    query.isFourYear?.let { fourYear ->
      filters += IndexFilter("is_four_year = ?", listOf(booleanBinder(fourYear)), UnknownAxis("is_four_year"))
    }

    query.states?.let { states ->
      if (states.isNotEmpty()) {
        // NOT NULL on the index, so there is no unknown to exclude or report.
        filters += IndexFilter("state = ANY ($TEXT_ARRAY_PARAM)", listOf(jsonbArrayBinder(states)))
      }
    }
    query.region?.let { region ->
      filters += IndexFilter("region = ?", listOf(stringBinder(region)), UnknownAxis("region"))
    }
    query.locales?.let { locales ->
      if (locales.isNotEmpty()) {
        filters +=
          IndexFilter("locale = ANY ($TEXT_ARRAY_PARAM)", listOf(jsonbArrayBinder(locales)), UnknownAxis("locale"))
      }
    }
    query.control?.let { control ->
      if (control.isNotEmpty()) {
        // The word is produced HERE, from the enum the query carries: the index
        // column stores [InstitutionControl]'s own label under a CHECK, so the
        // label is the bind and no unvalidated string can reach it.
        filters +=
          IndexFilter("control = ANY ($TEXT_ARRAY_PARAM)", listOf(jsonbArrayBinder(control.map { it.label })))
      }
    }
    query.minUndergradEnrollmentHeadcount?.let { min ->
      filters +=
        IndexFilter(
          "undergrad_enrollment_headcount >= ?",
          listOf(intBinder(min)),
          UnknownAxis("undergrad_enrollment_headcount"),
        )
    }
    query.maxUndergradEnrollmentHeadcount?.let { max ->
      filters +=
        IndexFilter(
          "undergrad_enrollment_headcount <= ?",
          listOf(intBinder(max)),
          UnknownAxis("undergrad_enrollment_headcount"),
        )
    }
    query.minAdmissionRateShare?.let { min ->
      filters += IndexFilter("admission_rate_share >= ?", listOf(doubleBinder(min)), UnknownAxis("admission_rate_share"))
    }
    query.maxAdmissionRateShare?.let { max ->
      filters += IndexFilter("admission_rate_share <= ?", listOf(doubleBinder(max)), UnknownAxis("admission_rate_share"))
    }
    query.maxNetPricePerYearUsd?.let { max ->
      filters += IndexFilter("net_price_per_year_usd <= ?", listOf(intBinder(max)), UnknownAxis("net_price_per_year_usd"))
    }
    query.minCompletionRate150pct4yrShare?.let { min ->
      filters +=
        IndexFilter(
          "completion_rate_150pct_4yr_share >= ?",
          listOf(doubleBinder(min)),
          UnknownAxis("completion_rate_150pct_4yr_share"),
        )
    }
    query.testPolicy?.let { slug ->
      filters += IndexFilter("test_policy = ?", listOf(stringBinder(slug)), UnknownAxis("test_policy"))
    }
    query.religiousAffiliation?.let { slug ->
      filters += IndexFilter("religious_affiliation = ?", listOf(stringBinder(slug)), UnknownAxis("religious_affiliation"))
    }
    query.carnegieClass?.let { slug ->
      filters += IndexFilter("carnegie_class = ?", listOf(stringBinder(slug)), UnknownAxis("carnegie_class"))
    }
    query.carnegieSize?.let { slug ->
      filters += IndexFilter("carnegie_size = ?", listOf(stringBinder(slug)), UnknownAxis("carnegie_size"))
    }
    query.athleticAssociation?.let { slug ->
      // Unjudgeable is NULL — nothing was reported about this college's
      // associations. An EMPTY array is the KNOWN answer "it belongs to none",
      // which is a judged NO and must never be counted as unknown: most of the
      // country belongs to no athletic association, so the sentinel this column
      // used to carry made `excluded_unknown` a number in the thousands.
      filters +=
        IndexFilter(
          "athletic_associations @> ARRAY[?]::slug[]",
          listOf(stringBinder(slug)),
          UnknownAxis("athletic_associations"),
        )
    }
    query.hasRotc?.let { value ->
      filters += IndexFilter("has_rotc = ?", listOf(booleanBinder(value)), UnknownAxis("has_rotc"))
    }
    query.hasStudyAbroad?.let { value ->
      filters += IndexFilter("has_study_abroad = ?", listOf(booleanBinder(value)), UnknownAxis("has_study_abroad"))
    }
    query.hasHousing?.let { value ->
      filters += IndexFilter("offers_housing = ?", listOf(booleanBinder(value)), UnknownAxis("offers_housing"))
    }
    query.subject?.let { slug ->
      // The taxonomy expansion is MATERIALISED on the index (D51), so a subject
      // is one GIN containment test, not a prefix join over the census.
      //
      // Unjudgeable is "this college reported NO programs at all", which the
      // rebuild writes as a NULL `subject_slugs` (it is NULL exactly when
      // `cip_codes` is). An EMPTY `subject_slugs` beside a non-empty
      // `cip_codes` is a judged NO — the programs are known and none of them is
      // this subject — and counting it as unknown would overstate the number a
      // coach reads aloud on the one axis this slice was built for.
      filters +=
        IndexFilter(
          "subject_slugs @> ARRAY[?]::slug[]",
          listOf(stringBinder(slug)),
          UnknownAxis("subject_slugs"),
        )
    }
    if (programCodes.cipPrefixCodes != null) {
      val codes = programCodes.cipPrefixCodes
      filters +=
        IndexFilter(
          "cip_codes && $TEXT_ARRAY_PARAM",
          listOf(jsonbArrayBinder(codes)),
          UnknownAxis("cip_codes"),
        )
    }

    return SearchPlan(universe, filters)
  }

  /**
   * Has the `search-index` phase ever run against this database?
   *
   * TWO facts, because either one alone lies. Rows in the table settle it
   * outright; a `college_index_build` row carrying a non-null
   * `search_index_rows` settles the honest empty case — a database whose
   * `colleges` table really is empty has a BUILT index with no rows, and that
   * search should answer zero. Neither present means the migration has landed
   * and no ingest has followed it: every search would report zero out of a full
   * database.
   */
  fun isSearchIndexBuilt(session: SqlSession): Result<Boolean> =
    session.queryOne(
      """
      SELECT (EXISTS (SELECT 1 FROM college_search_index)
           OR EXISTS (SELECT 1 FROM college_index_build WHERE search_index_rows IS NOT NULL)) AS built
      """.trimIndent(),
      bind = {},
      map = { rs -> rs.getBoolean("built") },
    )

  /**
   * The page itself: at most `limit` index rows, then the join back to the
   * source of truth for the payload (see [search]).
   */
  private fun listMatches(
    session: SqlSession,
    query: CollegeQuery,
    plan: SearchPlan,
    matchedCodes: List<String>?,
  ): Result<List<CollegeMatch>> {
    // The titles LATERAL is restricted to the filter's expanded code set when
    // there is one, so `programs` keeps its meaning: the titles that matched
    // YOUR program filter. With NO program filter the column is SQL NULL — there
    // is nothing to report, which is not the same claim as the empty array's
    // "your filter matched none of this college's programs", and the boundary
    // omits the key rather than printing `programs: []` on every search. Titles
    // are never stored on the index — one join over a 1,710-row table for at
    // most 25 rows beats a second place for a CIP title to live.
    val titlesSelect =
      if (matchedCodes == null) "NULL::text[]" else "coalesce(array_agg(cc.title ORDER BY cc.code), ARRAY[]::text[])"
    val censusRestriction = if (matchedCodes == null) "" else "AND pc.cip_code = ANY ($TEXT_ARRAY_PARAM)"

    val sql =
      """
      SELECT
        i.college_id AS id, i.ipeds_unit_id, i.name, i.state, i.control, i.region, i.locale,
        i.undergrad_enrollment_headcount, i.admission_rate_share, i.net_price_per_year_usd,
        i.completion_rate_150pct_4yr_share,
        c.city, c.net_price_per_year_income_q1_usd, c.net_price_per_year_income_q2_usd,
        c.net_price_per_year_income_q3_usd, c.net_price_per_year_income_q4_usd,
        c.net_price_per_year_income_q5_usd, c.median_earnings_10y_after_entry_usd,
        c.median_debt_at_completion_usd, c.pell_share, c.website,
        ci.survey_year AS ipeds_survey_year,
        t.titles AS program_titles,
        t.census_year AS programs_census_survey_year
      FROM (
        SELECT
          college_id, ipeds_unit_id, name, state, control, region, locale,
          undergrad_enrollment_headcount, admission_rate_share, net_price_per_year_usd,
          completion_rate_150pct_4yr_share
        FROM college_search_index
        ${plan.whereClause}
        ORDER BY ${orderBy(query.sortBy, "")}
        LIMIT ?
      ) i
      JOIN colleges c ON c.id = i.college_id
      LEFT JOIN college_ipeds ci ON ci.ipeds_unit_id = i.ipeds_unit_id
      LEFT JOIN LATERAL (
        SELECT $titlesSelect AS titles, max(pc.survey_year) AS census_year
        FROM college_programs_census pc
        JOIN cip_codes cc ON cc.code = pc.cip_code
        WHERE pc.college_id = i.college_id
        $censusRestriction
      ) t ON TRUE
      ORDER BY ${orderBy(query.sortBy, "i.")}
      """.trimIndent()

    return session.queryList(
      sql,
      bind = { stmt ->
        var idx = plan.bindPredicate(stmt)
        stmt.setInt(idx++, query.limit)
        if (matchedCodes != null) jsonbArrayBinder(matchedCodes)(stmt, idx++)
      },
      map = ::mapMatch,
    )
  }

  /**
   * The counts, and the finished page. ONE statement (D55): the honest
   * population total plus one `excluded_unknown` arm per supplied filter whose
   * column can be unknown. The arms read the UNIVERSE, not the other filters.
   */
  private fun countPage(
    session: SqlSession,
    plan: SearchPlan,
    matches: List<CollegeMatch>,
  ): Result<CollegeSearchOutcome> {
    val countSelects =
      buildList {
        add("count(*) FILTER (WHERE ${plan.matchClause}) AS total")
        plan.unknownAxes.forEach { axis ->
          add("count(*) FILTER (WHERE ${plan.universeClause} AND ${axis.condition}) AS ${axis.countColumn}")
        }
      }
    val countSql = "SELECT ${countSelects.joinToString(", ")} FROM college_search_index"

    return session
      .queryOne(
        countSql,
        // The unknown arms restate the universe, which [SearchPlan] guarantees
        // binds nothing, so the predicate binds are the whole statement's binds
        // — the same call, in the same order, as the page query above.
        bind = { stmt -> plan.bindPredicate(stmt) },
        map = { rs ->
          val total = rs.getInt("total")
          val excluded = plan.unknownAxes.associate { axis -> axis.key to rs.getInt(axis.countColumn) }
          total to excluded
        },
      ).map { (total, excluded) ->
        CollegeSearchOutcome.Page(
          CollegeSearchPage(
            matches = matches,
            totalMatches = total,
            excludedUnknown = excluded,
            sourceYears = sourceYears(matches),
          ),
        )
      }
  }

  /**
   * One search's predicate: the clause TEXT and the binds that feed it, from
   * ONE list, so the page query and the count query cannot desynchronise.
   *
   * A statement takes [whereClause] or [matchClause] and then calls
   * [bindPredicate]; there is no way to obtain the text without the matching
   * binder sequence, and no second place that flattens the filters into a
   * binder list of its own.
   */
  private class SearchPlan(
    private val universe: List<String>,
    private val filters: List<IndexFilter>,
  ) {
    init {
      // The count query restates the universe once per unknown arm WITHOUT
      // rebinding it. That is only sound while no universe fragment carries a
      // parameter, so the plan refuses to exist if one ever does.
      require(universe.none { "?" in it }) { "a universe fragment may not bind a parameter" }
    }

    /** The DEFAULT-universe predicate alone: the corpus an unknown count is measured against. */
    val universeClause: String = if (universe.isEmpty()) "TRUE" else universe.joinToString(" AND ")

    /** The universe AND every filter: the rows the search matches. */
    val matchClause: String =
      (universe + filters.map { it.clause }).let { if (it.isEmpty()) "TRUE" else it.joinToString(" AND ") }

    /** [matchClause] as a `WHERE`, or nothing at all when the search is unrestricted. */
    val whereClause: String = if (universe.isEmpty() && filters.isEmpty()) "" else "WHERE $matchClause"

    /** The unjudgeable axes to report, one `excluded_unknown` key each, in select-list order. */
    val unknownAxes: List<UnknownAxis> = filters.mapNotNull { it.unknown }.distinctBy { it.key }

    /**
     * Binds every parameter [matchClause] and [whereClause] carry, starting at
     * [from], and returns the NEXT free index for whatever the statement adds
     * of its own (a `LIMIT`, a LATERAL restriction).
     */
    fun bindPredicate(
      stmt: PreparedStatement,
      from: Int = 1,
    ): Int {
      var idx = from
      filters.forEach { filter -> filter.binders.forEach { bind -> bind(stmt, idx++) } }
      return idx
    }
  }

  /** One index filter: its clause, its binds, and how it can be unjudgeable. */
  private data class IndexFilter(
    val clause: String,
    val binders: List<Bind>,
    val unknown: UnknownAxis? = null,
  )

  /**
   * How a filter's column can be unjudgeable: the `excluded_unknown` key it is
   * reported under, and the predicate that counts it. The key DEFAULTS the
   * predicate and the two travel together, so a condition cannot exist without
   * its key — the state that used to compile and then silently delete the
   * filter's whole `excluded_unknown` count (D55).
   */
  private data class UnknownAxis(
    val key: String,
    val condition: String = "$key IS NULL",
  ) {
    /**
     * The count column BOTH sides of the count query cite: the select list that
     * writes the arm, and the read that puts it under [key].
     *
     * They used to be numbered by POSITION (`AS unk_$n`, read back by a second,
     * independent `mapIndexed`), so the correspondence between arm 3 and the
     * third axis was a fact about two loops rather than anything either one
     * stated. Naming it after the axis makes the two sides quote the SAME
     * string, so they cannot be reordered apart.
     */
    val countColumn: String = "unknown_$key"
  }

  /**
   * The program filter, expanded to real CIP codes before anything is matched.
   *
   * [ExpandedPrograms.cipPrefixCodes] is the `cipPrefix` escape hatch's own
   * expansion — the set the `cip_codes &&` clause binds — and
   * [ExpandedPrograms.matchedCodes] is what the titles LATERAL restricts to,
   * which is the INTERSECTION when both a `subject` and a `cipPrefix` were
   * given, because `programs` reports the titles that satisfied the whole
   * program filter.
   *
   * A prefix matching no real CIP code, and a subject word no taxonomy row
   * carries, are both NAMED refusals (D54): a filter that silently matches
   * nothing answers a narrow question with an empty answer and no reason. The
   * refusal is a [CollegeSearchOutcome.UnresolvableProgramFilter], not a
   * `Result.failure` — the vocabulary is wrong, the database is not — so a
   * caller renders it as the validation error it is rather than as a fault.
   */
  private fun expandProgramCodes(
    session: SqlSession,
    query: CollegeQuery,
  ): Result<ProgramExpansion> {
    val prefix = query.cipPrefix
    val subject = query.subject
    if (prefix == null && subject == null) {
      return Result.success(ProgramExpansion.Codes(ExpandedPrograms(null, null)))
    }

    val prefixCodes =
      prefix?.let {
        when (val expansion = expandCipPrefix(session, it).getOrElse { e -> return Result.failure(e) }) {
          is WordExpansion.Unresolvable -> return Result.success(ProgramExpansion.Unresolvable(expansion.refusal))
          is WordExpansion.Codes -> expansion.codes
        }
      }
    val subjectCodes =
      subject?.let {
        when (val expansion = expandSubject(session, it).getOrElse { e -> return Result.failure(e) }) {
          is WordExpansion.Unresolvable -> return Result.success(ProgramExpansion.Unresolvable(expansion.refusal))
          is WordExpansion.Codes -> expansion.codes
        }
      }

    // THE two-filter decision, stated once and named, rather than left implicit
    // in a `when` at the end of three inline queries. `programs` reports the
    // titles that satisfied the WHOLE program filter, so when both words were
    // written the answer is their INTERSECTION — and an EMPTY intersection is a
    // refusal, not a search. The two clauses are independent on the index
    // (`cip_codes &&` and `subject_slugs @>`), so running it would have matched
    // a college offering biology and, separately, nursing: a page of colleges no
    // single program of which satisfies the question, handed back with
    // `programs: []` and no reason for the emptiness. The two words contradict
    // each other; say that, the way every other unusable program word is said.
    if (prefixCodes != null && subjectCodes != null) {
      val shared = prefixCodes.filter { it in subjectCodes.toSet() }
      if (shared.isEmpty()) {
        return createUnresolvableExpansion(
          CollegeSearchOutcome.UnresolvableProgramFilter.Field.SUBJECT,
          checkNotNull(subject),
          CollegeSearchOutcome.UnresolvableProgramFilter.Cause.SUBJECT_AND_CIP_PREFIX_SHARE_NO_CIP_CODE,
          conflictsWith = prefix,
        )
      }
      return Result.success(ProgramExpansion.Codes(ExpandedPrograms(prefixCodes, shared)))
    }
    // Exactly one word was written: what it expands to is both the clause's code
    // set and what `programs` reports.
    return Result.success(ProgramExpansion.Codes(ExpandedPrograms(prefixCodes, prefixCodes ?: subjectCodes)))
  }

  /**
   * The `cipPrefix` escape hatch's own expansion: every published CIP code the
   * prefix names, or the refusal that it names none.
   */
  private fun expandCipPrefix(
    session: SqlSession,
    prefix: String,
  ): Result<WordExpansion> {
    val codes =
      session
        .queryList(
          "SELECT code FROM cip_codes WHERE code LIKE ? || '%' ORDER BY code",
          bind = { stmt -> stmt.setString(1, prefix) },
          map = { rs -> rs.getString("code") },
        ).getOrElse { return Result.failure(it) }
    if (codes.isEmpty()) {
      return createUnresolvableWord(
        CollegeSearchOutcome.UnresolvableProgramFilter.Field.CIP_PREFIX,
        prefix,
        CollegeSearchOutcome.UnresolvableProgramFilter.Cause.NOT_A_PUBLISHED_CIP_CODE,
      )
    }
    return Result.success(WordExpansion.Codes(codes))
  }

  /**
   * A `subject` word's expansion through the taxonomy, or the refusal — with the
   * SECOND query that splits the two ways it can fail: a word no `subjects` row
   * carries at all, and a subject whose prefixes name no published CIP code.
   * They are different defects — a wrong word, versus a taxonomy this vocabulary
   * has outgrown — and the caller is told which.
   */
  private fun expandSubject(
    session: SqlSession,
    subject: String,
  ): Result<WordExpansion> {
    val codes =
      session
        .queryList(
          """
          SELECT c.code
          FROM cip_codes c
          WHERE EXISTS (
            SELECT 1 FROM subjects s, unnest(s.cip_prefixes) AS pfx
            WHERE s.slug = ? AND c.code LIKE pfx || '%')
          ORDER BY c.code
          """.trimIndent(),
          bind = { stmt -> stmt.setString(1, subject) },
          map = { rs -> rs.getString("code") },
        ).getOrElse { return Result.failure(it) }
    if (codes.isNotEmpty()) return Result.success(WordExpansion.Codes(codes))

    val known =
      session
        .queryList(
          "SELECT 1 AS one FROM subjects WHERE slug = ?",
          bind = { stmt -> stmt.setString(1, subject) },
          map = { rs -> rs.getInt("one") },
        ).getOrElse { return Result.failure(it) }
    return createUnresolvableWord(
      CollegeSearchOutcome.UnresolvableProgramFilter.Field.SUBJECT,
      subject,
      if (known.isEmpty()) {
        CollegeSearchOutcome.UnresolvableProgramFilter.Cause.SUBJECT_NOT_IN_TAXONOMY
      } else {
        CollegeSearchOutcome.UnresolvableProgramFilter.Cause.SUBJECT_MATCHES_NO_CIP_CODE
      },
    )
  }

  /** One program WORD's expansion: the codes it names, or the refusal it is. */
  private sealed interface WordExpansion {
    data class Codes(
      val codes: List<String>,
    ) : WordExpansion

    data class Unresolvable(
      val refusal: CollegeSearchOutcome.UnresolvableProgramFilter,
    ) : WordExpansion
  }

  /** [createUnresolvableExpansion], for one word rather than the whole program filter. */
  private fun createUnresolvableWord(
    field: CollegeSearchOutcome.UnresolvableProgramFilter.Field,
    value: String,
    cause: CollegeSearchOutcome.UnresolvableProgramFilter.Cause,
  ): Result<WordExpansion> =
    Result.success(
      WordExpansion.Unresolvable(CollegeSearchOutcome.UnresolvableProgramFilter(field, value, cause)),
    )

  /**
   * One named program-filter refusal, as the successful outcome it is. The DAO
   * states the FACT — which field, which word, which cause — and never the
   * sentence: the wording belongs to whichever boundary is speaking.
   */
  private fun createUnresolvableExpansion(
    field: CollegeSearchOutcome.UnresolvableProgramFilter.Field,
    value: String,
    cause: CollegeSearchOutcome.UnresolvableProgramFilter.Cause,
    conflictsWith: String? = null,
  ): Result<ProgramExpansion> =
    Result.success(
      ProgramExpansion.Unresolvable(
        CollegeSearchOutcome.UnresolvableProgramFilter(field, value, cause, conflictsWith),
      ),
    )

  /**
   * The two outcomes of expanding the program filter (see [expandProgramCodes]):
   * the real code sets, or the refusal the caller returns as its own outcome.
   */
  private sealed interface ProgramExpansion {
    data class Codes(
      val programs: ExpandedPrograms,
    ) : ProgramExpansion

    data class Unresolvable(
      val refusal: CollegeSearchOutcome.UnresolvableProgramFilter,
    ) : ProgramExpansion
  }

  /** See [expandProgramCodes]. Both members are null when no program filter was given. */
  private data class ExpandedPrograms(
    val cipPrefixCodes: List<String>?,
    val matchedCodes: List<String>?,
  )

  /**
   * `source_years` for the rows actually returned (D55): the RANGE each source's
   * vintages span, which is one year in the ordinary case where the rows agree.
   *
   * It reduced with `singleOrNull()` first, so a page mixing 2022 and 2023
   * reported nothing for that source and read exactly like a page carrying no
   * vintage — a real fact about the answer, deleted. A source no returned row
   * carries is still absent, because there is genuinely nothing to report.
   */
  private fun sourceYears(matches: List<CollegeMatch>): Map<String, IntRange> =
    buildMap {
      yearRange(matches.mapNotNull { it.ipedsSurveyYear })?.let { put("ipeds", it) }
      yearRange(matches.mapNotNull { it.programsCensusSurveyYear })?.let { put("programs_census", it) }
    }

  /** The span of [years], or null when no returned row carried one. */
  private fun yearRange(years: List<Int>): IntRange? {
    val min = years.minOrNull() ?: return null
    return min..years.max()
  }

  /**
   * The ORDER BY clause for a [CollegeQuery.SortBy] — a closed enum-to-constant
   * mapping (no caller text reaches SQL) over `college_search_index` columns.
   * [prefix] is the alias the columns are read through: empty inside the
   * filtered subquery, `i.` in the payload query that re-states the same order.
   *
   * A sort never filters: rows NULL on the sort key sink (`NULLS LAST`), they
   * do not vanish (brief 0004 D11); every ordering ends with the
   * `ipeds_unit_id ASC` tiebreak for a total, deterministic order (`name` is
   * not unique, so NAME_ASC needs the tiebreak too).
   */
  private fun orderBy(
    sortBy: CollegeQuery.SortBy,
    prefix: String,
  ): String =
    when (sortBy) {
      CollegeQuery.SortBy.ENROLLMENT_DESC -> {
        "${prefix}undergrad_enrollment_headcount DESC NULLS LAST"
      }

      CollegeQuery.SortBy.ADMISSION_RATE_SHARE_ASC -> {
        "${prefix}admission_rate_share ASC NULLS LAST"
      }

      CollegeQuery.SortBy.NET_PRICE_PER_YEAR_USD_ASC -> {
        "${prefix}net_price_per_year_usd ASC NULLS LAST"
      }

      CollegeQuery.SortBy.COMPLETION_RATE_150PCT_4YR_SHARE_DESC -> {
        "${prefix}completion_rate_150pct_4yr_share DESC NULLS LAST"
      }

      CollegeQuery.SortBy.NAME_ASC -> {
        "${prefix}name ASC NULLS LAST"
      }
    } + ", ${prefix}ipeds_unit_id ASC"

  // ---------------------------------------------------------------------------
  // Filter binding helpers (RFC 150)
  // ---------------------------------------------------------------------------

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
    // The same honesty gate [search] takes, and this path needs it MORE: name
    // search used to degrade to a `colleges` substring scan, so an unbuilt
    // index makes it strictly worse than before rather than merely narrower.
    // It is a named, retryable failure rather than an empty list — the next
    // ingest fixes it — and never a zero result.
    if (!isSearchIndexBuilt(session).getOrElse { return Result.failure(it) }) {
      return Result.failure(SearchIndexNotBuiltException())
    }
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
      -- The MATCHING and the RANKING read `college_search_index` (RFC 150 D53):
      -- one search path, so the substring arm and the enrollment tiebreak come
      -- off the same table the structured search filters. The PROJECTION stays
      -- on the source of truth (D60) -- `city` is not on the index and does not
      -- need to be, because nothing matches or sorts on it. Both
      -- `college_name_words.college_id` and `college_search_index.college_id`
      -- ARE `colleges.id`, so the join needs no translation and
      -- `PublicCollegeSummary.id` keeps carrying the same value: the REST
      -- contract is byte-identical in shape.
      SELECT c.id, c.name, c.city, c.state
      FROM college_search_index i
        JOIN colleges c ON c.id = i.college_id
        CROSS JOIN q
        LEFT JOIN scored s ON s.college_id = i.college_id
      WHERE (cardinality(q.words) > 0 AND s.matched_words = cardinality(q.words))
         OR i.search_text ILIKE '%' || ? || '%'
      ORDER BY (i.name ILIKE ? || '%') DESC,
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
        i.undergrad_enrollment_headcount DESC NULLS LAST, i.name, i.ipeds_unit_id
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
  // The derived search index (RFC 150)
  // ---------------------------------------------------------------------------

  /**
   * The `colleges.control` codes [InstitutionControl] does not name, with how
   * many rows carry each — empty in every healthy database.
   *
   * `colleges_control_valid_check` restricts the column to 1/2/3 today, so this
   * can only be non-empty after the CHECK and the enum drift apart. That is
   * exactly the case worth naming: the alternative report is a NOT NULL
   * violation on `college_search_index.control` with no code in it.
   */
  fun unmappedControlCodes(session: SqlSession): Result<Map<String, Int>> =
    unmappedCodeCounts(session, "colleges", "control", InstitutionControl.entries.map { it.code })

  /**
   * The same read over `college_ipeds.sector` (D61b) — a COUNTED REPORT rather
   * than a failure, because `sector` is nullable and SECTOR_CASE degrades an
   * unnamed code to NULL. Silent degradation is the defect: a college whose
   * sector went NULL is silently kept out of the administrative-unit exclusion
   * and reported NOWHERE. The `search-index` phase logs this, RFC 147 D46 style.
   */
  fun unmappedSectorCodes(session: SqlSession): Result<Map<String, Int>> =
    unmappedCodeCounts(session, "college_ipeds", "sector", InstitutionSector.entries.map { it.code })

  /**
   * Distinct stored values of [column] that are not in [known], with row counts.
   *
   * Table, column and codes are all constants of this file — the codes come
   * from the Kotlin enums the rebuild generates its `CASE` arms from — so no
   * caller text reaches the SQL text.
   */
  private fun unmappedCodeCounts(
    session: SqlSession,
    table: String,
    column: String,
    known: List<Int>,
  ): Result<Map<String, Int>> =
    session
      .queryList(
        """
        SELECT $column::text AS code, count(*) AS n
        FROM $table
        WHERE $column IS NOT NULL AND $column NOT IN (${known.joinToString(", ")})
        GROUP BY $column
        ORDER BY $column
        """.trimIndent(),
        bind = {},
        map = { rs -> rs.getString("code") to rs.getInt("n") },
      ).map { it.toMap() }

  /**
   * `CASE` arms mapping a raw `colleges.control` code to the word
   * `college_search_index.control` stores, GENERATED from
   * [InstitutionControl] rather than written out as SQL literals (D61a).
   *
   * Generated on purpose: the schema CHECK, the enum and this SQL are three
   * statements of one vocabulary, and a hand-written third copy is the one that
   * silently drifts. A code the enum does not define yields NULL, and `control`
   * is NOT NULL, so the rebuild FAILS rather than storing a school with no
   * control — a school with no control is not a searchable school.
   */
  private val CONTROL_CASE: String =
    InstitutionControl.entries.joinToString(
      separator = " ",
      prefix = "CASE c.control ",
      postfix = " END",
    ) { "WHEN ${it.code} THEN '${it.label}'" }

  /**
   * The same generation for `college_ipeds.sector` (D61b), from
   * [InstitutionSector].
   *
   * The difference from [CONTROL_CASE] is what NULL means. `sector` is nullable,
   * and it is NULL exactly when there is no `college_ipeds` row to read — an
   * absence. The publisher's OWN "sector unknown (not active)" is code 99 and
   * maps to the word `unknown`, a reported fact. A code outside the eleven
   * cannot arrive here at all: `college_ipeds_sector_domain_check` refused it at
   * ingest.
   */
  private val SECTOR_CASE: String =
    InstitutionSector.entries.joinToString(
      separator = " ",
      prefix = "CASE ci.sector ",
      postfix = " END",
    ) { "WHEN ${it.code} THEN '${it.value}'" }

  /**
   * `HD.ICLEVEL` = 1, "four or more years": the one `college_ipeds.inst_level`
   * code that makes a college four-year, named rather than typed as a bare `1`
   * in the SELECT below.
   *
   * Every other coded axis in that statement is GENERATED from an enum
   * ([CONTROL_CASE], [SECTOR_CASE]) precisely so a raw publisher code cannot be
   * hand-written into SQL; this one axis was the exception, and `= 1` beside
   * `= 2` (two-year) and `= 3` (less than two years) is one keystroke from
   * dropping every four-year school out of the default universe. The domain is
   * the 0055 `college_ipeds_inst_level_domain_check`, which `IpedsLoader` bounds
   * the ingest against.
   */
  private const val INST_LEVEL_FOUR_OR_MORE_YEARS = 1

  /**
   * Rebuilds `college_search_index` WHOLESALE inside the caller's transaction
   * and returns the rows written — the `search-index` phase (RFC 150 D47), and
   * [rebuildNameWords] in shape and in transaction discipline.
   *
   * Four statements, and there is no fifth: D60 removed `build_id`, so nothing
   * has to be stamped after the fact and the reproducibility assertion (D59)
   * has no column to exempt. The body names them in order — delete, insert
   * ([insertIndexRows]), rank ([rankPercentiles]), analyze — so this list no
   * longer has to describe code the reader cannot see:
   *
   * - `DELETE`, not `TRUNCATE` — the reasoning already recorded on
   *   [rebuildNameWords]: TRUNCATE takes an ACCESS EXCLUSIVE lock against live
   *   readers. The DELETE also settles the foreign key: every child row is gone
   *   before the INSERT re-references a parent, and the INSERT draws its keys
   *   FROM `colleges`, so inside this one transaction the constraint cannot be
   *   the thing that fails.
   * - `ANALYZE`, inside the same transaction — permitted there, unlike
   *   `VACUUM` — because the table was just emptied and refilled and the
   *   planner's statistics otherwise describe the previous build.
   *
   * Determinism is a property, not a hope (D59): every `array_agg` carries an
   * explicit `ORDER BY`, `percent_rank()` is deterministic under ties, and no
   * row contains `NOW()`. Re-ingesting the same snapshot reproduces the table
   * column for column.
   *
   * Wholesale is the complete story in production because `colleges` is written
   * only by the ingest, so this phase sees the finished snapshot. A test that
   * seeds `colleges` directly must call this itself.
   */
  fun rebuildSearchIndex(session: SqlSession): Result<Int> {
    // A `colleges.control` code [InstitutionControl] does not name would make
    // CONTROL_CASE evaluate to NULL against a NOT NULL column, so the rebuild
    // already failed — as a bare constraint violation naming neither the code
    // nor how many rows carry it (D61a). Say the cause instead, before the
    // write: the operator needs the CODE, not the constraint name.
    val unmappedControl = unmappedControlCodes(session).getOrElse { return Result.failure(it) }
    if (unmappedControl.isNotEmpty()) return Result.failure(UnmappedControlCodeException(unmappedControl))

    session.execute("DELETE FROM college_search_index").getOrElse { return Result.failure(it) }
    val written = insertIndexRows(session).getOrElse { return Result.failure(it) }
    rankPercentiles(session).getOrElse { return Result.failure(it) }
    session.execute("ANALYZE college_search_index").getOrElse { return Result.failure(it) }
    return Result.success(written)
  }

  /**
   * Statement 2 of [rebuildSearchIndex]: the one `INSERT ... SELECT` that
   * derives every index row from `colleges` and its sources, returning the rows
   * written.
   *
   * **Every join is a LEFT JOIN** — both the source joins (a Scorecard-only
   * ingest has no `college_ipeds` rows at all) and the six code-to-slug
   * resolutions. A college is NEVER dropped from the index because one of its
   * codes has no codebook row: the column goes NULL, the college stays
   * searchable, and RFC 147 D46's unknown-code report is what names the gap. An
   * INNER JOIN here would silently delete colleges from search, which is the
   * worst failure this table can have and the hardest to notice.
   *
   * Every `array_agg` carries an explicit `ORDER BY`, so a re-ingest of the same
   * snapshot reproduces these rows column for column (D59).
   */
  private fun insertIndexRows(session: SqlSession): Result<Int> =
    session.execute(
      """
      INSERT INTO college_search_index (
          college_id, ipeds_unit_id, name, search_text, state, region, locale,
          control, is_active, is_four_year, is_degree_granting, sector,
          undergrad_enrollment_headcount, admission_rate_share,
          net_price_per_year_usd, completion_rate_150pct_4yr_share,
          test_policy, religious_affiliation, carnegie_class, carnegie_size,
          has_rotc, has_study_abroad, offers_housing, athletic_associations,
          cip_codes, subject_slugs)
      SELECT
          c.id,
          c.ipeds_unit_id,
          c.name,
          -- The SAME expression the name-search path matches on (0051), not a
          -- second copy of it: materialising the words here cannot drift from
          -- what a name query reads.
          college_search_text(c.name, c.aliases),
          c.state,
          reg.slug,
          loc.slug,
          $CONTROL_CASE,
          -- These two lines read a MISSING `college_ipeds` row in opposite ways,
          -- on purpose. `is_active` is NOT NULL, so it has to say something and
          -- says "not known to be closed" -- on a Scorecard-only ingest that is
          -- every row, and the column then carries no information. It is the one
          -- axis here where unknown reads as "yes", and it is the trade-off Ian
          -- deferred at the RFC 150 approval gate (`## Deferred`, the tri-state
          -- `is_operating` sketch). `is_four_year` below does it correctly: an
          -- unreported level stays NULL -- unknown, never "no" -- which is why
          -- the default universe reads it as `IS NOT FALSE`.
          (coalesce(ci.cy_active, TRUE) AND ci.death_year IS NULL AND ci.closed_at IS NULL),
          (ci.inst_level = $INST_LEVEL_FOUR_OR_MORE_YEARS),
          -- `HD.UGOFFER` ("offers undergraduate awards") -> `is_degree_granting`:
          -- the one column D60 carries that nothing filters, sorts or indexes on
          -- today. Kept because brief 0004 D2 mandates the three universe flags
          -- together, so the axis is there without a rebuild. See 0064's comment.
          ci.ug_offer,
          $SECTOR_CASE,
          c.undergrad_enrollment_headcount,
          c.admission_rate_share,
          c.net_price_per_year_usd,
          c.completion_rate_150pct_4yr_share,
          pol.slug, rel.slug, cbc.slug, csz.slug,
          ci.has_rotc, ci.has_study_abroad, ci.offers_housing,
          -- NULL is "nothing was reported", the empty array is "reported: none"
          -- (D55). `array_agg` already returns NULL over no rows, so the only
          -- work here is keeping the two apart where the SOURCE distinguishes
          -- them: a college that reported an EMPTY `athletic_assoc` knows it
          -- belongs to none, and `subject_slugs` is unknown exactly when the
          -- program census is. Coalescing all three into '{}' was the sentinel
          -- that made `excluded_unknown` count every association-less school as
          -- unjudgeable.
          CASE WHEN ci.athletic_assoc IS NULL THEN NULL ELSE coalesce(aso.slugs, '{}'::slug[]) END,
          pr.cip_codes,
          CASE WHEN pr.cip_codes IS NULL THEN NULL ELSE coalesce(sub.subject_slugs, '{}'::slug[]) END
      FROM colleges c
      LEFT JOIN college_ipeds ci ON ci.ipeds_unit_id = c.ipeds_unit_id
      LEFT JOIN ipeds_regions                reg ON reg.code = c.region
      LEFT JOIN nces_locales                 loc ON loc.code = c.locale
      LEFT JOIN admission_test_policies      pol ON pol.code = ci.test_policy
      LEFT JOIN religious_affiliations       rel ON rel.code = ci.rel_affil
      LEFT JOIN carnegie_2021_basic_classes  cbc ON cbc.code = ci.carnegie_basic
      LEFT JOIN carnegie_2021_size_settings  csz ON csz.code = ci.carnegie_size
      LEFT JOIN LATERAL (
          SELECT array_agg(a.slug ORDER BY a.code) AS slugs
          FROM unnest(coalesce(ci.athletic_assoc, '{}'::smallint[])) AS ord
          JOIN athletic_associations a ON a.code = ord
      ) aso ON TRUE
      LEFT JOIN LATERAL (
          SELECT array_agg(DISTINCT pc.cip_code ORDER BY pc.cip_code) AS cip_codes
          FROM college_programs_census pc
          WHERE pc.college_id = c.id
      ) pr ON TRUE
      LEFT JOIN LATERAL (
          SELECT array_agg(DISTINCT s.slug ORDER BY s.slug) AS subject_slugs
          FROM subjects s
          WHERE EXISTS (
              SELECT 1 FROM college_programs_census pc
              WHERE pc.college_id = c.id
                AND EXISTS (SELECT 1 FROM unnest(s.cip_prefixes) p
                            WHERE pc.cip_code LIKE p || '%'))
      ) sub ON TRUE
      """.trimIndent(),
    )

  /**
   * Statement 3 of [rebuildSearchIndex]: the percentile `UPDATE`, over the
   * DEFAULT universe only (D52).
   *
   * The four ranks are computed INDEPENDENTLY so a row missing one input still
   * ranks on the others, and the universe CTE joins `colleges` for
   * `sat_average_equivalent_score`, which D60 does not carry on the index: it is
   * the input to a percentile and nothing else. Rows OUTSIDE the default
   * universe are never touched and keep NULL — a percentile taken against the
   * 2-year rows and the system offices describes a corpus no student is
   * searching. The corpus is [DefaultUniverse]'s own words, not a second copy of
   * them, so it cannot drift from what a default search returns (D52).
   * `percent_rank()` is deterministic under ties (D59).
   */
  private fun rankPercentiles(session: SqlSession): Result<Int> =
    session.execute(
      """
      WITH universe AS (
          SELECT i.college_id, i.undergrad_enrollment_headcount,
                 i.admission_rate_share, i.net_price_per_year_usd,
                 c.sat_average_equivalent_score
          FROM college_search_index i
          JOIN colleges c ON c.id = i.college_id
          WHERE ${DefaultUniverse.sql("i.")}
      ),
      enrollment AS (
          SELECT college_id,
                 percent_rank() OVER (ORDER BY undergrad_enrollment_headcount) AS v
          FROM universe WHERE undergrad_enrollment_headcount IS NOT NULL),
      admission AS (
          SELECT college_id, percent_rank() OVER (ORDER BY admission_rate_share) AS v
          FROM universe WHERE admission_rate_share IS NOT NULL),
      sat AS (
          SELECT college_id,
                 percent_rank() OVER (ORDER BY sat_average_equivalent_score) AS v
          FROM universe WHERE sat_average_equivalent_score IS NOT NULL),
      price AS (
          SELECT college_id, percent_rank() OVER (ORDER BY net_price_per_year_usd) AS v
          FROM universe WHERE net_price_per_year_usd IS NOT NULL)
      UPDATE college_search_index t
      SET undergrad_enrollment_percentile_share = e.v,
          admission_rate_percentile_share       = a.v,
          sat_average_percentile_share          = s.v,
          net_price_percentile_share            = p.v
      FROM universe u
      LEFT JOIN enrollment e ON e.college_id = u.college_id
      LEFT JOIN admission  a ON a.college_id = u.college_id
      LEFT JOIN sat        s ON s.college_id = u.college_id
      LEFT JOIN price      p ON p.college_id = u.college_id
      WHERE t.college_id = u.college_id
      """.trimIndent(),
    )

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
      // The alias set is bound through the shared [TEXT_ARRAY_PARAM] /
      // [jsonbArrayBinder] pair — one jsonb parameter expanded to text[] by
      // Postgres. ONE binder is bound at BOTH indexes, so the SET value and the
      // IS DISTINCT FROM comparison cannot be given different alias sets.
      val sql =
        """
        UPDATE colleges
        SET aliases = $TEXT_ARRAY_PARAM, version = version + 1
        WHERE ipeds_unit_id = ?
          AND aliases IS DISTINCT FROM $TEXT_ARRAY_PARAM
        """.trimIndent()
      val bindAliases = jsonbArrayBinder(aliases)
      val updated =
        session.prepareStatement(sql).use { stmt ->
          bindAliases(stmt, 1)
          stmt.setInt(2, ipedsUnitId)
          bindAliases(stmt, 3)
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
      // The six published cost components (RFC 149): counted so the ingest
      // change summary proves they actually loaded.
      "housing_and_food_on_campus_per_year_usd",
      "housing_and_food_off_campus_per_year_usd",
      "books_and_supplies_per_year_usd",
      "other_expenses_on_campus_per_year_usd",
      "other_expenses_off_campus_per_year_usd",
      "other_expenses_with_family_per_year_usd",
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
        started_at, finished_at, sources, rows_ingested, name_words_rows,
        search_index_rows, change_summary, method_version
      )
      VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?)
      RETURNING id
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setTimestamp(1, java.sql.Timestamp.from(input.startedAt))
        stmt.setTimestamp(2, java.sql.Timestamp.from(input.finishedAt))
        stmt.setJsonbOrNull(3, input.sources)
        stmt.setJsonbOrNull(4, input.rowsIngested)
        stmt.setIntOrNull(5, input.nameWordsRows)
        stmt.setIntOrNull(6, input.searchIndexRows)
        stmt.setJsonbOrNull(7, input.changeSummary)
        stmt.setInt(8, input.methodVersion)
      },
      map = { rs -> UUID.fromString(rs.getString("id")) },
      mapError = ::mapCollegeWriteError,
    )
  }
}
