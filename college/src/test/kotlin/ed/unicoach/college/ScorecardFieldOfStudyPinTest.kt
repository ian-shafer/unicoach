package ed.unicoach.college

import ed.unicoach.common.util.CalendarYear
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The field-of-study vintage pins (RFC 218), on `ScoreBandCopyTest`'s shape:
 * a FILE-only suite, no database.
 *
 * `ScorecardFieldOfStudyColumns` is a hand transcription of the publisher's own
 * cohort cells, and `bin/fetch-scorecard` discovers the CURRENT release -- so
 * the seed directory can move under it with no Kotlin change, leaving three
 * cohorts dated to a release nobody re-read. These are the assertions that make
 * that a failing test instead of a silent wrong year.
 */
class ScorecardFieldOfStudyPinTest {
  /** The committed release, located by walking UP for the seed directory (the `ScorecardDictionaryPinTest` idiom). */
  private fun committedRelease(): String {
    val provenance =
      generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "db/seed/scorecard/PROVENANCE.json") }
        .firstOrNull { it.isFile }
    assertNotNull(
      provenance,
      "no db/seed/scorecard/PROVENANCE.json on the walk UP from [${File(".").absoluteFile}]: it is a COMMITTED " +
        "record (the two CSVs beside it are gitignored), so a checkout without it cannot pin the cohort",
    )
    return Json
      .parseToJsonElement(provenance.readText())
      .jsonObject
      .getValue("release")
      .jsonPrimitive.content
  }

  @Test
  fun `the pinned snapshot release still equals the committed provenance`() {
    val release = committedRelease()
    assertEquals(
      release,
      ScorecardFieldOfStudyColumns.COHORT_SNAPSHOT_RELEASE,
      "the three field-of-study cohorts were transcribed from release " +
        "[${ScorecardFieldOfStudyColumns.COHORT_SNAPSHOT_RELEASE}] and the seed is now release [$release]: re-read " +
        "CollegeScorecardDataDictionary.xlsx sheet FieldOfStudy_Cohort_Map for EARN_MDN_4YR, " +
        "DEBT_ALL_STGP_EVAL_MDN and DEBT_ALL_STGP_EVAL_MDN10YRPAY, then update the registry, " +
        "COHORT_SNAPSHOT_RELEASE and db/seed/scorecard/FETCH-NOTES.md together",
    )
  }

  @Test
  fun `the loader writes cohort years for exactly the pinned variables, and only those`() {
    // EQUALITY, both ways: a column the loader reads with no pinned cohort is a
    // figure served with no stated year, and a pinned variable nothing reads is
    // a transcription nobody checks.
    assertEquals(
      ScorecardFieldOfStudyColumns.LOADED_VARIABLES,
      ScorecardFieldOfStudyColumns.VARIABLES.map { it.sourceVariable }.toSet(),
    )
    assertEquals(
      ScorecardFieldOfStudyColumns.LOADED_VARIABLES,
      CollegeScorecardLoader.REQUIRED_FIELDS_COLUMNS
        .filter { it in ScorecardFieldOfStudyColumns.LOADED_VARIABLES }
        .toSet(),
      "every pinned variable must be in the one header assertion the run makes on that file",
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
  fun `every pinned cohort ends no earlier than it starts`() {
    // The Kotlin half of program_money_stats_cohort_years_ordered_check: the
    // registry is where a transposed transcription would enter.
    for (variable in ScorecardFieldOfStudyColumns.VARIABLES) {
      assertTrue(
        variable.cohortLastYear >= variable.cohortFirstYear,
        variable.sourceVariable,
      )
    }
  }

  @Test
  fun `the earnings variable carries an inflation base year and the two debt variables carry none`() {
    // An earnings figure is in 2024 dollars and nothing on its row says so; this
    // is the ONE place a reader finds the dollar base before speaking it out
    // loud. NULL on the debt columns is a FINDING, not an omission: the
    // publisher names no dollar year for debt anywhere.
    assertEquals(CalendarYear(2024), ScorecardFieldOfStudyColumns.EARNINGS.inflationBaseYear)
    assertNull(ScorecardFieldOfStudyColumns.DEBT.inflationBaseYear)
    assertNull(ScorecardFieldOfStudyColumns.DEBT_MONTHLY_PAYMENT.inflationBaseYear)
    assertEquals(
      1,
      ScorecardFieldOfStudyColumns.VARIABLES.count { it.inflationBaseYear != null },
    )
  }

  @Test
  fun `each variable quotes the publisher's own cohort cell, and the years come from it`() {
    // The cell is the EVIDENCE for the two stored years; a paraphrase would be
    // us restating a source in our own words in the one place a reader checks us.
    assertTrue(
      ScorecardFieldOfStudyColumns.EARNINGS.publisherCohortCell
        .startsWith("Treasury AY2017-2018, AY2018-2019 pooled earnings cohort"),
      ScorecardFieldOfStudyColumns.EARNINGS.publisherCohortCell,
    )
    assertTrue(
      ScorecardFieldOfStudyColumns.EARNINGS.publisherCohortCell.contains("inflation adjusted to 2024 dollars"),
      "the inflation base must be quoted from the cell, not only typed as a number",
    )
    for (variable in listOf(ScorecardFieldOfStudyColumns.DEBT, ScorecardFieldOfStudyColumns.DEBT_MONTHLY_PAYMENT)) {
      assertEquals("NSLDS pooled AY2018-19, AY2019-20 cohort", variable.publisherCohortCell)
      assertEquals("NSLDS", variable.publisher)
      // The publisher states no adjustment for debt, so neither do we.
      assertTrue(
        !variable.publisherCohortCell.contains("inflation"),
        "we must not claim a dollar year the publisher never stated",
      )
    }
    assertEquals("Treasury", ScorecardFieldOfStudyColumns.EARNINGS.publisher)
  }

  @Test
  fun `earnings and debt are different cohorts, which is why neither is ever derived from the other`() {
    // brief 0011 D27, confirmed from the publisher's own cells: two publishers,
    // two cohorts, two dollar bases. A ratio over them is a figure nobody
    // published.
    assertTrue(
      ScorecardFieldOfStudyColumns.EARNINGS.cohortFirstYear !=
        ScorecardFieldOfStudyColumns.DEBT.cohortFirstYear,
    )
    assertTrue(
      ScorecardFieldOfStudyColumns.EARNINGS.publisher != ScorecardFieldOfStudyColumns.DEBT.publisher,
    )
  }

  @Test
  fun `these variables are deliberately absent from the institution money seam's transcription`() {
    // That file's key set must EQUAL ScorecardInstitutionColumns.LOADED_VARIABLES,
    // every VintageGroup in it is an institution cohort, and its Cohort type
    // parses `AcadYr <label>` only -- so a pooled two-year field-of-study cohort
    // cannot be spelled there. RFC 195's score bands hit the same wall; this is
    // that shipped decision, held by a test rather than by a comment.
    for (variable in ScorecardFieldOfStudyColumns.LOADED_VARIABLES) {
      assertTrue(
        variable !in ScorecardInstitutionColumns.LOADED_VARIABLES,
        "[$variable] is a field-of-study variable and must not be in the institution registry",
      )
    }
  }
}
