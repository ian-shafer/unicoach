package ed.unicoach.college

import ed.unicoach.db.dao.captureWarns
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `program-money` fill (RFC 218): the three silences kept apart, the cents
 * discarded half up in one place, the cohort years stamped per COLUMN, the third
 * absence counter, and the rebuild's idempotency.
 */
class ProgramMoneyLoaderTest : CollegeScorecardTestBase() {
  private val loader = CollegeScorecardLoader(database)
  private val programMoney = ProgramMoneyLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-fixture.csv")

  /** One stored row, addressed the way the publisher addresses the cell. */
  private data class Stat(
    val amountUsd: Int?,
    val status: String,
    val source: String,
    val sourceVariable: String,
    val cohortFirst: Int,
    val cohortLast: Int,
  )

  private fun stat(
    ipedsUnitId: Int,
    cipCode: String,
    credentialLevel: Int,
    measure: String,
  ): Stat? =
    query(
      "SELECT s.amount_usd, s.status, s.source, s.source_variable, " +
        "s.cohort_first_year, s.cohort_last_year " +
        "FROM program_money_stats s " +
        "JOIN college_programs p ON p.id = s.college_program_id " +
        "JOIN colleges c ON c.id = p.college_id " +
        "WHERE c.ipeds_unit_id = $ipedsUnitId AND p.cip_code = '$cipCode' " +
        "AND p.credential_level = $credentialLevel AND s.measure = '$measure'",
    ) { rs ->
      Stat(
        amountUsd = rs.getInt(1).takeUnless { rs.wasNull() },
        status = rs.getString(2),
        source = rs.getString(3),
        sourceVariable = rs.getString(4),
        cohortFirst = rs.getInt(5),
        cohortLast = rs.getInt(6),
      )
    }.singleOrNull()

  /** The `fields` phase is this fill's write precondition, so every test runs it first. */
  private fun fillFromFixture(fields: File = fieldsCsv): ProgramMoneyLoader.FillResult =
    runBlocking {
      loader.load(institutionCsv, fields)
      programMoney.fill(fields)
    }

  @Test
  fun `a numeric cell stores reported with the amount, and the three silences stay three`() {
    val result = fillFromFixture()

    val reported = assertNotNull(stat(110100, "260702", 3, "median_earnings_4yr_after_entry"))
    assertEquals(52000, reported.amountUsd)
    assertEquals("reported", reported.status)

    // PS is the publisher withholding a figure it has.
    val suppressed = assertNotNull(stat(110100, "110701", 3, "median_earnings_4yr_after_entry"))
    assertNull(suppressed.amountUsd)
    assertEquals("suppressed_by_publisher", suppressed.status)

    // NA is the school having no figure for this program -- a DIFFERENT fact.
    val notReported = assertNotNull(stat(220200, "260101", 3, "median_earnings_4yr_after_entry"))
    assertNull(notReported.amountUsd)
    assertEquals("not_reported_by_institution", notReported.status)

    // And the three are distinguishable in ONE query, which is the whole point
    // of storing the absence rows rather than dropping them (RFC 206).
    assertEquals(
      setOf("reported", "suppressed_by_publisher", "not_reported_by_institution"),
      query("SELECT DISTINCT status FROM program_money_stats") { rs -> rs.getString(1) }.toSet(),
    )
    assertEquals(mapOf("EARN_MDN_4YR" to 1), result.unrecognisedTokens)
  }

  @Test
  fun `one row within one program can be reported, suppressed and unreported at once`() {
    // The three columns are different publishers on different cohorts, so a
    // per-ROW status would be a lie: the statuses are per CELL.
    fillFromFixture()
    assertEquals("reported", assertNotNull(stat(220200, "540101", 3, "median_earnings_4yr_after_entry")).status)
    assertEquals(
      "suppressed_by_publisher",
      assertNotNull(stat(220200, "540101", 3, "median_debt_at_completion")).status,
    )
  }

  @Test
  fun `the ten-year payment rounds half up to whole dollars -- 123_49 stores 123 and 123_50 stores 124`() {
    // The rule, by name and in the ONE place it lives. The publisher's monthly
    // payment is the only one of the three that can carry a fraction, and it is
    // never truncated.
    assertEquals(123, ProgramMoneyLoader.wholeDollars(123.49))
    assertEquals(124, ProgramMoneyLoader.wholeDollars(123.50))
    assertEquals(124, ProgramMoneyLoader.wholeDollars(123.51))
    assertEquals(0, ProgramMoneyLoader.wholeDollars(0.49))
    assertEquals(1, ProgramMoneyLoader.wholeDollars(0.50))
  }

  @Test
  fun `the stored payment is the rounded figure, not a truncation`() {
    fillFromFixture()
    // 227.86 -> 228 (truncation would store 227).
    assertEquals(228, assertNotNull(stat(110100, "260702", 3, "median_debt_10yr_monthly_payment")).amountUsd)
    // 312.49 -> 312, the other side of the same rule.
    assertEquals(312, assertNotNull(stat(330300, "141001", 3, "median_debt_10yr_monthly_payment")).amountUsd)
    // 243.50 -> 244: exactly half, rounded UP.
    assertEquals(244, assertNotNull(stat(330300, "260702", 5, "median_debt_10yr_monthly_payment")).amountUsd)
  }

  @Test
  fun `every stored row carries its own column's cohort years and its publisher's variable`() {
    fillFromFixture()
    // Treasury's earnings cohort and NSLDS' debt cohort are DIFFERENT, and the
    // stamp is per column: this is what stops an earnings figure being dated by
    // a debt year.
    val earnings = assertNotNull(stat(110100, "260702", 3, "median_earnings_4yr_after_entry"))
    assertEquals(2017, earnings.cohortFirst)
    assertEquals(2018, earnings.cohortLast)
    assertEquals("EARN_MDN_4YR", earnings.sourceVariable)
    assertEquals("scorecard", earnings.source)

    val debt = assertNotNull(stat(110100, "260702", 3, "median_debt_at_completion"))
    assertEquals(2018, debt.cohortFirst)
    assertEquals(2019, debt.cohortLast)
    assertEquals("DEBT_ALL_STGP_EVAL_MDN", debt.sourceVariable)

    // And EVERY row, not only the two read above.
    val rows =
      query(
        "SELECT source_variable, cohort_first_year, cohort_last_year FROM program_money_stats",
      ) { rs -> Triple(rs.getString(1), rs.getInt(2), rs.getInt(3)) }
    assertTrue(rows.isNotEmpty(), "the fill wrote nothing, so this guard proves nothing")
    for ((variable, first, last) in rows) {
      val pinned =
        assertNotNull(
          ScorecardFieldOfStudyColumns.ofOrNull(variable),
          "[$variable] is stored but not in ScorecardFieldOfStudyColumns",
        )
      assertEquals(pinned.cohortFirstYear.firstCalendarYear, first, variable)
      assertEquals(pinned.cohortLastYear.firstCalendarYear, last, variable)
    }
  }

  @Test
  fun `a token this seam has never been taught is COUNTED for its column, and still warned about`() {
    // RFC 206 left this arm with a WARN line and no counter (brief 0011 D45).
    // There was no test of it at all; this is the first.
    lateinit var result: ProgramMoneyLoader.FillResult
    val warns = captureWarns { result = fillFromFixture() }
    assertEquals(mapOf("EARN_MDN_4YR" to 1), result.unrecognisedTokens)
    // The counter does not change the STATUS: an unreadable token is still
    // absence, said out loud rather than passed off as school silence.
    val row = assertNotNull(stat(110100, "0901", 3, "median_earnings_4yr_after_entry"))
    assertEquals("not_reported_by_institution", row.status)
    assertNull(row.amountUsd)
    // And a KNOWN null sentinel is NOT counted -- the counter names the tokens
    // nobody taught this seam, not every absence.
    assertTrue("DEBT_ALL_STGP_EVAL_MDN" !in result.unrecognisedTokens, result.unrecognisedTokens.toString())
    // The counter does not REPLACE the WARN: the count says how many and in
    // which column; the line names the token and the row. D45 keeps both halves.
    assertTrue(
      warns.any {
        it.contains("Unrecognised non-numeric cell read as not-reported") &&
          it.contains("EARN_MDN_4YR") &&
          it.contains("NOT_A_NUMBER")
      },
      "the WARN naming the token must still fire beside the counter: $warns",
    )
  }

  @Test
  fun `the fill writes one row per measure per loadable program row`() {
    val result = fillFromFixture()
    assertEquals(9, result.seen)
    assertEquals(9, result.mapped)
    assertEquals(9 * ScorecardFieldOfStudyColumns.VARIABLES.size, result.rowsWritten)
    assertEquals(emptyMap(), result.skipsByReason)
  }

  @Test
  fun `two fills of the same file leave identical rows`() {
    // The rebuild shape's whole claim: DELETE this source's rows, then
    // re-insert. Idempotency is by construction, and this is what says so.
    val first = fillFromFixture()
    val second = runBlocking { programMoney.fill(fieldsCsv) }
    assertEquals(first.rowsWritten, second.rowsWritten)
    assertEquals(first.statusCounts, second.statusCounts)
    assertEquals(
      first.rowsWritten,
      query("SELECT count(*) FROM program_money_stats") { rs -> rs.getInt(1) }.single(),
    )
  }

  @Test
  fun `a field-of-study row with no program row is counted and named, never assumed away`() {
    // It cannot happen in a normal run -- the `fields` phase wrote the program
    // minutes earlier from this same file -- so the ONE way to see a fill
    // running against a stale college_programs is for this number to move.
    val orphan = File.createTempFile("orphan-fields", ".csv")
    orphan.deleteOnExit()
    orphan.writeText(
      CollegeScorecardLoader.REQUIRED_FIELDS_COLUMNS.joinToString(",") + "\n" +
        "110100,260702,Marine Biology and Biological Oceanography,3,52000,21500,227.86\n",
    )
    // No `fields` phase, so no college_programs row exists at all.
    val result = runBlocking { programMoney.fill(orphan) }
    assertEquals(1, result.seen)
    assertEquals(0, result.mapped)
    assertEquals(0, result.rowsWritten)
    assertEquals(mapOf<SkipReason, Int>(SkipReason.NoProgramForKey to 1), result.skipsByReason)
  }

  @Test
  fun `a row the fields phase skips is skipped here under the same named reason`() {
    // ONE definition of "which field-of-study rows are loadable", shared: two
    // copies would be two places for them to disagree, and the disagreement
    // would be a money row about a program that is not there.
    val odd = File.createTempFile("odd-fields", ".csv")
    odd.deleteOnExit()
    odd.writeText(
      CollegeScorecardLoader.REQUIRED_FIELDS_COLUMNS.joinToString(",") + "\n" +
        "NA,260702,Marine Biology,3,52000,21500,227.86\n" +
        "110100,260702,Marine Biology,99,52000,21500,227.86\n",
    )
    val result = runBlocking { programMoney.fill(odd) }
    assertEquals(2, result.seen)
    assertEquals(
      mapOf<SkipReason, Int>(SkipReason.IpedsUnitIdNa to 1, SkipReason.CredentialLevelOutOfDomain to 1),
      result.skipsByReason,
    )
  }
}
