package ed.unicoach.db.dao

import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.NewCollegeIpeds
import ed.unicoach.db.models.NewCollegeProgramsCensus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.sql.PreparedStatement
import java.util.UUID

/**
 * Data-access layer over the two IPEDS reference tables (RFC 144):
 * `college_ipeds` and `college_programs_census`.
 *
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned by
 * the caller — the [CollegesDao] shape. Both writes are upsert-if-changed: the
 * `DO UPDATE` carries a `WHERE` comparing every curated column as a row-tuple
 * with `IS DISTINCT FROM`, so re-ingesting an unchanged snapshot writes nothing.
 * [upsertProgramsCensus] gets that statement from the shared
 * [upsertDetectingChange] primitive; [upsert] hand-writes it, because
 * `athletic_assoc` needs a value expression the primitive does not generate.
 *
 * Neither table is versioned (gate-2 D15): they are reference data whose history
 * is the `college_index_build` provenance row, so there is no `version` column to
 * read the inserted/changed/unchanged split off. Each upsert therefore reports
 * the split itself, from a `before` CTE reading the pre-statement snapshot
 * alongside the data-modifying one — no `xmax = 0` folklore, and no
 * whole-table pre-read.
 */
object CollegeIpedsDao {
  // ---------------------------------------------------------------------------
  // Writes
  // ---------------------------------------------------------------------------

  /**
   * Upserts one IPEDS attribute row on its natural key `ipeds_unit_id`, reporting the
   * three-way [UpsertOutcome]. `id` and `created_at` are preserved on conflict
   * and the `_03` trigger advances `updated_at` — but only when the `WHERE`
   * tuple compare finds a real difference, so an unchanged re-ingest leaves the
   * row byte-identical, `updated_at` included.
   *
   * This one statement stays hand-written rather than going through the shared
   * [upsertDetectingChange] primitive (which [upsertProgramsCensus] uses): the
   * primitive binds every column as a bare `?`, and `athletic_assoc` needs a
   * value EXPRESSION —
   * `ARRAY(SELECT jsonb_array_elements_text(?::jsonb))::smallint[]` — around its
   * parameter. Generalising the primitive to per-column value expressions for a
   * single call site is not worth the added surface; if a second such column
   * appears, that is the moment to do it.
   *
   * `athletic_assoc` is bound as ONE jsonb parameter and expanded to
   * `smallint[]` by Postgres rather than built client-side with
   * `Connection.createArrayOf` — the [updateAliases][CollegesDao.updateAliases]
   * precedent: the [SqlSession] boundary deliberately withholds the pooled
   * connection, and reaching through a returned statement to recover it would
   * make that boundary advisory.
   */
  fun upsert(
    session: SqlSession,
    input: NewCollegeIpeds,
  ): Result<UpsertOutcome> {
    val sql =
      """
      WITH before AS (
        SELECT 1 FROM college_ipeds WHERE ipeds_unit_id = ?
      ), up AS (
        INSERT INTO college_ipeds (
          ipeds_unit_id, survey_year, cy_active, death_year, closed_at, new_ipeds_unit_id,
          inst_level, ug_offer, sector, carnegie_basic, carnegie_size, cbsa,
          rel_affil, has_rotc, has_study_abroad, disability_band, registered_disability_percent,
          offers_housing, housing_capacity_headcount, application_fee_usd, athletic_assoc,
          football_conf, test_policy
        )
        VALUES (
          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
          ARRAY(SELECT jsonb_array_elements_text(?::jsonb))::smallint[], ?, ?
        )
        ON CONFLICT (ipeds_unit_id) DO UPDATE SET
          survey_year = EXCLUDED.survey_year,
          cy_active = EXCLUDED.cy_active,
          death_year = EXCLUDED.death_year,
          closed_at = EXCLUDED.closed_at,
          new_ipeds_unit_id = EXCLUDED.new_ipeds_unit_id,
          inst_level = EXCLUDED.inst_level,
          ug_offer = EXCLUDED.ug_offer,
          sector = EXCLUDED.sector,
          carnegie_basic = EXCLUDED.carnegie_basic,
          carnegie_size = EXCLUDED.carnegie_size,
          cbsa = EXCLUDED.cbsa,
          rel_affil = EXCLUDED.rel_affil,
          has_rotc = EXCLUDED.has_rotc,
          has_study_abroad = EXCLUDED.has_study_abroad,
          disability_band = EXCLUDED.disability_band,
          registered_disability_percent = EXCLUDED.registered_disability_percent,
          offers_housing = EXCLUDED.offers_housing,
          housing_capacity_headcount = EXCLUDED.housing_capacity_headcount,
          application_fee_usd = EXCLUDED.application_fee_usd,
          athletic_assoc = EXCLUDED.athletic_assoc,
          football_conf = EXCLUDED.football_conf,
          test_policy = EXCLUDED.test_policy
        WHERE (
          college_ipeds.survey_year, college_ipeds.cy_active, college_ipeds.death_year,
          college_ipeds.closed_at, college_ipeds.new_ipeds_unit_id, college_ipeds.inst_level,
          college_ipeds.ug_offer, college_ipeds.sector, college_ipeds.carnegie_basic,
          college_ipeds.carnegie_size, college_ipeds.cbsa, college_ipeds.rel_affil,
          college_ipeds.has_rotc, college_ipeds.has_study_abroad,
          college_ipeds.disability_band, college_ipeds.registered_disability_percent,
          college_ipeds.offers_housing, college_ipeds.housing_capacity_headcount,
          college_ipeds.application_fee_usd, college_ipeds.athletic_assoc,
          college_ipeds.football_conf, college_ipeds.test_policy, college_ipeds.ipeds_unit_id
        ) IS DISTINCT FROM (
          EXCLUDED.survey_year, EXCLUDED.cy_active, EXCLUDED.death_year,
          EXCLUDED.closed_at, EXCLUDED.new_ipeds_unit_id, EXCLUDED.inst_level,
          EXCLUDED.ug_offer, EXCLUDED.sector, EXCLUDED.carnegie_basic,
          EXCLUDED.carnegie_size, EXCLUDED.cbsa, EXCLUDED.rel_affil,
          EXCLUDED.has_rotc, EXCLUDED.has_study_abroad,
          EXCLUDED.disability_band, EXCLUDED.registered_disability_percent,
          EXCLUDED.offers_housing, EXCLUDED.housing_capacity_headcount,
          EXCLUDED.application_fee_usd, EXCLUDED.athletic_assoc,
          EXCLUDED.football_conf, EXCLUDED.test_policy, EXCLUDED.ipeds_unit_id
        )
        RETURNING 1
      )
      SELECT EXISTS (SELECT 1 FROM before) AS existed, EXISTS (SELECT 1 FROM up) AS wrote
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        // Two statements, one key: parameter 1 is the `before` CTE's lookup
        // (the pre-statement existence probe) and parameter 2 the INSERT's own
        // ipeds_unit_id. They are the same value on purpose, not a duplicate.
        stmt.setInt(1, input.ipedsUnitId)
        stmt.setInt(2, input.ipedsUnitId)
        stmt.setInt(3, input.surveyYear)
        stmt.setBoolean(4, input.cyActive)
        stmt.setIntOrNull(5, input.deathYear)
        stmt.setDateOrNull(6, input.closedAt)
        stmt.setIntOrNull(7, input.newIpedsUnitId)
        stmt.setIntOrNull(8, input.instLevel)
        stmt.setBooleanOrNull(9, input.ugOffer)
        stmt.setIntOrNull(10, input.sector)
        stmt.setIntOrNull(11, input.carnegieBasic)
        stmt.setIntOrNull(12, input.carnegieSize)
        stmt.setIntOrNull(13, input.cbsa)
        stmt.setIntOrNull(14, input.relAffil)
        stmt.setBooleanOrNull(15, input.hasRotc)
        stmt.setBooleanOrNull(16, input.hasStudyAbroad)
        stmt.setIntOrNull(17, input.disabilityBand)
        stmt.setDoubleOrNull(18, input.registeredDisabilityPercent)
        stmt.setBooleanOrNull(19, input.offersHousing)
        stmt.setIntOrNull(20, input.housingCapacityHeadcount)
        stmt.setIntOrNull(21, input.applicationFeeUsd)
        stmt.setString(22, JsonArray(input.athleticAssoc.map { JsonPrimitive(it) }).toString())
        stmt.setIntOrNull(23, input.footballConf)
        stmt.setIntOrNull(24, input.testPolicy)
      },
      map = ::mapUpsertOutcome,
      mapError = ::mapCollegeWriteError,
    )
  }

  /**
   * Upserts one program-census row on its natural key
   * `(college_id, cip_code, award_level)`, reporting the same three-way
   * [UpsertOutcome]. Only `awards_count` and `survey_year` are re-writable; the
   * key columns are the conflict target.
   *
   * Expressed through the shared [upsertDetectingChange] primitive (the
   * [CdsAdmissionsDao] precedent): every value is a plain bound parameter, so
   * there is nothing here the generated statement cannot say.
   */
  fun upsertProgramsCensus(
    session: SqlSession,
    input: NewCollegeProgramsCensus,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = "college_programs_census",
      keyColumns =
        linkedMapOf(
          "college_id" to { stmt: PreparedStatement, i: Int -> stmt.setObject(i, input.collegeId.value) },
          "cip_code" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, input.cipCode) },
          "award_level" to { stmt: PreparedStatement, i: Int -> stmt.setInt(i, input.awardLevel) },
        ),
      columns =
        linkedMapOf<String, Bind>(
          "awards_count" to { stmt, i -> stmt.setInt(i, input.awardsCount) },
          "survey_year" to { stmt, i -> stmt.setInt(i, input.surveyYear) },
        ),
      mapError = ::mapCollegeWriteError,
    )

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /**
   * Every college's surface id keyed by `ipeds_unit_id` (RFC 144): the IPEDS phases'
   * pre-load match set, read once (~6k rows) so a per-row lookup never hits the
   * DB and an IPEDS record with no owning college is counted and skipped rather
   * than inventing one.
   */
  fun collegeIdsByIpedsUnitId(session: SqlSession): Result<Map<Int, CollegeId>> =
    session
      .queryList(
        "SELECT ipeds_unit_id, id FROM colleges",
        bind = {},
        map = { rs -> rs.getInt("ipeds_unit_id") to CollegeId(UUID.fromString(rs.getString("id"))) },
      ).map { it.toMap() }

  /**
   * Every `college_ipeds` column [nonNullCounts] may count — the closed
   * identifier allowlist, mirroring [CollegesDao.NON_NULL_COUNTABLE_COLUMNS].
   * SQL has no identifier binding, so the boundary is this set: anything outside
   * it never becomes SQL text. `cy_active` and `athletic_assoc` are absent
   * because they are NOT NULL — their count is the row count, which measures
   * nothing.
   */
  val NON_NULL_COUNTABLE_COLUMNS: Set<String> =
    setOf(
      "death_year",
      "closed_at",
      "new_ipeds_unit_id",
      "inst_level",
      "ug_offer",
      "sector",
      "carnegie_basic",
      "carnegie_size",
      "cbsa",
      "rel_affil",
      "has_rotc",
      "has_study_abroad",
      "disability_band",
      "registered_disability_percent",
      "offers_housing",
      "housing_capacity_headcount",
      "application_fee_usd",
      "football_conf",
      "test_policy",
    )

  /**
   * Non-null counts over the given `college_ipeds` columns in one SELECT (RFC
   * 144): the ingest change-summary's `non_null.college_ipeds` axis. [columns]
   * must be members of [NON_NULL_COUNTABLE_COLUMNS] — they are interpolated as
   * identifiers, not bound, so an unknown name is rejected here rather than
   * reaching SQL, and every accepted name is emitted double-quoted.
   */
  fun nonNullCounts(
    session: SqlSession,
    columns: List<String>,
  ): Result<Map<String, Int>> {
    val unknown = columns.toSet() - NON_NULL_COUNTABLE_COLUMNS
    require(unknown.isEmpty()) {
      "nonNullCounts: unknown college_ipeds column(s) ${unknown.sorted()}; " +
        "allowed: ${NON_NULL_COUNTABLE_COLUMNS.sorted()}"
    }
    val select = columns.joinToString(", ") { """count("$it") AS "$it"""" }
    return session.queryOne(
      "SELECT $select FROM college_ipeds",
      bind = {},
      map = { rs -> columns.associateWith { rs.getInt(it) } },
    )
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Reads the `(existed, wrote)` pair the attribute upsert returns into the three-way
   * [UpsertOutcome]. `wrote = false` can only mean the tuple compare found no
   * difference, so the row existed and is identical.
   */
  private fun mapUpsertOutcome(rs: java.sql.ResultSet): UpsertOutcome {
    val existed = rs.getBoolean("existed")
    val wrote = rs.getBoolean("wrote")
    return when {
      !wrote -> UpsertOutcome.UNCHANGED
      existed -> UpsertOutcome.CHANGED
      else -> UpsertOutcome.INSERTED
    }
  }
}
