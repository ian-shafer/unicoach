package ed.unicoach.college

import ed.unicoach.college.ScorecardDictionaryTranscription.Cohort
import ed.unicoach.college.ScorecardOutcomeCitations.Citation
import ed.unicoach.college.ScorecardOutcomeCitations.CohortPeriod
import ed.unicoach.db.dao.FIXTURE_COMPLETION_8YR_NON_PELL_SHARE
import ed.unicoach.db.dao.FIXTURE_COMPLETION_8YR_PELL_SHARE
import ed.unicoach.db.dao.FIXTURE_COMPLETION_8YR_SHARE
import ed.unicoach.db.dao.FIXTURE_TRANSFER_OUT_RATE_4YR_SHARE
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeMatch
import ed.unicoach.db.models.ResidencyTierBasis
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The seven outcome citations, pinned against the committed dictionary
 * transcription (RFC 226, widened by RFC 242): each citation names its own
 * period in [Citation.cohort] and speaks it inside [Citation.citedAs] through
 * [CohortPeriod.spoken], so the pin test derives the period from the CSV and
 * compares it as a VALUE.
 *
 * TWO period shapes are pinned, not one. The publisher dates `C150_4`,
 * `TRANS_4`, `ADM_RATE` and `UGDS` by an entering fall TERM and the three
 * `OMAWDP8_*` figures by a full entering ACADEMIC YEAR, so [pinnedPeriodOf] is
 * an exhaustive `when` over the transcription's own [Cohort] type. `Cohort` now
 * has four arms, so a FIFTH cohort shape is a compile error here, which is the
 * only way this pin stays a derivation from the committed cell rather than a
 * second place the period is typed.
 *
 * A FILE-only suite, like `ScorecardDictionaryPinTest`: no database.
 */
class ScorecardOutcomeCitationsTest {
  private val citations: Map<String, Citation> = ScorecardOutcomeCitations.byWireKey

  /**
   * One search row carrying NO outcome figure, so a test can add back exactly
   * the one figure it is about. Every other field is the minimum a
   * [CollegeMatch] needs to exist: this suite is about the stamps, not the row.
   */
  private fun rowWithNoOutcomeFigures(): CollegeMatch =
    CollegeMatch(
      id = CollegeId(UUID.randomUUID()),
      ipedsUnitId = 100000,
      name = "Stampless College",
      city = "Testville",
      state = "CA",
      control = "public",
      region = null,
      locale = null,
      undergradEnrollmentHeadcount = null,
      admissionRateShare = null,
      netPricePerYearUsd = null,
      rulerPriceUsd = null,
      residencyTierBasis = ResidencyTierBasis.TWO_TIERS_PUBLISHED,
      netPriceUsdByBand = emptyMap(),
      completionRate150pct4yrShare = null,
      completionRate8yrShare = null,
      completionRate8yrPellShare = null,
      completionRate8yrNonPellShare = null,
      transferOutRate4yrShare = null,
      medianEarnings10yAfterEntryUsd = null,
      medianDebtAtCompletionUsd = null,
      pellShare = null,
      website = null,
      programTitles = null,
    )

  /**
   * The one row above, carrying exactly the figure [citation] stamps.
   *
   * Dispatched on the CITATION, not on its wire key: [Citation] is a closed
   * enum, so this `when` is exhaustive and a fourth citation fails to COMPILE
   * here. Keyed by the key, it needed an `else -> error(...)` and a fourth
   * citation reached that branch at run time, in whichever test happened to
   * iterate first.
   */
  private fun rowCarryingOnly(citation: Citation): CollegeMatch =
    rowWithNoOutcomeFigures().let { row ->
      when (citation) {
        Citation.UNDERGRAD_ENROLLMENT -> row.copy(undergradEnrollmentHeadcount = 5000)
        Citation.ADMISSION_RATE -> row.copy(admissionRateShare = 0.4)
        Citation.COMPLETION_RATE -> row.copy(completionRate150pct4yrShare = 0.93)
        Citation.COMPLETION_RATE_8YR -> row.copy(completionRate8yrShare = FIXTURE_COMPLETION_8YR_SHARE)
        Citation.COMPLETION_RATE_8YR_PELL -> row.copy(completionRate8yrPellShare = FIXTURE_COMPLETION_8YR_PELL_SHARE)
        Citation.COMPLETION_RATE_8YR_NON_PELL -> row.copy(completionRate8yrNonPellShare = FIXTURE_COMPLETION_8YR_NON_PELL_SHARE)
        Citation.TRANSFER_OUT_RATE -> row.copy(transferOutRate4yrShare = FIXTURE_TRANSFER_OUT_RATE_4YR_SHARE)
      }
    }

  @Test
  fun `one citation per outcome wire key and no others`() {
    assertEquals(
      setOf(
        WIRE_UNDERGRAD_ENROLLMENT_HEADCOUNT,
        WIRE_ADMISSION_RATE_SHARE,
        WIRE_COMPLETION_RATE_150PCT_4YR_SHARE,
        WIRE_COMPLETION_RATE_8YR_SHARE,
        WIRE_COMPLETION_RATE_8YR_PELL_SHARE,
        WIRE_COMPLETION_RATE_8YR_NON_PELL_SHARE,
        WIRE_TRANSFER_OUT_RATE_4YR_SHARE,
      ),
      citations.keys,
      "the seven outcome figures this corpus stamps, keyed by the figure's own wire key",
    )
  }

  @Test
  fun `every fragment names its publisher inside the sentence`() {
    for (citation in citations.values) {
      assertTrue(
        citation.citedAs.contains("U.S. Department of Education's"),
        "[${citation.wireKey}] names the publisher in the sentence, never in a trailing list",
      )
    }
  }

  /**
   * The period the committed dictionary cell states, as the VALUE the citation
   * registry itself carries.
   *
   * An entering fall term is said as an autumn; a full entering academic year
   * is said as that academic year and NEVER as an autumn, because a cohort
   * that arrived across a July-June year did not arrive in the autumn and
   * saying so would misstate the population (RFC 242). Both of those wordings
   * belong to [CohortPeriod.spoken] and are no longer rebuilt here: this pin
   * used to author the prose a second time and then scrape the finished
   * sentence for the year it had just formatted.
   *
   * The two remaining arms fail rather than being absent: [Cohort] is closed,
   * so a citation dated by a money vintage or by nothing at all is a mistake
   * this suite must NAME, and a fifth cohort shape is a compile error here.
   * Each refusal names the CELL it refused, read verbatim from the
   * transcription -- the reader's own reading of a cell is not the cell, and
   * the cell is the one value that separates a bad transcription from a bad
   * sentence.
   */
  private fun pinnedPeriodOf(
    variable: String,
    cohort: Cohort,
  ): CohortPeriod =
    when (cohort) {
      is Cohort.StatedTerm -> {
        CohortPeriod.EnteringTerm(cohort.autumnYear)
      }

      is Cohort.StatedEnteringAcademicYear -> {
        CohortPeriod.EnteringAcademicYear(cohort.year)
      }

      is Cohort.StatedAcademicYear -> {
        fail(
          "[$variable] is dated by an [AcadYr] cell, which is how the publisher dates a MONEY collection " +
            "year: a figure a family is shown is dated by the cohort it describes, not by when it was " +
            "collected (cell=[${committedCellOf(variable)}], which this reader read as [${cohort.year.label}])",
        )
      }

      is Cohort.NoAcademicYear -> {
        fail(
          "[$variable] carries an EMPTY [most_recent_cohort] cell in " +
            "[${ScorecardDictionaryTranscription.FILE_NAME}]: a figure whose period cannot be transcribed is " +
            "not served, so either transcribe the publisher's cell or drop the citation",
        )
      }
    }

  /** The committed `most_recent_cohort` cell for [variable], VERBATIM: what a refusal here names. */
  private fun committedCellOf(variable: String): String = ScorecardDictionaryTranscription.cellByVariable.getValue(variable)

  /**
   * The period is a VALUE on the citation, so the committed cell and the
   * citation's period are compared as values -- this is the assertion that
   * used to substring-search English prose for a year it had rebuilt itself.
   *
   * The sentence is still checked to SPEAK that period, because what a family
   * hears is the sentence; and it is checked to speak no OTHER year, because a
   * second period beside the stated one is exactly what "the sentence cannot
   * contradict its own period" claims cannot happen, and a `contains` pin
   * cannot see it. The year that scan is held to is READ from the citation's
   * own typed period, never re-typed here.
   */
  @Test
  fun `each fragment speaks the term the transcription states, and no other year`() {
    val cohortByVariable = ScorecardDictionaryTranscription.cohortByVariable
    for (citation in citations.values) {
      val period = pinnedPeriodOf(citation.variable, cohortByVariable.getValue(citation.variable))

      assertEquals(
        period,
        citation.cohort,
        "[${citation.wireKey}] names a period its dictionary cell does not state: " +
          "cell=[${committedCellOf(citation.variable)}]",
      )
      assertTrue(
        citation.citedAs.contains(citation.cohort.spoken),
        "[${citation.wireKey}] does not speak its own period: period=[${citation.cohort.spoken}] " +
          "sentence=[${citation.citedAs}]",
      )
      assertEquals(
        listOf(citation.cohort.statedYear.toString()),
        Regex("""\d{4}""").findAll(citation.citedAs).map { it.value }.toList(),
        "[${citation.wireKey}] states one period and no other: sentence=[${citation.citedAs}]",
      )
    }
  }

  /**
   * The three eight-year figures are dated by an ENTERING ACADEMIC YEAR, and
   * EVERY OTHER citation by an entering fall TERM -- asserted by SHAPE, so
   * this suite proves it exercises both arms of [pinnedPeriodOf] rather than
   * silently pinning term cells that happen to pass.
   *
   * The enum is WALKED and the term group is the COMPLEMENT, never a second
   * hand-written list: two subsets of a closed enum with nothing asserting
   * they exhaust it left `ADM_RATE` and `UGDS` in neither, so an eighth
   * citation would have joined neither and been shape-checked nowhere --
   * silently, because a list that names five of seven still passes.
   */
  @Test
  fun `the eight-year figures are dated by an entering academic year and every other citation by a term`() {
    val cohortByVariable = ScorecardDictionaryTranscription.cohortByVariable
    val enteringAcademicYear =
      setOf(Citation.COMPLETION_RATE_8YR, Citation.COMPLETION_RATE_8YR_PELL, Citation.COMPLETION_RATE_8YR_NON_PELL)

    for (citation in Citation.entries) {
      val cohort = cohortByVariable.getValue(citation.variable)
      if (citation in enteringAcademicYear) {
        assertIs<Cohort.StatedEnteringAcademicYear>(
          cohort,
          "[${citation.variable}] is an entering academic-year cohort, which is why it is not said as an autumn",
        )
      } else {
        assertIs<Cohort.StatedTerm>(cohort, "[${citation.variable}] is an entering term")
      }
    }
  }

  @Test
  fun `the completion fragment carries the population this RFC exists to say`() {
    val completion = Citation.COMPLETION_RATE
    assertTrue(completion.citedAs.contains("full-time"), "the completion figure names its population")
    assertTrue(completion.citedAs.contains("for the first time"), "first-time is the load-bearing half")
  }

  /**
   * The eight-year figure exists because `C150_4` counts only the students who
   * started full-time, for the first time. Its sentence has to say so, in the
   * publisher's own union of its four sibling elements -- otherwise a family
   * hears a second graduation rate and no reason it differs (RFC 242, D9).
   */
  @Test
  fun `the eight-year fragment says it counts everyone who entered, over eight years`() {
    val eightYear = Citation.COMPLETION_RATE_8YR

    assertTrue(eightYear.citedAs.contains("full-time and part-time"), "both attendance intensities are counted")
    assertTrue(eightYear.citedAs.contains("new to college and transferring in"), "transfers in are counted")
    assertTrue(eightYear.citedAs.contains("within eight years"), "the window is eight years, said in words")
  }

  /**
   * Each Pell arm names the group the PUBLISHER counted and says it is not a
   * forecast (brief 0011 D48).
   *
   * That neither arm compares itself to the other is the SAME rule every
   * citation obeys, so it is asserted over all seven in
   * `no citation sentence compares its figure with another figure` below
   * rather than over these two here.
   */
  @Test
  fun `each Pell arm names the group the publisher counted`() {
    val pell = Citation.COMPLETION_RATE_8YR_PELL
    val nonPell = Citation.COMPLETION_RATE_8YR_NON_PELL

    assertTrue(pell.citedAs.contains("received a federal Pell Grant"), "the Pell arm names the group it counts")
    assertTrue(
      nonPell.citedAs.contains("did not receive a federal Pell Grant"),
      "the non-Pell arm names the group it counts",
    )
    for (arm in listOf(pell, nonPell)) {
      assertTrue(
        arm.citedAs.contains("not a forecast for any one student"),
        "[${arm.wireKey}] says it is a group the publisher counted: [${arm.citedAs}]",
      )
    }
  }

  /**
   * The exact sentence each citation may speak.
   *
   * An ALLOWLIST, and the reason it exists is that the guard below is not one:
   * [FIGURE_COMPARISON_WORDS] names six forbidden words and permits every
   * comparative it failed to think of -- `better`, `worse`, `outperforms`,
   * `exceeds`, `ahead of`, `behind`, `twice as many`, `falls short` all pass
   * it. The seven sentences are a CLOSED set of seven literals, so pinning
   * them exactly means a reworded sentence is REFUSED whatever words it uses,
   * and a comparison between two figures has to be argued for by editing this
   * pin rather than by dodging six nouns.
   *
   * Spelled out rather than interpolated from the constants under test: a pin
   * that shares its subject's own constants pins nothing.
   *
   * The word guard below is KEPT beside it, and is not made redundant by it.
   * It states the RULE -- these words, as words, compare two figures -- over
   * the same vocabulary the coach prompt's guard reads, so the two surfaces
   * still share one list; this map states the CLOSURE.
   */
  private val permittedCitedAs: Map<Citation, String> =
    mapOf(
      Citation.COMPLETION_RATE to
        "the share of students who started full-time, for the first time, in autumn 2018 and had finished a degree at " +
        "that school within six years, as the U.S. Department of Education's College Scorecard reports it",
      Citation.ADMISSION_RATE to
        "the share of applicants that school admitted for the class entering in autumn 2024, as the U.S. Department of " +
        "Education's College Scorecard reports it -- one overall rate, not a rate for any single application round",
      Citation.UNDERGRAD_ENROLLMENT to
        "the number of undergraduates seeking a degree or certificate there in autumn 2024, as the U.S. Department of " +
        "Education's College Scorecard reports it",
      Citation.COMPLETION_RATE_8YR to
        "the share of ALL students who entered that school in the 2016-17 academic year -- full-time and part-time, new " +
        "to college and transferring in -- who had been awarded a degree or certificate within eight years, as the U.S. " +
        "Department of Education's College Scorecard reports it",
      Citation.COMPLETION_RATE_8YR_PELL to
        "the share of the students who entered that school in the 2016-17 academic year and received a federal Pell " +
        "Grant who had been awarded a degree or certificate within eight years, as the U.S. Department of Education's " +
        "College Scorecard reports it -- a group the publisher counted, not a forecast for any one student",
      Citation.COMPLETION_RATE_8YR_NON_PELL to
        "the share of the students who entered that school in the 2016-17 academic year and did not receive a federal " +
        "Pell Grant who had been awarded a degree or certificate within eight years, as the U.S. Department of " +
        "Education's College Scorecard reports it -- a group the publisher counted, not a forecast for any one student",
      Citation.TRANSFER_OUT_RATE to
        "the share of students who started full-time, for the first time, in autumn 2018 and transferred to another " +
        "school instead of finishing at that one, as the U.S. Department of Education's College Scorecard reports it -- " +
        "counted out of the same six-year group as the six-year completion rate, and leaving is not the same as not " +
        "finishing",
    )

  @Test
  fun `each citation speaks exactly the sentence this suite permits`() {
    assertEquals(
      Citation.entries.toSet(),
      permittedCitedAs.keys,
      "a citation with no permitted sentence, or a permitted sentence with no citation",
    )
    for (citation in Citation.entries) {
      assertEquals(
        permittedCitedAs.getValue(citation),
        citation.citedAs,
        "[${citation.wireKey}] speaks a sentence this suite does not permit; a reworded figure sentence is a " +
          "copy change a human reviews, not a diff that lands green",
      )
    }
  }

  /**
   * NO citation's sentence compares its figure with another figure -- all
   * seven, not the two Pell arms alone.
   *
   * The class states the rule over every citation and it used to be enforced on
   * two of them, so the sentence most likely to break it -- the eight-year
   * figure gaining "higher than the six-year rate" -- was guarded nowhere. A
   * citation is ONE figure's sentence; a relation between figures is the coach
   * prompt's to state, and stated here it would ride the wire beside a row
   * where it is false. Measured over the pinned snapshot, the Pell figure is
   * the lower of the two at only 65.8% of institutions.
   *
   * The scanned words are [FIGURE_COMPARISON_WORDS], the same list the coach
   * prompt's own guard reads: one copy rule, one vocabulary.
   */
  @Test
  fun `no citation sentence compares its figure with another figure`() {
    for (citation in Citation.entries) {
      for (comparison in FIGURE_COMPARISON_WORDS) {
        // WORD boundaries, not a bare substring: `lower` is inside `slower`
        // and `follower`, so a bare match would accuse a sentence of comparing
        // figures when it does no such thing. The RULE is the same -- these
        // words, as words, are forbidden in a citation's sentence.
        assertFalse(
          Regex("""\b${Regex.escape(comparison)}\b""").containsMatchIn(citation.citedAs),
          "[${citation.wireKey}] compares its figure with another one using [$comparison]: two measures over " +
            "two populations and two cohorts are two measures, and the relation between them belongs to the " +
            "coach prompt: [${citation.citedAs}]",
        )
      }
    }
  }

  /**
   * Transfer-out counts a MOVE, out of a different denominator from the
   * eight-year figures -- `TRANS_4` is the six-year cohort's own share. A
   * sentence that omitted either would let the number be read as a failure
   * rate, or subtracted from a figure it shares no denominator with.
   */
  @Test
  fun `the transfer-out fragment says whose cohort it counts and that leaving is not failing`() {
    val transferOut = Citation.TRANSFER_OUT_RATE

    assertTrue(transferOut.citedAs.contains("six-year group"), "the denominator is the six-year cohort, said aloud")
    assertTrue(
      transferOut.citedAs.contains("leaving is not the same as not finishing"),
      "a transfer-out share is a move, not a failure: [${transferOut.citedAs}]",
    )
    assertFalse(transferOut.citedAs.contains("dropping out"), "a transfer is never called dropping out")
  }

  /**
   * The stamp a figure gets is keyed by THAT figure. The renderer used to take
   * the caller's own map key, so a page could ship the completion rate's
   * population and period under `pell_share` and compile -- the coach would
   * then say a confident, precisely-attributed sentence about the wrong
   * measure, to a family.
   *
   * Asserted one citation at a time, from a row carrying only that figure, so
   * a renderer taking its key from anywhere but the citation fails here.
   */
  @Test
  fun `the figure_sources key a citation is emitted under is that citation's own wire key`() {
    for (citation in citations.values) {
      val present = ScorecardOutcomeCitations.presentIn(listOf(rowCarryingOnly(citation)))
      val answer = buildJsonObject { putFigureSources(present) }
      val figureSources =
        assertNotNull(
          answer["figure_sources"],
          "the answer carries no figure_sources block at all: $answer",
        ).jsonObject

      assertEquals(
        setOf(citation.wireKey),
        figureSources.keys,
        "the row carries only [${citation.wireKey}], so that is the only figure stamped",
      )
      val stamp = assertNotNull(figureSources[citation.wireKey], "[${citation.wireKey}] is stamped: $figureSources").jsonObject
      assertEquals(
        citation.citedAs,
        assertNotNull(stamp["cited_as"], "the stamp for [${citation.wireKey}] says nothing: $stamp").jsonPrimitive.content,
        "the sentence under [${citation.wireKey}] is that figure's own",
      )
    }
  }

  /**
   * The ONE presence rule both the payload path and the fit lens's prose read
   * (RFC 226): a figure no returned row reports is not stamped, and a page
   * carrying none is stamped with nothing at all.
   */
  @Test
  fun `presentIn names only the figures some row carries`() {
    val admissionOnly = rowCarryingOnly(Citation.ADMISSION_RATE)

    assertEquals(
      listOf(Citation.ADMISSION_RATE),
      ScorecardOutcomeCitations.presentIn(listOf(rowWithNoOutcomeFigures(), admissionOnly)),
      "one row carries an admission rate and nothing else does",
    )
    assertEquals(
      emptyList<Citation>(),
      ScorecardOutcomeCitations.presentIn(listOf(rowWithNoOutcomeFigures())),
      "a page whose rows carry no outcome figure stamps nothing",
    )
    assertEquals(
      emptyList<Citation>(),
      ScorecardOutcomeCitations.presentIn(emptyList()),
      "an empty page stamps nothing",
    )
  }

  /**
   * Sorted by wire key, which is the order the wire is written in: adding a
   * fourth figure cannot silently re-order the three already there.
   */
  @Test
  fun `presentIn is sorted by wire key`() {
    val everyFigure =
      rowWithNoOutcomeFigures().copy(
        undergradEnrollmentHeadcount = 5000,
        admissionRateShare = 0.4,
        completionRate150pct4yrShare = 0.93,
        completionRate8yrShare = FIXTURE_COMPLETION_8YR_SHARE,
        completionRate8yrPellShare = FIXTURE_COMPLETION_8YR_PELL_SHARE,
        completionRate8yrNonPellShare = FIXTURE_COMPLETION_8YR_NON_PELL_SHARE,
        transferOutRate4yrShare = FIXTURE_TRANSFER_OUT_RATE_4YR_SHARE,
      )

    val wireKeys = ScorecardOutcomeCitations.presentIn(listOf(everyFigure)).map { it.wireKey }

    assertEquals(wireKeys.sorted(), wireKeys, "the citations arrive in wire-key order")
    assertEquals(citations.keys.sorted(), wireKeys, "every figure this row carries is stamped")
  }

  /**
   * The renderer imposes the order it promises, whoever built the list: the
   * sort lives in `presentIn` for the surfaces that ask it, and a caller
   * assembling its own list used to emit whatever order it held.
   */
  @Test
  fun `putFigureSources writes wire-key order whatever order it is handed`() {
    val answer =
      buildJsonObject {
        putFigureSources(listOf(Citation.UNDERGRAD_ENROLLMENT, Citation.COMPLETION_RATE, Citation.ADMISSION_RATE))
      }
    val figureSources =
      assertNotNull(answer["figure_sources"], "the answer carries no figure_sources block at all: $answer").jsonObject

    assertEquals(
      figureSources.keys.sorted(),
      figureSources.keys.toList(),
      "the renderer sorts the keys it was handed unsorted",
    )
  }

  /**
   * One stamp per figure: a repeated citation would write its key twice and
   * the reader would see whichever copy survived, which is a silently dropped
   * stamp rather than a refusal.
   */
  @Test
  fun `putFigureSources refuses a figure stamped twice`() {
    val refusal =
      runCatching {
        buildJsonObject { putFigureSources(listOf(Citation.ADMISSION_RATE, Citation.ADMISSION_RATE)) }
      }.exceptionOrNull()

    val message = assertNotNull(refusal, "stamping one figure twice is refused").message
    assertTrue(
      message?.contains(Citation.ADMISSION_RATE.wireKey) == true,
      "the refusal names the figure stamped twice: [$message]",
    )
  }
}
