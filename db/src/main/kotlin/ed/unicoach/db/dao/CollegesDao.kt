package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.models.AnchoredAxis
import ed.unicoach.db.models.CohortAddresses
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CohortStatAddress
import ed.unicoach.db.models.College
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeMatch
import ed.unicoach.db.models.CollegeProgram
import ed.unicoach.db.models.CollegeProgramId
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.CollegeSearchOutcome
import ed.unicoach.db.models.CollegeSearchPage
import ed.unicoach.db.models.CollegeSimilarityOutcome
import ed.unicoach.db.models.CollegeSimilarityPage
import ed.unicoach.db.models.CollegeSummary
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.InstitutionControl
import ed.unicoach.db.models.InstitutionSector
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeIndexBuild
import ed.unicoach.db.models.NewCollegeProgram
import ed.unicoach.db.models.PriceConcept
import ed.unicoach.db.models.PriceRuler
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ResidencyTierBasis
import ed.unicoach.db.models.SimilarityAnchor
import ed.unicoach.db.models.SimilarityAnchorOutcome
import ed.unicoach.db.models.SimilarityAxis
import ed.unicoach.db.models.SimilarityMatch
import ed.unicoach.db.models.SimilarityQuery
import ed.unicoach.db.models.TuitionTierReading
import ed.unicoach.db.models.Version
import ed.unicoach.db.models.residencyTierBasisOf
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
 *
 * Before adding a method that FINDS a college — by name, by attribute, by
 * anything a user typed — read the module convention on
 * `ed.unicoach.college.CollegeSearchService` (not linkable from here: `:db` does
 * not depend on `:college`). Search goes through that service over
 * `college_search_index`; this file's point-reads are what stays on `colleges`
 * (RFC 154 D-F).
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
      completionRate150pct4yrShare = rs.doubleOrNull("completion_rate_150pct_4yr_share"),
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
   * One tuition tier as [residencyTierBasisOf] reads it, from the two columns
   * [PUBLISHED_TUITION_TIERS_LATERAL] emits for it.
   *
   * The pair is read together because the two answer different questions: a
   * NULL amount beside a real status is a row that bears no value, and a NULL
   * status is no row at all. Reading only the amount would collapse the
   * publisher's `not_applicable` ANSWER into every other silence, which is the
   * defect D19 exists against.
   *
   * A status the enum cannot read is a located [CorruptPersistedValueException],
   * never a null — the [CanonicalMoneyReadDao] and [AidPolicyDao] treatment of
   * this very column. `figure_statuses` is an AUTHORED VOCABULARY TABLE, so a
   * seventh code can exist in the database without any Kotlin change, and
   * decoding it to null would make it indistinguishable from "no row at all".
   * That silence is precisely what prints
   * [ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT]'s sentence to a
   * family: a code we cannot read would become a claim we cannot support. A loud
   * failure is the only honest answer.
   */
  private fun ResultSet.getTuitionTierReading(prefix: String): TuitionTierReading {
    val rawStatus = getString("${prefix}_status")
    return TuitionTierReading(
      amountPresent = intOrNull("${prefix}_usd") != null,
      status =
        rawStatus?.let { raw ->
          FigureStatus.fromValue(raw)
            ?: throw CorruptPersistedValueException(
              raw,
              ValidationError.InvalidFormat(expected = "a known [FigureStatus] value"),
              location = "price_figures.status (tuition_and_fees, [${prefix.removeSuffix("_tuition")}])",
            )
        },
    )
  }

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
      // The price the ACTIVE ruler put this row in its place with (RFC 169),
      // reported under a key that names the tier applied to THIS row.
      rulerPriceUsd = rs.intOrNull(RULER_PRICE_COLUMN),
      // WHICH tuition tiers this school publishes, decided by the ONE derivation
      // both the cost path and search read (brief 0006 D19). Computed here, at
      // the row boundary, so no consumer re-derives the tier shape from one
      // amount -- the drift `publishedTuitionTiersOf`'s doc forbids.
      residencyTierBasis =
        residencyTierBasisOf(
          rs.getTuitionTierReading("in_district_tuition"),
          rs.getTuitionTierReading("in_state_tuition"),
          rs.getTuitionTierReading("out_of_state_tuition"),
        ),
      // The band prices, the earnings, the debt and the Pell share come from
      // [CANONICAL_COHORT_LATERAL] -- `cohort_money_stats` at the full address
      // (RFC 176) -- and no longer off the publisher-shaped `colleges`
      // columns. The ALIASES are the lateral's, and deliberately not the old
      // column names: a `c.` select left behind by a half-done move cannot
      // then feed this mapper by accident. [CollegeMatch]'s field names and
      // every `CollegeMatchRow` wire key are unchanged -- a source move, not a
      // contract change.
      //
      // ITERATED, never five hand-paired arms: the band that NAMED the column
      // is the band the value is filed under, so no arm exists to transpose
      // and ship a real price under the wrong dollar range.
      netPriceUsdByBand =
        IncomeBand.entries
          .mapNotNull { band -> rs.intOrNull(netPriceColumn(band))?.let { band to it } }
          .toMap(),
      completionRate150pct4yrShare = rs.doubleOrNull("completion_rate_150pct_4yr_share"),
      medianEarnings10yAfterEntryUsd = rs.intOrNull(MEDIAN_EARNINGS_COLUMN),
      medianDebtAtCompletionUsd = rs.intOrNull(MEDIAN_DEBT_COLUMN),
      pellShare = rs.doubleOrNull(PELL_SHARE_COLUMN),
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
          control, undergrad_enrollment_headcount, admission_rate_share, sat_average_equivalent_score,
          completion_rate_150pct_4yr_share, website
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
          completion_rate_150pct_4yr_share = EXCLUDED.completion_rate_150pct_4yr_share,
          website = EXCLUDED.website,
          version = colleges.version + 1
        WHERE (
          colleges.opeid, colleges.name, colleges.city, colleges.state,
          colleges.region, colleges.locale, colleges.latitude, colleges.longitude,
          colleges.control, colleges.undergrad_enrollment_headcount, colleges.admission_rate_share,
          colleges.sat_average_equivalent_score, colleges.completion_rate_150pct_4yr_share,
          colleges.website, colleges.ipeds_unit_id
        ) IS DISTINCT FROM (
          EXCLUDED.opeid, EXCLUDED.name, EXCLUDED.city, EXCLUDED.state,
          EXCLUDED.region, EXCLUDED.locale, EXCLUDED.latitude, EXCLUDED.longitude,
          EXCLUDED.control, EXCLUDED.undergrad_enrollment_headcount, EXCLUDED.admission_rate_share,
          EXCLUDED.sat_average_equivalent_score, EXCLUDED.completion_rate_150pct_4yr_share,
          EXCLUDED.website, EXCLUDED.ipeds_unit_id
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
        stmt.setDoubleOrNull(next(), input.completionRate150pct4yrShare)
        stmt.setStringOrNull(next(), input.website)
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

    return countPage(session, plan, matches, query.priceRuler)
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
    extraFilters: List<IndexFilter> = emptyList(),
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
      filters += IndexFilter("is_four_year = ?", listOf(booleanBinder(fourYear)), UnknownAxis.ofColumn("is_four_year"))
    }

    query.states?.let { states ->
      if (states.isNotEmpty()) {
        // NOT NULL on the index, so there is no unknown to exclude or report.
        filters += IndexFilter("state = ANY ($TEXT_ARRAY_PARAM)", listOf(jsonbArrayBinder(states)))
      }
    }
    query.region?.let { region ->
      filters += IndexFilter("region = ?", listOf(stringBinder(region)), UnknownAxis.ofColumn("region"))
    }
    query.locales?.let { locales ->
      if (locales.isNotEmpty()) {
        filters +=
          IndexFilter("locale = ANY ($TEXT_ARRAY_PARAM)", listOf(jsonbArrayBinder(locales)), UnknownAxis.ofColumn("locale"))
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
          UnknownAxis.ofColumn("undergrad_enrollment_headcount"),
        )
    }
    query.maxUndergradEnrollmentHeadcount?.let { max ->
      filters +=
        IndexFilter(
          "undergrad_enrollment_headcount <= ?",
          listOf(intBinder(max)),
          UnknownAxis.ofColumn("undergrad_enrollment_headcount"),
        )
    }
    query.minAdmissionRateShare?.let { min ->
      filters += IndexFilter("admission_rate_share >= ?", listOf(doubleBinder(min)), UnknownAxis.ofColumn("admission_rate_share"))
    }
    query.maxAdmissionRateShare?.let { max ->
      filters += IndexFilter("admission_rate_share <= ?", listOf(doubleBinder(max)), UnknownAxis.ofColumn("admission_rate_share"))
    }
    query.maxPricePerYearUsd?.let { max ->
      // The ACTIVE ruler's own expression (RFC 169 D1). Under the published
      // ruler it is a `CASE` over the two tier columns -- built from INDEX
      // columns only, so the filter and count statements still name no table but
      // `college_search_index`, which is what keeps a residency-correct price
      // off the hot path of every search.
      val ruler = query.priceRuler
      filters += IndexFilter("${ruler.valueSql()} <= ?", listOf(intBinder(max)), UnknownAxis.of(ruler))
    }
    query.minCompletionRate150pct4yrShare?.let { min ->
      filters +=
        IndexFilter(
          "completion_rate_150pct_4yr_share >= ?",
          listOf(doubleBinder(min)),
          UnknownAxis.ofColumn("completion_rate_150pct_4yr_share"),
        )
    }
    query.testPolicy?.let { slug ->
      filters += IndexFilter("test_policy = ?", listOf(stringBinder(slug)), UnknownAxis.ofColumn("test_policy"))
    }
    query.religiousAffiliation?.let { slug ->
      filters += IndexFilter("religious_affiliation = ?", listOf(stringBinder(slug)), UnknownAxis.ofColumn("religious_affiliation"))
    }
    query.carnegieClass?.let { slug ->
      filters += IndexFilter("carnegie_class = ?", listOf(stringBinder(slug)), UnknownAxis.ofColumn("carnegie_class"))
    }
    query.carnegieSize?.let { slug ->
      filters += IndexFilter("carnegie_size = ?", listOf(stringBinder(slug)), UnknownAxis.ofColumn("carnegie_size"))
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
          UnknownAxis.ofColumn("athletic_associations"),
        )
    }
    query.hasRotc?.let { value ->
      filters += IndexFilter("has_rotc = ?", listOf(booleanBinder(value)), UnknownAxis.ofColumn("has_rotc"))
    }
    query.hasStudyAbroad?.let { value ->
      filters += IndexFilter("has_study_abroad = ?", listOf(booleanBinder(value)), UnknownAxis.ofColumn("has_study_abroad"))
    }
    query.hasHousing?.let { value ->
      filters += IndexFilter("offers_housing = ?", listOf(booleanBinder(value)), UnknownAxis.ofColumn("offers_housing"))
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
          UnknownAxis.ofColumn("subject_slugs"),
        )
    }
    if (programCodes.cipPrefixCodes != null) {
      val codes = programCodes.cipPrefixCodes
      filters +=
        IndexFilter(
          "cip_codes && $TEXT_ARRAY_PARAM",
          listOf(jsonbArrayBinder(codes)),
          UnknownAxis.ofColumn("cip_codes"),
        )
    }

    // A similarity call appends its own clauses (the anchor's exclusion from
    // its own results, and D68's two anchor-relative constraints) rather than
    // forking the predicate builder: one home for a search predicate, still.
    return SearchPlan(universe, filters + extraFilters)
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
        i.completion_rate_150pct_4yr_share, i.$RULER_PRICE_COLUMN,
        c.city, c.website,
        ci.survey_year AS ipeds_survey_year,
        $PUBLISHED_TUITION_TIERS_SELECT,
        $CANONICAL_COHORT_SELECT,
        t.titles AS program_titles,
        t.census_year AS programs_census_survey_year
      FROM (
        SELECT
          college_id, ipeds_unit_id, name, state, control, region, locale,
          undergrad_enrollment_headcount, admission_rate_share, net_price_per_year_usd,
          completion_rate_150pct_4yr_share,
          -- The price THIS query is on, per row (RFC 169): the sort key, and the
          -- number the result reports under a key naming the tier applied to
          -- this row. It is an expression over index columns, so this subquery
          -- still names no table but `college_search_index`.
          ${query.priceRuler.valueSql()} AS $RULER_PRICE_COLUMN
        FROM college_search_index
        ${plan.whereClause}
        ORDER BY ${orderBy(query.sortBy, "")}
        LIMIT ?
      ) i
      JOIN colleges c ON c.id = i.college_id
      LEFT JOIN college_ipeds ci ON ci.ipeds_unit_id = i.ipeds_unit_id
      $PUBLISHED_TUITION_TIERS_LATERAL
      $CANONICAL_COHORT_LATERAL
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
    ruler: PriceRuler,
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
            priceRuler = ruler,
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

    /**
     * The unjudgeable axes to report, one `excluded_unknown` key each, in
     * select-list order: the supplied filters' columns.
     */
    val unknownAxes: List<UnknownAxis> = filters.mapNotNull { it.unknown }.distinctBy { it.key }

    /**
     * [matchClause] WITHOUT the filters over the very columns [unknown] reads —
     * the population an `excluded_unknown` arm for that subject is honestly
     * measured over (RFC 153 D67 as amended).
     *
     * A filter over a nullable column is exactly what silently removes the rows
     * that cannot be judged on it, so counting those rows inside its own clause
     * would always answer zero; counting them over the BARE universe answers
     * about colleges the caller's other constraints already excluded. Every
     * other constraint applies, and only the filters the count is about do not.
     *
     * The correspondence is STATED by [UnknownSubject.columns] rather than
     * inferred from string equality: a ranked axis's key is a WORD (`price`)
     * and the filter it must drop is over a COLUMN (`net_price_per_year_usd`),
     * so matching the two by name dropped nothing and every axis arm answered
     * zero by construction.
     */
    fun createMatchClauseExcluding(unknown: UnknownAxis): String =
      (universe + listRetainedFilters(unknown).map { it.clause })
        .let { if (it.isEmpty()) "TRUE" else it.joinToString(" AND ") }

    /**
     * Binds every parameter [createMatchClauseExcluding] carries for [unknown],
     * starting at [from], and returns the NEXT free index — the same list that
     * wrote the text, so an arm's clause and its binds cannot drift.
     */
    fun bindPredicateExcluding(
      stmt: PreparedStatement,
      unknown: UnknownAxis,
      from: Int = 1,
    ): Int {
      var idx = from
      listRetainedFilters(unknown).forEach { filter -> filter.binders.forEach { bind -> bind(stmt, idx++) } }
      return idx
    }

    /** The filters an arm about [unknown] keeps: every one that is not over a column [unknown] itself reads. */
    private fun listRetainedFilters(unknown: UnknownAxis): List<IndexFilter> =
      filters.filter { filter ->
        filter.unknown == null ||
          filter.unknown.subject.columns
            .none { it in unknown.subject.columns }
      }

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
   * WHAT an `excluded_unknown` count is ABOUT: a supplied filter's own column,
   * or a ranked similarity axis.
   *
   * The two used to share one `String` key, so a reader could not tell
   * `net_price_per_year_usd` (a schema identifier) from `price` (an axis word)
   * without already knowing, the default `"$key IS NULL"` condition compiled a
   * broken predicate for an axis key, and the filter-exclusion lookup matched
   * arms to filters by string identity — which an axis word never satisfies.
   */
  private sealed interface UnknownSubject {
    /** The `excluded_unknown` key this subject is reported under. */
    val key: String

    /**
     * The index columns this subject READS. An arm about it drops the filters
     * over exactly these columns, because those filters are what remove the
     * rows the arm is trying to count.
     */
    val columns: Set<String>

    /** The SQL identifier fragment an arm's count column is built from: never caller text, never a word. */
    val sqlAlias: String

    /**
     * A filter's column. The key defaults to the column, so every existing key
     * stays byte-identical, and an axis whose WIRE NAME differs from its schema
     * name — or whose filter is an expression over two columns rather than one —
     * names itself instead (RFC 169 D7).
     *
     * [column] is then only the SQL identifier fragment the count column is
     * built from, and [columns] the set of index columns the filter actually
     * READS. Those must be stated for the published ruler or its arm would drop
     * no filters and count zero by construction — the exact defect [columns]
     * exists to prevent.
     */
    data class FilterColumn(
      val column: String,
      override val key: String = column,
      override val columns: Set<String> = setOf(column),
    ) : UnknownSubject {
      override val sqlAlias: String get() = column
    }

    /** A ranked axis: the key is the axis WORD and never a column, so the caller states both predicate and columns. */
    data class RankedAxis(
      val axis: SimilarityAxis,
      override val columns: Set<String>,
    ) : UnknownSubject {
      override val key: String get() = axis.word

      override val sqlAlias: String get() = axisAlias(axis)
    }
  }

  /**
   * How a candidate can be unjudgeable: the subject it is reported under, and
   * the predicate that counts it. Neither can exist without the other — the
   * state that used to compile and then silently delete the whole
   * `excluded_unknown` count (D55).
   */
  private data class UnknownAxis(
    val subject: UnknownSubject,
    val condition: String,
  ) {
    /** The `excluded_unknown` key this count is reported under. */
    val key: String get() = subject.key

    /**
     * The count column BOTH sides of the count query cite: the select list that
     * writes the arm, and the read that puts it under [key].
     *
     * They used to be numbered by POSITION (`AS unk_$n`, read back by a second,
     * independent `mapIndexed`), so the correspondence between arm 3 and the
     * third axis was a fact about two loops rather than anything either one
     * stated. Naming it after the subject makes the two sides quote the SAME
     * string, so they cannot be reordered apart.
     */
    val countColumn: String = "unknown_${subject.sqlAlias}"

    companion object {
      /** A filter's column, whose unjudgeable rows are exactly the NULLs in it. */
      fun ofColumn(column: String): UnknownAxis = UnknownAxis(UnknownSubject.FilterColumn(column), "$column IS NULL")

      /**
       * A bound on the ACTIVE price ruler (RFC 169 D11). The subject is the
       * AXIS, reported under one key, because a page mixes rows on both tuition
       * tiers and two keys would ask a reader to add two numbers about the same
       * question. A row whose residency-correct price is NULL is dropped,
       * counted and named here — never substituted, never "maybe cheaper".
       */
      fun of(ruler: PriceRuler): UnknownAxis =
        UnknownAxis(
          UnknownSubject.FilterColumn(
            column = ruler.excludedUnknownKey,
            columns = ruler.columns,
          ),
          "${ruler.valueSql()} IS NULL",
        )

      /**
       * A ranked axis, whose unjudgeable rows are the ones its `scored`
       * predicate rejects, and whose [columns] are the index columns that
       * predicate reads.
       */
      fun ofAxis(
        axis: SimilarityAxis,
        scored: String,
        columns: Set<String>,
      ): UnknownAxis = UnknownAxis(UnknownSubject.RankedAxis(axis, columns), "NOT ($scored)")
    }
  }

  // ---------------------------------------------------------------------------
  // Similar colleges (RFC 153)
  // ---------------------------------------------------------------------------

  /**
   * The anchor row of a "schools like X" query (RFC 153 D63/D64), read from
   * `college_search_index` — the same table the ranking reads, so the anchor's
   * position and the candidates' positions can never come from two corpora.
   *
   * THREE outcomes, none of them a null (RFC 150's rule, which every other read
   * of this table already follows): a database whose index was never built
   * answers [SimilarityAnchorOutcome.IndexNotBuilt] rather than "no college has
   * that id", which is the false zero a missing row and a missing INDEX used to
   * arrive as together. A row that exists but sits OUTSIDE the default universe
   * comes back with [SimilarityAnchor.inDefaultUniverse] false and NULL on
   * every percentile: that is the fact D64 refuses on, and it is read here
   * rather than inferred from four nulls, because "closed" and "reports
   * nothing" are different statements about a college.
   */
  fun findSimilarityAnchor(
    session: SqlSession,
    id: CollegeId,
    ruler: PriceRuler,
  ): Result<SimilarityAnchorOutcome> {
    if (!isSearchIndexBuilt(session).getOrElse { return Result.failure(it) }) {
      return Result.success(SimilarityAnchorOutcome.IndexNotBuilt)
    }
    return findSimilarityAnchorRow(session, id, ruler).map { anchor ->
      anchor?.let(SimilarityAnchorOutcome::Found) ?: SimilarityAnchorOutcome.NoSuchCollege
    }
  }

  /**
   * The anchor's index row itself, or null when this database holds none for
   * that id.
   *
   * The anchor's price and price POSITION are read through the SAME [PriceRuler]
   * the candidates will be ranked with (RFC 169), so "like Bowdoin but cheaper"
   * compares two figures on one ladder. Reading the anchor on one ruler and the
   * candidates on another is the one way a single-measure guarantee could still
   * have produced a mixed comparison.
   */
  private fun findSimilarityAnchorRow(
    session: SqlSession,
    id: CollegeId,
    ruler: PriceRuler,
  ): Result<SimilarityAnchor?> =
    session
      .queryOne(
        """
        SELECT
          college_id, name, state, control, locale,
          -- `slug[]` is a DOMAIN over text, which JDBC hands back as Object[];
          -- the cast is what makes it readable as the text[] every other array
          -- read in this file expects.
          subject_slugs::text[] AS subject_slugs,
          ${ruler.valueSql()} AS $RULER_PRICE_COLUMN,
          admission_rate_share,
          undergrad_enrollment_percentile_share,
          ${ruler.shareSql()} AS $RULER_SHARE_COLUMN,
          -- The anchor's SELECTIVITY position, computed by the SAME expression
          -- that positions every candidate: one definition of the axis, so a
          -- distance compares two colleges and never two formulas.
          ($SELECTIVITY_POSITION) AS selectivity_percentile_share,
          (${DefaultUniverse.sql()}) AS in_default_universe
        FROM college_search_index
        WHERE college_id = ?
        """.trimIndent(),
        bind = { stmt -> stmt.setObject(1, id.value) },
        map = { rs -> mapSimilarityAnchor(rs, ruler) },
      ).orNullOnNotFound()

  /**
   * The ranked peers of [SimilarityQuery.anchor] (RFC 153 D62): ONE `SELECT`
   * over `college_search_index` — the default universe, the caller's hard
   * constraints, an `ORDER BY` on the distance expression, a `LIMIT` — then the
   * payload read-back [listMatches] already uses. Nothing is stored, and no
   * second query path exists: the predicate is the same [SearchPlan], with the
   * distance appended to it.
   *
   * The same honesty gates [search] takes, in the same order: an unbuilt index
   * is a named refusal rather than "nothing is similar", and a program word the
   * vocabulary cannot expand is a named refusal rather than a silent zero.
   */
  fun findSimilar(
    session: SqlSession,
    query: SimilarityQuery,
  ): Result<CollegeSimilarityOutcome> {
    if (!isSearchIndexBuilt(session).getOrElse { return Result.failure(it) }) {
      return Result.success(CollegeSimilarityOutcome.IndexNotBuilt)
    }

    val programCodes =
      when (val expansion = expandProgramCodes(session, query.filters).getOrElse { return Result.failure(it) }) {
        is ProgramExpansion.Unresolvable -> {
          return Result.success(CollegeSimilarityOutcome.UnresolvableProgramFilter(expansion.refusal))
        }

        is ProgramExpansion.Codes -> {
          expansion.programs
        }
      }

    val plan =
      SimilarityPlan(
        createSearchPlan(query.filters, programCodes, extraFilters = createFilters(query)),
        createDistance(query),
      )

    val matches =
      listSimilarMatches(session, query, plan, programCodes.matchedCodes).getOrElse { return Result.failure(it) }

    return countSimilarPage(session, plan, matches, query.filters.priceRuler)
  }

  /**
   * The constraints a similarity query adds to the ordinary filter set: the
   * anchor's exclusion from its own results, and D68's two anchor-relative
   * constraints EXPANDED against the anchor's own figures.
   *
   * They are expanded here rather than in the model because the coach cannot
   * know the anchor's numbers before it calls: "but cheaper" is a sentence
   * about Bowdoin's net price, and this is where Bowdoin's net price is known.
   * Strictly cheaper and strictly easier, with no margin — an invented fudge
   * factor would be a product judgement nobody made and invisible in the answer.
   * A candidate that reports neither figure is EXCLUDED and counted, never kept
   * as "maybe cheaper".
   */
  private fun createFilters(query: SimilarityQuery): List<IndexFilter> =
    buildList {
      // The anchor is never similar to itself in a way worth printing.
      add(IndexFilter("college_id <> ?", listOf<Bind>({ stmt, i -> stmt.setObject(i, query.anchor.id.value) })))
      // The FIGURE, not a flag: the query carries the anchor's own number when
      // the constraint was asked for, so there is nothing to re-check here.
      query.cheaperThanUsd?.let { price ->
        // The anchor's own figure is on the ACTIVE ruler (RFC 169), so
        // "but cheaper" compares two residency-correct published totals or two
        // net prices — never one of each.
        val ruler = query.filters.priceRuler
        add(IndexFilter("${ruler.valueSql()} < ?", listOf(intBinder(price)), UnknownAxis.of(ruler)))
      }
      query.easierToAdmitThanShare?.let { rate ->
        add(
          IndexFilter("admission_rate_share > ?", listOf(doubleBinder(rate)), UnknownAxis.ofColumn("admission_rate_share")),
        )
      }
    }

  /**
   * The distance expression (RFC 153 D66): a weighted mean absolute difference
   * over the axes BOTH colleges can be measured on.
   *
   *     SUM  CASE WHEN <scored> THEN w * <difference> ELSE 0 END
   *     / NULLIF(SUM CASE WHEN <scored> THEN w ELSE 0 END, 0)
   *
   * Every `w` and every one of the anchor's own values is a PARAMETER; only
   * column names and the fixed arithmetic are text. `NULLIF(..., 0)` makes a
   * candidate sharing no axis sort as NULL, which [SearchPlan.sharedAxisClause]
   * excludes and [countSimilarPage] counts. Each difference is in `[0, 1]`, so
   * the quotient is too, and the weights are normalised by their own sum —
   * scaling every weight cannot inflate or deflate a score, once the tool's
   * clamp has admitted them.
   *
   * A missing axis contributes 0 to BOTH sums rather than a zero difference:
   * that is the whole of D67 in arithmetic, and the reason no median is ever
   * substituted for an unreported percentile.
   */
  private fun createDistance(query: SimilarityQuery): SimilarityDistance {
    val terms = query.axes.map { (anchored, weight) -> createDistanceTerm(anchored, weight, query.filters.priceRuler) }
    val numerator = terms.joinToString(" + ") { "CASE WHEN ${it.scored} THEN ? * (${it.difference}) ELSE 0 END" }
    val denominator = terms.joinToString(" + ") { "CASE WHEN ${it.scored} THEN ? ELSE 0 END" }
    return SimilarityDistance(
      expression = "($numerator) / nullif($denominator, 0)",
      // Textual order, which is the only order a positional bind can take: each
      // numerator term's weight then its anchor value, and then one weight per
      // denominator term.
      binders = terms.flatMap { listOf(doubleBinder(it.weight)) + it.binders } + terms.map { doubleBinder(it.weight) },
      scoredSelects = terms.map { it.axis to it.scored },
      sharedAxisClause = terms.joinToString(" OR ", prefix = "(", postfix = ")") { it.scored },
      unknownAxes = terms.map { UnknownAxis.ofAxis(it.axis, it.scored, it.columns) },
    )
  }

  /**
   * One axis of the distance: the predicate that says the CANDIDATE can be
   * measured on it, and the difference from the anchor once it can.
   *
   * The numeric axes read the percentile columns; [SimilarityAxis.SELECTIVITY]
   * averages the INVERTED admission-rate percentile with the SAT percentile
   * over whichever the candidate reports, so both inputs point the same way
   * before they are averaged. The categorical axes state the same shape with an
   * equality test on the locale slug and a Jaccard distance over the subject
   * slugs, whose GIN index RFC 150 already landed.
   *
   * The ANCHOR's own value arrives ON the axis ([AnchoredAxis]), so there is no
   * nullable to re-check here: an axis the anchor cannot be measured on was
   * dropped before a query could name it.
   */
  private fun createDistanceTerm(
    anchored: AnchoredAxis,
    weight: Double,
    ruler: PriceRuler,
  ): DistanceTerm =
    when (anchored) {
      is AnchoredAxis.Size -> {
        createPercentileTerm(
          anchored.axis,
          "undergrad_enrollment_percentile_share",
          setOf("undergrad_enrollment_percentile_share", "undergrad_enrollment_headcount"),
          anchored.percentile,
          weight,
        )
      }

      is AnchoredAxis.Price -> {
        // The ACTIVE ruler's share column (RFC 169). Both published shares are
        // positions on ONE ladder, so anchor and candidate are commensurable
        // even when they sit on different tuition tiers — which is precisely
        // what a second, in-state ladder would have broken.
        createPercentileTerm(
          anchored.axis,
          ruler.shareSql(),
          ruler.columns,
          anchored.percentile,
          weight,
        )
      }

      is AnchoredAxis.Selectivity -> {
        DistanceTerm(
          axis = anchored.axis,
          weight = weight,
          scored = "($ADMISSION_PERCENTILE IS NOT NULL OR $SAT_PERCENTILE IS NOT NULL)",
          difference = "abs($SELECTIVITY_POSITION - ?)",
          binders = listOf(doubleBinder(anchored.percentile)),
          columns = setOf(ADMISSION_PERCENTILE, SAT_PERCENTILE, "admission_rate_share"),
        )
      }

      is AnchoredAxis.Setting -> {
        DistanceTerm(
          axis = anchored.axis,
          weight = weight,
          scored = "locale IS NOT NULL",
          difference = "CASE WHEN locale = ? THEN 0 ELSE 1 END",
          binders = listOf(stringBinder(anchored.locale)),
          columns = setOf("locale"),
        )
      }

      is AnchoredAxis.Subjects -> {
        val anchorSubjects = anchored.slugs
        DistanceTerm(
          axis = anchored.axis,
          weight = weight,
          // `{}` is "the programs are known and none of them is a taxonomy
          // subject" (schema 0064) -- a real state, and not one a Jaccard
          // distance can describe, so the candidate is unjudgeable here for
          // exactly the reason an anchor in the same state is (D67).
          scored = "(subject_slugs IS NOT NULL AND cardinality(subject_slugs) > 0)",
          // 1 - |A n B| / |A u B|, over the slug arrays. Both sides bind the
          // anchor's set as ONE jsonb parameter, never as interpolated text.
          difference =
            "(1 - cardinality(ARRAY(SELECT unnest(subject_slugs::text[]) " +
              "INTERSECT SELECT unnest($TEXT_ARRAY_PARAM)))::double precision " +
              "/ nullif(cardinality(ARRAY(SELECT unnest(subject_slugs::text[]) " +
              "UNION SELECT unnest($TEXT_ARRAY_PARAM))), 0))",
          binders = listOf(jsonbArrayBinder(anchorSubjects), jsonbArrayBinder(anchorSubjects)),
          columns = setOf("subject_slugs"),
        )
      }
    }

  /**
   * A PERCENTILE axis: judgeable when the column carries a position, and as far
   * from the anchor as the two positions are apart. [SimilarityAxis.SIZE] and
   * [SimilarityAxis.PRICE] differ only in which column they read, so they state
   * that difference and nothing else.
   */
  private fun createPercentileTerm(
    axis: SimilarityAxis,
    column: String,
    columns: Set<String>,
    anchorPercentile: Double,
    weight: Double,
  ): DistanceTerm =
    DistanceTerm(
      axis = axis,
      weight = weight,
      scored = "$column IS NOT NULL",
      difference = "abs($column - ?)",
      binders = listOf(doubleBinder(anchorPercentile)),
      columns = columns,
    )

  /**
   * The ranked page: at most `limit` index rows ordered by distance, then the
   * join back to the source of truth for the payload — [listMatches]'s shape,
   * because the payload contract is the same one (D70) and reinventing it is
   * how two result rows start disagreeing about what a college is.
   *
   * The order ends with the `ipeds_unit_id ASC` tiebreak every search ordering
   * ends with, so ties are broken the same way here as there and the page is
   * deterministic.
   */
  private fun listSimilarMatches(
    session: SqlSession,
    query: SimilarityQuery,
    plan: SimilarityPlan,
    matchedCodes: List<String>?,
  ): Result<List<SimilarityMatch>> {
    val titlesSelect =
      if (matchedCodes == null) "NULL::text[]" else "coalesce(array_agg(cc.title ORDER BY cc.code), ARRAY[]::text[])"
    val censusRestriction = if (matchedCodes == null) "" else "AND pc.cip_code = ANY ($TEXT_ARRAY_PARAM)"
    val scoredSelects = plan.scoredSelects.joinToString("") { (axis, scored) -> ", ($scored) AS ${scoredColumn(axis)}" }
    val scoredColumns = plan.scoredSelects.joinToString("") { (axis, _) -> ", i.${scoredColumn(axis)}" }

    val sql =
      """
      SELECT
        i.college_id AS id, i.ipeds_unit_id, i.name, i.state, i.control, i.region, i.locale,
        i.undergrad_enrollment_headcount, i.admission_rate_share, i.net_price_per_year_usd,
        i.completion_rate_150pct_4yr_share, i.$RULER_PRICE_COLUMN, i.distance$scoredColumns,
        c.city, c.website,
        ci.survey_year AS ipeds_survey_year,
        $PUBLISHED_TUITION_TIERS_SELECT,
        $CANONICAL_COHORT_SELECT,
        t.titles AS program_titles,
        t.census_year AS programs_census_survey_year
      FROM (
        SELECT
          college_id, ipeds_unit_id, name, state, control, region, locale,
          undergrad_enrollment_headcount, admission_rate_share, net_price_per_year_usd,
          completion_rate_150pct_4yr_share,
          -- The price this query is on, per row (RFC 169) -- the same expression
          -- the ordinary page statement carries, so a peer list and a search
          -- print one college's price the same way.
          ${query.filters.priceRuler.valueSql()} AS $RULER_PRICE_COLUMN,
          ${plan.distanceExpression} AS distance$scoredSelects
        FROM college_search_index
        WHERE ${plan.search.matchClause} AND ${plan.sharedAxisClause}
        -- `NULLS LAST` cannot fire: the shared-axis clause admits no NULL
        -- distance. It is stated anyway so that a row slipping through a future
        -- relaxation sinks to the end of the page instead of leading it, as
        -- PostgreSQL's default NULLS FIRST would, and the read then raises.
        ORDER BY distance ASC NULLS LAST, ipeds_unit_id ASC
        LIMIT ?
      ) i
      JOIN colleges c ON c.id = i.college_id
      LEFT JOIN college_ipeds ci ON ci.ipeds_unit_id = i.ipeds_unit_id
      $PUBLISHED_TUITION_TIERS_LATERAL
      $CANONICAL_COHORT_LATERAL
      LEFT JOIN LATERAL (
        SELECT $titlesSelect AS titles, max(pc.survey_year) AS census_year
        FROM college_programs_census pc
        JOIN cip_codes cc ON cc.code = pc.cip_code
        WHERE pc.college_id = i.college_id
        $censusRestriction
      ) t ON TRUE
      ORDER BY i.distance ASC NULLS LAST, i.ipeds_unit_id ASC
      """.trimIndent()

    return session.queryList(
      sql,
      bind = { stmt ->
        // The select list is written before the `WHERE`, so the distance binds
        // come first; the plan owns both sequences, so neither can drift.
        var idx = plan.bindDistance(stmt)
        idx = plan.search.bindPredicate(stmt, idx)
        stmt.setInt(idx++, query.limit)
        if (matchedCodes != null) jsonbArrayBinder(matchedCodes)(stmt, idx++)
      },
      map = { rs ->
        SimilarityMatch(
          match = mapMatch(rs),
          // `nullif(..., 0)` makes the distance NULL for a candidate sharing no
          // axis with the anchor, and `sharedAxisClause` excludes exactly those
          // rows. A NULL here is therefore a broken query, never a peer -- and
          // `getDouble`'s 0.0 sentinel would read as "identical to the anchor"
          // and sort it FIRST, so it is raised rather than read back.
          distance =
            rs.doubleOrNull("distance")
              ?: error("similarity distance is NULL for a row the shared-axis clause admitted"),
          // Which axes this college was actually judged on -- read from the
          // SAME predicate the arithmetic used, not recomputed in Kotlin from
          // the columns, so the number in `distance` and the list beside it
          // cannot describe different axes.
          axesScored = plan.scoredSelects.map { it.first }.filter { rs.getBoolean(scoredColumn(it)) },
        )
      },
    )
  }

  /**
   * The SQL identifier fragment for [axis], taken from the enum CONSTANT rather
   * than from [SimilarityAxis.word]: a constant is a Kotlin identifier by
   * construction and so a legal SQL one, while the word is model-facing copy a
   * product decision may rewrite with a space or a hyphen in it.
   */
  private fun axisAlias(axis: SimilarityAxis): String = axis.name.lowercase()

  /** The `scored_<axis>` column name both sides of the ranked query quote. */
  private fun scoredColumn(axis: SimilarityAxis): String = "scored_${axisAlias(axis)}"

  /**
   * The counts, and the finished page — [countPage]'s shape over the similarity
   * predicate. ONE statement: the honest candidate population (every college
   * the constraints admit that shares an axis with the anchor), plus one
   * `excluded_unknown` arm per ranked axis and per supplied filter whose column
   * can be unknown.
   *
   * Both counts are CONSTRAINT-RELATIVE (D67 as amended): an axis arm counts
   * the colleges the caller's own constraints admit that CANNOT BE JUDGED on
   * that axis, not the colleges of the bare universe. Measured over the
   * universe the arms answered about schools the constraints had already
   * excluded, so a call narrowed to one state could report more unjudgeable
   * colleges than it considered candidates — two numbers the prompt tells the
   * coach to read aloud together.
   *
   * The one constraint an arm drops is the filter ABOUT that same column: a
   * filter over a nullable column is exactly what removes the rows that cannot
   * be judged on it, so counting them inside its own clause would always answer
   * zero and D68's "excluded and counted, never kept as maybe-cheaper" would
   * have nothing to report.
   *
   * A candidate sharing no axis at all is still visible: it is unjudgeable on
   * every one of them, so it appears under every axis name.
   */
  private fun countSimilarPage(
    session: SqlSession,
    plan: SimilarityPlan,
    matches: List<SimilarityMatch>,
    ruler: PriceRuler,
  ): Result<CollegeSimilarityOutcome> {
    val countSelects =
      buildList {
        add("count(*) FILTER (WHERE ${plan.search.matchClause} AND ${plan.sharedAxisClause}) AS total")
        plan.unknownAxes.forEach { axis ->
          add(
            "count(*) FILTER (WHERE ${plan.search.createMatchClauseExcluding(axis)} AND ${axis.condition}) " +
              "AS ${axis.countColumn}",
          )
        }
      }
    val countSql = "SELECT ${countSelects.joinToString(", ")} FROM college_search_index"

    return session
      .queryOne(
        countSql,
        // Every arm states a predicate of its own now, so every arm binds:
        // total first, then one arm per unknown axis, in select-list order —
        // the same list that wrote the text, so the two cannot drift.
        bind = { stmt ->
          var idx = plan.search.bindPredicate(stmt)
          plan.unknownAxes.forEach { axis -> idx = plan.search.bindPredicateExcluding(stmt, axis, idx) }
          idx
        },
        map = { rs ->
          val total = rs.getInt("total")
          val excluded = plan.unknownAxes.associate { axis -> axis.key to rs.getInt(axis.countColumn) }
          total to excluded
        },
      ).map { (total, excluded) ->
        CollegeSimilarityOutcome.Page(
          CollegeSimilarityPage(
            matches = matches,
            totalCandidates = total,
            excludedUnknown = excluded,
            sourceYears = sourceYears(matches.map { it.match }),
            priceRuler = ruler,
          ),
        )
      }
  }

  /**
   * A [SearchPlan] with the similarity RANKING layered over it (RFC 153 D66).
   *
   * The shared predicate stays unaware of it, so an ordinary search carries no
   * distance slot and no member it must never call: the distance is a
   * constructor requirement here, which is why "this plan carries no distance
   * expression" is not a state this type can be in and not a runtime check
   * anybody has to remember.
   */
  private class SimilarityPlan(
    val search: SearchPlan,
    private val distance: SimilarityDistance,
  ) {
    /** The DISTANCE expression, NULL for a candidate that shares no axis with the anchor. */
    val distanceExpression: String = distance.expression

    /** One `scored_<axis>` boolean per ranked axis, in axis order. */
    val scoredSelects: List<Pair<SimilarityAxis, String>> = distance.scoredSelects

    /**
     * The bind-free predicate "this candidate shares at least one axis with the
     * anchor". It carries no parameter on purpose: the count query restates it
     * without rebinding anything, and a `WHERE` cannot read the `distance`
     * output column.
     */
    val sharedAxisClause: String = distance.sharedAxisClause

    /**
     * The axes a candidate may not be measurable on (D67), then the filters'
     * unjudgeable columns — minus every filter column a ranked axis already
     * reads.
     *
     * `cheaper_than_anchor` with the `price` axis used to report net price
     * TWICE, once under a schema identifier and once under an axis word, as
     * though they were two different facts about a college. One subject, one
     * key: the axis says it, in the word the caller asked in.
     */
    val unknownAxes: List<UnknownAxis> =
      (
        distance.unknownAxes +
          search.unknownAxes.filterNot { filter ->
            distance.unknownAxes.any { axis -> filter.key in axis.subject.columns }
          }
      ).distinctBy { it.key }

    /**
     * Binds every parameter [distanceExpression] carries, starting at [from],
     * and returns the NEXT free index — the ranked query's select list is
     * written before its `WHERE`, so the distance binds come first.
     */
    fun bindDistance(
      stmt: PreparedStatement,
      from: Int = 1,
    ): Int {
      var idx = from
      distance.binders.forEach { bind -> bind(stmt, idx++) }
      return idx
    }
  }

  /** Maps a [findSimilarityAnchor] row. */
  private fun mapSimilarityAnchor(
    rs: ResultSet,
    ruler: PriceRuler,
  ): SimilarityAnchor {
    // The stored label is resolved ONCE, at the row boundary, and both halves
    // travel: the category this vocabulary defines (null when it defines none),
    // and the label exactly as stored, which is what a refusal must quote.
    val controlLabel = rs.getString("control")
    return SimilarityAnchor(
      id = CollegeId(UUID.fromString(rs.getString("college_id"))),
      name = rs.getString("name"),
      state = rs.getString("state"),
      control = InstitutionControl.fromLabel(controlLabel),
      controlLabel = controlLabel,
      locale = rs.getString("locale"),
      subjectSlugs = rs.getStringListOrNull("subject_slugs"),
      rulerPriceUsd = rs.intOrNull(RULER_PRICE_COLUMN),
      // Stamped at the READ, from the ruler this statement was built with: the
      // two figures above are ruler-dependent and neither says so alone.
      priceRuler = ruler,
      admissionRateShare = rs.doubleOrNull("admission_rate_share"),
      sizePercentile = rs.doubleOrNull("undergrad_enrollment_percentile_share"),
      selectivityPercentile = rs.doubleOrNull("selectivity_percentile_share"),
      pricePercentile = rs.doubleOrNull(RULER_SHARE_COLUMN),
      inDefaultUniverse = rs.getBoolean("in_default_universe"),
    )
  }

  /** The admission-rate percentile column, named once for the selectivity axis. */
  private const val ADMISSION_PERCENTILE = "admission_rate_percentile_share"

  /** The SAT-average percentile column, named once for the selectivity axis. */
  private const val SAT_PERCENTILE = "sat_average_percentile_share"

  /**
   * A candidate's SELECTIVITY position (RFC 153 D65): the mean of the INVERTED
   * admission-rate percentile and the SAT percentile over whichever of the two
   * the college reports, and SQL NULL when it reports neither. The inversion is
   * what makes both inputs point the same way — higher is harder to get into —
   * before they are averaged.
   */
  private const val SELECTIVITY_POSITION =
    "((coalesce(1 - $ADMISSION_PERCENTILE, 0) + coalesce($SAT_PERCENTILE, 0)) " +
      "/ nullif((CASE WHEN $ADMISSION_PERCENTILE IS NULL THEN 0 ELSE 1 END " +
      "+ CASE WHEN $SAT_PERCENTILE IS NULL THEN 0 ELSE 1 END), 0))"

  /**
   * The distance expression as ONE value: its text, its binds, the per-axis
   * "this candidate is judgeable" predicates the result reports, and the axes
   * whose unknowns are counted. Text and binds travel together for the same
   * reason [IndexFilter]'s do — there is no way to obtain one without the other.
   */
  private class SimilarityDistance(
    val expression: String,
    val binders: List<Bind>,
    val scoredSelects: List<Pair<SimilarityAxis, String>>,
    val sharedAxisClause: String,
    val unknownAxes: List<UnknownAxis>,
  )

  /** One axis's contribution to [SimilarityDistance]. */
  private class DistanceTerm(
    val axis: SimilarityAxis,
    val weight: Double,
    val scored: String,
    val difference: String,
    val binders: List<Bind>,
    /**
     * The index columns [scored] reads, so an `excluded_unknown` arm about this
     * axis can drop the caller's filters over them — the filters that removed
     * the very rows the arm counts.
     */
    val columns: Set<String>,
  )

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

      // BOTH price sorts order by the same output column, `ruler_price`, which
      // the statement computes from the ACTIVE ruler's own expression (RFC 169).
      // One key means the sort and the filter cannot end up on two measures, and
      // `CollegeQuery.init` has already refused the inactive ruler's word, so
      // there is nothing here to choose between.
      CollegeQuery.SortBy.IN_STATE_NET_PRICE_ASC, CollegeQuery.SortBy.PUBLISHED_PRICE_ON_CAMPUS_ASC -> {
        "${prefix}$RULER_PRICE_COLUMN ASC NULLS LAST"
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
   * The output column carrying the ACTIVE price ruler's value for one row (RFC
   * 169): computed once in the page statement's subquery, sorted on, and read
   * back as [CollegeMatch.rulerPriceUsd].
   *
   * It exists because the published ruler's value is an EXPRESSION over two tier
   * columns, so an `ORDER BY` in an outer query has nothing to name and a result
   * row has no column to read. Naming it once means the sort key and the number
   * the result prints are the same expression evaluated once, never two.
   */
  private const val RULER_PRICE_COLUMN = "ruler_price"

  /** The anchor's position on the active ruler's ladder, named for the same reason. */
  private const val RULER_SHARE_COLUMN = "ruler_price_share"

  /**
   * The three published tuition tiers of ONE returned row, read live from
   * `price_figures` — brief 0006 D19, and the reason it costs no column.
   *
   * It sits on the PAYLOAD half of both page statements, beside the join back to
   * `colleges`, and never on the filter subquery or the count: the label is a
   * fact about the at-most-25 rows being returned, not a thing anything filters,
   * sorts or ranks on. `CollegesDaoTest`'s scope pin is written that way on
   * purpose — its absence assertions are taken over the `FROM (` … `) i`
   * subquery, and it separately asserts the payload half DOES reach `colleges` —
   * so this is legal by that test's design rather than in spite of it.
   *
   * `DISTINCT ON (residency_basis) … ORDER BY academic_year DESC` serves the
   * LATEST year per tier, the same rule the rebuild and the cost path both use,
   * so the search label and a cost answer about the same school read the same
   * row. The outer aggregates see AT MOST ONE row per tier by construction, so
   * `max(...)` is a projection and not a comparison.
   *
   * Both halves of each tier travel: the amount says whether the school
   * publishes that tier, and the status says WHY when it does not. A row bearing
   * no value has a NULL amount and a real status; a tier with no row at all has
   * both NULL. Telling those apart is the whole of D19 — an `in_district` row
   * saying `not_applicable` is the publisher ANSWERING, and every other absence
   * is a district price nobody can speak about.
   *
   * One lateral over `price_figures_college_idx` for at most 25 rows, on a
   * statement that already joins `colleges`, `college_ipeds` and a census
   * lateral.
   */
  private val PUBLISHED_TUITION_TIERS_LATERAL =
    """
    LEFT JOIN LATERAL (
      SELECT
        max(f.amount_usd) FILTER (WHERE f.residency_basis = '${ResidencyBasis.IN_DISTRICT.value}')
          AS in_district_tuition_usd,
        max(f.status)     FILTER (WHERE f.residency_basis = '${ResidencyBasis.IN_DISTRICT.value}')
          AS in_district_tuition_status,
        max(f.amount_usd) FILTER (WHERE f.residency_basis = '${ResidencyBasis.IN_STATE.value}')
          AS in_state_tuition_usd,
        max(f.status)     FILTER (WHERE f.residency_basis = '${ResidencyBasis.IN_STATE.value}')
          AS in_state_tuition_status,
        max(f.amount_usd) FILTER (WHERE f.residency_basis = '${ResidencyBasis.OUT_OF_STATE.value}')
          AS out_of_state_tuition_usd,
        max(f.status)     FILTER (WHERE f.residency_basis = '${ResidencyBasis.OUT_OF_STATE.value}')
          AS out_of_state_tuition_status
      FROM (
        SELECT DISTINCT ON (pf.residency_basis) pf.residency_basis, pf.amount_usd, pf.status
        FROM price_figures pf
        WHERE pf.college_id = i.college_id
          AND pf.price_concept = '${PriceConcept.TUITION_AND_FEES.value}'
          AND pf.arrangement = '${FigureArrangement.NOT_APPLICABLE.value}'
          AND pf.residency_basis IN (
            '${ResidencyBasis.IN_DISTRICT.value}',
            '${ResidencyBasis.IN_STATE.value}',
            '${ResidencyBasis.OUT_OF_STATE.value}')
        ORDER BY pf.residency_basis, pf.academic_year DESC
      ) f
    ) tiers ON TRUE
    """.trimIndent()

  /**
   * The four canonical cohort addresses the search / similar payload serves
   * (RFC 176 D4), each named from the ONE catalogue in `:db`
   * ([CohortAddresses]) that the fill writing these cells names too.
   *
   * Which addresses THIS surface serves is a per-surface decision, so the list
   * lives here; what each address IS is not, so the members do not. The grid
   * holds exactly ONE address per measure, which is what lets the lateral
   * group by `(measure, income_band)` alone -- a rule now EVALUATED rather
   * than merely written down ([requireOneAddressPerProjectedMeasure]).
   */
  val SERVED_ADDRESSES: Set<CohortStatAddress> =
    setOf(
      CohortAddresses.AVG_NET_PRICE,
      CohortAddresses.MEDIAN_EARNINGS_10Y,
      CohortAddresses.MEDIAN_DEBT_AT_COMPLETION,
      CohortAddresses.PELL_SHARE,
    )

  /**
   * The address as a SQL row-value, generated from the enums and never typed
   * as literals (RFC 176 D4).
   *
   * A DAO-private extension, not a method on [CohortStatAddress]: the type
   * says WHERE a cell lives, and rendering that as one dialect's SQL is this
   * reader's business alone.
   */
  private fun CohortStatAddress.createTupleSql(alias: String): String =
    "($alias.measure, $alias.population, $alias.aid_scope) = " +
      "('${measure.value}', '${population.value}', '${aidScope.value}')"

  /**
   * ONE cell of this address: the triple PLUS the band axis, which is what
   * finishes naming a cell.
   *
   * The band parameter is REQUIRED, and `null` -- the store's own way of
   * saying "the overall figure" (`0083`) -- is spelled by this function rather
   * than by the caller. A single-row read used to state the address through
   * the type and then hand-write `AND cs.income_band IS NULL` beside it;
   * deleting that line compiled, and `LIMIT 1` then served some BAND's price
   * as the corpus-wide net-price ruler.
   */
  private fun CohortStatAddress.createCellSql(
    alias: String,
    incomeBand: IncomeBand?,
  ): String =
    createTupleSql(alias) +
      if (incomeBand == null) {
        " AND $alias.income_band IS NULL"
      } else {
        " AND $alias.income_band = '${incomeBand.value}'"
      }

  /**
   * How the rows of ONE cohort cell order, newest-and-right-scope first --
   * a TOTAL order, which is the whole point of it.
   *
   * Two rows of one cell are separated by exactly two columns the address does
   * not pin: `residency_scope` and `vintage` (`cohort_money_stats_natural_key`,
   * `0083`). Pin both and the natural key leaves exactly ONE row, so what the
   * reader serves can never be whichever row the planner happened to return
   * first.
   *
   * - **Scope first, and the rule is stated rather than pinned in the WHERE.**
   *   The scope a college's figures are quoted on is CONTROL-dependent, and
   *   both fills choose it that way: a public school's cohorts are
   *   in-state-rate-paying and everyone else's are `all` (RFC 157 --
   *   `CanonicalMoneyLoader.blendScope`, `CollegeSfaLoader.SfaFamily`).
   *   Pinning one scope in the WHERE would silently drop every public school's
   *   net price; preferring the one that MATCHES THIS ROW'S CONTROL serves the
   *   figure the publisher meant, and still answers when only the other exists.
   * - **Then vintage, dated years first and an UNDATED row (a NULL `vintage`
   *   since `0087`) last.** `ORDER BY vintage DESC` alone would put the NULLs
   *   FIRST -- PostgreSQL's default for a descending sort -- and an undated row
   *   would then outrank every dated one. `:service` states the same policy
   *   from the Kotlin end (`CollegeFigures.VINTAGE_ORDER`, a `nullsFirst()`
   *   read as "undated sorts oldest"), and that comparator exists because the
   *   value was once the literal `'undated'`, which sorted ABOVE every year by
   *   char code. This is the same accident, in the other language.
   *
   * `:service` meets the two-scope case and REFUSES it
   * (`CollegeFigures.cohortOf`: "one cohort address is one population basis").
   * The two layers differ ON PURPOSE. There, one college's cost answer is the
   * whole answer, and a wrong population basis is worth failing over. Here the
   * read is one row of a ranked PAGE and the same expression fills the whole
   * corpus's price ruler, so throwing would fail a search that asked nothing
   * about the offending school. A total order answers instead, by the same
   * rule the fills wrote by.
   *
   * [control] is the outer row's own control test, because the two callers
   * hold control in two shapes: `colleges.control` is the published SMALLINT
   * code, `college_search_index.control` is our word (RFC 150 D61). It is a
   * [ControlSource] and not the bare SQL, because the two tests also name two
   * different OUTER ALIASES (`c.` and `i.`): as strings they were swappable at
   * compile time, and the swap is a `42P01` at best -- at worst, in a statement
   * where both aliases are in scope, a silently inverted residency preference.
   *
   * The scope preference is a PREFERENCE and the vintage tie is broken by year,
   * so neither key is total on its own; `residency_scope` itself closes the
   * order last. Without it "0 before 1" is total only while
   * [CohortResidencyScope] has exactly two members -- a third would put two rows
   * of one cell in the losing rank with nothing to separate them, and
   * `DISTINCT ON` would serve whichever the planner returned first.
   */
  private fun createCohortRowOrderSql(
    alias: String,
    control: ControlSource,
  ): String =
    "(CASE WHEN $alias.residency_scope = (CASE WHEN ${control.isPublicSql} " +
      "THEN '${CohortResidencyScope.IN_STATE_RATE_PAYING.value}' " +
      "ELSE '${CohortResidencyScope.ALL.value}' END) THEN 0 ELSE 1 END), " +
      "$alias.vintage DESC NULLS LAST, $alias.residency_scope"

  /**
   * WHOSE control column [createCohortRowOrderSql] tests, as a TYPE rather than as a
   * hand-passed SQL string.
   *
   * Each member carries both halves of one caller's answer -- the outer alias
   * and the shape control is held in there -- so the two cannot be mixed: the
   * payload statement reads `college_search_index.control`, our word (RFC 150
   * D61), through `i`; the rebuild reads `colleges.control`, the published
   * code, through `c`.
   */
  private enum class ControlSource(
    val isPublicSql: String,
  ) {
    /** The payload half of the search / similar statements, where `i` is `college_search_index`. */
    SEARCH_INDEX_ROW("i.control = '${InstitutionControl.PUBLIC.label}'"),

    /** The index rebuild's SELECT, where `c` is `colleges`. */
    COLLEGE_ROW("c.control = ${InstitutionControl.PUBLIC.code}"),
  }

  /**
   * The lateral's aliases for the three overall figures, stated ONCE each.
   *
   * A name here is the join between three sites -- the cell the lateral
   * projects, the payload select list, and the row mapper's `rs.get...` -- and
   * three raw strings could disagree, which is a `42703` at best and a NULL
   * field at worst. The five band aliases already have this via
   * [netPriceColumn]; these are the same rule for the cells that do not
   * band.
   */
  private const val MEDIAN_EARNINGS_COLUMN = "median_earnings_10y"

  /** The lateral's alias for the median debt at completion. See [MEDIAN_EARNINGS_COLUMN]. */
  private const val MEDIAN_DEBT_COLUMN = "median_debt"

  /**
   * The lateral's alias for the Pell share. See [MEDIAN_EARNINGS_COLUMN].
   *
   * Deliberately NOT `pell_share`, which is one of the eighteen names `0094`
   * dropped from `colleges`: an alias that spells a dropped column would let a
   * leftover `c.pell_share` in some select list feed this mapper, which is the
   * one accident these aliases exist to make impossible.
   */
  private const val PELL_SHARE_COLUMN = "pell_share_of_undergraduates"

  /**
   * The money the search / similar payload carries about ONE returned row,
   * read live from `cohort_money_stats` -- RFC 176 D2, and the reason it costs
   * no column on `college_search_index`.
   *
   * It sits on the PAYLOAD half of both page statements, beside
   * [PUBLISHED_TUITION_TIERS_LATERAL] and shaped exactly like it: these are
   * DISPLAY values for the at-most-25 rows being returned, never rulers.
   * Nothing filters, sorts or ranks the corpus on them -- confirmed against
   * `CollegeQuery`, [PriceRuler] and `PriceRulerSql` -- so materialising them
   * into the index would put eight more columns into a table that exists to
   * rank a corpus, and leave every one of them one rebuild stale.
   *
   * Three rules are in the SQL and all three are load-bearing:
   * - **Each disjunct is a FULL address triple** ([SERVED_ADDRESSES]),
   *   generated from the house enums and never typed as a SQL literal.
   *   `cohort_money_stats` holds TWO `avg_net_price` series that differ by
   *   population and aid scope and NEVER by vintage, so a `(measure,
   *   income_band)` key would serve IPEDS SFA's grant-aided cohort as the
   *   Title IV band price -- RFC 166 tier-0 blocker 1, with every test green.
   * - **`DISTINCT ON (measure, income_band)` over [createCohortRowOrderSql] serves ONE
   *   row per cell**, the rule the cost path folds in Kotlin. The grid holds
   *   one address per measure, so those two columns name a cell exactly; the
   *   order then pins the two columns that separate two rows of one cell --
   *   residency scope, preferred to match this row's control, and vintage --
   *   so the natural key leaves exactly one candidate and the outer aggregates
   *   see AT MOST ONE row per cell by construction (`max(...)` is a
   *   projection, not a comparison).
   * - **No `value IS NOT NULL` filter.** A suppressed newest row is the
   *   publisher's own answer about the latest cohort; skipping it to reach an
   *   older figure would quote a year the school has since restated. The cost
   *   path takes the latest vintage and then reads its status, and this reads
   *   the same row it does.
   *
   * The dollar cells are rounded to whole dollars HALF UP
   * ([createWholeDollarsHalfUpSql]) -- the cost surface's own rule, so one school can
   * never quote two integers for one figure -- which is what the `INTEGER`
   * columns they replace held; the Pell share stays the fraction it is stored
   * as, on both sides.
   *
   * DECLARATION ORDER IS LOAD-BEARING. This initialiser runs eagerly, and the
   * named refusal [requireOneAddressPerProjectedMeasure] promises depends on
   * it standing BELOW [SERVED_ADDRESSES]: an object's properties initialise in
   * source order, so moving this line above that set would read it as `null`
   * and turn the refusal into a bare NPE inside the static initialiser.
   */
  private val CANONICAL_COHORT_LATERAL = createCanonicalCohortLateral()

  /** The money columns [CANONICAL_COHORT_LATERAL] contributes, for both payload select lists. */
  private val CANONICAL_COHORT_SELECT =
    (
      IncomeBand.entries.map { "money.${netPriceColumn(it)}" } +
        listOf(MEDIAN_EARNINGS_COLUMN, MEDIAN_DEBT_COLUMN, PELL_SHARE_COLUMN).map { "money.$it" }
    ).joinToString(", ")

  /**
   * The lateral's alias for one band's net price, named by the BAND'S OWN CODE
   * ([IncomeBand.value]) so the five aliases and the five bands cannot be
   * transposed by an edit to either list.
   *
   * Not the published `q1..q5` digit. That positional convention is exactly
   * what RFC 176 deleted from the Kotlin field names -- five parallel slots a
   * `when` could file under the wrong bracket -- and re-encoding it in the
   * alias would have put it back one layer down, on the same line as the band
   * code the WHERE is already generated from.
   */
  private fun netPriceColumn(band: IncomeBand): String = "net_price_income_${band.value}"

  /**
   * The ONE row of a cell, projected out of the already-deduplicated `s`.
   *
   * `max(...)` is a projection here rather than a comparison: `DISTINCT ON`
   * has already left at most one row per `(measure, income_band)`, so this
   * picks that row's value out of the group. Named, so the four cells state
   * their address the SAME way and a hand-written `FILTER` cannot address a
   * cell the deduplication never keyed.
   *
   * A null [band] is the store's own "overall figure" (`0083`) and is spelled
   * `IS NULL` by this function, never by the caller -- the rule
   * [CohortStatAddress.createCellSql] states for the single-row reads.
   */
  private fun createCohortCellValueSql(
    measure: MoneyMeasure,
    band: IncomeBand?,
  ): String =
    "max(s.value) FILTER (WHERE s.measure = '${measure.value}' " +
      if (band == null) "AND s.income_band IS NULL)" else "AND s.income_band = '${band.value}')"

  /**
   * One cohort value as whole dollars, rounded HALF UP.
   *
   * `floor(x + 0.5)`, and NOT SQL `round()`. PostgreSQL's `round()` breaks a
   * tie AWAY FROM ZERO, and `CollegeFigures.toWholeDollars` -- the cost
   * surface's rounding -- uses Kotlin's `roundToInt`, which breaks it UPWARD.
   * The two agree on every positive value and disagree on a negative half
   * dollar, which `cohort_money_stats.value` admits BY DESIGN (aid exceeding
   * cost at the lowest bands is why that column carries no non-negative
   * CHECK). A -1234.5 band net price would then read -1235 on search and
   * -1234 on the cost page: one school quoting two numbers for one figure.
   *
   * Stated once and used by every dollar cell in BOTH laterals, so the two
   * readers of this table cannot round differently again.
   */
  private fun createWholeDollarsHalfUpSql(valueSql: String): String = "floor(($valueSql) + 0.5)::INTEGER"

  /**
   * The lateral, assembled as ONE list of cells so the five band cells are
   * GENERATED from [IncomeBand] and the four scalar cells are stated once
   * each. Whitespace in generated SQL is not a style question here: the
   * statement text is what `CollegesDaoTest`'s scope pins read.
   *
   * The address set is CHECKED here, at the one site that can see both it and
   * the cells: see [SERVED_ADDRESSES].
   */
  private fun createCanonicalCohortLateral(): String {
    // The measures this lateral actually PROJECTS a cell for -- the second half
    // of the check below, written beside the cells rather than as a list to
    // maintain: an address at a measure no cell reads would be admitted by the
    // WHERE and then silently discarded.
    val bandedMeasure = MoneyMeasure.AVG_NET_PRICE
    val overallMeasures =
      listOf(MoneyMeasure.MEDIAN_EARNINGS_10Y, MoneyMeasure.MEDIAN_DEBT_AT_COMPLETION, MoneyMeasure.PELL_SHARE)
    requireOneAddressPerProjectedMeasure(listOf(bandedMeasure) + overallMeasures)

    val bandCells =
      IncomeBand.entries.map { band ->
        "${createWholeDollarsHalfUpSql(createCohortCellValueSql(bandedMeasure, band))} AS ${netPriceColumn(band)}"
      }
    val overallCells =
      listOf(
        "${createWholeDollarsHalfUpSql(createCohortCellValueSql(MoneyMeasure.MEDIAN_EARNINGS_10Y, band = null))} " +
          "AS $MEDIAN_EARNINGS_COLUMN",
        "${createWholeDollarsHalfUpSql(createCohortCellValueSql(MoneyMeasure.MEDIAN_DEBT_AT_COMPLETION, band = null))} " +
          "AS $MEDIAN_DEBT_COLUMN",
        // The Pell share is a 0-1 SHARE, not money ([MoneyMeasure.unit]), so it
        // is served as the fraction it is stored as. Rounded like a dollar cell
        // it would read 0 for every school.
        "${createCohortCellValueSql(MoneyMeasure.PELL_SHARE, band = null)} AS $PELL_SHARE_COLUMN",
      )
    val cells = (bandCells + overallCells).joinToString(",\n    ")
    val addresses = SERVED_ADDRESSES.joinToString("\n      OR ") { it.createTupleSql("cs") }
    return """
      LEFT JOIN LATERAL (
        SELECT
          $cells
        FROM (
          SELECT DISTINCT ON (cs.measure, cs.income_band)
                 cs.measure, cs.income_band, cs.value
          FROM cohort_money_stats cs
          WHERE cs.college_id = i.college_id
            AND ($addresses)
          ORDER BY cs.measure, cs.income_band, ${createCohortRowOrderSql("cs", ControlSource.SEARCH_INDEX_ROW)}
        ) s
      ) money ON TRUE
      """.trimIndent()
  }

  /**
   * Refuses a [SERVED_ADDRESSES] this lateral cannot serve: the set is
   * the reader's whole input boundary, and its precondition -- one address per
   * projected measure -- was documented three times and evaluated nowhere.
   *
   * Two things are refused, and they are different failures. A measure
   * addressed TWICE is admitted by the WHERE and then merged by
   * `DISTINCT ON (measure, income_band)` into one cell, so two populations'
   * numbers would be served under one label: RFC 166 tier-0 blocker 1, which
   * adding `SFA_GRANT_AIDED_NET_PRICE` to the set would have reintroduced
   * with every test green. A measure addressed but NOT projected is admitted
   * and then silently discarded, which is a set that no longer says what this
   * surface serves.
   *
   * At construction, so a wrong set cannot reach a query: the DAO fails to
   * initialise rather than answering a search with a cohort nobody asked for.
   */
  private fun requireOneAddressPerProjectedMeasure(projected: List<MoneyMeasure>) {
    val byMeasure = SERVED_ADDRESSES.groupBy { it.measure }
    val doubled = byMeasure.filterValues { it.size != 1 }.keys.map { it.value }
    require(doubled.isEmpty()) {
      "one measure, one address: [$doubled] is addressed more than once, and " +
        "DISTINCT ON (measure, income_band) would merge those cohorts into one cell"
    }
    require(byMeasure.keys == projected.toSet()) {
      "the served addresses and the projected cells must name the same measures: " +
        "addressed=[${byMeasure.keys.map { it.value }.sorted()}] " +
        "projected=[${projected.map { it.value }.sorted()}]"
    }
  }

  /** The tier columns [PUBLISHED_TUITION_TIERS_LATERAL] contributes, for both payload select lists. */
  private const val PUBLISHED_TUITION_TIERS_SELECT =
    "tiers.in_district_tuition_usd, tiers.in_district_tuition_status, " +
      "tiers.in_state_tuition_usd, tiers.in_state_tuition_status, " +
      "tiers.out_of_state_tuition_usd, tiers.out_of_state_tuition_status"

  /**
   * The four `price_figures` cells a published ON-CAMPUS total is made of (RFC
   * 169 D3), mirroring `CostBreakdown`'s own arrangement components: tuition and
   * fees at the residency tier, plus housing and food on campus, books and
   * supplies, and other expenses on campus.
   *
   * Residency lives on exactly ONE addend. Every living cost is
   * `residency_basis = 'not_applicable'` in 0083, so the tier enters through
   * `tuition_and_fees` alone — which is what makes "only the rate tier varies,
   * never the measure" true of the two value columns rather than merely
   * intended.
   *
   * Generated from the vocabulary enums, never typed as SQL literals, on the
   * [CONTROL_CASE] argument: the schema's foreign keys, these enums and this SQL
   * are three statements of one vocabulary and the hand-written third copy is
   * the one that drifts.
   */
  private val PUBLISHED_ON_CAMPUS_COMPONENTS:
    List<Triple<PriceConcept, ResidencyBasis?, FigureArrangement>> =
    listOf(
      Triple(PriceConcept.TUITION_AND_FEES, null, FigureArrangement.NOT_APPLICABLE),
      Triple(PriceConcept.HOUSING_AND_FOOD, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.ON_CAMPUS),
      Triple(PriceConcept.BOOKS_AND_SUPPLIES, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.NOT_APPLICABLE),
      Triple(PriceConcept.OTHER_EXPENSES, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.ON_CAMPUS),
    )

  /**
   * One `LEFT JOIN LATERAL` summing [PUBLISHED_ON_CAMPUS_COMPONENTS] at [tier]
   * into the published on-campus total for the college of the enclosing row, or
   * NULL — RFC 169 §5, statement 2.
   *
   * LEFT, so a college with no canonical money keeps its index row and the
   * column goes NULL: the established rule of this INSERT, and exactly the shape
   * `excluded_unknown` already counts.
   *
   * Three rules are in the SQL, and all three are load-bearing:
   * - **`count(*) = 4` IS the all-or-nothing rule.** Three components produce
   *   NULL, not a short total. A total missing its housing line is not a cheaper
   *   school, it is a school this ruler cannot rank, and quoting the short sum
   *   would put the wrong school first.
   * - **`amount_usd IS NOT NULL` is value-bearing-statuses-only.** It is
   *   equivalent to `status IN ('reported','imputed_by_publisher')` by
   *   `price_figures_value_iff_status_check`; the other four statuses are an
   *   absence to report, never a zero.
   * - **`DISTINCT ON ... academic_year DESC` serves the LATEST year per cell**
   *   (brief 0006 D15, store all / serve latest). IPEDS carries four years and
   *   the Scorecard writes one, so "the latest year" is not one year across the
   *   table: a literal year here would silently drop every Scorecard-only
   *   college. The natural key `UNIQUE (college_id, price_concept,
   *   residency_basis, arrangement, academic_year)` guarantees there is no tie
   *   left to break, so the pick is deterministic (D59).
   *
   * It is a SUM of published components and nothing else — never a subtraction,
   * never a blend, never `net_price_per_year_usd` (RFC 149; the guard scan
   * reaches this file for exactly that reason).
   */
  private fun createPublishedPriceLateral(
    tier: ResidencyBasis,
    alias: String,
  ): String {
    val cells =
      PUBLISHED_ON_CAMPUS_COMPONENTS.joinToString(",\n                ") { (concept, residency, arrangement) ->
        "('${concept.value}', '${(residency ?: tier).value}', '${arrangement.value}')"
      }
    return """
      LEFT JOIN LATERAL (
          SELECT CASE WHEN count(*) = ${PUBLISHED_ON_CAMPUS_COMPONENTS.size} THEN sum(f.amount_usd)::INTEGER END AS total
          FROM (
              SELECT DISTINCT ON (pf.price_concept, pf.residency_basis, pf.arrangement)
                     pf.amount_usd
              FROM price_figures pf
              WHERE pf.college_id = c.id
                AND pf.amount_usd IS NOT NULL
                AND (pf.price_concept, pf.residency_basis, pf.arrangement) IN (
                $cells)
              ORDER BY pf.price_concept, pf.residency_basis, pf.arrangement,
                       pf.academic_year DESC
          ) f
      ) $alias ON TRUE
      """.trimIndent()
  }

  /**
   * The OVERALL average net price of one college, read from
   * `cohort_money_stats` at [CohortAddresses.AVG_NET_PRICE] in full -- the
   * index rebuild's only money source (RFC 176 D3).
   *
   * `income_band IS NULL` is the store's own way of saying "the overall
   * figure" (`0083`: not unanswered, not unknown, and never a sentinel
   * `'overall'` band), so the five band rows of the SAME address cannot be
   * mistaken for it. It is stated through `createCellSql` rather than as a line of
   * hand-written SQL beside the address: the band is part of naming a cell,
   * and a caller that could forget it would rank the whole corpus on some
   * band's price.
   *
   * This is the address that made D4 necessary. The SFA fill writes a SECOND
   * overall `avg_net_price` -- a grant-aided, full-time first-time cohort --
   * at a NEWER vintage, so a lateral keyed on the measure alone would fill the
   * whole corpus's price ruler with a different cohort's number, ranking every
   * school against a figure the Scorecard never published. Population and aid
   * scope are what tell the two apart; the vintage never does.
   *
   * LEFT, so a college with no canonical money keeps its index row and the
   * column goes NULL -- the established rule of this INSERT, and the shape
   * `excluded_unknown` already counts.
   */
  private val CANONICAL_NET_PRICE_LATERAL =
    """
    LEFT JOIN LATERAL (
        SELECT ${createWholeDollarsHalfUpSql("cs.value")} AS net_price_per_year_usd
        FROM cohort_money_stats cs
        WHERE cs.college_id = c.id
          AND ${CohortAddresses.AVG_NET_PRICE.createCellSql("cs", incomeBand = null)}
        ORDER BY ${createCohortRowOrderSql("cs", ControlSource.COLLEGE_ROW)}
        LIMIT 1
    ) net ON TRUE
    """.trimIndent()

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
          published_price_in_state_on_campus_per_year_usd,
          published_price_out_of_state_on_campus_per_year_usd,
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
          -- The NET ruler's value, read from the canonical store at the FULL
          -- address (RFC 176 D3/D4). It used to be copied off the `colleges`
          -- column of the same name, which migration 0094 dropped; the INDEX
          -- column `net_price_per_year_usd` stays -- it IS a ruler, the default
          -- one and the no-residency-on-file fallback -- and only its SOURCE
          -- moved. `net_price_percentile_share` is ranked
          -- from it by [rankPercentiles], unchanged, so both stored shares
          -- remain positions on one ladder.
          net.net_price_per_year_usd,
          c.completion_rate_150pct_4yr_share,
          -- The published-price ruler (RFC 169), summed in the two LATERALs
          -- below. Four parts or no number, and never a subtraction: no aid can
          -- enter this measure by construction.
          ins.total,
          oos.total,
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
      ${createPublishedPriceLateral(ResidencyBasis.IN_STATE, "ins")}
      ${createPublishedPriceLateral(ResidencyBasis.OUT_OF_STATE, "oos")}
      $CANONICAL_NET_PRICE_LATERAL
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
   * ONE reading of the published ladder, as a CTE named [cte] over the universe
   * column [valueColumn] — the SQL half of "one ladder, two readings".
   *
   * Written once and called twice, for the reason [createPublishedPriceLateral] is:
   * the RFC's guarantee is that both stored shares are positions on ONE ladder,
   * and two hand-copied blocks make that guarantee a fact about two pieces of
   * text staying in step. Four things had to agree between them — the `n > 1`
   * guard, the STRICT `<`, the `least(…, 1.0)` cap and the `numeric(5,4)` cast —
   * and a divergence in any one of them would have moved a school's position on
   * one tier and not the other, silently.
   *
   * `least()` is capped at 1.0 rather than left to the division, and the
   * one-school corpus is guarded by `CASE WHEN n > 1` and NOT by a
   * `nullif(n - 1, 0)`: `least()` IGNORES a NULL argument, so a NULL quotient
   * would come back out of `least(NULL, 1.0)` as 1.0 and pin the only school in
   * the corpus to the top of the ladder.
   */
  private fun createPublishedLadderShareCte(
    cte: String,
    valueColumn: String,
  ): String =
    """
    $cte AS (
        SELECT u.college_id,
               (CASE WHEN s.n > 1 THEN
                   least((SELECT count(*) FROM ladder l WHERE l.v < u.$valueColumn)::numeric
                         / (s.n - 1), 1.0)
                END)::numeric(5,4) AS v
        FROM universe u CROSS JOIN ladder_size s
        WHERE u.$valueColumn IS NOT NULL)
    """.trimIndent()

  /**
   * Statement 3 of [rebuildSearchIndex]: the percentile `UPDATE`, over the
   * DEFAULT universe only (D52).
   *
   * The ranks are computed INDEPENDENTLY so a row missing one input still
   * ranks on the others, and the universe CTE joins `colleges` for
   * `sat_average_equivalent_score`, which D60 does not carry on the index: it is
   * the input to a percentile and nothing else. Rows OUTSIDE the default
   * universe are never touched and keep NULL — a percentile taken against the
   * 2-year rows and the system offices describes a corpus no student is
   * searching. The corpus is [DefaultUniverse]'s own words, not a second copy of
   * them, so it cannot drift from what a default search returns (D52).
   * `percent_rank()` is deterministic under ties (D59).
   *
   * The published price is the one axis with TWO stored shares and ONE ladder
   * (RFC 169 D2). The ladder's corpus is each school's OUT-OF-STATE published
   * on-campus total over the same default universe — one value per school, one
   * scale — and the in-state share is that school's in-state value's place on
   * THAT ladder, not a second `percent_rank()` over in-state values. Two ladders
   * would mean an anchor and a candidate on different tuition tiers were
   * compared on different scales, and `AnchoredAxis.Price` would then measure a
   * distance between two rulers.
   *
   * Postgres' `percent_rank()` is `(rank - 1) / (n - 1)`, where `rank - 1` is
   * the count of strictly smaller rows, so ONE expression serves both columns:
   *
   *     share(v) = least(count(corpus.value < v) / (n - 1), 1.0)
   *
   * evaluated at the row's own out-of-state value for one column and its
   * in-state value for the other. For a corpus member the two definitions are
   * identical, which a test pins against `percent_rank()` itself. `least(…, 1.0)`
   * bounds a value ABOVE the whole corpus (a possible in-state reading only in
   * principle, since the in-state total never exceeds the out-of-state one) so
   * the range CHECK can never be the thing that fails a rebuild. A one-school
   * corpus is guarded by `CASE WHEN n > 1`, NOT by a `nullif(n - 1, 0)` inside
   * the division: `least()` IGNORES a NULL argument, so a NULL quotient would
   * have come back out of `least(NULL, 1.0)` as 1.0 and put the only school in
   * the corpus at the very top of the ladder.
   *
   * Coverage honesty is the existing rule, unchanged: the CORPUS is not
   * restricted, only the ladder's POPULATION (`WHERE … IS NOT NULL`). A school
   * with no published total is absent from the ladder and keeps a NULL share —
   * dropped, counted and named by `excluded_unknown`, never substituted.
   */
  private fun rankPercentiles(session: SqlSession): Result<Int> =
    session.execute(
      """
      WITH universe AS (
          SELECT i.college_id, i.undergrad_enrollment_headcount,
                 i.admission_rate_share, i.net_price_per_year_usd,
                 i.published_price_in_state_on_campus_per_year_usd AS published_in,
                 i.published_price_out_of_state_on_campus_per_year_usd AS published_out,
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
          FROM universe WHERE net_price_per_year_usd IS NOT NULL),
      -- ONE ladder, read twice: the out-of-state totals of the default universe.
      ladder AS (
          SELECT published_out AS v FROM universe WHERE published_out IS NOT NULL),
      ladder_size AS (SELECT count(*)::numeric AS n FROM ladder),
      ${createPublishedLadderShareCte("published_out", "published_out")},
      ${createPublishedLadderShareCte("published_in", "published_in")}
      UPDATE college_search_index t
      SET undergrad_enrollment_percentile_share = e.v,
          admission_rate_percentile_share       = a.v,
          sat_average_percentile_share          = s.v,
          net_price_percentile_share            = p.v,
          published_price_out_of_state_on_campus_ladder_share = po.v,
          published_price_in_state_on_campus_ladder_share     = pi.v
      FROM universe u
      LEFT JOIN enrollment e ON e.college_id = u.college_id
      LEFT JOIN admission  a ON a.college_id = u.college_id
      LEFT JOIN sat        s ON s.college_id = u.college_id
      LEFT JOIN price      p ON p.college_id = u.college_id
      LEFT JOIN published_out po ON po.college_id = u.college_id
      LEFT JOIN published_in  pi ON pi.college_id = u.college_id
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
   *
   * It carries no money since RFC 176: the eighteen publisher money columns
   * left `colleges` in migration `0094`, and this list becomes SQL, so a name
   * left behind here would fail the ingest with `42703 column does not exist`
   * AFTER the load. Its twin, `CollegeScorecardLoader.NON_NULL_SUMMARY_COLUMNS`,
   * is the same list and shrank in the same commit. The money axis of the
   * provenance is `canonical_money_summary`.
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
      "completion_rate_150pct_4yr_share",
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
        search_index_rows, price_figure_rows, cohort_money_stat_rows,
        cohort_population_count_rows, aid_form_requirement_rows,
        canonical_money_summary, change_summary,
        method_version
      )
      VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
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
        stmt.setIntOrNull(7, input.priceFigureRows)
        stmt.setIntOrNull(8, input.cohortMoneyStatRows)
        stmt.setIntOrNull(9, input.cohortPopulationCountRows)
        stmt.setIntOrNull(10, input.aidFormRequirementRows)
        stmt.setJsonbOrNull(11, input.canonicalMoneySummary)
        stmt.setJsonbOrNull(12, input.changeSummary)
        stmt.setInt(13, input.methodVersion)
      },
      map = { rs -> UUID.fromString(rs.getString("id")) },
      mapError = ::mapCollegeWriteError,
    )
  }
}
