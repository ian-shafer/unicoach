package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NewCollegeProgram
import ed.unicoach.db.models.ProgramMoneyMeasure
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
 * `program_money_stats` and its DAO (RFC 218): every constraint asserted BY
 * NAME, the natural key, the rebuild-by-delete shape, and the
 * `ProgramMoneyMeasure` enum pinned to the SQL CHECK list in both directions.
 *
 * On `CanonicalMoneyDaoTest`'s shape: one connection from the harness DB, a
 * TRUNCATE + re-seed of the committed vocabulary per test, and an inline
 * anonymous [SqlSession] over the connection.
 */
class ProgramMoneyDaoTest {
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
        "TRUNCATE TABLE colleges, college_programs, program_money_stats, " +
          "residency_bases, arrangements, figure_statuses, price_concepts, income_bands, aid_forms, " +
          "ipeds_regions, us_states, nces_locales CASCADE",
      )
    }
    CodebookReferenceFixture.seed(connection)
    MoneyVocabularyFixture.seed(connection)
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun program(
    ipedsUnitId: Int = 110100,
    cipCode: String = "2607",
    credentialLevel: Int = 3,
  ): UUID {
    val college = CollegesDao.upsert(session, newCollegeFixture(ipedsUnitId)).getOrThrow()
    return CollegesDao
      .upsertProgram(
        session,
        NewCollegeProgram(
          collegeId = college.id,
          cipCode = cipCode,
          cipTitle = "Marine Biology and Biological Oceanography",
          credentialLevel = credentialLevel,
        ),
      ).getOrThrow()
      .id
      .value
  }

  private fun stat(
    programId: UUID,
    measure: ProgramMoneyMeasure = ProgramMoneyMeasure.MEDIAN_EARNINGS_4YR_AFTER_ENTRY,
    first: AcademicYear = AcademicYear(2017),
    last: AcademicYear = AcademicYear(2018),
    reading: FigureReading<Int> = FigureReading.Present(52000, ValueBearingStatus.REPORTED),
    sourceVariable: String = "EARN_MDN_4YR",
  ) = NewProgramMoneyStat(
    collegeProgramId = programId,
    measure = measure,
    cohortFirstYear = first,
    cohortLastYear = last,
    reading = reading,
    source = MoneySource.SCORECARD,
    sourceVariable = sourceVariable,
  )

  /** Raw SQL, because the Kotlin types refuse most of these pairings before the CHECK ever sees them. */
  private fun insertRaw(
    programId: UUID?,
    measure: String = "median_earnings_4yr_after_entry",
    first: Int = 2017,
    last: Int = 2018,
    amount: String = "52000",
    status: String = "reported",
    sourceVariable: String = "EARN_MDN_4YR",
  ) {
    connection
      .prepareStatement(
        "INSERT INTO program_money_stats (college_program_id, measure, cohort_first_year, " +
          "cohort_last_year, amount_usd, status, source, source_variable) " +
          "VALUES (?, '$measure', $first, $last, $amount, '$status', 'scorecard', '$sourceVariable')",
      ).use { stmt ->
        stmt.setObject(1, programId)
        stmt.executeUpdate()
      }
  }

  private fun rowCount(): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT count(*) FROM program_money_stats").use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  @Test
  fun `one row inserts and reads back under its status`() {
    val programId = program()
    assertEquals(1, ProgramMoneyDao.batchInsert(session, listOf(stat(programId))).getOrThrow())
    assertEquals(
      mapOf(FigureStatus.REPORTED to 1),
      ProgramMoneyDao.countsByStatus(session).getOrThrow(),
    )
  }

  @Test
  fun `a batch writes all three measures of one program, absences included`() {
    val programId = program()
    val written =
      ProgramMoneyDao
        .batchInsert(
          session,
          listOf(
            stat(programId),
            stat(
              programId,
              measure = ProgramMoneyMeasure.MEDIAN_DEBT_AT_COMPLETION,
              first = AcademicYear(2018),
              last = AcademicYear(2019),
              reading = FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
              sourceVariable = "DEBT_ALL_STGP_EVAL_MDN",
            ),
            stat(
              programId,
              measure = ProgramMoneyMeasure.MEDIAN_DEBT_10YR_MONTHLY_PAYMENT,
              first = AcademicYear(2018),
              last = AcademicYear(2019),
              reading = FigureReading.Absent(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION),
              sourceVariable = "DEBT_ALL_STGP_EVAL_MDN10YRPAY",
            ),
          ),
        ).getOrThrow()
    assertEquals(3, written)
    // The three silences stay three, and they are distinguishable in ONE read:
    // an absence row is stored on purpose (RFC 206).
    assertEquals(
      mapOf(
        FigureStatus.REPORTED to 1,
        FigureStatus.SUPPRESSED_BY_PUBLISHER to 1,
        FigureStatus.NOT_REPORTED_BY_INSTITUTION to 1,
      ),
      ProgramMoneyDao.countsByStatus(session).getOrThrow(),
    )
  }

  @Test
  fun `the rebuild is a delete of this source's rows, then a re-insert`() {
    val programId = program()
    ProgramMoneyDao.batchInsert(session, listOf(stat(programId))).getOrThrow()
    assertEquals(1, ProgramMoneyDao.deleteFactsOfSource(session, MoneySource.SCORECARD).getOrThrow())
    assertEquals(0, rowCount())
    ProgramMoneyDao.batchInsert(session, listOf(stat(programId))).getOrThrow()
    assertEquals(1, rowCount())
  }

  @Test
  fun `the delete is bounded to the source named -- another publisher's rows stay`() {
    val programId = program()
    ProgramMoneyDao.batchInsert(session, listOf(stat(programId))).getOrThrow()
    assertEquals(0, ProgramMoneyDao.deleteFactsOfSource(session, MoneySource.IPEDS_SFA).getOrThrow())
    assertEquals(1, rowCount())
  }

  @Test
  fun `an amount under a status that bears none is refused by name, and so is the reverse`() {
    val programId = program()
    for ((amount, status) in listOf("52000" to "suppressed_by_publisher", "NULL" to "reported")) {
      val thrown =
        assertFailsWith<Exception>("amount=$amount status=$status") {
          insertRaw(programId, amount = amount, status = status)
        }
      assertTrue(
        thrown.message!!.contains("program_money_stats_amount_iff_status_check"),
        thrown.message!!,
      )
    }
  }

  @Test
  fun `a negative amount is refused by name`() {
    val programId = program()
    val thrown = assertFailsWith<Exception> { insertRaw(programId, amount = "-1") }
    assertTrue(
      thrown.message!!.contains("program_money_stats_amount_nonnegative_check"),
      thrown.message!!,
    )
  }

  @Test
  fun `a cohort that ends before it starts is refused by name`() {
    val programId = program()
    val thrown = assertFailsWith<Exception> { insertRaw(programId, first = 2019, last = 2018) }
    assertTrue(
      thrown.message!!.contains("program_money_stats_cohort_years_ordered_check"),
      thrown.message!!,
    )
  }

  @Test
  fun `a duplicate natural key is refused by name`() {
    val programId = program()
    ProgramMoneyDao.batchInsert(session, listOf(stat(programId))).getOrThrow()
    val thrown = assertFailsWith<Exception> { insertRaw(programId) }
    assertTrue(thrown.message!!.contains("program_money_stats_unique_idx"), thrown.message!!)
  }

  @Test
  fun `the same measure for a DIFFERENT cohort is a different row, not a collision`() {
    // The natural key includes the cohort years on purpose: a snapshot that
    // re-dates a column adds rows beside the old ones rather than overwriting a
    // cohort nobody re-published.
    val programId = program()
    ProgramMoneyDao.batchInsert(session, listOf(stat(programId))).getOrThrow()
    ProgramMoneyDao
      .batchInsert(session, listOf(stat(programId, first = AcademicYear(2019), last = AcademicYear(2020))))
      .getOrThrow()
    assertEquals(2, rowCount())
  }

  @Test
  fun `an unknown measure is refused by name`() {
    val programId = program()
    val thrown = assertFailsWith<Exception> { insertRaw(programId, measure = "debt_to_earnings_ratio") }
    assertTrue(thrown.message!!.contains("program_money_stats_measure_check"), thrown.message!!)
  }

  @Test
  fun `an empty source_variable is refused by name`() {
    val programId = program()
    val thrown = assertFailsWith<Exception> { insertRaw(programId, sourceVariable = "") }
    assertTrue(
      thrown.message!!.contains("program_money_stats_source_variable_nonempty_check"),
      thrown.message!!,
    )
  }

  @Test
  fun `a row about a program that does not exist is refused`() {
    val thrown =
      assertFailsWith<Exception> {
        ProgramMoneyDao.batchInsert(session, listOf(stat(UUID.randomUUID()))).getOrThrow()
      }
    assertTrue(
      thrown.message!!.contains("college_programs") || thrown.message!!.contains("Referenced row not found"),
      thrown.message!!,
    )
  }

  @Test
  fun `a program's rows go when the program goes -- ON DELETE CASCADE`() {
    val programId = program()
    ProgramMoneyDao.batchInsert(session, listOf(stat(programId))).getOrThrow()
    connection.prepareStatement("DELETE FROM college_programs WHERE id = ?").use { stmt ->
      stmt.setObject(1, programId)
      stmt.executeUpdate()
    }
    assertEquals(0, rowCount())
  }

  @Test
  fun `the Kotlin measure enum and the SQL CHECK list are the same set, both ways`() {
    // Containment either way is not enough: a member with no CHECK value is a
    // row the database refuses, and a CHECK value with no member is a stored
    // slug nothing reads.
    val definition =
      connection
        .prepareStatement("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")
        .use { stmt ->
          stmt.setString(1, "program_money_stats_measure_check")
          stmt.executeQuery().use { rs ->
            assertTrue(rs.next(), "program_money_stats_measure_check is not defined on this database")
            rs.getString(1)
          }
        }
    val inCheck = Regex("'([a-z0-9_]+)'").findAll(definition).map { it.groupValues[1] }.toSet()
    assertEquals(ProgramMoneyMeasure.entries.map { it.value }.toSet(), inCheck, definition)
    // And every stored slug reads back through the one fromValue companion.
    for (value in inCheck) {
      assertEquals(value, ProgramMoneyMeasure.fromValue(value)?.value, "no ProgramMoneyMeasure reads [$value]")
    }
  }

  @Test
  fun `no measure in this table is a ratio of two others`() {
    // brief 0011 D27, pinned where the vocabulary lives: the earnings column and
    // the debt columns are different cohorts, different publishers and different
    // dollar bases, so a ratio over them is a figure nobody published.
    for (measure in ProgramMoneyMeasure.entries) {
      assertTrue(
        !measure.value.contains("ratio") && !measure.value.contains("per_dollar"),
        "[${measure.value}] names a ratio; no debt-to-earnings figure is ever stored or computed",
      )
    }
  }
}
