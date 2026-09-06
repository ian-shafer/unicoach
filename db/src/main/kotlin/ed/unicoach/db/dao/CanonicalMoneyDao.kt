package ed.unicoach.db.dao

import ed.unicoach.db.models.FactTable
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NewAidForm
import ed.unicoach.db.models.NewAidFormRequirement
import ed.unicoach.db.models.NewArrangement
import ed.unicoach.db.models.NewCohortMoneyStat
import ed.unicoach.db.models.NewCohortPopulationCount
import ed.unicoach.db.models.NewFigureStatus
import ed.unicoach.db.models.NewIncomeBand
import ed.unicoach.db.models.NewPriceConcept
import ed.unicoach.db.models.NewPriceFigure
import ed.unicoach.db.models.NewResidencyBasis
import java.sql.PreparedStatement
import java.sql.SQLException

/**
 * The canonical money store (RFC 158): five authored vocabulary tables and the
 * two fact tables, created by `0083.create-canonical-money-tables.sql`.
 *
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned
 * by the caller -- the [CodebooksDao] shape. The vocabulary writes are
 * change-suppressed upserts plus a delete-not-in per table (the
 * `subjects` discipline: an authored row removed from the seed is removed from
 * the table, in the same transaction). The fact tables are never upserted:
 * the `canonical-money` phase is a wholesale DELETE + batch re-insert (P12),
 * so the write path here is [deleteFactsOfSources]/[insertPriceFigures] and
 * their cohort twins, and idempotency is by construction.
 */
object CanonicalMoneyDao {
  // ---------------------------------------------------------------------------
  // Vocabulary writes -- one change-suppressed upsert + delete-not-in per table.
  // ---------------------------------------------------------------------------

  fun upsertResidencyBasis(
    session: SqlSession,
    row: NewResidencyBasis,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = "residency_bases",
      keyColumns = linkedMapOf("slug" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, row.slug) }),
      columns =
        linkedMapOf<String, Bind>(
          "description" to { stmt, i -> stmt.setString(i, row.description) },
        ),
      mapError = ::mapWriteError,
    )

  fun upsertArrangement(
    session: SqlSession,
    row: NewArrangement,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = "arrangements",
      keyColumns = linkedMapOf("slug" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, row.slug) }),
      columns =
        linkedMapOf<String, Bind>(
          "description" to { stmt, i -> stmt.setString(i, row.description) },
          "is_living_arrangement" to { stmt, i -> stmt.setBoolean(i, row.isLivingArrangement) },
        ),
      mapError = ::mapWriteError,
    )

  fun upsertFigureStatus(
    session: SqlSession,
    row: NewFigureStatus,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = "figure_statuses",
      keyColumns = linkedMapOf("slug" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, row.slug) }),
      columns =
        linkedMapOf<String, Bind>(
          "description" to { stmt, i -> stmt.setString(i, row.description) },
          "value_bearing" to { stmt, i -> stmt.setBoolean(i, row.valueBearing) },
        ),
      mapError = ::mapWriteError,
    )

  fun upsertPriceConcept(
    session: SqlSession,
    row: NewPriceConcept,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = "price_concepts",
      keyColumns = linkedMapOf("slug" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, row.slug) }),
      columns =
        linkedMapOf<String, Bind>(
          "description" to { stmt, i -> stmt.setString(i, row.description) },
          "arrangement_varies" to { stmt, i -> stmt.setBoolean(i, row.arrangementVaries) },
        ),
      mapError = ::mapWriteError,
    )

  /** Upserts one `aid_forms` row (RFC 170, D4); the vocabulary shape above. */
  fun upsertAidForm(
    session: SqlSession,
    row: NewAidForm,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = "aid_forms",
      keyColumns = linkedMapOf("slug" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, row.slug) }),
      columns =
        linkedMapOf<String, Bind>(
          "description" to { stmt, i -> stmt.setString(i, row.description) },
        ),
      mapError = ::mapWriteError,
    )

  fun upsertIncomeBand(
    session: SqlSession,
    row: NewIncomeBand,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = "income_bands",
      keyColumns = linkedMapOf("slug" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, row.slug) }),
      columns =
        linkedMapOf<String, Bind>(
          "min_usd" to { stmt, i -> stmt.setInt(i, row.minUsd) },
          "max_usd" to { stmt, i -> stmt.setIntOrNull(i, row.maxUsd) },
          "bracket_label" to { stmt, i -> stmt.setString(i, row.bracketLabel) },
          "sort_order" to { stmt, i -> stmt.setInt(i, row.sortOrder) },
        ),
      mapError = ::mapWriteError,
    )

  /**
   * Deletes every row of [table] whose slug the seed no longer carries,
   * returning how many went. ONE parameterised delete over the closed
   * [VOCABULARY_TABLES] identifier allowlist rather than five pasted
   * statements. The caller must have cleared any fact rows still referencing
   * a retired slug first -- [MoneyVocabularyLoader] empties both fact tables
   * inside its own transaction when a retirement is pending (they are
   * wholesale-rebuilt by the `canonical-money` phase later the same run,
   * P12) -- otherwise the FK refusal here would wedge every subsequent run.
   */
  fun deleteVocabularyNotIn(
    session: SqlSession,
    table: String,
    keptSlugs: Collection<String>,
  ): Result<Int> {
    require(table in VOCABULARY_TABLES) {
      "deleteVocabularyNotIn: unknown vocabulary table [$table]; allowed: [$VOCABULARY_TABLES]"
    }
    return session.execute(
      "DELETE FROM $table WHERE slug <> ALL ($TEXT_ARRAY_PARAM)",
    ) { stmt ->
      jsonbArrayBinder(keptSlugs)(stmt, 1)
    }
  }

  /** Every stored slug of [table], sorted -- the agreement tests' read. */
  fun vocabularySlugs(
    session: SqlSession,
    table: String,
  ): Result<List<String>> {
    require(table in VOCABULARY_TABLES) {
      "vocabularySlugs: unknown vocabulary table [$table]; allowed: [$VOCABULARY_TABLES]"
    }
    return session.queryList(
      "SELECT slug FROM $table ORDER BY slug",
      bind = {},
      map = { rs -> rs.getString("slug") },
    )
  }

  // ---------------------------------------------------------------------------
  // Fact-table writes: wholesale delete + batch insert (P12).
  // ---------------------------------------------------------------------------

  /**
   * Deletes the rows of [table] published by [sources], returning how many
   * went -- the first half of the wholesale rebuild (P12).
   *
   * BY SOURCE, not the whole table, because the fact tables now have TWO
   * writers: the `canonical-money` phase rebuilds what it derives from the
   * Scorecard and the two IPEDS surveys, and the `cds` phase rebuilds what it
   * reads out of the Common Data Set seed (RFC 170). A blanket `DELETE FROM`
   * in either would silently erase the other's rows -- and, because `cds` runs
   * FIRST, the run would still exit green with the CDS figures gone. Each
   * writer rebuilds exactly what it publishes; a caller that really means
   * every row passes every source.
   *
   * [table] is a [FactTable] member rather than a table name: the identifier
   * reaches SQL, so it must not be caller data, and an enum makes the mistake
   * a compile error instead of a runtime `require` fired mid-transaction.
   */
  fun deleteFactsOfSources(
    session: SqlSession,
    table: FactTable,
    sources: Collection<MoneySource>,
  ): Result<Int> =
    session.execute(
      "DELETE FROM ${table.tableName} WHERE source = ANY ($TEXT_ARRAY_PARAM)",
    ) { stmt ->
      jsonbArrayBinder(sources.map { it.value })(stmt, 1)
    }

  /**
   * Batch-inserts [rows] into `price_figures`, returning how many landed. A
   * plain INSERT, no ON CONFLICT: the caller just deleted the table (P12), so
   * a collision is a defect in the fill's own key discipline and deserves the
   * constraint violation, not a silent overwrite.
   */
  fun insertPriceFigures(
    session: SqlSession,
    rows: List<NewPriceFigure>,
  ): Result<Int> =
    batchInsert(
      session,
      """
      INSERT INTO price_figures (
        college_id, price_concept, residency_basis, arrangement, academic_year,
        amount_usd, status, source, source_variable, publisher_flag
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
      rows,
    ) { stmt, row ->
      stmt.setObject(1, row.collegeId)
      stmt.setString(2, row.priceConcept.value)
      stmt.setString(3, row.residencyBasis.value)
      stmt.setString(4, row.arrangement.value)
      stmt.setInt(5, row.academicYear.firstCalendarYear)
      // The two D3 columns derive from the ONE reading (a value exists
      // exactly when the status bears one, by construction).
      stmt.setIntOrNull(6, (row.reading as? FigureReading.Present)?.value)
      stmt.setString(7, row.reading.status.value)
      stmt.setString(8, row.source.value)
      stmt.setString(9, row.sourceVariable)
      stmt.setStringOrNull(10, row.publisherFlag)
    }

  /** Batch-inserts [rows] into `cohort_money_stats`; see [insertPriceFigures]. */
  fun insertCohortMoneyStats(
    session: SqlSession,
    rows: List<NewCohortMoneyStat>,
  ): Result<Int> =
    batchInsert(
      session,
      """
      INSERT INTO cohort_money_stats (
        college_id, measure, population, residency_scope, aid_scope, income_band,
        vintage, value, status, source, source_variable, publisher_flag,
        source_document_id
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
      rows,
    ) { stmt, row ->
      stmt.setObject(1, row.collegeId)
      stmt.setString(2, row.measure.value)
      stmt.setString(3, row.population.value)
      stmt.setString(4, row.residencyScope.value)
      stmt.setString(5, row.aidScope.value)
      stmt.setStringOrNull(6, row.incomeBand?.value)
      stmt.setIntOrNull(7, row.vintage?.firstCalendarYear)
      stmt.setDoubleOrNull(8, (row.reading as? FigureReading.Present)?.value)
      stmt.setString(9, row.reading.status.value)
      stmt.setString(10, row.source.value)
      stmt.setString(11, row.sourceVariable)
      stmt.setStringOrNull(12, row.publisherFlag)
      stmt.setObject(13, row.sourceDocumentId?.value)
    }

  /** Batch-inserts [rows] into `cohort_population_counts` (RFC 162); see [insertPriceFigures]. */
  fun insertCohortPopulationCounts(
    session: SqlSession,
    rows: List<NewCohortPopulationCount>,
  ): Result<Int> =
    batchInsert(
      session,
      """
      INSERT INTO cohort_population_counts (
        college_id, population, residency_basis, arrangement, vintage,
        headcount, status, source, source_variable, publisher_flag,
        source_document_id
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
      rows,
    ) { stmt, row ->
      stmt.setObject(1, row.collegeId)
      stmt.setString(2, row.population.value)
      stmt.setString(3, row.residencyBasis.value)
      stmt.setString(4, row.arrangement.value)
      stmt.setInt(5, row.vintage.firstCalendarYear)
      stmt.setIntOrNull(6, (row.reading as? FigureReading.Present)?.value)
      stmt.setString(7, row.reading.status.value)
      stmt.setString(8, row.source.value)
      stmt.setString(9, row.sourceVariable)
      stmt.setStringOrNull(10, row.publisherFlag)
      stmt.setObject(11, row.sourceDocumentId?.value)
    }

  /**
   * Batch-inserts [rows] into `aid_form_requirements` (RFC 170); see
   * [insertPriceFigures] for why there is no ON CONFLICT.
   *
   * `is_required` is bound from the reading, so a row with an absent status
   * carries no value and a row with a value carries a value-bearing status --
   * the pairing cannot be got wrong at a call site.
   */
  fun insertAidFormRequirements(
    session: SqlSession,
    rows: List<NewAidFormRequirement>,
  ): Result<Int> =
    batchInsert(
      session,
      """
      INSERT INTO aid_form_requirements (
        college_id, aid_form, applicant_group, academic_year, is_required,
        status, source, source_variable, source_document_id
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
      rows,
    ) { stmt, row ->
      stmt.setObject(1, row.collegeId)
      stmt.setString(2, row.form.value)
      stmt.setString(3, row.applicantGroup.value)
      stmt.setInt(4, row.academicYear.firstCalendarYear)
      stmt.setBooleanOrNull(5, (row.reading as? FigureReading.Present)?.value)
      stmt.setString(6, row.reading.status.value)
      stmt.setString(7, row.source.value)
      stmt.setString(8, row.sourceVariable)
      stmt.setObject(9, row.sourceDocumentId.value)
    }

  /**
   * Every publisher still holding rows in any fact table, EXCLUDING [sources] --
   * "whose data would this deletion destroy?", asked before destroying it.
   *
   * The vocabulary phase needs it because a retirement deletes fact rows to
   * free a slug, and it may only delete rows this run writes back.
   */
  fun factSourcesOtherThan(
    session: SqlSession,
    sources: Collection<MoneySource>,
  ): Result<List<MoneySource>> =
    session
      .queryList(
        FactTable.entries.joinToString(" UNION ") { "SELECT DISTINCT source FROM ${it.tableName}" } +
          " ORDER BY source",
        bind = {},
        map = { rs -> rs.getString("source") },
      ).mapCatching { stored ->
        stored
          .map { raw ->
            MoneySource.fromValue(raw)
              ?: throw CorruptPersistedValueException(
                raw,
                ed.unicoach.common.models.ValidationError
                  .InvalidFormat(expected = "a known MoneySource value"),
                location = "a canonical fact table's source column",
              )
          }.filterNot { it in sources }
      }

  // ---------------------------------------------------------------------------
  // Provenance reads (P11).
  // ---------------------------------------------------------------------------

  /** Per-status row counts over `price_figures`, typed: an unknown stored slug is refused loudly, never smuggled as a string key. */
  fun priceFigureCountsByStatus(session: SqlSession): Result<Map<FigureStatus, Int>> = countsByStatus(session, "price_figures")

  /** Per-status row counts over `cohort_money_stats`, typed as above. */
  fun cohortMoneyStatCountsByStatus(session: SqlSession): Result<Map<FigureStatus, Int>> = countsByStatus(session, "cohort_money_stats")

  /** Per-status row counts over `cohort_population_counts`, typed as above (RFC 162). */
  fun cohortPopulationCountCountsByStatus(session: SqlSession): Result<Map<FigureStatus, Int>> =
    countsByStatus(session, "cohort_population_counts")

  /**
   * Per-SOURCE row counts over `price_figures` (RFC 161): the operator's one
   * view of the upstream-wins split, and the number that moves when a source's
   * coverage changes. Typed like the status counts -- a stored value no
   * [MoneySource] reads is refused loudly, which the 0084 domain CHECK should
   * already have prevented.
   */
  fun priceFigureCountsBySource(session: SqlSession): Result<Map<MoneySource, Int>> =
    countsBy(
      session,
      "price_figures",
      "source",
      domain = "MoneySource",
      accepted = MoneySource.entries.map { it.value },
      decode = MoneySource::fromValue,
    )

  /**
   * One `GROUP BY` over a coded column, decoded through the enum that owns it.
   *
   * The status breakdown and the source breakdown are the SAME read against a
   * different column and a different `fromValue`, and they were written twice.
   * A stored value the enum does not read is FATAL rather than a string key:
   * these counts land in the provenance row, and a provenance row that reports
   * a slug nothing can read is worse than a failed one.
   *
   * [table] and [column] are fixed DAO identifiers, never caller data -- the
   * interpolation rule this file already follows.
   *
   * [domain] and [accepted] are what the FAILURE says. "holds a value nothing
   * reads" names neither the enumeration that refused the value nor what it
   * would have accepted, so the reader of that line has to find the decode
   * function to learn either -- while the caller had both in hand. A stored
   * slug outside the domain is a migration and an enum that disagree, and the
   * message should be able to say which two.
   */
  private fun <T> countsBy(
    session: SqlSession,
    table: String,
    column: String,
    domain: String,
    accepted: List<String>,
    decode: (String) -> T?,
  ): Result<Map<T, Int>> =
    session
      .queryList(
        "SELECT $column, count(*) AS n FROM $table GROUP BY $column",
        bind = {},
        map = { rs ->
          val value = rs.getString(column)
          val decoded =
            decode(value)
              ?: error("[$table.$column] holds a slug no $domain reads: [$value]; the accepted values are $accepted")
          decoded to rs.getInt("n")
        },
      ).map { it.toMap() }

  private fun countsByStatus(
    session: SqlSession,
    table: String,
  ): Result<Map<FigureStatus, Int>> =
    countsBy(
      session,
      table,
      "status",
      domain = "FigureStatus",
      accepted = FigureStatus.entries.map { it.value },
      decode = FigureStatus::fromValue,
    )

  /**
   * One prepared statement, JDBC-batched: see [ed.unicoach.db.dao.batchInsert],
   * the shared writer this and the SFA staging rebuild both use.
   */
  private fun <T> batchInsert(
    session: SqlSession,
    sql: String,
    rows: List<T>,
    bind: (PreparedStatement, T) -> Unit,
  ): Result<Int> = batchInsert(session, sql, rows, ::mapWriteError, bind)

  /**
   * The write-path SQLSTATE mapping. A `23503` here can only be a fact row
   * naming a vocabulary slug (or college) that is not there -- the vocabulary
   * phase is a write precondition (P2) -- so the message says that instead of
   * a constant sentence; `23505`/`23514` keep the violated constraint name.
   */
  private fun mapWriteError(e: SQLException): Exception =
    mapReferenceWriteError(
      e,
      "Referenced row not found: a canonical money row names a vocabulary slug or college that does not exist " +
        "(the money-vocabulary phase is a write precondition, RFC 158 P2)",
    )

  /** The closed identifier allowlist for the two vocabulary reads/deletes above. */
  val VOCABULARY_TABLES: Set<String> =
    setOf("residency_bases", "arrangements", "figure_statuses", "price_concepts", "income_bands", "aid_forms")
}
