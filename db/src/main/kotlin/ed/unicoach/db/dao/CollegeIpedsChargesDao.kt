package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.ChargeKey
import ed.unicoach.db.models.CollegeIpedsCharge
import ed.unicoach.db.models.CollegeIpedsChargeId
import ed.unicoach.db.models.NewCollegeIpedsCharge
import java.sql.PreparedStatement
import java.util.UUID

/**
 * Data-access layer over `college_ipeds_charges`, the IPEDS IC_AY published
 * charges staging table (RFC 161).
 *
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned
 * by the caller — the [CollegeIpedsDao] shape it mirrors. The table is
 * unversioned reference data (RFC 84 composition, gate-2 D15): its history is
 * the `college_index_build` provenance row, so [upsert] reports the
 * inserted/changed/unchanged split itself rather than reading it off a version
 * column.
 *
 * Every write goes through the shared [upsertDetectingChange] primitive: each
 * value is a plain bound parameter, so a re-ingest of an unchanged snapshot
 * writes nothing and does not advance `updated_at`.
 */
object CollegeIpedsChargesDao {
  // ---------------------------------------------------------------------------
  // Writes
  // ---------------------------------------------------------------------------

  /**
   * Upserts one charge row on its natural key
   * `(college_id, charge_variable, academic_year)`, reporting the three-way
   * [UpsertOutcome]. Only `amount_usd` and `imputation_flag` are re-writable;
   * the key columns are the conflict target.
   */
  fun upsert(
    session: SqlSession,
    input: NewCollegeIpedsCharge,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = "college_ipeds_charges",
      keyColumns =
        linkedMapOf(
          "college_id" to { stmt: PreparedStatement, i: Int -> stmt.setObject(i, input.collegeId) },
          "charge_variable" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, input.chargeVariable) },
          "academic_year" to { stmt: PreparedStatement, i: Int -> stmt.setInt(i, input.academicYear.firstCalendarYear) },
        ),
      columns =
        linkedMapOf<String, Bind>(
          "amount_usd" to { stmt, i -> stmt.setIntOrNull(i, input.amountUsd) },
          "imputation_flag" to { stmt, i -> stmt.setString(i, input.imputationFlag) },
        ),
      mapError = ::mapCollegeWriteError,
    )

  /**
   * Deletes every staged row whose natural key this run did NOT WRITE — the
   * [ed.unicoach.db.dao.CodebooksDao.deleteSubjectsNotIn] precedent, applied to
   * a three-part key.
   *
   * Staging is upsert-only, so without this nothing ever leaves the table and
   * two real events go unnoticed. A survey-year bump adds the new window's rows
   * beside the old window's, which the canonical fill then meets as an academic
   * year it cannot decode. An institution that STOPS reporting into IC_AY keeps
   * its last rows forever, and — because the fill takes the first write per key
   * (upstream-wins) — those stale rows keep beating the Scorecard, so a family
   * is shown last year's price indefinitely. Both are silent; a delete is what
   * makes staging a snapshot of the loaded file rather than an accumulation of
   * every file ever loaded.
   *
   * The keep-set is the exact [ChargeKey]s written, matched as WHOLE triples by
   * an `unnest` of the three parallel arrays — never a cross-product of three
   * independent axes. The difference is a real row: a charge row whose own
   * upsert FAILED is not in this set, so it is retired here instead of
   * surviving with the previous file's amount and being served as current by
   * the canonical fill. Keys merely ATTEMPTED are not enough either, for the
   * same reason.
   *
   * The caller passes the keys it actually wrote, never an empty collection (an
   * empty keep-set matches nothing and would delete the table).
   *
   * `staged.college_id = c.college_id::text` casts the STORED uuid to text
   * rather than the bound key to uuid, because this repo's array binder has one
   * form and it is text-only ([jsonbArrayBinder]); there is no `uuid[]` binder
   * to reach for. The cast is on the column, so it defeats the natural-key
   * index and this DELETE plans as a sequential scan. That is deliberate and
   * not a defect to "fix": the table is ~180k narrow rows, the scan runs ONCE
   * per ingest inside a transaction that has just written every one of them,
   * and the alternative (a uuid[] binder) buys milliseconds for a second
   * binding path to keep correct.
   */
  fun deleteNotIn(
    session: SqlSession,
    stagedKeys: Collection<ChargeKey>,
  ): Result<Int> =
    session.execute(
      """
      DELETE FROM college_ipeds_charges c
      WHERE NOT EXISTS (
        SELECT 1
        FROM unnest($TEXT_ARRAY_PARAM, $TEXT_ARRAY_PARAM, $TEXT_ARRAY_PARAM)
             AS staged (college_id, charge_variable, academic_year)
        WHERE staged.college_id = c.college_id::text
          AND staged.charge_variable = c.charge_variable
          AND staged.academic_year = c.academic_year::TEXT
      )
      """.trimIndent(),
    ) { stmt ->
      jsonbArrayBinder(stagedKeys.map { it.collegeId.toString() })(stmt, 1)
      jsonbArrayBinder(stagedKeys.map { it.chargeVariable })(stmt, 2)
      jsonbArrayBinder(stagedKeys.map { it.academicYear.firstCalendarYear.toString() })(stmt, 3)
    }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /**
   * Every staged charge row, ordered by its natural key.
   *
   * The canonical-money fill reads STAGING rather than re-parsing the CSV, so
   * the canonical phase keeps its "rows first, derived state second" position
   * after `search-index` (RFC 158 P12). The whole table is ~180k rows of five
   * small columns; one read is cheaper than any per-college lookup and the
   * ordering makes a fill deterministic.
   */
  fun list(session: SqlSession): Result<List<CollegeIpedsCharge>> =
    session.queryList(
      """
      SELECT id, college_id, charge_variable, academic_year, amount_usd, imputation_flag,
             created_at, updated_at
      FROM college_ipeds_charges
      ORDER BY college_id, charge_variable, academic_year
      """.trimIndent(),
      bind = {},
      map = { rs ->
        CollegeIpedsCharge(
          id = CollegeIpedsChargeId(UUID.fromString(rs.getString("id"))),
          collegeId = UUID.fromString(rs.getString("college_id")),
          chargeVariable = rs.getString("charge_variable"),
          academicYear = AcademicYear(rs.getInt("academic_year")),
          amountUsd = rs.getInt("amount_usd").takeUnless { rs.wasNull() },
          imputationFlag = rs.getString("imputation_flag"),
          createdAt = rs.getInstant("created_at"),
          updatedAt = rs.getInstant("updated_at"),
        )
      },
    )

  /** The staged row count — the ingest summary's `ipeds_charges.rows` axis. */
  fun rowCount(session: SqlSession): Result<Int> =
    session.queryOne(
      "SELECT count(*) AS n FROM college_ipeds_charges",
      bind = {},
      map = { rs -> rs.getInt("n") },
    )
}
