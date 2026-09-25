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
  fun `the transcription dates every variable whose date we cite, and only those`() {
    assertEquals(
      ScorecardInstitutionColumns.DATED_VARIABLES,
      cohortByVariable.keys,
      "one dated row per string whose date we cite -- money and outcome alike; an undated string is a figure served with no stated year",
    )
  }

  @Test
  fun `the money registry and the outcome registry are disjoint`() {
    // A column that is both money and outcome would be dated by two owners and
    // the file would not say which won.
    assertEquals(
      emptySet(),
      ScorecardInstitutionColumns.LOADED_VARIABLES intersect ScorecardInstitutionColumns.OUTCOME_VARIABLES,
      "no Scorecard column is both a money cell and an outcome figure",
    )
  }

  @Test
  fun `the three outcome cells carry the publisher's exact term clause`() {
    assertEquals(
      Cohort.StatedTerm("Fall 2024", 2024),
      cohortByVariable[ScorecardInstitutionColumns.ADM_RATE],
      "ADM_RATE is an entering term, carried verbatim with its year parsed",
    )
    // This equality IS the round-trip assertion the RFC asks for, made against
    // the COMMITTED file rather than a copy. C150_4's cell contains a comma, so
    // the file quotes it, and a hand-edited quoted cell is exactly the kind of
    // thing that reads as two columns -- but a cell read as two columns cannot
    // carry the clause whole, so it cannot equal the string below. A test that
    // wrote its own temp CSV and parsed that proved the READER and said nothing
    // about the artifact we ship.
    assertEquals(
      Cohort.StatedTerm("Fall 2018 or AcadYr 2018-19 cohort (differs by institutional calendar)", 2018),
      cohortByVariable[ScorecardInstitutionColumns.C150_4],
      "C150_4's quoted cell is ONE column, comma and all, and a term sentence rather than an AcadYr year",
    )
    assertEquals(
      Cohort.StatedTerm("Fall 2024", 2024),
      cohortByVariable[ScorecardInstitutionColumns.UGDS],
      "UGDS is an entering term, carried verbatim with its year parsed",
    )
  }

  /**
   * RFC 242's four cells, pinned by SHAPE and by value against the committed
   * file, exactly as the three above are.
   *
   * The three `OMAWDP8_*` cells are an entering ACADEMIC YEAR and not a term.
   * That is the whole reason [Cohort.StatedEnteringAcademicYear] exists: the
   * publisher's Outcome Measures cohort is everyone who arrived across a
   * July-June year, so "autumn 2016" would name a term those students did not
   * all enter in. `TRANS_4` carries `C150_4`'s cell byte for byte, because it
   * counts that same six-year cohort -- and it is still transcribed as its own
   * row, so a snapshot may re-date one without the other.
   */
  @Test
  fun `the four completion cells carry the publisher's exact clause`() {
    val enteringYear = Cohort.StatedEnteringAcademicYear("AY 2016-17 cohort", AcademicYear(2016))
    for (
    variable in
    listOf(
      ScorecardInstitutionColumns.OMAWDP8_ALL,
      ScorecardInstitutionColumns.OMAWDP8_PELL_ALL,
      ScorecardInstitutionColumns.OMAWDP8_NOPELL_ALL,
    )
    ) {
      assertEquals(
        enteringYear,
        cohortByVariable[variable],
        "[$variable] is the publisher's AY 2016-17 ENTERING cohort, carried verbatim with its year parsed",
      )
    }
    assertEquals(
      Cohort.StatedTerm("Fall 2018 or AcadYr 2018-19 cohort (differs by institutional calendar)", 2018),
      cohortByVariable[ScorecardInstitutionColumns.TRANS_4],
      "TRANS_4 counts C150_4's own cohort, so the publisher dates it with C150_4's own quoted cell",
    )
    assertEquals(
      cohortByVariable[ScorecardInstitutionColumns.C150_4],
      cohortByVariable[ScorecardInstitutionColumns.TRANS_4],
      "the two six-year figures are dated alike BY THE PUBLISHER -- asserted, never assumed, and never folded " +
        "into one transcribed row",
    )
  }

  /**
   * An entering-cohort cell is `AY <label> cohort` and nothing else.
   *
   * Three near-misses, each a plausible transcription slip: the publisher's own
   * trailing clause left on, a four-digit second year, and the closing word
   * dropped. What must break if this guard is absent: a cell nobody can read
   * silently deciding the period a family is told a completion figure
   * describes.
   *
   * Each case is asserted against the CAUSE it means, not against the one
   * substring all three share. Two of them fail the cell SHAPE and the third
   * fails inside the label reader for a completely different reason, so a
   * reader who swapped the label check for the shape check would have kept a
   * message-only assertion green.
   */
  @Test
  fun `an entering-cohort cell that is not exactly AY label cohort is refused, naming the cell and the cause`() {
    val nearMisses =
      mapOf(
        "AY 2016-17 cohort, reported in IPEDS DCY2024-25" to
          ScorecardDictionaryTranscription.AcademicYearCell.Cause.WRONG_CELL_SHAPE,
        "AY 2016-17" to ScorecardDictionaryTranscription.AcademicYearCell.Cause.WRONG_CELL_SHAPE,
        "AY 2016-2017 cohort" to
          ScorecardDictionaryTranscription.AcademicYearCell.Cause.LABEL_NOT_SPOKEN_THAT_WAY,
      )
    for ((cell, cause) in nearMisses) {
      val refusal =
        assertFailsWith<ScorecardDictionaryTranscription.UnreadableCohortCellException> {
          ScorecardDictionaryTranscription.cohortOf(ScorecardInstitutionColumns.OMAWDP8_ALL, cell)
        }
      assertEquals(cell, refusal.cell, "the refusal carries the cell it refused")
      assertEquals(ScorecardInstitutionColumns.OMAWDP8_ALL, refusal.variable, "and the variable it was transcribed for")
      assertEquals(cause, refusal.read.cause, "[$cell] is refused for its OWN reason: [${refusal.read.reason}]")
      val message = assertNotNull(refusal.message, "the refusal of [$cell] says something")
      assertTrue(
        message.contains("[most_recent_cohort=$cell]") && message.contains("[${ScorecardInstitutionColumns.OMAWDP8_ALL}]"),
        "the refusal names the cell AND the variable it was transcribed for: [$message]",
      )
    }
  }

  /**
   * The LAST router arm refuses like the other two: one exception type, one
   * routable cause.
   *
   * A cell that is not empty, not `AY … cohort` and not `AcadYr …` must name an
   * entering fall term or be refused. That arm used to throw `require`'s
   * `IllegalArgumentException`, so a caller asking "is this cell unreadable?"
   * had to catch two unrelated types and could route on a cause for two cell
   * shapes of three. What must break if this guard is absent: a re-worded
   * snapshot cell reaching a pin comparison as an unequal value rather than as
   * a named refusal.
   *
   * The cause is the term arm's OWN, never the `AY` arm's
   * [WRONG_CELL_SHAPE][ScorecardDictionaryTranscription.AcademicYearCell.Cause.WRONG_CELL_SHAPE]:
   * shared, this assertion could not tell a cell the router sent to the wrong
   * parser from a cell the right parser refused.
   */
  @Test
  fun `a cell naming no shape this reader knows is refused the same way, with its own cause`() {
    // Three plausible re-wordings: a term with a second year glued on, the
    // publisher's word changed, and a two-digit year.
    for (cell in listOf("Fall 2024-25", "Autumn 2024", "Fall 24")) {
      val refusal =
        assertFailsWith<ScorecardDictionaryTranscription.UnreadableCohortCellException> {
          ScorecardDictionaryTranscription.cohortOf(ScorecardInstitutionColumns.ADM_RATE, cell)
        }
      assertEquals(cell, refusal.cell, "the refusal carries the cell it refused")
      assertEquals(ScorecardInstitutionColumns.ADM_RATE, refusal.variable, "and the variable it was transcribed for")
      assertEquals(
        ScorecardDictionaryTranscription.AcademicYearCell.Cause.NOT_AN_ENTERING_TERM,
        refusal.read.cause,
        "[$cell] is refused as no shape this reader knows: [${refusal.read.reason}]",
      )
      val message = assertNotNull(refusal.message, "the refusal of [$cell] says something")
      assertTrue(
        message.contains("[most_recent_cohort=$cell]") && message.contains("[${ScorecardInstitutionColumns.ADM_RATE}]"),
        "the refusal names the cell AND the variable it was transcribed for: [$message]",
      )
    }
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
