package ed.unicoach.db.dao

import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.MoneySource
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.UUID

/**
 * The program-grained money store (RFC 218): `program_money_stats`, created by
 * `db/schema/create-program-money-stats.sql`.
 *
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned
 * by the caller -- the [CanonicalMoneyDao] shape, and raw JDBC with `Result<T>`
 * returns rather than an ORM. The table is never upserted: the
 * `program-money` phase is a wholesale DELETE + batch re-insert, because the
 * Scorecard field-of-study file is a SNAPSHOT and this table is a projection of
 * it, so idempotency is by construction.
 *
 * It is a SIBLING of [CanonicalMoneyDao] and not a method on it: that DAO's
 * fill resolves competing publishers for one institution-level key, and these
 * figures have exactly one publisher and a different grain.
 */
object ProgramMoneyDao {
  /**
   * Deletes every `program_money_stats` row of [source] -- the rebuild's own
   * first step, bounded to the publisher this run is about to write back.
   *
   * SINGULAR, unlike [CanonicalMoneyDao.deleteFactsOfSources]: that table is
   * filled from three publishers under a precedence order, and this one has
   * exactly one. A `Collection` parameter here would be an argument that the
   * caller has a choice it does not have.
   */
  fun deleteFactsOfSource(
    session: SqlSession,
    source: MoneySource,
  ): Result<Int> =
    session.execute("DELETE FROM program_money_stats WHERE source = ?") { stmt ->
      stmt.setString(1, source.value)
    }

  /**
   * Every `college_programs` row, keyed as the publisher's field-of-study file
   * keys it -- the one read the fill needs to turn a CSV row into a program id.
   *
   * Read WHOLE, once, like `CollegeIpedsDao.collegeIdsByIpedsUnitId`: the fill
   * streams ~228,000 rows, and a per-row `SELECT` would be one round trip each.
   */
  fun programIdsByKey(session: SqlSession): Result<Map<ProgramKey, UUID>> =
    session
      .queryList(
        """
        SELECT c.ipeds_unit_id, p.cip_code, p.credential_level, p.id
          FROM college_programs p
          JOIN colleges c ON c.id = p.college_id
        """.trimIndent(),
        bind = {},
        map = { rs ->
          ProgramKey(
            ipedsUnitId = rs.getInt("ipeds_unit_id"),
            cipCode = rs.getString("cip_code"),
            credentialLevel = rs.getInt("credential_level"),
          ) to rs.getObject("id", UUID::class.java)
        },
      ).map { it.toMap() }

  /**
   * Batch-inserts [rows], returning how many landed. A plain INSERT, no ON
   * CONFLICT: the caller just deleted this source's rows, so a collision is a
   * defect in the fill's own key discipline and deserves the constraint
   * violation rather than a silent overwrite.
   *
   * The amount and the status are bound FROM THE SAME reading, so the pairing
   * cannot be got wrong at a call site.
   */
  fun batchInsert(
    session: SqlSession,
    rows: List<NewProgramMoneyStat>,
  ): Result<Int> =
    batchInsert(
      session,
      """
      INSERT INTO program_money_stats (
        college_program_id, measure,
        cohort_first_year, cohort_last_year,
        amount_usd, status, source, source_variable
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
      rows,
      ::mapWriteError,
    ) { stmt: PreparedStatement, row: NewProgramMoneyStat ->
      stmt.setObject(1, row.collegeProgramId)
      stmt.setString(2, row.measure.value)
      stmt.setInt(3, row.cohortFirstYear.firstCalendarYear)
      stmt.setInt(4, row.cohortLastYear.firstCalendarYear)
      stmt.setIntOrNull(5, (row.reading as? FigureReading.Present)?.value)
      stmt.setString(6, row.reading.status.value)
      stmt.setString(7, row.source.value)
      stmt.setString(8, row.sourceVariable)
    }

  /**
   * Per-status row counts, typed: an unknown stored slug is refused loudly
   * rather than smuggled to the operator summary as a string key. The
   * suppressed and not-reported counts are the point of the table -- two thirds
   * of its rows are absences, and RFC 206 exists because we once served those
   * as silence.
   */
  fun countsByStatus(session: SqlSession): Result<Map<FigureStatus, Int>> = countsByStatus(session, "program_money_stats")

  /**
   * The write-path SQLSTATE mapping. A missing reference here is a row naming a
   * `college_programs` id or a `figure_statuses` slug that is not there -- the
   * `fields` and `money-vocabulary` phases are both write preconditions -- so
   * the message says that rather than a constant sentence.
   */
  private fun mapWriteError(e: SQLException): Exception =
    mapReferenceWriteError(
      e,
      "Referenced row not found: a program money row names a college_programs id or a figure status slug that " +
        "does not exist (the fields and money-vocabulary phases are write preconditions)",
    )
}
