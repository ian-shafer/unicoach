package ed.unicoach.db.dao

import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.NewCohortMoneyStat
import ed.unicoach.db.models.NewPriceFigure
import ed.unicoach.db.models.NewResidencyBasis
import ed.unicoach.db.models.PriceConcept
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ValueBearingStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The canonical money store's constraints and DAO (RFC 158): the D3
 * value-iff-status CHECKs on both fact tables, the `NULLS NOT DISTINCT`
 * natural key, the vocabulary foreign keys as the no-unknown-slug (and P6
 * no-blend) rule, the year/vintage format CHECKs, `aid_policy_facts`'
 * one-value-shape CHECK, and the vocabulary upsert/delete-not-in discipline.
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
        "TRUNCATE TABLE colleges, price_figures, cohort_money_stats, aid_policy_facts, " +
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
    academicYear: String = "2022-23",
    reading: FigureReading<Int> = FigureReading.Present(11000, ValueBearingStatus.REPORTED),
    sourceVariable: String = "TUITIONFEE_IN",
  ) = NewPriceFigure(
    collegeId = collegeId,
    priceConcept = concept,
    residencyBasis = residency,
    arrangement = arrangement,
    academicYear = academicYear,
    reading = reading,
    source = "scorecard",
    sourceVariable = sourceVariable,
  )

  private fun cohortStat(
    collegeId: UUID,
    measure: MoneyMeasure = MoneyMeasure.AVG_NET_PRICE,
    incomeBand: IncomeBand? = null,
    vintage: String = "2021-22",
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
    source = "scorecard",
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
                "VALUES (?, 'tuition_and_fees', 'in_state', 'not_applicable', '2022-23', $amount, " +
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
                "'federal_aid_receiving', NULL, '2021-22', $value, '$status', 'scorecard', 'NPT4_PUB')",
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
              "VALUES (?, 'tuition_and_fees', NULL, 'not_applicable', '2022-23', 11000, 'reported', " +
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
            "VALUES (?, 'sticker_blend', 'in_state', 'not_applicable', '2022-23', 11000, 'reported', " +
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
  fun `a price row's academic_year must be a real YYYY-YY, never undated`() {
    val id = college()
    for (bad in listOf("2022", "undated", "2022-2023")) {
      val thrown =
        assertFailsWith<ConstraintViolationException> {
          CanonicalMoneyDao.insertPriceFigures(session, listOf(priceFigure(id, academicYear = bad))).getOrThrow()
        }
      assertEquals("price_figures_academic_year_format_check", thrown.constraint, "for [$bad]")
    }
  }

  @Test
  fun `a cohort vintage is YYYY-YY or the literal undated, nothing else`() {
    val id = college()
    CanonicalMoneyDao
      .insertCohortMoneyStats(
        session,
        listOf(
          cohortStat(
            id,
            measure = MoneyMeasure.PELL_SHARE,
            vintage = "undated",
            reading = FigureReading.Present(0.4, ValueBearingStatus.REPORTED),
          ),
        ),
      ).getOrThrow()
    val thrown =
      assertFailsWith<ConstraintViolationException> {
        CanonicalMoneyDao.insertCohortMoneyStats(session, listOf(cohortStat(id, vintage = "unknown"))).getOrThrow()
      }
    assertEquals("cohort_money_stats_vintage_format_check", thrown.constraint)
  }

  // ---------------------------------------------------------------------------
  // aid_policy_facts (D9): modeled ahead, constrained now
  // ---------------------------------------------------------------------------

  @Test
  fun `an aid policy row may carry one value shape, never both`() {
    val id = college()
    val thrown =
      assertFailsWith<Exception> {
        connection
          .prepareStatement(
            "INSERT INTO aid_policy_facts (college_id, policy, academic_year, value_boolean, value_number, " +
              "status, source) VALUES (?, 'meets_full_need', '2024-25', TRUE, 0.5, 'reported', 'cds')",
          ).use { stmt ->
            stmt.setObject(1, id)
            stmt.executeUpdate()
          }
      }
    assertTrue(thrown.message!!.contains("aid_policy_facts_one_value_shape_check"), thrown.message!!)
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
    assertEquals(3, CanonicalMoneyDao.deleteAllPriceFigures(session).getOrThrow())
    assertEquals(emptyMap<FigureStatus, Int>(), CanonicalMoneyDao.priceFigureCountsByStatus(session).getOrThrow())
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

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
