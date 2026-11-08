package ed.unicoach.db.dao

import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeTuitionExchange
import ed.unicoach.db.models.CollegeTuitionExchangeId
import ed.unicoach.db.models.ExchangeSelection
import ed.unicoach.db.models.NewCollegeTuitionExchange
import ed.unicoach.db.models.SeparateApplication
import ed.unicoach.db.models.TuitionExchange
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

/**
 * Read and write layer over `college_tuition_exchanges` (RFC 277), the
 * [NationalMeritAwardsDao] shape: a stateless object, one [SqlSession] per call,
 * and a write path that exists for the ingest loader and the fixtures.
 *
 * Expiry and eligibility are the service's to decide; this layer returns rows.
 */
object CollegeTuitionExchangesDao {
  private const val TABLE = "college_tuition_exchanges"

  /**
   * Every row for [collegeIds], each carrying its exchange's member states.
   *
   * One statement whatever the number of colleges. An empty [collegeIds]
   * short-circuits without a query, because `IN ()` is not valid SQL.
   */
  fun listByColleges(
    session: SqlSession,
    collegeIds: Collection<CollegeId>,
  ): Result<List<CollegeTuitionExchange>> {
    val ids = IdList(collegeIds.map { it.value })
    if (ids.isEmpty) return Result.success(emptyList())
    return session.queryList(
      """
      SELECT e.*, s.url AS source_url,
             ARRAY(SELECT m.state_code FROM tuition_exchange_states m WHERE m.exchange = e.exchange) AS member_states
      FROM $TABLE e
      JOIN external_documents s ON s.id = e.source_document_id
      WHERE e.college_id IN (${ids.placeholders})
      ORDER BY e.college_id, e.exchange
      """.trimIndent(),
      bind = { stmt -> ids.bindFrom(stmt, 1) },
      map = ::mapRow,
    )
  }

  /**
   * One row on its natural key `(college_id, exchange)`. A corrected re-read is
   * a correction of that row, and an unchanged corpus writes nothing and does
   * not advance `updated_at`, through the `IS DISTINCT FROM` guard.
   *
   * The citation goes through [ExternalDocumentsDao.citationIds], which cites
   * and never sweeps, so this path cannot overwrite a swept link status.
   */
  fun upsert(
    session: SqlSession,
    row: NewCollegeTuitionExchange,
  ): Result<WrittenRow<CollegeTuitionExchangeId>> =
    ExternalDocumentsDao
      .citationIds(session, row.sourceUrl, archiveUrl = null, row.checkedOn)
      .mapCatching { (sourceId, _) ->
        session
          .mutateReturning(
            """
            WITH up AS (
              INSERT INTO $TABLE (
                college_id, exchange, max_percent_of_resident_tuition, selection, separate_application,
                minimum_unweighted_gpa, percent_of_eligible_offered, apply_by,
                source_document_id, checked_on, expires_on
              ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              ON CONFLICT (college_id, exchange) DO UPDATE SET
                max_percent_of_resident_tuition = EXCLUDED.max_percent_of_resident_tuition,
                selection = EXCLUDED.selection,
                separate_application = EXCLUDED.separate_application,
                minimum_unweighted_gpa = EXCLUDED.minimum_unweighted_gpa,
                percent_of_eligible_offered = EXCLUDED.percent_of_eligible_offered,
                apply_by = EXCLUDED.apply_by,
                source_document_id = EXCLUDED.source_document_id,
                checked_on = EXCLUDED.checked_on,
                expires_on = EXCLUDED.expires_on
              WHERE ($TABLE.max_percent_of_resident_tuition, $TABLE.selection, $TABLE.separate_application,
                     $TABLE.minimum_unweighted_gpa, $TABLE.percent_of_eligible_offered, $TABLE.apply_by,
                     $TABLE.source_document_id, $TABLE.checked_on, $TABLE.expires_on)
                IS DISTINCT FROM
                    (EXCLUDED.max_percent_of_resident_tuition, EXCLUDED.selection, EXCLUDED.separate_application,
                     EXCLUDED.minimum_unweighted_gpa, EXCLUDED.percent_of_eligible_offered, EXCLUDED.apply_by,
                     EXCLUDED.source_document_id, EXCLUDED.checked_on, EXCLUDED.expires_on)
              RETURNING id, (xmax = 0) AS inserted
            )
            SELECT id, inserted FROM up
            UNION ALL
            SELECT e.id, NULL::boolean
            FROM $TABLE e
            WHERE e.college_id = ? AND e.exchange = ?
              AND NOT EXISTS (SELECT 1 FROM up)
            """.trimIndent(),
            bind = { stmt ->
              var idx = 1
              idx = bindNaturalKey(stmt, idx, row)
              stmt.setInt(idx++, row.maxPercentOfResidentTuition)
              stmt.setString(idx++, row.selection.value)
              stmt.setString(idx++, row.separateApplication.value)
              stmt.setObject(idx++, row.minimumUnweightedGpa)
              stmt.setIntOrNull(idx++, row.percentOfEligibleOffered)
              stmt.setDateOrNull(idx++, row.applyBy)
              stmt.setObject(idx++, sourceId.value)
              stmt.setObject(idx++, row.checkedOn)
              stmt.setObject(idx++, row.expiresOn)
              // The natural key again, for the arm that reads back the row the
              // IS DISTINCT FROM guard declined to write.
              bindNaturalKey(stmt, idx, row)
            },
            map = { rs -> WrittenRow(CollegeTuitionExchangeId(UUID.fromString(rs.getString("id"))), rs.upsertOutcome()) },
            mapError = { e -> mapReferenceWriteError(e, location(row)) },
          ).getOrThrow()
      }

  /** Removes every row except [keep], so a school dropped from the corpus stops being answered for. */
  fun deleteExcept(
    session: SqlSession,
    keep: Collection<CollegeTuitionExchangeId>,
  ): Result<Int> = session.deleteAllExcept(TABLE, keep.map { it.value })

  /** Binds the natural key at [firstIndex] and answers the next free slot. */
  private fun bindNaturalKey(
    stmt: PreparedStatement,
    firstIndex: Int,
    row: NewCollegeTuitionExchange,
  ): Int {
    var idx = firstIndex
    stmt.setObject(idx++, row.collegeId.value)
    stmt.setString(idx++, row.exchange.value)
    return idx
  }

  private fun location(row: NewCollegeTuitionExchange): String =
    "[$TABLE] row for college [${row.collegeId.value}] exchange [${row.exchange.value}] could not be written"

  private fun mapRow(rs: ResultSet): CollegeTuitionExchange {
    val id = CollegeTuitionExchangeId(UUID.fromString(rs.getString("id")))
    val location = "$TABLE (row [${id.value}])"
    val exchangeRaw = rs.getString("exchange")
    val selectionRaw = rs.getString("selection")
    val separateApplicationRaw = rs.getString("separate_application")
    return CollegeTuitionExchange(
      id = id,
      collegeId = CollegeId(UUID.fromString(rs.getString("college_id"))),
      exchange =
        TuitionExchange.fromValue(exchangeRaw)
          ?: throw corruptValue(exchangeRaw, "TuitionExchange", "$location.exchange"),
      memberStates = rs.getStringList("member_states").toSet(),
      maxPercentOfResidentTuition = rs.getInt("max_percent_of_resident_tuition"),
      selection =
        ExchangeSelection.fromValue(selectionRaw)
          ?: throw corruptValue(selectionRaw, "ExchangeSelection", "$location.selection"),
      separateApplication =
        SeparateApplication.fromValue(separateApplicationRaw)
          ?: throw corruptValue(separateApplicationRaw, "SeparateApplication", "$location.separate_application"),
      minimumUnweightedGpa = rs.getBigDecimal("minimum_unweighted_gpa"),
      percentOfEligibleOffered = rs.getIntOrNull("percent_of_eligible_offered"),
      applyBy = rs.getDate("apply_by")?.toLocalDate(),
      sourceUrl = rs.getString("source_url"),
      checkedOn = rs.getDate("checked_on").toLocalDate(),
      expiresOn = rs.getDate("expires_on").toLocalDate(),
    )
  }
}
