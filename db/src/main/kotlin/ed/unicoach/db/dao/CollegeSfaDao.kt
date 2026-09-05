package ed.unicoach.db.dao

import ed.unicoach.db.models.CollegeSfaCell
import ed.unicoach.db.models.IpedsImputationFlag
import ed.unicoach.db.models.NewCollegeSfaCell

/**
 * The IPEDS SFA staging table (RFC 162), created by
 * `0085.create-sfa-staging-and-population-counts.sql`.
 *
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned
 * by the caller. The table is never upserted: the `sfa` ingest phase is a
 * wholesale DELETE + batch re-insert of one pinned file, so idempotency is by
 * construction and a stale cell from a previous file cannot survive a run
 * that no longer publishes it -- which, for a source that restates prior
 * years, is the whole point.
 */
object CollegeSfaDao {
  /** Deletes every staged cell, returning how many went. */
  fun deleteAll(session: SqlSession): Result<Int> = session.execute("DELETE FROM college_sfa")

  /**
   * Batch-inserts [rows], returning how many landed. A plain INSERT, no ON
   * CONFLICT: the caller just deleted the table, so a collision is a defect in
   * the loader's own key discipline and deserves the constraint violation.
   */
  fun insertAll(
    session: SqlSession,
    rows: List<NewCollegeSfaCell>,
  ): Result<Int> =
    batchInsert(
      session,
      """
      INSERT INTO college_sfa (
        ipeds_unit_id, aid_year_start, variable, aid_year, value, publisher_flag
      )
      VALUES (?, ?, ?, ?, ?, ?)
      """.trimIndent(),
      rows,
      ::mapWriteError,
    ) { stmt, row ->
      stmt.setInt(1, row.ipedsUnitId)
      stmt.setInt(2, row.aidYearStart)
      stmt.setString(3, row.variable)
      stmt.setString(4, row.aidYear)
      stmt.setDoubleOrNull(5, row.value)
      stmt.setString(6, row.flag.code)
    }

  /**
   * Every cell staged for [aidYearStart] -- the START year of the FILE's own
   * aid year (SFA2223 -> 2022), which is NOT `college_ipeds.survey_year` (2023
   * for the same file set). Read whole rather than per institution: the fill
   * walks every college once, and one query beats 6,000.
   *
   * The year is BOUND, never inferred from whatever the table holds: this
   * table is only ever emptied by the `sfa` phase, so a run that supplied no
   * SFA group would otherwise read an EARLIER run's file as its own input.
   * The fill passes the year its argv declared.
   */
  fun allCells(
    session: SqlSession,
    aidYearStart: Int,
  ): Result<List<CollegeSfaCell>> =
    session.queryList(
      """
      SELECT ipeds_unit_id, variable, aid_year, value, publisher_flag
      FROM college_sfa
      WHERE aid_year_start = ?
      """.trimIndent(),
      bind = { stmt -> stmt.setInt(1, aidYearStart) },
      map = { rs ->
        val raw = rs.getString("publisher_flag")
        val value = rs.getBigDecimal("value")
        val ipedsUnitId = rs.getInt("ipeds_unit_id")
        val variable = rs.getString("variable")
        val aidYear = rs.getString("aid_year")
        CollegeSfaCell(
          ipedsUnitId = ipedsUnitId,
          variable = variable,
          aidYear = aidYear,
          value = value?.toDouble(),
          // The CHECK keeps the column inside the published code set, so an
          // unreadable letter here means the schema and this enum have
          // drifted -- loud, never a defaulted status. The ROW is named, not
          // just the letter: one bad cell in ~460k is only findable by its own
          // key, and every part of that key is already in this ResultSet.
          flag =
            IpedsImputationFlag.fromCode(raw)
              ?: error(
                "college_sfa holds a publisher_flag no IpedsImputationFlag reads: [$raw] at " +
                  "[ipeds_unit_id=$ipedsUnitId] [variable=$variable] [aid_year=$aidYear]",
              ),
        )
      },
    )

  /**
   * The write-path SQLSTATE mapping for a table that has NO foreign key:
   * `college_sfa.ipeds_unit_id` is the published UNITID, matched in the loader
   * against `colleges`, never referenced by the schema (an institution the
   * snapshot does not carry is a counted skip, not a rejected INSERT). So an
   * authored "referenced row not found" sentence would describe a refusal this
   * table cannot produce.
   *
   * What it CAN produce is the unique index (a repeated
   * institution/aid-year/variable cell, i.e. a defect in the loader's own key
   * discipline) and its CHECKs -- `college_sfa_not_applicable_has_no_value_check`
   * above all, an `A` cell that reached the batch still carrying a value. Both
   * arrive as [ConstraintViolationException] carrying the violated constraint
   * NAME and the server DETAIL, which is what says which cell broke it; passing
   * no FK message keeps every one of those and invents none.
   */
  private fun mapWriteError(e: java.sql.SQLException): Exception = mapChildWriteError(e, emptyMap())
}
