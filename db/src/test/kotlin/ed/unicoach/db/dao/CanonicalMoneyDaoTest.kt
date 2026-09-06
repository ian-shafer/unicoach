package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.AidForm
import ed.unicoach.db.models.AidFormApplicantGroup
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FactTable
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NewAidFormRequirement
import ed.unicoach.db.models.NewCohortMoneyStat
import ed.unicoach.db.models.NewPriceFigure
import ed.unicoach.db.models.NewResidencyBasis
import ed.unicoach.db.models.NewSourceDocument
import ed.unicoach.db.models.PriceConcept
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.SourceDocumentId
import ed.unicoach.db.models.ValueBearingStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The canonical money store's constraints and DAO (RFC 158): the D3
 * value-iff-status CHECKs on both fact tables, the `NULLS NOT DISTINCT`
 * natural key, the vocabulary foreign keys as the no-unknown-slug (and P6
 * no-blend) rule, the `academic_year` domain, the RFC 170 forms relation and
 * its `source_documents` reference, and the vocabulary upsert/delete-not-in
 * discipline.
 */
class CanonicalMoneyDaoTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @org.junit.jupiter.api.BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        ed.unicoach.db.DatabaseConfig
          .from(config)
          .getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @org.junit.jupiter.api.AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) {
        connection.close()
      }
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    connection.createStatement().use { stmt ->
      stmt.execute(
        "TRUNCATE TABLE colleges, price_figures, cohort_money_stats, cohort_population_counts, " +
          "aid_form_requirements, source_documents, aid_forms, " +
          "residency_bases, arrangements, figure_statuses, price_concepts, income_bands, " +
          "ipeds_regions, us_states, nces_locales CASCADE",
      )
    }
    // P2's accepted cost, paid here: the vocabulary is a write precondition of
    // every fact row, so the fixture seeds it (from the committed seed file,
    // never a hand-typed copy) exactly as the codebook fixture serves 0067.
    CodebookReferenceFixture.seed(connection)
    MoneyVocabularyFixture.seed(connection)
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun college(ipedsUnitId: Int = 110100): UUID =
    CollegesDao
      .upsert(session, newCollegeFixture(ipedsUnitId))
      .getOrThrow()
      .id.value

  private fun priceFigure(
    collegeId: UUID,
    concept: PriceConcept = PriceConcept.TUITION_AND_FEES,
    residency: ResidencyBasis = ResidencyBasis.IN_STATE,
    arrangement: FigureArrangement = FigureArrangement.NOT_APPLICABLE,
    academicYear: AcademicYear = AcademicYear(2022),
    reading: FigureReading<Int> = FigureReading.Present(11000, ValueBearingStatus.REPORTED),
    sourceVariable: String = "TUITIONFEE_IN",
  ) = NewPriceFigure(
    collegeId = collegeId,
    priceConcept = concept,
    residencyBasis = residency,
    arrangement = arrangement,
    academicYear = academicYear,
    reading = reading,
    source = MoneySource.SCORECARD,
    sourceVariable = sourceVariable,
  )

  private fun cohortStat(
    collegeId: UUID,
    measure: MoneyMeasure = MoneyMeasure.AVG_NET_PRICE,
    incomeBand: IncomeBand? = null,
    vintage: AcademicYear? = AcademicYear(2021),
    reading: FigureReading<Double> = FigureReading.Present(18000.0, ValueBearingStatus.REPORTED),
  ) = NewCohortMoneyStat(
    collegeId = collegeId,
    measure = measure,
    population = CohortPopulation.TITLE_IV_AIDED_UNDERGRADUATES,
    residencyScope = CohortResidencyScope.IN_STATE_RATE_PAYING,
    aidScope = CohortAidScope.FEDERAL_AID_RECEIVING,
    incomeBand = incomeBand,
    vintage = vintage,
    reading = reading,
    source = MoneySource.SCORECARD,
    sourceVariable = "NPT4_PUB",
  )

  // ---------------------------------------------------------------------------
  // D3: a value exists exactly when the status bears one (both tables)
  // ---------------------------------------------------------------------------

  @Test
  fun `a price value without a value-bearing status is refused, and so is the reverse`() {
    // The invalid pairings no longer COMPILE through NewPriceFigure (the
    // sealed FigureReading, D3), so this pins the CHECK as the backstop for
    // any writer that bypasses the Kotlin types: raw SQL, the only way left
    // to even express the pairing.
    val id = college()
    for ((amount, status) in listOf("11000" to "suppressed_by_publisher", "NULL" to "reported")) {
      val thrown =
        assertFailsWith<Exception>("amount=$amount status=$status") {
          connection
            .prepareStatement(
              "INSERT INTO price_figures (college_id, price_concept, residency_basis, arrangement, " +
                "academic_year, amount_usd, status, source, source_variable) " +
                "VALUES (?, 'tuition_and_fees', 'in_state', 'not_applicable', 2022, $amount, " +
                "'$status', 'scorecard', 'TUITIONFEE_IN')",
            ).use { stmt ->
              stmt.setObject(1, id)
              stmt.executeUpdate()
            }
        }
      assertTrue(thrown.message!!.contains("price_figures_value_iff_status_check"), thrown.message!!)
    }
  }

  @Test
  fun `a cohort value without a value-bearing status is refused, and so is the reverse`() {
    // The cohort twin of the raw-SQL backstop pin above.
    val id = college()
    for ((value, status) in listOf("18000.0" to "not_reported_by_institution", "NULL" to "reported")) {
      val thrown =
        assertFailsWith<Exception>("value=$value status=$status") {
          connection
            .prepareStatement(
              "INSERT INTO cohort_money_stats (college_id, measure, population, residency_scope, " +
                "aid_scope, income_band, vintage, value, status, source, source_variable) " +
                "VALUES (?, 'avg_net_price', 'title_iv_aided_undergraduates', 'in_state_rate_paying', " +
                "'federal_aid_receiving', NULL, 2021, $value, '$status', 'scorecard', 'NPT4_PUB')",
            ).use { stmt ->
              stmt.setObject(1, id)
              stmt.executeUpdate()
            }
        }
      assertTrue(thrown.message!!.contains("cohort_money_stats_value_iff_status_check"), thrown.message!!)
    }
  }

  @Test
  fun `a status row with no reason is unrepresentable -- residency is NOT NULL`() {
    val id = college()
    val thrown =
      assertFailsWith<Exception> {
        connection
          .prepareStatement(
            "INSERT INTO price_figures (college_id, price_concept, residency_basis, arrangement, " +
              "academic_year, amount_usd, status, source, source_variable) " +
              "VALUES (?, 'tuition_and_fees', NULL, 'not_applicable', 2022, 11000, 'reported', " +
              "'scorecard', 'TUITIONFEE_IN')",
          ).use { stmt ->
            stmt.setObject(1, id)
            stmt.executeUpdate()
          }
      }
    assertTrue(thrown.message!!.contains("residency_basis"), thrown.message!!)
  }

  // ---------------------------------------------------------------------------
  // The natural keys
  // ---------------------------------------------------------------------------

  @Test
  fun `two price rows for the same cell collide on the natural key`() {
    val id = college()
    CanonicalMoneyDao.insertPriceFigures(session, listOf(priceFigure(id))).getOrThrow()
    val thrown =
      assertFailsWith<ConstraintViolationException> {
        CanonicalMoneyDao
          .insertPriceFigures(
            session,
            listOf(priceFigure(id, reading = FigureReading.Present(12000, ValueBearingStatus.REPORTED))),
          ).getOrThrow()
      }
    assertEquals("price_figures_natural_key", thrown.constraint)
  }

  @Test
  fun `NULLS NOT DISTINCT collides two overall cohort rows`() {
    // income_band NULL means "the overall figure" (P4): a second overall row
    // for the same cell must collide, which a plain UNIQUE index would allow.
    val id = college()
    CanonicalMoneyDao.insertCohortMoneyStats(session, listOf(cohortStat(id, incomeBand = null))).getOrThrow()
    val thrown =
      assertFailsWith<ConstraintViolationException> {
        CanonicalMoneyDao
          .insertCohortMoneyStats(
            session,
            listOf(cohortStat(id, incomeBand = null, reading = FigureReading.Present(1.0, ValueBearingStatus.REPORTED))),
          ).getOrThrow()
      }
    assertEquals("cohort_money_stats_natural_key", thrown.constraint)

    // A banded row beside the overall row is a DIFFERENT cell and lands fine.
    CanonicalMoneyDao.insertCohortMoneyStats(session, listOf(cohortStat(id, incomeBand = IncomeBand.UNDER_30K))).getOrThrow()
  }

  // ---------------------------------------------------------------------------
  // The vocabulary FKs (P2/P6)
  // ---------------------------------------------------------------------------

  @Test
  fun `an unknown vocabulary slug is refused by the foreign key`() {
    val id = college()
    assertFailsWith<NotFoundException> {
      connection
        .prepareStatement(
          "INSERT INTO price_figures (college_id, price_concept, residency_basis, arrangement, " +
            "academic_year, amount_usd, status, source, source_variable) " +
            "VALUES (?, 'sticker_blend', 'in_state', 'not_applicable', 2022, 11000, 'reported', " +
            "'scorecard', 'COSTT4_A')",
        ).use { stmt ->
          stmt.setObject(1, id)
          try {
            stmt.executeUpdate()
          } catch (e: java.sql.SQLException) {
            throw mapReferenceWriteError(e, "no such vocabulary row")
          }
        }
    }
  }

  @Test
  fun `an empty vocabulary refuses every fact write -- the phase is a precondition`() {
    val id = college()
    connection.createStatement().use { it.execute("DELETE FROM price_figures; DELETE FROM price_concepts") }
    val thrown =
      assertFailsWith<NotFoundException> {
        CanonicalMoneyDao.insertPriceFigures(session, listOf(priceFigure(id))).getOrThrow()
      }
    assertTrue(thrown.message!!.contains("money-vocabulary"), thrown.message!!)
  }

  // ---------------------------------------------------------------------------
  // Year / vintage format CHECKs (P5)
  // ---------------------------------------------------------------------------

  @Test
  fun `the academic_year domain admits a real start year and refuses one outside its range`() {
    val id = college()
    CanonicalMoneyDao.insertPriceFigures(session, listOf(priceFigure(id, academicYear = AcademicYear(2025)))).getOrThrow()

    // Through raw SQL, because the Kotlin type cannot spell the mistake: the
    // domain is what refuses it at the database, for every writer.
    val thrown =
      assertFailsWith<Exception> {
        connection
          .prepareStatement(
            "INSERT INTO price_figures (college_id, price_concept, residency_basis, arrangement, " +
              "academic_year, amount_usd, status, source, source_variable) " +
              "VALUES (?, 'tuition_and_fees', 'in_state', 'not_applicable', 2199, 11000, 'reported', " +
              "'scorecard', 'TUITIONFEE_IN')",
          ).use { stmt ->
            stmt.setObject(1, id)
            stmt.executeUpdate()
          }
      }
    assertTrue(thrown.message!!.contains("academic_year_check"), thrown.message!!)
  }

  @Test
  fun `an undated cohort statistic carries an absent vintage, never a sentinel year`() {
    val id = college()
    CanonicalMoneyDao
      .insertCohortMoneyStats(
        session,
        listOf(
          cohortStat(
            id,
            measure = MoneyMeasure.PELL_SHARE,
            vintage = null,
            reading = FigureReading.Present(0.4, ValueBearingStatus.REPORTED),
          ),
        ),
      ).getOrThrow()

    val stored =
      connection.createStatement().use { stmt ->
        stmt.executeQuery("SELECT count(*) AS n FROM cohort_money_stats WHERE vintage IS NULL").use { rs ->
          rs.next()
          rs.getInt("n")
        }
      }
    assertEquals(1, stored)
  }

  // ---------------------------------------------------------------------------
  // Forms and documents (RFC 170)
  // ---------------------------------------------------------------------------

  @Test
  fun `the money layer stores a start year, never a YYYY-YY string`() {
    // D14 in one assertion: the four columns that carried 'YYYY-YY' text are
    // SMALLINT under the academic_year domain, and the label is rendered.
    val typed =
      connection.createStatement().use { stmt ->
        stmt
          .executeQuery(
            "SELECT table_name || '.' || column_name AS col, data_type, domain_name " +
              "FROM information_schema.columns " +
              "WHERE (table_name, column_name) IN " +
              "(('price_figures', 'academic_year'), ('college_ipeds_charges', 'academic_year'), " +
              " ('cohort_money_stats', 'vintage'), ('cohort_population_counts', 'vintage'), " +
              " ('aid_form_requirements', 'academic_year'), ('source_documents', 'academic_year')) " +
              "ORDER BY col",
          ).use { rs ->
            buildList {
              while (rs.next()) add(Triple(rs.getString("col"), rs.getString("data_type"), rs.getString("domain_name")))
            }
          }
      }
    assertEquals(6, typed.size, "every money-layer year column must be present: $typed")
    for ((column, dataType, domain) in typed) {
      assertEquals("smallint", dataType, "[$column] must be a start year")
      assertEquals("academic_year", domain, "[$column] must carry the domain's range rule")
    }
    assertEquals("2025-26", AcademicYear(2025).label, "the label is rendered, never stored")
  }

  @Test
  fun `aid_policy_facts is gone, not extended`() {
    // D2: it was empty, with zero readers and zero writers, and its contents
    // were two different shapes. A table that still EXISTS is a table the next
    // slice can fill by accident.
    val present =
      connection.createStatement().use { stmt ->
        stmt.executeQuery("SELECT to_regclass('aid_policy_facts') IS NOT NULL AS present").use { rs ->
          rs.next()
          rs.getBoolean("present")
        }
      }
    assertFalse(present, "aid_policy_facts must be dropped (RFC 170, D2)")
  }

  @Test
  fun `one document per college, publisher and year`() {
    val id = college()
    val first = document(id)
    // The same natural key upserts the SAME row -- which is the whole point:
    // one filing, one url, however many facts cite it.
    assertEquals(first, document(id, sourceUrl = "https://example.edu/corrected.pdf"))
    assertEquals(
      "https://example.edu/corrected.pdf",
      scalarString("SELECT source_url FROM source_documents WHERE id = '${first.value}'"),
    )
    // A different year is a different document.
    assertTrue(document(id, academicYear = AcademicYear(2025)) != first)
  }

  @Test
  fun `a document url may not be an empty string`() {
    val id = college()
    val thrown =
      assertFailsWith<Exception> {
        SourceDocumentsDao
          .upsert(
            session,
            NewSourceDocument(
              collegeId = CollegeId(id),
              source = MoneySource.COMMON_DATA_SET,
              academicYear = AcademicYear(2024),
              sourceUrl = "",
              archiveUrl = null,
            ),
          ).getOrThrow()
      }
    assertTrue(causeChain(thrown).contains("source_documents_source_url_nonempty_check"), causeChain(thrown))
  }

  @Test
  fun `an aid form requirement lands on its natural key, once`() {
    val id = college()
    val document = document(id)
    assertEquals(1, CanonicalMoneyDao.insertAidFormRequirements(session, listOf(requirement(id, document))).getOrThrow())
    val thrown =
      assertFailsWith<Exception> {
        CanonicalMoneyDao.insertAidFormRequirements(session, listOf(requirement(id, document))).getOrThrow()
      }
    assertTrue(causeChain(thrown).contains("aid_form_requirements_natural_key"), causeChain(thrown))
  }

  @Test
  fun `the same form required of two applicant groups is two requirements`() {
    val id = college()
    val document = document(id)
    assertEquals(
      2,
      CanonicalMoneyDao
        .insertAidFormRequirements(
          session,
          listOf(
            requirement(id, document, group = AidFormApplicantGroup.DOMESTIC_FIRST_YEAR),
            requirement(id, document, group = AidFormApplicantGroup.NONRESIDENT_FIRST_YEAR),
          ),
        ).getOrThrow(),
    )
  }

  @Test
  fun `an applicant group outside the two published blocks is refused`() {
    val id = college()
    val document = document(id)
    val thrown =
      assertFailsWith<Exception> {
        rawRequirement(id, document, applicantGroup = "transfer_applicants", form = "fafsa")
      }
    assertTrue(thrown.message!!.contains("aid_form_requirements_applicant_group_check"), thrown.message!!)
  }

  @Test
  fun `a requirement carries a value exactly when its status bears one`() {
    val id = college()
    val document = document(id)
    // OUR gap (D7): not_collected_by_us, and so no value.
    assertEquals(
      1,
      CanonicalMoneyDao
        .insertAidFormRequirements(
          session,
          listOf(
            requirement(
              id,
              document,
              reading = FigureReading.Absent(AbsenceStatus.NOT_COLLECTED_BY_US),
            ),
          ),
        ).getOrThrow(),
    )
    val thrown =
      assertFailsWith<Exception> {
        rawRequirement(id, document, isRequired = "TRUE", status = "not_collected_by_us")
      }
    assertTrue(thrown.message!!.contains("aid_form_requirements_value_iff_status_check"), thrown.message!!)
  }

  @Test
  fun `a Common Data Set fact must cite its document, and every other publisher may not`() {
    val id = college()
    // The invariant the read depends on: the citation join is inner, so a CDS
    // fact written with no document would VANISH from the answer and the
    // school would be told we hold no filing for it. Per source, because the
    // Scorecard and IPEDS cite a national release and have no document row.
    val thrown =
      assertFailsWith<Exception> {
        connection
          .prepareStatement(
            "INSERT INTO cohort_money_stats (college_id, measure, population, residency_scope, aid_scope, " +
              "vintage, value, status, source, source_variable) " +
              "VALUES (?, 'avg_need_met_share', 'first_time_full_time_freshmen_awarded_need_based_grant', " +
              "'all', 'need_based_aid_receiving', 2024, 0.9, 'reported', 'common_data_set', 'H.209')",
          ).use { stmt ->
            stmt.setObject(1, id)
            stmt.executeUpdate()
          }
      }
    assertTrue(
      causeChain(thrown).contains("cohort_money_stats_cds_cites_document_check"),
      causeChain(thrown),
    )
    // The same row from a publisher with no per-school document is fine.
    CanonicalMoneyDao
      .insertCohortMoneyStats(
        session,
        listOf(cohortStat(id, measure = MoneyMeasure.PELL_SHARE, reading = FigureReading.Present(0.4, ValueBearingStatus.REPORTED))),
      ).getOrThrow()
  }

  @Test
  fun `every CDS fact must cite a document that exists`() {
    val id = college()
    val ghost = UUID.randomUUID()
    // source_document_id is a real reference, not a UUID-shaped column: a fact
    // citing a document nobody stored is a fact nobody can check, which is the
    // whole reason D13 made the document an entity. Table-driven, because all
    // four fact tables carry the same reference and a new one must not be able
    // to skip the check.
    val inserts =
      mapOf(
        "college_merit_aid" to
          "INSERT INTO college_merit_aid (college_id, source_year, source_document_id) VALUES (?, 2024, ?)",
        "college_admission_factors" to
          "INSERT INTO college_admission_factors (college_id, source_year, source_document_id) VALUES (?, 2024, ?)",
        "college_deadlines" to
          "INSERT INTO college_deadlines (college_id, source_year, round, offered, source_document_id) " +
          "VALUES (?, 2024, 'regular', TRUE, ?)",
        "aid_form_requirements" to
          "INSERT INTO aid_form_requirements (college_id, aid_form, applicant_group, academic_year, " +
          "is_required, status, source, source_variable, source_document_id) " +
          "VALUES (?, 'fafsa', 'domestic_first_year_aid_applicants', 2024, TRUE, 'reported', " +
          "'common_data_set', 'H.801', ?)",
      )
    for ((table, sql) in inserts) {
      val thrown =
        assertFailsWith<Exception>("[$table] accepted a document that does not exist") {
          connection.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, id)
            stmt.setObject(2, ghost)
            stmt.executeUpdate()
          }
        }
      assertTrue(
        causeChain(thrown).contains("${table}_source_document_id_fkey"),
        "[$table]: ${causeChain(thrown)}",
      )
    }
  }

  @Test
  fun `a requirement names a form the vocabulary carries, and a publisher the domain admits`() {
    val id = college()
    val document = document(id)
    val unknownForm =
      assertFailsWith<Exception> { rawRequirement(id, document, form = "parent_tax_return") }
    // The FK by NAME: "aid_form" alone also matches the natural key, the
    // applicant-group CHECK and the table itself, so it would pass for a
    // refusal that has nothing to do with the vocabulary.
    assertTrue(
      unknownForm.message!!.contains("aid_form_requirements_aid_form_fkey"),
      unknownForm.message!!,
    )

    // The publisher axis is a DOMAIN (RFC 170), so the refusal names
    // `money_source_check` -- once, for every source column in the schema --
    // rather than a CHECK this table declares for itself.
    val unknownSource =
      assertFailsWith<Exception> { rawRequirement(id, document, source = "cds") }
    assertTrue(unknownSource.message!!.contains("money_source_check"), unknownSource.message!!)
  }

  // ---------------------------------------------------------------------------
  // Vocabulary upserts: the three-way split + delete-not-in
  // ---------------------------------------------------------------------------

  @Test
  fun `an unchanged vocabulary re-upsert writes nothing and leaves updated_at alone`() {
    val row = NewResidencyBasis("in_state", MoneyVocabularyFixtureDescriptions.inState)
    assertEquals(UpsertOutcome.UNCHANGED, CanonicalMoneyDao.upsertResidencyBasis(session, row).getOrThrow())
    val firstUpdatedAt = updatedAt("residency_bases", "in_state")

    assertEquals(
      UpsertOutcome.CHANGED,
      CanonicalMoneyDao
        .upsertResidencyBasis(session, row.copy(description = "relabelled"))
        .getOrThrow(),
    )
    assertTrue(updatedAt("residency_bases", "in_state") > firstUpdatedAt)

    assertEquals(
      UpsertOutcome.INSERTED,
      CanonicalMoneyDao.upsertResidencyBasis(session, NewResidencyBasis("in_country", "new row")).getOrThrow(),
    )
  }

  @Test
  fun `delete-not-in removes exactly the slugs the seed no longer carries`() {
    CanonicalMoneyDao.upsertResidencyBasis(session, NewResidencyBasis("in_country", "stray")).getOrThrow()
    val kept = ResidencyBasis.entries.map { it.value }
    assertEquals(1, CanonicalMoneyDao.deleteVocabularyNotIn(session, "residency_bases", kept).getOrThrow())
    assertEquals(kept.sorted(), CanonicalMoneyDao.vocabularySlugs(session, "residency_bases").getOrThrow())
  }

  @Test
  fun `the vocabulary table allowlist refuses an unknown identifier`() {
    assertFailsWith<IllegalArgumentException> {
      CanonicalMoneyDao.deleteVocabularyNotIn(session, "colleges", listOf("x"))
    }
  }

  // ---------------------------------------------------------------------------
  // Provenance reads
  // ---------------------------------------------------------------------------

  @Test
  fun `counts-by-status reports the per-status breakdown, and deleteAll empties it`() {
    val id = college()
    CanonicalMoneyDao
      .insertPriceFigures(
        session,
        listOf(
          priceFigure(id),
          priceFigure(
            id,
            residency = ResidencyBasis.OUT_OF_STATE,
            reading = FigureReading.Present(29000, ValueBearingStatus.REPORTED),
            sourceVariable = "TUITIONFEE_OUT",
          ),
          priceFigure(
            id,
            concept = PriceConcept.BOOKS_AND_SUPPLIES,
            residency = ResidencyBasis.NOT_APPLICABLE,
            reading = FigureReading.Absent(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION),
            sourceVariable = "BOOKSUPPLY",
          ),
        ),
      ).getOrThrow()

    assertEquals(
      mapOf(FigureStatus.REPORTED to 2, FigureStatus.NOT_REPORTED_BY_INSTITUTION to 1),
      CanonicalMoneyDao.priceFigureCountsByStatus(session).getOrThrow(),
    )
    assertEquals(
      3,
      CanonicalMoneyDao.deleteFactsOfSources(session, FactTable.PRICE_FIGURES, listOf(MoneySource.SCORECARD)).getOrThrow(),
    )
    assertEquals(emptyMap<FigureStatus, Int>(), CanonicalMoneyDao.priceFigureCountsByStatus(session).getOrThrow())
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** The `source_documents` row a CDS fact cites (RFC 170, D13). */
  private fun document(
    collegeId: UUID,
    academicYear: AcademicYear = AcademicYear(2024),
    sourceUrl: String = "https://example.edu/cds-2024-25.pdf",
  ): SourceDocumentId =
    SourceDocumentsDao
      .upsert(
        session,
        NewSourceDocument(
          collegeId = CollegeId(collegeId),
          source = MoneySource.COMMON_DATA_SET,
          academicYear = academicYear,
          sourceUrl = sourceUrl,
          archiveUrl = null,
        ),
      ).getOrThrow()

  private fun requirement(
    collegeId: UUID,
    sourceDocumentId: SourceDocumentId,
    form: AidForm = AidForm.FAFSA,
    group: AidFormApplicantGroup = AidFormApplicantGroup.DOMESTIC_FIRST_YEAR,
    reading: FigureReading<Boolean> = FigureReading.Present(true, ValueBearingStatus.REPORTED),
  ) = NewAidFormRequirement(
    collegeId = collegeId,
    form = form,
    applicantGroup = group,
    academicYear = AcademicYear(2024),
    reading = reading,
    source = MoneySource.COMMON_DATA_SET,
    sourceVariable = "H.801",
    sourceDocumentId = sourceDocumentId,
  )

  /**
   * A requirement written as raw SQL, for the states the Kotlin types refuse
   * to spell: an unknown applicant group, a value under an absent status, a
   * form outside the vocabulary, a publisher outside the domain. Each of those
   * is refused by the DATABASE, for every writer, which is what these tests
   * pin.
   */
  private fun rawRequirement(
    collegeId: UUID,
    sourceDocumentId: SourceDocumentId,
    form: String = "fafsa",
    applicantGroup: String = "domestic_first_year_aid_applicants",
    isRequired: String = "TRUE",
    status: String = "reported",
    source: String = "common_data_set",
  ) {
    connection
      .prepareStatement(
        "INSERT INTO aid_form_requirements (college_id, aid_form, applicant_group, academic_year, " +
          "is_required, status, source, source_variable, source_document_id) " +
          "VALUES (?, '$form', '$applicantGroup', 2024, $isRequired, '$status', '$source', 'H.801', ?)",
      ).use { stmt ->
        stmt.setObject(1, collegeId)
        stmt.setObject(2, sourceDocumentId.value)
        stmt.executeUpdate()
      }
  }

  /**
   * Every message in an exception's cause chain, joined. The DAO wraps a
   * driver fault in its own typed exception, so the CONSTRAINT that fired is a
   * cause down; asserting on the wrapper's own sentence would pass for any
   * database error at all.
   */
  private fun causeChain(error: Throwable): String =
    generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

  private fun scalarString(sql: String): String =
    connection.createStatement().use { stmt ->
      stmt.executeQuery(sql).use { rs ->
        rs.next()
        rs.getString(1)
      }
    }

  private fun updatedAt(
    table: String,
    slug: String,
  ): String =
    connection.prepareStatement("SELECT updated_at::text FROM $table WHERE slug = ?").use { stmt ->
      stmt.setString(1, slug)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getString(1)
      }
    }
}

/**
 * The seed's `in_state` description, read from the committed file so the
 * UNCHANGED assertion above compares against what the fixture actually
 * seeded -- never a hand-typed copy that would drift.
 */
private object MoneyVocabularyFixtureDescriptions {
  val inState: String by lazy {
    val root =
      kotlinx.serialization.json.Json
        .parseToJsonElement(MoneyVocabularyFixture.COMMITTED_FILE.readText())
        .let { it as kotlinx.serialization.json.JsonObject }
    (root.getValue("residency_bases") as kotlinx.serialization.json.JsonArray)
      .map { it as kotlinx.serialization.json.JsonObject }
      .first {
        (it.getValue("slug") as kotlinx.serialization.json.JsonPrimitive).content == "in_state"
      }.getValue("description")
      .let { (it as kotlinx.serialization.json.JsonPrimitive).content }
  }
}
