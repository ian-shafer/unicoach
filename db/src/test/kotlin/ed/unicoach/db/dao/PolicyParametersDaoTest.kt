package ed.unicoach.db.dao

import ed.unicoach.db.models.PolicyParameter
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The migration-seeded `policy_parameters` catalog and its read-only DAO
 * (RFC 159). Like [SystemPromptsDaoTest]'s catalog half, the rows under test
 * are the migration's own: `bin/test` re-migrates the database before the
 * suite runs, and nothing here seeds or truncates the table -- the seed IS the
 * subject.
 */
class PolicyParametersDaoTest {
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
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  @Test
  fun `the seed carries the five Pell parameters for 2026 and the full vocabulary for 2025`() {
    val rows2026 = PolicyParametersDao.listForAwardYear(session, 2026).getOrThrow()
    assertEquals(
      setOf(
        PolicyParameter.PELL_MAX_AWARD_USD,
        PolicyParameter.PELL_MIN_AWARD_USD,
        PolicyParameter.PELL_MAX_AGI_DEPENDENT_SINGLE_PARENT_PCT,
        PolicyParameter.PELL_MAX_AGI_DEPENDENT_NON_SINGLE_PARENT_PCT,
        PolicyParameter.SAI_FLOOR,
      ),
      rows2026.map { it.parameter }.toSet(),
      "AY 2026-27 seeds the five Pell/SAI parameters and DELIBERATELY no loan limits: " +
        "Volume 8 of the 2026-27 Handbook is not yet published (RFC 159 D-C)",
    )

    // AY 2025-26 is the fully-verifiable year: every member of the vocabulary
    // has a row, so the enum and the seed are proven against each other.
    val rows2025 = PolicyParametersDao.listForAwardYear(session, 2025).getOrThrow()
    assertEquals(PolicyParameter.entries.toSet(), rows2025.map { it.parameter }.toSet())

    // And those two years are the whole seed.
    assertEquals(rows2026.size + rows2025.size, PolicyParametersDao.listAll(session).getOrThrow().size)
  }

  @Test
  fun `every seeded row carries a non-empty spoken source name and a primary FSA url`() {
    val rows = PolicyParametersDao.listAll(session).getOrThrow()
    assertTrue(rows.isNotEmpty(), "the seed must exist before this asserts anything")
    rows.forEach { row ->
      assertTrue(row.sourceName.isNotBlank(), "row [${row.awardYear}/${row.parameter.value}] must name its source")
      assertTrue(
        row.sourceUrl.startsWith("https://fsapartners.ed.gov/"),
        "row [${row.awardYear}/${row.parameter.value}] must cite a primary FSA page, got [${row.sourceUrl}]",
      )
    }
  }

  @Test
  fun `the launch figures read back exactly as verified against FSA`() {
    val by2025 = PolicyParametersDao.listForAwardYear(session, 2025).getOrThrow().associate { it.parameter to it.value }
    val by2026 = PolicyParametersDao.listForAwardYear(session, 2026).getOrThrow().associate { it.parameter to it.value }

    // Pell (GEN-26-01 / GEN-25-02) and SAI (AVG Ch 3), both years.
    listOf(by2025, by2026).forEach { year ->
      assertEquals(7395, year[PolicyParameter.PELL_MAX_AWARD_USD])
      assertEquals(740, year[PolicyParameter.PELL_MIN_AWARD_USD])
      assertEquals(225, year[PolicyParameter.PELL_MAX_AGI_DEPENDENT_SINGLE_PARENT_PCT])
      assertEquals(175, year[PolicyParameter.PELL_MAX_AGI_DEPENDENT_NON_SINGLE_PARENT_PCT])
      assertEquals(-1500, year[PolicyParameter.SAI_FLOOR])
    }

    // Direct Loan limits, 2025-26 Handbook Vol 8 Ch 4 Tables 1A/1B/1C/4.
    assertEquals(5500, by2025[PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y1_TOTAL_USD])
    assertEquals(3500, by2025[PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y1_SUBSIDIZED_USD])
    assertEquals(6500, by2025[PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y2_TOTAL_USD])
    assertEquals(4500, by2025[PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y2_SUBSIDIZED_USD])
    assertEquals(7500, by2025[PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y3_PLUS_TOTAL_USD])
    assertEquals(5500, by2025[PolicyParameter.DIRECT_LOAN_ANNUAL_DEPENDENT_Y3_PLUS_SUBSIDIZED_USD])
    assertEquals(9500, by2025[PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y1_TOTAL_USD])
    assertEquals(3500, by2025[PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y1_SUBSIDIZED_USD])
    assertEquals(10500, by2025[PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y2_TOTAL_USD])
    assertEquals(4500, by2025[PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y2_SUBSIDIZED_USD])
    assertEquals(12500, by2025[PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y3_PLUS_TOTAL_USD])
    assertEquals(5500, by2025[PolicyParameter.DIRECT_LOAN_ANNUAL_INDEPENDENT_Y3_PLUS_SUBSIDIZED_USD])
    assertEquals(20500, by2025[PolicyParameter.DIRECT_LOAN_ANNUAL_GRAD_UNSUBSIDIZED_USD])
    assertEquals(31000, by2025[PolicyParameter.DIRECT_LOAN_AGGREGATE_DEPENDENT_TOTAL_USD])
    assertEquals(23000, by2025[PolicyParameter.DIRECT_LOAN_AGGREGATE_DEPENDENT_SUBSIDIZED_USD])
    assertEquals(57500, by2025[PolicyParameter.DIRECT_LOAN_AGGREGATE_INDEPENDENT_TOTAL_USD])
    assertEquals(23000, by2025[PolicyParameter.DIRECT_LOAN_AGGREGATE_INDEPENDENT_SUBSIDIZED_USD])
    assertEquals(138500, by2025[PolicyParameter.DIRECT_LOAN_AGGREGATE_GRAD_TOTAL_USD])
    assertEquals(65500, by2025[PolicyParameter.DIRECT_LOAN_AGGREGATE_GRAD_SUBSIDIZED_USD])
  }

  @Test
  fun `every stored parameter value round-trips through the enum`() {
    PolicyParametersDao.listAll(session).getOrThrow().forEach { row ->
      assertEquals(row.parameter, PolicyParameter.fromValue(row.parameter.value))
    }
  }

  @Test
  fun `a parameter name the enum does not know is refused as corruption, never skipped`() {
    // The CHECK makes this unreachable through any write path, so the bogus row
    // is written inside a transaction with the constraint dropped, and the
    // whole mischief is rolled back before the assertions -- the shared,
    // migration-seeded table is left exactly as the migration made it.
    // Skipping the row instead of throwing would let the coach serve a
    // parameter group with a figure quietly missing from it.
    connection.autoCommit = false
    val error =
      try {
        connection.createStatement().use { stmt ->
          stmt.execute("ALTER TABLE policy_parameters DROP CONSTRAINT policy_parameters_parameter_check")
          stmt.execute(
            "INSERT INTO policy_parameters (award_year, parameter, value, source_name, source_url) " +
              "VALUES (2024, 'pell_max_vibes', 1, 'A Source', 'https://fsapartners.ed.gov/x')",
          )
        }
        PolicyParametersDao.listAll(session).exceptionOrNull()
      } finally {
        connection.rollback()
        connection.autoCommit = true
      }

    assertTrue(error is CorruptPersistedValueException, "got $error")
    assertTrue(
      error.message!!.contains("policy_parameters.parameter"),
      "the failure must name the corrupt column: [${error.message}]",
    )
  }

  @Test
  fun `a negative dollar or percent value is refused by the schema`() {
    // policy_parameters_value_check: only sai_floor may be negative. A
    // mis-authored correction migration is the one write path this table has,
    // so the CHECK is the guard that catches it before any coach serves it.
    connection.autoCommit = false
    try {
      val refused =
        try {
          connection.createStatement().use { stmt ->
            stmt.execute(
              "INSERT INTO policy_parameters (award_year, parameter, value, source_name, source_url) " +
                "VALUES (2024, 'pell_max_award_usd', -7395, 'A Source', 'https://fsapartners.ed.gov/x')",
            )
          }
          null
        } catch (e: java.sql.SQLException) {
          e
        }
      assertTrue(refused != null, "a negative pell_max_award_usd must not seed cleanly")
      assertTrue(
        refused.message!!.contains("policy_parameters_value_check"),
        "the refusal must name the value CHECK: [${refused.message}]",
      )
    } finally {
      connection.rollback()
      connection.autoCommit = true
    }
  }

  @Test
  fun `sai_floor stays seedable below zero`() {
    // The other half of the value CHECK: the one legitimately negative
    // parameter must not be caught by it (the 2026 seed row proves it in
    // place; this asserts on the live seed rather than mutating anything).
    val floor =
      PolicyParametersDao
        .listForAwardYear(session, 2026)
        .getOrThrow()
        .single { it.parameter == PolicyParameter.SAI_FLOOR }
    assertTrue(floor.value < 0, "the seeded SAI floor is negative by its federal definition")
  }

  @Test
  fun `every parameter name carries its unit as its suffix`() {
    // The unit-in-name convention (RFC 159 D-A) is what lets one INTEGER
    // column carry dollars, percent points and one index: it holds only while
    // every name says which it is.
    PolicyParameter.entries.forEach { parameter ->
      assertTrue(
        parameter.value.endsWith("_usd") ||
          parameter.value.endsWith("_pct") ||
          parameter.value == "sai_floor",
        "parameter [${parameter.value}] names no unit: expected an _usd/_pct suffix or the one sai_floor index",
      )
    }
  }
}
