package ed.unicoach.college

import ed.unicoach.common.util.AcademicYear
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The field-of-study cohort a family is told, driven by the FETCHED data
 * dictionary (RFC 274): the `program-money` fill stamps each row with the
 * pooled cohort and dollar year the dictionary states, a newer release moves
 * them with no code edit, and a back-dated, dropped or mis-dollared variable is
 * refused with the previous fill standing. Every year is read from the committed
 * dictionary or derived.
 */
class ProgramMoneyDictionaryTest : CollegeScorecardTestBase() {
  private val loader = CollegeScorecardLoader(database)
  private val programMoney = ProgramMoneyLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-fixture.csv")

  private val debt = ScorecardFieldOfStudyColumns.DEBT_ALL_STGP_EVAL_MDN
  private val payment = ScorecardFieldOfStudyColumns.DEBT_ALL_STGP_EVAL_MDN10YRPAY
  private val earnings = ScorecardFieldOfStudyColumns.EARN_MDN_4YR
  private val committedDebt = SeedScorecardDictionary.dictionary.fieldOfStudyCohortOf(debt)
  private val committedEarnings = SeedScorecardDictionary.dictionary.fieldOfStudyCohortOf(earnings)

  /** The publisher's NSLDS pooled shape, shifted by [years]. */
  private fun debtCell(years: Int): String {
    val first = AcademicYear(committedDebt.first.firstCalendarYear + years)
    val last = AcademicYear(committedDebt.last.firstCalendarYear + years)
    return "NSLDS pooled AY${first.label}, AY${last.label} cohort"
  }

  /** The publisher's Treasury earnings shape over the committed cohort, stating the dollars of [year]. */
  private fun earningsCell(year: Int): String {
    val first = committedEarnings.first.firstCalendarYear
    val last = committedEarnings.last.firstCalendarYear
    return "Treasury AY$first-${first + 1}, AY$last-${last + 1} pooled earnings cohort measured in " +
      "CY${year - 2}, CY${year - 1}, inflation adjusted to $year dollars"
  }

  private fun fill(dictionary: ScorecardDictionary): ProgramMoneyLoader.FillResult =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      programMoney.fill(fieldsCsv, dictionary)
    }

  /** (first, last) of every stored row of [variable]. */
  private fun storedCohorts(variable: String): Set<Pair<Int, Int>> =
    query("SELECT cohort_first_year, cohort_last_year FROM program_money_stats WHERE source_variable = '$variable'") { rs ->
      rs.getInt(1) to rs.getInt(2)
    }.toSet()

  @Test
  fun `a dictionary dating the debt cohort a year later stamps every debt row with it`() {
    fill(EditedScorecardDictionary.of(fieldOfStudyCells = mapOf(debt to debtCell(+1))))
    assertEquals(
      setOf(committedDebt.first.firstCalendarYear + 1 to committedDebt.last.firstCalendarYear + 1),
      storedCohorts(debt),
    )
    // The sibling variable keeps its own committed cell: one cell, one variable.
    val payment = ScorecardFieldOfStudyColumns.DEBT_ALL_STGP_EVAL_MDN10YRPAY
    val committedPayment = SeedScorecardDictionary.dictionary.fieldOfStudyCohortOf(payment)
    assertEquals(setOf(committedPayment.first.firstCalendarYear to committedPayment.last.firstCalendarYear), storedCohorts(payment))
  }

  @Test
  fun `a dictionary that back-dates a cohort is refused naming the variable and both spans, and the fill stands`() {
    fill(SeedScorecardDictionary.dictionary)
    val before = storedCohorts(debt)
    val refusal =
      assertFailsWith<ScorecardYearGuard.DictionaryBackdatesServedVariableException> {
        fill(EditedScorecardDictionary.of(fieldOfStudyCells = mapOf(debt to debtCell(-1))))
      }
    val backdate = refusal.backdates.single()
    assertEquals(debt, backdate.variable)
    assertEquals(ScorecardYearGuard.Backdate.Period.Span(committedDebt.first, committedDebt.last), backdate.stored)
    assertTrue(refusal.message!!.contains("stored [${committedDebt.first.label}..${committedDebt.last.label}]"), refusal.message)
    assertEquals(before, storedCohorts(debt), "nothing was rewritten")
  }

  @Test
  fun `a dictionary that drops a variable is refused naming it, and the fill stands`() {
    fill(SeedScorecardDictionary.dictionary)
    val before = storedCohorts(ScorecardFieldOfStudyColumns.EARN_MDN_4YR)
    val refusal =
      assertFailsWith<ScorecardYearGuard.DictionaryRefusesServedVariablesException> {
        fill(EditedScorecardDictionary.of(fieldOfStudyCells = mapOf(ScorecardFieldOfStudyColumns.EARN_MDN_4YR to null)))
      }
    assertEquals(listOf(ScorecardFieldOfStudyColumns.EARN_MDN_4YR), refusal.variables)
    assertTrue(before.isNotEmpty())
    assertEquals(before, storedCohorts(ScorecardFieldOfStudyColumns.EARN_MDN_4YR), "nothing was rewritten")
  }

  /** The distinct `amount_usd_year` of every stored row of [variable]; null is an empty cell. */
  private fun storedAmountUsdYears(variable: String): Set<Int?> =
    query("SELECT DISTINCT amount_usd_year FROM program_money_stats WHERE source_variable = '$variable'") { rs ->
      rs.getInt(1).takeUnless { rs.wasNull() }
    }.toSet()

  @Test
  fun `the fill stamps earnings rows with the dictionary's dollar year and leaves the other two empty`() {
    fill(SeedScorecardDictionary.dictionary)
    assertEquals(setOf<Int?>(assertNotNull(committedEarnings.amountUsdYear).year), storedAmountUsdYears(earnings))
    assertEquals(setOf<Int?>(null), storedAmountUsdYears(debt))
    assertEquals(setOf<Int?>(null), storedAmountUsdYears(payment))
  }

  @Test
  fun `a dictionary stating a later dollar year stamps every earnings row with it`() {
    val later = assertNotNull(committedEarnings.amountUsdYear).year + 1
    fill(EditedScorecardDictionary.of(fieldOfStudyCells = mapOf(earnings to earningsCell(later))))
    assertEquals(setOf<Int?>(later), storedAmountUsdYears(earnings))
  }

  /** Runs a fill over [cell] for [variable] after a good one, asserting the refusal names both and the first fill stands. */
  private fun assertRefusedNaming(
    variable: String,
    cell: String,
  ) {
    fill(SeedScorecardDictionary.dictionary)
    val before = storedAmountUsdYears(variable)
    val refusal =
      assertFailsWith<ScorecardYearGuard.DictionaryRefusesServedVariablesException> {
        fill(EditedScorecardDictionary.of(fieldOfStudyCells = mapOf(variable to cell)))
      }
    val fault = assertIs<ScorecardDictionary.UnexpectedCohortShapeException>(refusal.faults.single())
    assertEquals(variable, fault.variable)
    assertEquals(cell, fault.cell)
    val message = assertNotNull(refusal.message)
    assertTrue(message.contains(variable) && message.contains(cell), message)
    assertEquals(before, storedAmountUsdYears(variable), "nothing was rewritten")
  }

  @Test
  fun `an earnings cell that states no dollar year is refused naming the variable and the cell`() {
    val first = committedEarnings.first
    val last = committedEarnings.last
    assertRefusedNaming(earnings, "Treasury pooled AY${first.label}, AY${last.label} cohort")
  }

  @Test
  fun `a debt or payment cell that now states a dollar year is refused naming the variable and the cell`() {
    val first = committedDebt.first
    val last = committedDebt.last
    val year = last.firstCalendarYear + 2
    val cell =
      "NSLDS AY${first.label}, AY${last.label} pooled earnings cohort measured in " +
        "CY${year - 2}, CY${year - 1}, inflation adjusted to $year dollars"
    assertRefusedNaming(debt, cell)
    assertRefusedNaming(payment, cell)
  }
}
