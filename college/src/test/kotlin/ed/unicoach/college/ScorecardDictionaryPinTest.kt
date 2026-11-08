package ed.unicoach.college

import ed.unicoach.college.ScorecardDictionary.Cohort
import ed.unicoach.college.ScorecardOutcomeCitations.CohortPeriod
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The periods unicoach still states in Kotlin for a College Scorecard figure,
 * pinned against the FETCHED data dictionary (RFC 274) -- and the money
 * registry's shape, which decides only WHETHER the fill dates a column.
 *
 * The seven outcome citations carry their period as a value until the outcome
 * figures move onto rows that carry their own (brief 0017 slice
 * `cost/07/the-figures-carry-their-year`). Each is asserted equal to the period
 * the committed dictionary states for its variable, read through the
 * production reader: a release that moves a cohort fails HERE, naming the
 * variable, the Kotlin value and the publisher's cell, and the fix is the
 * value.
 *
 * A FILE-only suite: no database. The write behaviour the dictionary drives is
 * `CanonicalMoneyDictionaryTest`'s.
 */
class ScorecardDictionaryPinTest {
  private val dictionary = SeedScorecardDictionary.dictionary

  /** The period a citation's Kotlin value names, as the dictionary's reader would read it -- or a refusal naming both. */
  private fun assertSamePeriod(citation: ScorecardOutcomeCitations.Citation) {
    val stated = dictionary.enteringPeriodOf(citation.variable)
    val cell = dictionary.institutionCellOf(citation.variable)
    val agrees =
      when (val period = citation.cohort) {
        is CohortPeriod.EnteringTerm -> stated is Cohort.StatedTerm && stated.autumnYear == period.autumnYear
        is CohortPeriod.EnteringAcademicYear -> stated is Cohort.StatedEnteringAcademicYear && stated.year == period.year
      }
    assertTrue(
      agrees,
      "[${citation.variable}] is cited as [${citation.cohort}] and the fetched dictionary dates it [$cell]: update " +
        "the period in ScorecardOutcomeCitations to the one that cell states",
    )
  }

  @Test
  fun `every outcome citation's period is the one the fetched dictionary states for its variable`() {
    for (citation in ScorecardOutcomeCitations.Citation.entries) assertSamePeriod(citation)
  }

  @Test
  fun `the eight-year trio is an entering academic year, and the other four are entering terms`() {
    // The SHAPE, read off the fetched file: the Outcome Measures cohort arrived
    // across a July-June year, so "autumn" would misstate the population.
    val trio =
      setOf(
        ScorecardInstitutionColumns.OMAWDP8_ALL,
        ScorecardInstitutionColumns.OMAWDP8_PELL_ALL,
        ScorecardInstitutionColumns.OMAWDP8_NOPELL_ALL,
      )
    for (variable in ScorecardInstitutionColumns.OUTCOME_VARIABLES) {
      val stated = dictionary.enteringPeriodOf(variable)
      if (variable in trio) {
        assertTrue(stated is Cohort.StatedEnteringAcademicYear, "[$variable] reads as [$stated]")
      } else {
        assertTrue(stated is Cohort.StatedTerm, "[$variable] reads as [$stated]")
      }
    }
    assertEquals(
      ScorecardInstitutionColumns.OUTCOME_VARIABLES,
      ScorecardOutcomeCitations.Citation.entries
        .map { it.variable }
        .toSet(),
      "one citation per outcome variable, and no other",
    )
  }

  @Test
  fun `the two six-year figures are dated alike by the publisher, each by its own cell`() {
    // TRANS_4 counts C150_4's own cohort, so the publisher dates them with one
    // cell -- asserted, never assumed, and kept as two values in Kotlin.
    assertEquals(
      dictionary.institutionCellOf(ScorecardInstitutionColumns.C150_4),
      dictionary.institutionCellOf(ScorecardInstitutionColumns.TRANS_4),
    )
  }

  @Test
  fun `the money registry and the outcome registry are disjoint`() {
    // A column that is both money and outcome would be dated by two owners.
    assertEquals(
      emptySet(),
      ScorecardInstitutionColumns.LOADED_VARIABLES intersect ScorecardInstitutionColumns.OUTCOME_VARIABLES,
    )
  }

  @Test
  fun `a dated column is stamped with the year the dictionary states for it, an undated one with none`() {
    for ((variable, group) in ScorecardInstitutionColumns.VINTAGE_GROUP_BY_VARIABLE) {
      val expected =
        when (group) {
          ScorecardInstitutionColumns.VintageGroup.DATED -> dictionary.academicYearOf(variable)
          ScorecardInstitutionColumns.VintageGroup.UNDATED -> CanonicalMoneyLoader.VINTAGE_UNDATED
        }
      assertEquals(expected, ScorecardInstitutionColumns.stampedYearOf(variable, dictionary), "[$variable]")
    }
    assertNull(CanonicalMoneyLoader.VINTAGE_UNDATED, "an absent year is absent, never a magic year")
  }

  @Test
  fun `PCTPELL is dated by the publisher and stamped undated by the fill, and that gap is declared`() {
    // RFC 183 D3, pinned rather than described: the dictionary DOES date
    // PCTPELL, and the fill stamps it undated -- moving it onto a dated key
    // would change who wins that key, unmeasured. When someone fixes it, this
    // fails and makes them say so.
    assertNotNull(dictionary.academicYearOf(ScorecardInstitutionColumns.PCTPELL))
    assertEquals(
      setOf(ScorecardInstitutionColumns.PCTPELL),
      ScorecardInstitutionColumns.UNDATED_VARIABLES -
        setOf(ScorecardInstitutionColumns.GRAD_DEBT_MDN, ScorecardInstitutionColumns.MD_EARN_WNE_P10),
      "the fill stamps exactly one publisher-DATED variable undated, and it is PCTPELL",
    )
  }

  @Test
  fun `a column claimed by two vintage groups is refused, naming the column and both groups`() {
    val refusal =
      assertFailsWith<IllegalArgumentException> {
        with(ScorecardInstitutionColumns) {
          buildMap<String, ScorecardInstitutionColumns.VintageGroup> {
            setVintageGroup(TUITIONFEE_IN, ScorecardInstitutionColumns.VintageGroup.DATED)
            setVintageGroup(TUITIONFEE_IN, ScorecardInstitutionColumns.VintageGroup.UNDATED)
          }
        }
      }
    val message = assertNotNull(refusal.message)
    assertTrue(message.contains("[${ScorecardInstitutionColumns.TUITIONFEE_IN}]"), message)
    assertTrue(message.contains("[DATED]") && message.contains("[UNDATED]"), message)
  }
}
