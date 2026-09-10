package ed.unicoach.college

import ed.unicoach.college.ScorecardDictionaryTranscription.Cohort
import ed.unicoach.common.util.AcademicYear
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The year unicoach cites for every College Scorecard figure, pinned against
 * the publisher's own dictionary rather than re-typed (RFC 183).
 *
 * The two constants in [CanonicalMoneyLoader] were both wrong by two years for
 * as long as they existed, and nothing could contradict them: a naked
 * `AcademicYear(2022)` beside a comment saying it was right. The committed
 * transcription of the publisher's own `Most_Recent_Inst_Cohort_Map` sheet is
 * what contradicts them now. It is still a transcription -- there is no xlsx
 * reader on this project's JVM classpath and RFC 173 declined to add one -- but
 * a transcription with a digest is an artifact a constant can be CHECKED
 * against, and a snapshot bump that re-dates a variable now fails a test
 * instead of quietly re-dating every price a family is shown.
 *
 * A FILE-only suite: every assertion here reads a committed CSV and two Kotlin
 * constants. It needs no database, and so does not extend the DB base class --
 * the write behaviour the corrected year forces is
 * `ScorecardResidencyCollapseTest`, which does.
 */
class ScorecardDictionaryPinTest {
  private val cohortByVariable: Map<String, Cohort> = ScorecardDictionaryTranscription.cohortByVariable

  /** The dictionary's own cell, read: the publisher's `AcadYr ` sentence is parsed once, by the transcription. */
  private fun dictionaryYear(year: AcademicYear): Cohort = Cohort.StatedAcademicYear(year)

  @Test
  fun `the transcription dates every variable the loader writes, and only those`() {
    assertEquals(
      ScorecardInstitutionColumns.LOADED_VARIABLES,
      cohortByVariable.keys,
      "one dated row per string the loader writes -- an undated string is a figure served with no stated year",
    )
  }

  @Test
  fun `the published-charge year is the year the dictionary states for all eight charge columns`() {
    for (variable in ScorecardInstitutionColumns.PUBLISHED_CHARGE_VARIABLES) {
      assertEquals(
        dictionaryYear(CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR),
        cohortByVariable[variable],
        "PUBLISHED_PRICE_YEAR must be the year the publisher dates [$variable] to",
      )
    }
  }

  @Test
  fun `the blended-average vintage is the year the dictionary states for COSTT4_A and the NPT4 family`() {
    for (variable in ScorecardInstitutionColumns.BLENDED_AVERAGE_VARIABLES) {
      assertEquals(
        dictionaryYear(CanonicalMoneyLoader.BLENDED_AVERAGE_VINTAGE),
        cohortByVariable[variable],
        "BLENDED_AVERAGE_VINTAGE must be the year the publisher dates [$variable] to",
      )
    }
  }

  @Test
  fun `the vintage group a column joins is the year the fill stamps it with`() {
    // The group is not a label beside the write seams, it IS the year they
    // read (RFC 183): a ninth charge column joining the map is stamped by
    // joining it, and cannot be dated by whichever constant its call site
    // happens to type.
    for ((variable, group) in ScorecardInstitutionColumns.VINTAGE_GROUP_BY_VARIABLE) {
      val expected =
        when (group) {
          ScorecardInstitutionColumns.VintageGroup.PUBLISHED_CHARGE -> CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR
          ScorecardInstitutionColumns.VintageGroup.BLENDED_AVERAGE -> CanonicalMoneyLoader.BLENDED_AVERAGE_VINTAGE
          ScorecardInstitutionColumns.VintageGroup.UNDATED -> CanonicalMoneyLoader.VINTAGE_UNDATED
        }
      assertEquals(expected, ScorecardInstitutionColumns.stampedYearOf(variable), "[$variable] carries its group's year")
    }
    assertEquals(
      ScorecardInstitutionColumns.LOADED_VARIABLES,
      ScorecardInstitutionColumns.VINTAGE_GROUP_BY_VARIABLE.keys,
      "the registry IS the vintage map's key set -- a writable column with no year group cannot exist",
    )
  }

  @Test
  fun `the two administrative records state no academic year, and are stamped undated`() {
    // The publisher pools them -- NSLDS FY2020+FY2021, two Treasury cohorts --
    // so there is no year to carry and `undated` is the honest stamp, not a
    // gap. The publisher's cell is empty; the READER names that state, so this
    // asserts a state rather than an empty string.
    for (variable in listOf(ScorecardInstitutionColumns.GRAD_DEBT_MDN, ScorecardInstitutionColumns.MD_EARN_WNE_P10)) {
      assertEquals(
        Cohort.NoAcademicYear,
        cohortByVariable[variable],
        "[$variable] is pooled; the dictionary states no academic year",
      )
    }
    assertNull(CanonicalMoneyLoader.VINTAGE_UNDATED, "an absent year is absent, never a magic year")
  }

  @Test
  fun `PCTPELL is dated by the publisher and stamped undated by the fill, and that gap is declared`() {
    // RFC 183 D3, pinned rather than described: the dictionary DOES date
    // PCTPELL, the fill stamps it `VINTAGE_UNDATED`, and moving it onto a dated
    // key would change who wins that key -- unmeasured, so deliberately not
    // done here. When someone does fix it, this test fails and makes them say so.
    assertEquals(
      dictionaryYear(CanonicalMoneyLoader.BLENDED_AVERAGE_VINTAGE),
      cohortByVariable[ScorecardInstitutionColumns.PCTPELL],
      "the dictionary dates PCTPELL to the blended cohort's year, which is what makes the undated stamp a gap",
    )
    val pooled = setOf(ScorecardInstitutionColumns.GRAD_DEBT_MDN, ScorecardInstitutionColumns.MD_EARN_WNE_P10)
    assertEquals(
      setOf(ScorecardInstitutionColumns.PCTPELL),
      ScorecardInstitutionColumns.UNDATED_VARIABLES - pooled,
      "the fill stamps exactly one publisher-DATED variable undated, and it is PCTPELL",
    )
  }

  @Test
  fun `the years a family is told are the publisher's own labels`() {
    // The four copy sites (`ComparisonBasis`, reused verbatim on the report
    // page) print `AcademicYear.label` and nothing else, so this IS the
    // sentence: "the published price figures ... come from the 2024-25
    // academic year".
    assertEquals("2024-25", CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR.label)
    assertEquals("2023-24", CanonicalMoneyLoader.BLENDED_AVERAGE_VINTAGE.label)
  }

  @Test
  fun `the Scorecard's charges sit past IC_AY's staged window, so the two publishers cannot contend`() {
    // RFC 183 D2 as a CHECK rather than a claim. `CollegeCostService` says the
    // two publishers are one year apart and never contend, and the whole
    // precedence story on price cells is dead code only while that holds. It is
    // a relationship between two pinned constants in two files: an
    // `IC2024_AY.csv` bump (edit `IpedsChargeVocabulary.SURVEY_YEAR` alone)
    // puts IC_AY back on top of the Scorecard's key and silently restores the
    // displacement this RFC measured at 29,704 rows. It fails HERE instead,
    // where the fix is a decision someone makes.
    val ipedsNewestYear = AcademicYear(IpedsChargeVocabulary.SURVEY_YEAR)
    assertTrue(
      CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR > ipedsNewestYear,
      "IC_AY stages through [${ipedsNewestYear.label}] and the Scorecard is stamped " +
        "[${CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR.label}]: the two now share a key",
    )
  }

  @Test
  fun `a column claimed by two vintage groups is refused, naming the column and both groups`() {
    // The registry IS the year every Scorecard cell is served under, and a
    // plain `put` would let two claims on one column resolve themselves, last
    // writer winning -- the pin test above would then agree with whichever
    // group came second. What must break if this guard is absent: a price
    // silently dated by the group that happened to be registered later.
    val refusal =
      assertFailsWith<IllegalArgumentException> {
        with(ScorecardInstitutionColumns) {
          buildMap<String, ScorecardInstitutionColumns.VintageGroup> {
            setVintageGroup(TUITIONFEE_IN, ScorecardInstitutionColumns.VintageGroup.PUBLISHED_CHARGE)
            setVintageGroup(TUITIONFEE_IN, ScorecardInstitutionColumns.VintageGroup.BLENDED_AVERAGE)
          }
        }
      }
    val message = assertNotNull(refusal.message)
    assertTrue(
      message.contains("[${ScorecardInstitutionColumns.TUITIONFEE_IN}]"),
      "the refusal names the twice-claimed column: [$message]",
    )
    assertTrue(
      message.contains("[${ScorecardInstitutionColumns.VintageGroup.PUBLISHED_CHARGE}]") &&
        message.contains("[${ScorecardInstitutionColumns.VintageGroup.BLENDED_AVERAGE}]"),
      "the refusal names BOTH groups, so a fixer knows which claim to drop: [$message]",
    )
  }

  @Test
  fun `a variable transcribed twice is refused, not silently read off the last row`() {
    // The pinned constants are checked against this file, so a transcription
    // slip that repeats a variable must not resolve itself: `associate` would
    // keep the LAST row and pin the years against an arbitrary one of two
    // contradictory cells. What must break if this guard is absent: a
    // duplicated row deciding the year every family is shown.
    val duplicated =
      File.createTempFile("dictionary-variable-sources-duplicate", ".csv").apply {
        deleteOnExit()
        writeText(
          """
          variable_name,source,most_recent_cohort
          TUITIONFEE_IN,IPEDS,AcadYr 2024-25
          TUITIONFEE_IN,IPEDS,AcadYr 2022-23
          """.trimIndent(),
        )
      }
    val refusal =
      assertFailsWith<IllegalArgumentException> {
        ScorecardDictionaryTranscription.column(duplicated, "most_recent_cohort")
      }
    val message = assertNotNull(refusal.message)
    assertTrue(
      message.contains("[TUITIONFEE_IN]") && message.contains("twice"),
      "the refusal names the repeated variable: [$message]",
    )
  }
}
