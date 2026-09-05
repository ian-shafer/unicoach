package ed.unicoach.db.dao

import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeIpedsCharge
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The RFC 161 IPEDS charges staging table: the upsert-if-changed three-way
 * split on an UNVERSIONED table, the narrow reshape's natural key
 * (college × charge variable stem × academic year), the named CHECK domains,
 * and the read the canonical-money fill uses.
 */
class CollegeIpedsChargesDaoTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
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
    @AfterAll
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
      stmt.execute("TRUNCATE TABLE colleges, college_ipeds_charges CASCADE")
    }
    CodebookReferenceFixture.seed(session)
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun seedCollege(ipedsUnitId: Int): CollegeId =
    CollegesDao
      .upsert(
        session,
        NewCollege(
          housingAndFoodOnCampusPerYearUsd = null,
          housingAndFoodOffCampusPerYearUsd = null,
          booksAndSuppliesPerYearUsd = null,
          otherExpensesOnCampusPerYearUsd = null,
          otherExpensesOffCampusPerYearUsd = null,
          otherExpensesWithFamilyPerYearUsd = null,
          ipedsUnitId = ipedsUnitId,
          opeid = null,
          name = "Test U $ipedsUnitId",
          city = "Townsville",
          state = "CA",
          region = null,
          locale = null,
          latitude = null,
          longitude = null,
          control = 1,
          undergradEnrollmentHeadcount = null,
          admissionRateShare = null,
          satAverageEquivalentScore = null,
          costOfAttendancePerYearUsd = null,
          netPricePerYearUsd = null,
          netPricePerYearIncomeQ1Usd = null,
          netPricePerYearIncomeQ2Usd = null,
          netPricePerYearIncomeQ3Usd = null,
          netPricePerYearIncomeQ4Usd = null,
          netPricePerYearIncomeQ5Usd = null,
          tuitionAndFeesInStatePerYearUsd = null,
          tuitionAndFeesOutOfStatePerYearUsd = null,
          completionRate150pct4yrShare = null,
          medianEarnings10yAfterEntryUsd = null,
          medianDebtAtCompletionUsd = null,
          pellShare = null,
          website = null,
        ),
      ).getOrThrow()
      .id

  private fun charge(
    collegeId: CollegeId,
    chargeVariable: String = "CHG2AY",
    academicYear: String = "2023-24",
    amountUsd: Int? = 8580,
    imputationFlag: String = "R",
  ) = NewCollegeIpedsCharge(collegeId.value, chargeVariable, academicYear, amountUsd, imputationFlag)

  private fun <T> scalar(
    sql: String,
    read: (java.sql.ResultSet) -> T,
  ): T =
    connection.prepareStatement(sql).use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        read(rs)
      }
    }

  // ---------------------------------------------------------------------------
  // Upsert
  // ---------------------------------------------------------------------------

  @Test
  fun `the same charge inserts, then changes, then is an unchanged no-op`() {
    val collegeId = seedCollege(222992)
    assertEquals(UpsertOutcome.INSERTED, CollegeIpedsChargesDao.upsert(session, charge(collegeId)).getOrThrow())
    assertEquals(
      UpsertOutcome.CHANGED,
      CollegeIpedsChargesDao.upsert(session, charge(collegeId, amountUsd = 8600)).getOrThrow(),
    )
    assertEquals(
      UpsertOutcome.UNCHANGED,
      CollegeIpedsChargesDao.upsert(session, charge(collegeId, amountUsd = 8600)).getOrThrow(),
    )
    assertEquals(1, CollegeIpedsChargesDao.rowCount(session).getOrThrow())
  }

  @Test
  fun `an unchanged re-upsert writes nothing at all, updated_at included`() {
    val collegeId = seedCollege(222992)
    CollegeIpedsChargesDao.upsert(session, charge(collegeId)).getOrThrow()
    val before = scalar("SELECT updated_at FROM college_ipeds_charges") { it.getTimestamp(1) }
    CollegeIpedsChargesDao.upsert(session, charge(collegeId)).getOrThrow()
    assertEquals(before, scalar("SELECT updated_at FROM college_ipeds_charges") { it.getTimestamp(1) })
  }

  @Test
  fun `a real change advances updated_at through the _03 trigger`() {
    val collegeId = seedCollege(222992)
    CollegeIpedsChargesDao.upsert(session, charge(collegeId)).getOrThrow()
    val before = scalar("SELECT updated_at FROM college_ipeds_charges") { it.getTimestamp(1) }
    CollegeIpedsChargesDao.upsert(session, charge(collegeId, imputationFlag = "C")).getOrThrow()
    val after = scalar("SELECT updated_at FROM college_ipeds_charges") { it.getTimestamp(1) }
    assertTrue(after.after(before), "updated_at must advance on a real change: $before -> $after")
  }

  @Test
  fun `the natural key keeps every year and every variable distinct`() {
    // The whole point of the narrow reshape: four carried years and twelve
    // variable stems are rows, not columns, and none of them collide.
    val collegeId = seedCollege(222992)
    for (year in listOf("2020-21", "2021-22", "2022-23", "2023-24")) {
      for (variable in listOf("CHG1AY", "CHG2AY", "CHG3AY")) {
        assertEquals(
          UpsertOutcome.INSERTED,
          CollegeIpedsChargesDao
            .upsert(session, charge(collegeId, chargeVariable = variable, academicYear = year))
            .getOrThrow(),
        )
      }
    }
    assertEquals(12, CollegeIpedsChargesDao.rowCount(session).getOrThrow())
  }

  @Test
  fun `two colleges may carry the same variable and year`() {
    val austin = seedCollege(222992)
    val ucsd = seedCollege(110680)
    CollegeIpedsChargesDao.upsert(session, charge(austin, amountUsd = 8580)).getOrThrow()
    CollegeIpedsChargesDao.upsert(session, charge(ucsd, amountUsd = 15265)).getOrThrow()
    assertEquals(2, CollegeIpedsChargesDao.rowCount(session).getOrThrow())
  }

  @Test
  fun `a valueless flag stores a NULL amount, and the real zero of an implied-zero flag survives`() {
    // `A` (not applicable) is an absence; `Z` (implied zero) is a real zero.
    // Collapsing them would lose the distinction the source draws.
    val collegeId = seedCollege(222992)
    CollegeIpedsChargesDao
      .upsert(session, charge(collegeId, chargeVariable = "CHG5AY", amountUsd = null, imputationFlag = "A"))
      .getOrThrow()
    CollegeIpedsChargesDao
      .upsert(session, charge(collegeId, chargeVariable = "CHG2AF", amountUsd = 0, imputationFlag = "Z"))
      .getOrThrow()
    assertNull(
      scalar("SELECT amount_usd FROM college_ipeds_charges WHERE charge_variable = 'CHG5AY'") {
        it.getObject(1)
      },
    )
    assertEquals(
      0,
      scalar("SELECT amount_usd FROM college_ipeds_charges WHERE charge_variable = 'CHG2AF'") { it.getInt(1) },
    )
  }

  // ---------------------------------------------------------------------------
  // CHECK domains
  // ---------------------------------------------------------------------------

  @Test
  fun `a charge variable that is not upper-case alphanumerics is refused by its named CHECK`() {
    val collegeId = seedCollege(222992)
    for (bad in listOf("chg2ay", "CHG2AY!", "", "CHG 2AY")) {
      val error = CollegeIpedsChargesDao.upsert(session, charge(collegeId, chargeVariable = bad)).exceptionOrNull()
      assertTrue(error is ConstraintViolationException, "[$bad] must be refused, got $error")
      assertEquals("college_ipeds_charges_variable_format_check", (error as ConstraintViolationException).constraint)
    }
  }

  @Test
  fun `a long stem is NOT refused, because no published rule bounds its length`() {
    // The CHECK defends the SHAPE of an IPEDS variable name. A `{1,16}` length
    // bound defended nothing -- nothing derives 16 -- while refusing a longer
    // stem IPEDS is free to publish, which would arrive as an anonymous row
    // failure rather than as the vocabulary change it is.
    val collegeId = seedCollege(222992)
    CollegeIpedsChargesDao.upsert(session, charge(collegeId, chargeVariable = "ABCDEFGHIJKLMNOPQ")).getOrThrow()
    assertEquals(1, CollegeIpedsChargesDao.rowCount(session).getOrThrow())
  }

  @Test
  fun `an academic year that is not YYYY-YY is refused by its named CHECK`() {
    val collegeId = seedCollege(222992)
    for (bad in listOf("2023", "2023-2024", "undated", "23-24")) {
      val error = CollegeIpedsChargesDao.upsert(session, charge(collegeId, academicYear = bad)).exceptionOrNull()
      assertTrue(error is ConstraintViolationException, "[$bad] must be refused, got $error")
      assertEquals(
        "college_ipeds_charges_academic_year_format_check",
        (error as ConstraintViolationException).constraint,
      )
    }
  }

  @Test
  fun `an imputation flag outside the 13 published codes is refused, letter or not`() {
    // The column's boundary is a known 13-value set, so it is stated as one.
    // Under the `^[A-Z]$` SHAPE it replaces, `Q` and `Y` stored cleanly, read
    // as valueless through the value-IFF-flag CHECK, and then fataled in the
    // canonical-money fill 180,000 rows later.
    val collegeId = seedCollege(222992)
    for (bad in listOf("r", "RR", "", "1", "Q", "Y", "E")) {
      val error =
        CollegeIpedsChargesDao
          .upsert(session, charge(collegeId, amountUsd = null, imputationFlag = bad))
          .exceptionOrNull()
      assertTrue(error is ConstraintViolationException, "[$bad] must be refused, got $error")
      assertEquals("college_ipeds_charges_flag_domain_check", (error as ConstraintViolationException).constraint)
    }
  }

  @Test
  fun `a negative amount is refused by its named CHECK`() {
    val collegeId = seedCollege(222992)
    val error = CollegeIpedsChargesDao.upsert(session, charge(collegeId, amountUsd = -1)).exceptionOrNull()
    assertTrue(error is ConstraintViolationException, "expected a constraint violation, got $error")
    assertEquals("college_ipeds_charges_amount_nonneg_check", (error as ConstraintViolationException).constraint)
  }

  @Test
  fun `a charge row for an unknown college is a foreign-key NotFoundException`() {
    val error =
      CollegeIpedsChargesDao
        .upsert(session, charge(CollegeId(UUID.randomUUID())))
        .exceptionOrNull()
    assertTrue(error is NotFoundException, "expected NotFoundException, got $error")
    assertEquals("college_ipeds_charges_college_id_fkey", error.constraint)
  }

  @Test
  fun `the college FK follows a TRUNCATE CASCADE, which is the only deletion colleges allows`() {
    // A plain DELETE FROM colleges is refused outright by the prevent_delete
    // trigger, so ON DELETE CASCADE is never reached that way. It is reached by
    // TRUNCATE ... CASCADE, which is what every suite's reset does -- and a
    // charge row left behind there would be an orphan pointing at a college id
    // that no longer exists.
    val collegeId = seedCollege(222992)
    CollegeIpedsChargesDao.upsert(session, charge(collegeId)).getOrThrow()
    connection.createStatement().use { stmt -> stmt.execute("TRUNCATE TABLE colleges CASCADE") }
    assertEquals(0, CollegeIpedsChargesDao.rowCount(session).getOrThrow())
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  @Test
  fun `list reads every staged row back in natural-key order`() {
    val collegeId = seedCollege(222992)
    CollegeIpedsChargesDao.upsert(session, charge(collegeId, "CHG2AY", "2023-24", 8580, "R")).getOrThrow()
    CollegeIpedsChargesDao.upsert(session, charge(collegeId, "CHG1AY", "2023-24", 2550, "R")).getOrThrow()
    CollegeIpedsChargesDao.upsert(session, charge(collegeId, "CHG1AY", "2022-23", 2550, "R")).getOrThrow()
    CollegeIpedsChargesDao.upsert(session, charge(collegeId, "CHG5AY", "2023-24", null, "A")).getOrThrow()

    val rows = CollegeIpedsChargesDao.list(session).getOrThrow()
    assertEquals(
      listOf(
        Triple("CHG1AY", "2022-23", 2550),
        Triple("CHG1AY", "2023-24", 2550),
        Triple("CHG2AY", "2023-24", 8580),
        Triple("CHG5AY", "2023-24", null),
      ),
      rows.map { Triple(it.chargeVariable, it.academicYear, it.amountUsd) },
    )
    assertEquals(listOf("R", "R", "R", "A"), rows.map { it.imputationFlag })
    assertTrue(rows.all { it.collegeId == collegeId.value })
  }

  @Test
  fun `list on an empty table is an empty list, not a failure`() {
    assertEquals(emptyList(), CollegeIpedsChargesDao.list(session).getOrThrow())
    assertEquals(0, CollegeIpedsChargesDao.rowCount(session).getOrThrow())
  }
}
