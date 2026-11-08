package ed.unicoach.college

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The field-of-study registry against the data it describes (RFC 218, RFC 274):
 * a FILE-only suite, no database. What each variable is held to here is what the
 * registry itself carries; every period a figure describes is stamped on its row
 * ([ProgramMoneyDictionaryTest]).
 */
class ScorecardFieldOfStudyPinTest {
  private val dictionary = SeedScorecardDictionary.dictionary

  @Test
  fun `the loader writes exactly the registered variables, and only those`() {
    // EQUALITY, both ways: a column the loader reads with no registry entry is a
    // figure served with no publisher, and an entry nothing reads is a claim
    // nobody checks.
    assertEquals(
      ScorecardFieldOfStudyColumns.LOADED_VARIABLES,
      ScorecardFieldOfStudyColumns.VARIABLES.map { it.sourceVariable }.toSet(),
    )
    assertEquals(
      ScorecardFieldOfStudyColumns.LOADED_VARIABLES,
      CollegeScorecardLoader.REQUIRED_FIELDS_COLUMNS
        .filter { it in ScorecardFieldOfStudyColumns.LOADED_VARIABLES }
        .toSet(),
      "every registered variable must be in the one header assertion the run makes on that file",
    )
    // Every measure is written by exactly one variable: two variables claiming
    // one measure would silently collide on the natural key.
    assertEquals(
      ScorecardFieldOfStudyColumns.VARIABLES.size,
      ScorecardFieldOfStudyColumns.VARIABLES
        .map { it.measure }
        .toSet()
        .size,
    )
  }

  @Test
  fun `earnings and debt are different cohorts from different publishers, which is why neither is ever derived from the other`() {
    // brief 0011 D27, confirmed from the publisher's own cells: two publishers,
    // two cohorts. A ratio over them is a figure nobody published.
    val earnings = dictionary.fieldOfStudyCohortOf(ScorecardFieldOfStudyColumns.EARN_MDN_4YR)
    val debt = dictionary.fieldOfStudyCohortOf(ScorecardFieldOfStudyColumns.DEBT_ALL_STGP_EVAL_MDN)
    assertNotEquals(earnings.first to earnings.last, debt.first to debt.last)
    assertEquals("Treasury", ScorecardFieldOfStudyColumns.EARNINGS.publisher)
    assertEquals("NSLDS", ScorecardFieldOfStudyColumns.DEBT.publisher)
    assertEquals("NSLDS", ScorecardFieldOfStudyColumns.DEBT_MONTHLY_PAYMENT.publisher)
  }

  @Test
  fun `these variables are absent from the institution money registry`() {
    // Every institution vintage group is dated from the INSTITUTION sheet, so a
    // field-of-study variable joining it would be stamped with a tuition year.
    for (variable in ScorecardFieldOfStudyColumns.LOADED_VARIABLES) {
      assertTrue(
        variable !in ScorecardInstitutionColumns.LOADED_VARIABLES,
        "[$variable] is a field-of-study variable and must not be in the institution registry",
      )
    }
  }
}
