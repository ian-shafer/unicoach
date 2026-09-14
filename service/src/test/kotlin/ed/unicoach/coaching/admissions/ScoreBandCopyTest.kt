package ed.unicoach.coaching.admissions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D9, asserted on the copy itself (RFC 195).
 *
 * The rule is absolute and older than this slice: no test prep, no score
 * prediction, no chancing. A band is a fact about last year's admitted class,
 * so no sentence may say a student NEEDS a score, SHOULD score one, is
 * COMPETITIVE at a school, or has a CHANCE of admission -- and nothing may
 * compare a student to a band at all.
 *
 * It is asserted on the CONSTANTS rather than on a rendered payload, which is
 * the only place it can be asserted honestly: a payload test sees the
 * sentences one fixture happens to produce, while these constants are every
 * sentence this section can ever say. That is also why [ScoreBandCopy]'s
 * clauses are constants at all ([TestPolicyCopy]'s precedent): a test that had
 * to cut a claim out of a finished sentence would make punctuation a contract.
 *
 * The sweep is over EVERY string the object exposes. The sentences are LISTED
 * rather than collected, because two of them are functions and a constant
 * cannot be generated from a reflected field -- but the list is then CHECKED
 * against reflection over `ScoreBandCopy`'s own declared `String` fields, so a
 * constant added later and forgotten here fails this file instead of going
 * unswept. The self-check also proves the collection is not empty and that the
 * ban can fire.
 */
class ScoreBandCopyTest {
  /**
   * Every sentence and clause the section can say: the copy constants, the
   * three band statements, the average statement, and the citation.
   *
   * The band statements are GENERATED here from real figures rather than read
   * as constants, because `statementOf` is a function and a banned word could
   * enter through its template as easily as through a constant.
   */
  private val everySentence: List<String> =
    declaredStringsOf(ScoreBandCopy) +
      declaredStringsOf(ScoreBandCitation) +
      ScoreBandCopy.statementOf(sweptAverage()) +
      ScoredTest.entries.map { band(it) }.map { ScoreBandCopy.statementOf(it) } +
      ScoredTest.entries.map { it.label }

  /**
   * Every `String` an object DECLARES, read reflectively rather than listed.
   *
   * Listed, a clause added later is a clause no ban sweeps -- the failure mode
   * of every corpus guard, and the reason this reads the objects instead. It
   * reads FIELDS through Java reflection rather than `kotlin.reflect`, which
   * is not on this module's test classpath; a `const val` and a computed `val`
   * both have a field, so both are swept. BOTH
   * objects are swept: [ScoreBandCitation] owns live coach-facing copy too
   * (`citedAs` goes on the wire beside the figures), so a sentence added there
   * is as answerable to D9 as one added to [ScoreBandCopy].
   */
  private fun declaredStringsOf(owner: Any): List<String> =
    owner.javaClass.declaredFields
      .filter { it.type == String::class.java }
      .mapNotNull { field ->
        field.isAccessible = true
        field.get(owner) as? String
      }

  /** One band for the sweep. [ScoreBand.of] is the only constructor; a refused pair would be a test bug. */
  private fun band(test: ScoredTest): ScoreBand =
    assertIs<ScoreBandResult.Band>(
      ScoreBand.of(test, test.publishedScale.first + 1, test.publishedScale.first + 2),
      "[$test] must accept a pair inside its own published scale",
    ).band

  /**
   * The average the sweep speaks, through the only type that may hold one.
   *
   * A function and not a property, because [everySentence] is a property
   * declared above it: a property reading a later property reads it before it
   * is initialised.
   */
  private fun sweptAverage(): AdmittedAverage = assertNotNull(AdmittedAverage.of(1310), "1310 is inside the published SAT composite scale")

  /**
   * The phrases D9 forbids, lower-cased and matched as substrings.
   *
   * "need" catches `needs`, `needed` and `you need`; "target" is NOT banned,
   * because the one sentence that uses it says an average is NOT one, and a
   * ban that cannot tell a claim from its denial is a ban that gets deleted
   * the first time it fires.
   */
  private val bannedPhrases =
    listOf(
      "need",
      "should score",
      "aim for",
      "chance of admission",
      "chances",
      "competitive",
      "likely to get in",
      "safety school",
      "reach school",
      "your score",
      "you scored",
    )

  @Test
  fun `no sentence tells a student what to score, or compares her to a band`() {
    val offenders =
      everySentence.flatMap { sentence ->
        bannedPhrases.filter { sentence.lowercase().contains(it) }.map { "[$it] in [$sentence]" }
      }
    assertEquals(
      emptyList(),
      offenders,
      "D9 is absolute: a band is a fact about last year's admitted class, never a target and never a chance",
    )
  }

  @Test
  fun `cut-off is only ever spoken as a denial, never as a claim`() {
    // "cut-off" cannot join [bannedPhrases]: the two sentences whose whole job
    // is to DENY it would be the first offenders, and a ban that cannot tell a
    // claim from its denial is a ban that gets deleted the first time it
    // fires.
    //
    // So the DENIALS are deleted and the word is banned in what is left. The
    // legal spellings are the copy constants THEMSELVES -- not a character
    // offset, not a "not a " prefix two words long. An earlier form of this
    // test walked the indices of each occurrence and asked whether "not a "
    // sat immediately before it, which meant the guard's meaning was a string
    // arithmetic fact about the copy: reword a denial to "never a cut-off" or
    // "and not, to be clear, a cut-off" and the test silently starts calling
    // the denial a claim, or the claim a denial. Here, rewording a denial
    // fails the constant-equality above and is a decision someone makes.
    val offenders =
      everySentence.filter { sentence ->
        DENIALS.fold(sentence) { remainder, denial -> remainder.replace(denial, " ") }.lowercase().contains(CUT_OFF)
      }
    assertEquals(
      emptyList(),
      offenders,
      "a band is never a cut-off, so the only legal way to say the word is one of the named denials $DENIALS",
    )
  }

  @Test
  fun `the cut-off guard fires on a claim the phrase list cannot catch`() {
    // The planted claim, kept from the case above: it passes every banned
    // phrase, which is exactly why "cut-off" has its own test -- and it must
    // still be caught once the denials are removed, or the guard above is
    // vacuous.
    val plantedCutOff = "their cut-off was 1400"
    assertTrue(
      bannedPhrases.none { plantedCutOff.contains(it) },
      "the cut-off claim is the one the phrase list cannot catch, which is why it has its own test",
    )
    val remainder = DENIALS.fold(plantedCutOff) { text, denial -> text.replace(denial, " ") }
    assertTrue(
      remainder.contains(CUT_OFF),
      "deleting the denials must leave a planted claim exposed: [$remainder]",
    )
    // ...and the denials themselves must really be deletable, or the guard
    // passes by removing nothing and finding nothing.
    for (denial in DENIALS) {
      assertTrue(denial.contains(CUT_OFF), "a denial that does not say the word is not what this guard exempts: [$denial]")
      assertTrue(
        everySentence.any { it.contains(denial) },
        "a denial nothing says is a hole in the guard, not an exemption: [$denial]",
      )
    }
  }

  @Test
  fun `every band sentence says whose scores they are, and the standing qualifier says what they are not`() {
    // The claim and its limit, together: a range is only honest with the
    // "a quarter above, a quarter below" sentence beside it.
    for (test in ScoredTest.entries) {
      val scores = band(test)
      val statement = ScoreBandCopy.statementOf(scores)
      assertTrue(statement.startsWith(ScoreBandCopy.MIDDLE_HALF_OF_ADMITTED), "[$statement]")
      assertTrue(statement.contains(test.label), "a band names the test it is about: [$statement]")
      assertTrue(
        statement.contains("${scores.lower}") && statement.contains("${scores.upper}"),
        "and carries its figures: [$statement]",
      )
    }
    assertTrue(ScoreBandCopy.NOT_A_CUT_OFF.contains(ScoreBandCopy.A_QUARTER_EACH_SIDE))
    assertTrue(ScoreBandCopy.NOT_A_CUT_OFF.contains(ScoreBandCopy.A_PICTURE_OF_LAST_YEARS_CLASS))
    assertTrue(ScoreBandCopy.NOT_A_CUT_OFF.contains("not a cut-off"), "the qualifier states the limit in words")
  }

  @Test
  fun `the average is always labelled an average, and never a cut-off or a target`() {
    val statement = ScoreBandCopy.statementOf(sweptAverage())
    assertTrue(statement.contains("1310"), "the figure is in the sentence: [$statement]")
    assertTrue(statement.contains(ScoreBandCopy.AVERAGE_LABEL), "with its measure named: [$statement]")
    assertTrue(statement.contains(ScoreBandCopy.AVERAGE_IS_NOT_A_TARGET), "and with what it is not: [$statement]")
    assertTrue(ScoreBandCopy.AVERAGE_IS_NOT_A_TARGET.contains("not a cut-off"))
    assertTrue(ScoreBandCopy.AVERAGE_IS_NOT_A_TARGET.contains("not a target"))
  }

  @Test
  fun `the absent-section sentence is about us, never about the school`() {
    assertTrue(
      ScoreBandCopy.NOT_REPORTED.contains(TestPolicyCopy.POLICY_GAP_IS_OURS),
      "whose silence it is comes from the ONE home of that clause: [${ScoreBandCopy.NOT_REPORTED}]",
    )
    assertTrue(ScoreBandCopy.NOT_REPORTED.startsWith("we do not have"), "our gap, said first")
    assertFalse(
      ScoreBandCopy.NOT_REPORTED.contains("the school does not"),
      "our missing data is never rendered as a statement about the school",
    )
  }

  @Test
  fun `the cohort is written down once, and every sentence that dates a figure takes it from there`() {
    // D-B: one constant, quoting the publisher's own dictionary cell. A
    // sentence that typed the term would be a second place for the date to
    // live, and the two would drift the first time a snapshot moved.
    assertEquals("autumn 2024", ScoreBandCitation.COHORT_TERM)
    assertTrue(ScoreBandCopy.MIDDLE_HALF_OF_ADMITTED.contains(ScoreBandCitation.COHORT_TERM))
    assertTrue(ScoreBandCopy.AVERAGE_LABEL.contains(ScoreBandCitation.COHORT_TERM))
    assertTrue(ScoreBandCitation.citedAs.contains(ScoreBandCitation.COHORT_TERM))
    // The term is INSIDE the sentence, so no bare numeric year key ever
    // reaches the wire and the RFC 143 guard keeps its meaning.
    assertFalse(ScoreBandCitation.citedAs.contains("2024-25"), "a cohort is a term, not an academic year")
  }

  @Test
  fun `a band refuses to exist unless it is a pair, ordered, and inside its own scale`() {
    // The invariants the KDoc claims, asserted on the TYPE: the constructor is
    // private and `copy` with it, so `of` is the only way in and these are the
    // only bands there can be.
    //
    // Each refusal is a NAMED outcome and not a null. The three below are
    // stored rows the `colleges` CHECKs forbid, and telling them apart from
    // the school that simply publishes no score is the whole reason
    // [ScoreBandResult] exists.
    val test = ScoredTest.SAT_READING
    assertEquals(MalformedBand.HALF_A_PAIR, malformed(test, 620, null).reason, "half a band is not a range")
    assertEquals(MalformedBand.HALF_A_PAIR, malformed(test, null, 700).reason, "and neither is the other half")
    assertEquals(MalformedBand.INVERTED, malformed(test, 700, 620).reason, "an inverted pair is not a range")
    assertEquals(MalformedBand.OUT_OF_SCALE, malformed(test, 199, 700).reason, "199 is outside the SAT section scale")
    assertEquals(MalformedBand.OUT_OF_SCALE, malformed(test, 600, 801).reason, "and so is 801")
    // The malformed arm carries the STORED figures, or an operator cannot find
    // the row it is being told about.
    assertEquals(199 to 700, malformed(test, 199, 700).let { it.lower to it.upper })
    assertEquals(test, malformed(test, 199, 700).test)

    // Ordinary absence is its own outcome, and it is NOT malformed: five
    // schools in six publish no score, and logging them would bury the three
    // rows that matter.
    assertEquals(ScoreBandResult.Absent, ScoreBand.of(test, null, null), "no score published is not a defect")

    val narrow = assertIs<ScoreBandResult.Band>(ScoreBand.of(test, 700, 700))
    assertEquals(700, narrow.band.lower, "a one-score band is a real narrow class, not a fault")
    // Each test measures itself against its OWN scale: 33 is a fine ACT
    // composite and an impossible SAT section score.
    assertIs<ScoreBandResult.Band>(ScoreBand.of(ScoredTest.ACT_COMPOSITE, 28, 33))
    assertEquals(
      MalformedBand.OUT_OF_SCALE,
      malformed(ScoredTest.SAT_READING, 28, 33).reason,
      "the SAT section scale starts at 200",
    )
  }

  @Test
  fun `an average is refused unless it is inside the published composite scale`() {
    // The gate the average did not have. `sat_average_equivalent_score` is
    // loaded 0..Int.MAX_VALUE and its only DB guard is non-negative, so a
    // stored 0 rendered as "0 was the average score of students admitted for
    // autumn 2024" -- the one figure in this section with no published scale
    // behind it.
    assertNull(AdmittedAverage.of(0), "0 is not an average of anything: the SAT composite scale starts at 400")
    assertNull(AdmittedAverage.of(399), "399 is below the published composite scale")
    assertNull(AdmittedAverage.of(1601), "and 1601 is above it")
    assertNull(AdmittedAverage.of(null), "an absent average is not an average")
    // A SECTION score is not caught by the SCALE -- 620 is a legal composite
    // as well as a legal section score -- and it is not meant to be. What
    // forbids `statementOf(band.lower)` is the TYPE: that call used to
    // compile and no longer does, because a `ScoreBand`'s `Int` cannot become
    // an [AdmittedAverage] without passing [AdmittedAverage.of]. The scale
    // catches the figure that is not a composite at all.
    assertEquals(400, AdmittedAverage.of(400)?.score, "the floor of the scale is a real average")
    assertEquals(1600, AdmittedAverage.of(1600)?.score, "and so is its ceiling")
    assertEquals(1310, AdmittedAverage.of(1310)?.score)
  }

  /** One refused pair, as the malformed outcome it must be -- so a test never asserts on a bare null. */
  private fun malformed(
    test: ScoredTest,
    lower: Int?,
    upper: Int?,
  ): ScoreBandResult.Malformed = assertIs<ScoreBandResult.Malformed>(ScoreBand.of(test, lower, upper))

  @Test
  fun `the cohort is pinned to the snapshot it was transcribed from`() {
    // D-B, and the thing a literal cannot say on its own: `bin/fetch-scorecard`
    // discovers the CURRENT release, so the seed directory can move under this
    // constant with no Kotlin change -- leaving every band sentence dated to a
    // cohort nobody re-read. Read the committed provenance the way
    // `ScorecardDictionaryPinTest` reads its committed CSV.
    // Located by walking UP for the seed directory, `ScorecardDictionaryPinTest`'s
    // own idiom: a module-relative literal breaks the moment this suite is run
    // from a different working directory, and the failure would read as a
    // missing snapshot rather than a missing path.
    val provenance =
      generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "db/seed/scorecard/PROVENANCE.json") }
        .firstOrNull { it.isFile }
    assertNotNull(
      provenance,
      "no db/seed/scorecard/PROVENANCE.json on the walk UP from [${File(".").absoluteFile}]: it is a COMMITTED " +
        "record (the two CSVs beside it are gitignored), so a checkout without it cannot pin the cohort",
    )
    val release =
      Json
        .parseToJsonElement(provenance.readText())
        .jsonObject
        .getValue("release")
        .jsonPrimitive.content
    assertEquals(
      release,
      ScoreBandCitation.COHORT_SNAPSHOT_RELEASE,
      "the cohort term [${ScoreBandCitation.COHORT_TERM}] was transcribed from release " +
        "[${ScoreBandCitation.COHORT_SNAPSHOT_RELEASE}] and the seed is now release [$release]: re-read " +
        "CollegeScorecardDataDictionary.xlsx sheet Most_Recent_Inst_Cohort_Map for SATVR/SATMT/ACTCM, then update " +
        "COHORT_TERM, COHORT_SNAPSHOT_RELEASE and db/seed/scorecard/FETCH-NOTES.md together",
    )
  }

  @Test
  fun `the sweep reads real sentences and the ban can fire`() {
    // Without this, the guard above passes exactly as well over an empty list
    // -- the failure mode of every corpus sweep.
    assertTrue(everySentence.size >= 12, "the sweep must read every clause: ${everySentence.size}")
    assertTrue(everySentence.none { it.isBlank() }, "a blank sentence would pass every ban")
    val planted = "you need a 1400 to be competitive here"
    assertTrue(
      bannedPhrases.any { planted.contains(it) },
      "the ban list must match the sentence it exists to catch",
    )
  }

  @Test
  fun `the reflective sweep reaches the real copy of both owners`() {
    // NOT "is every declared constant in everySentence" -- `everySentence` IS
    // built by that same reflection, so such a test compares a list with
    // itself and passes over an empty object. What has to be proved instead is
    // that the MECHANISM reaches real, named, live copy in BOTH owners: if
    // `declaredStringsOf` silently returned nothing (a field filter that stops
    // matching, an object whose constants move to a companion), the ban tests
    // above would still pass while sweeping nothing at all.
    val copy = declaredStringsOf(ScoreBandCopy)
    val citation = declaredStringsOf(ScoreBandCitation)
    assertTrue(ScoreBandCopy.NOT_REPORTED in copy, "the absent-section sentence must be swept: $copy")
    assertTrue(ScoreBandCopy.NOT_A_CUT_OFF in copy, "and so must the standing qualifier")
    assertTrue(
      ScoreBandCitation.COHORT_TERM in citation,
      "the citation owns live coach-facing copy too, so its constants are swept: $citation",
    )
    assertTrue(
      everySentence.any { it.contains(ScoreBandCitation.citedAs) },
      "the cited sentence goes on the wire beside the figures, so D9 answers for it",
    )
  }

  private companion object {
    const val CUT_OFF = "cut-off"

    /**
     * The ONLY sentences allowed to contain the word, named as the constants
     * they are.
     *
     * [ScoreBandCopy.NOT_A_CUT_OFF] is listed as well as the clause inside it
     * because both are swept: the whole qualifier and the half of it that
     * carries the word are each a declared constant, and the longest is
     * deleted first so the shorter is not left as a fragment.
     */
    val DENIALS =
      listOf(
        ScoreBandCopy.NOT_A_CUT_OFF,
        ScoreBandCopy.A_PICTURE_OF_LAST_YEARS_CLASS,
        ScoreBandCopy.AVERAGE_IS_NOT_A_TARGET,
      )
  }
}
