package ed.unicoach.college

import ed.unicoach.college.ScorecardDictionaryTranscription.Cohort
import ed.unicoach.college.ScorecardOutcomeCitations.Citation
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeMatch
import ed.unicoach.db.models.ResidencyTierBasis
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The three outcome citations, pinned against the committed dictionary
 * transcription (RFC 226): each citation names its own period in
 * [Citation.cohortTerm] and speaks it inside [Citation.citedAs], so the pin
 * test derives the period from the CSV and compares it as a VALUE.
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
      }
    }

  @Test
  fun `one citation per outcome wire key and no others`() {
    assertEquals(
      setOf(
        WIRE_UNDERGRAD_ENROLLMENT_HEADCOUNT,
        WIRE_ADMISSION_RATE_SHARE,
        WIRE_COMPLETION_RATE_150PCT_4YR_SHARE,
      ),
      citations.keys,
      "the three outcome figures this run stamps, keyed by the figure's own wire key",
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
   * The period is a VALUE on the citation, so the committed cell and the
   * spoken term are compared as values -- this is the assertion that used to
   * substring-search English prose for a year it had rebuilt itself.
   *
   * The sentence is still checked to SPEAK that term, because what a family
   * hears is the sentence; and it is checked to speak no OTHER year, because a
   * second period beside the stated one is exactly what "the sentence cannot
   * contradict its own period" claims cannot happen, and a `contains` pin
   * cannot see it.
   */
  @Test
  fun `each fragment speaks the term the transcription states, and no other year`() {
    val cohortByVariable = ScorecardDictionaryTranscription.cohortByVariable
    for (citation in citations.values) {
      val cohort =
        assertIs<Cohort.StatedTerm>(cohortByVariable.getValue(citation.variable), "[${citation.variable}] is a term")

      assertEquals(
        "autumn ${cohort.autumnYear}",
        citation.cohortTerm,
        "[${citation.wireKey}] names a period its dictionary cell does not state: cell=[${cohort.term}]",
      )
      assertTrue(
        citation.citedAs.contains(citation.cohortTerm),
        "[${citation.wireKey}] does not speak its own period: term=[${citation.cohortTerm}] " +
          "sentence=[${citation.citedAs}]",
      )
      assertEquals(
        listOf(cohort.autumnYear.toString()),
        Regex("""\d{4}""").findAll(citation.citedAs).map { it.value }.toList(),
        "[${citation.wireKey}] states one period and no other: sentence=[${citation.citedAs}]",
      )
    }
  }

  @Test
  fun `the completion fragment carries the population this RFC exists to say`() {
    val completion = Citation.COMPLETION_RATE
    assertTrue(completion.citedAs.contains("full-time"), "the completion figure names its population")
    assertTrue(completion.citedAs.contains("for the first time"), "first-time is the load-bearing half")
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
