package ed.unicoach.coaching.admissions

import ed.unicoach.db.models.College
import ed.unicoach.db.models.PublishedScoreScale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Which TEST a published percentile band belongs to (RFC 195).
 *
 * The family, not the section, is what the D16 fallback is decided on (D-A):
 * the admitted-student average is an SAT-equivalent figure, so it stands in
 * for a missing SAT band and never for a missing ACT one. Two members, because
 * two tests are published; a third would arrive with its own sections and its
 * own fallback question.
 */
enum class ScoreFamily {
  SAT,
  ACT,
}

/**
 * One published band's IDENTITY: which test it describes, the words a family
 * hears for it, the family whose fallback rule it answers to, the two `College`
 * columns it is read from, and the published scale those columns may hold (RFC
 * 195).
 *
 * Everything a band needs travels on the member, which is what makes
 * [SchoolScoreBands.from] a single `mapNotNull` over `entries` rather than
 * three hand-paired constructions -- and therefore what makes the claim below
 * about DECLARATION ORDER true by construction rather than by promise.
 *
 * The LABEL is spoken English, not a source variable name: the Scorecard calls
 * these `SATVR25`, `SATMT75` and `ACTCM25`, and `SATVR` means "critical
 * reading" only to someone holding the dictionary. The [wireName] is the same
 * word the label names, so a model reading the payload and a student hearing
 * the sentence are told about the same test.
 *
 * DECLARATION ORDER IS RENDER ORDER: the two SAT sections, then the ACT -- the
 * order a family hears them in (RFC 145's rule, applied inside one section).
 *
 * [publishedScale] is the same scale the `colleges` CHECKs draw and the ingest
 * reads against, taken from the ONE home both of those read --
 * [PublishedScoreScale], in `:db` beside the row the columns live on. It is
 * named per TEST here so no call site can measure one test's band against
 * another's scale.
 */
enum class ScoredTest(
  val wireName: String,
  val label: String,
  val family: ScoreFamily,
  val publishedScale: IntRange,
  private val lowerColumn: (College) -> Int?,
  private val upperColumn: (College) -> Int?,
) {
  SAT_READING(
    wireName = "sat_reading",
    label = "the SAT reading section",
    family = ScoreFamily.SAT,
    publishedScale = PublishedScoreScale.SAT_SECTION,
    lowerColumn = College::satReading25thPercentileScore,
    upperColumn = College::satReading75thPercentileScore,
  ),
  SAT_MATH(
    wireName = "sat_math",
    label = "the SAT math section",
    family = ScoreFamily.SAT,
    publishedScale = PublishedScoreScale.SAT_SECTION,
    lowerColumn = College::satMath25thPercentileScore,
    upperColumn = College::satMath75thPercentileScore,
  ),
  ACT_COMPOSITE(
    wireName = "act_composite",
    label = "the ACT composite",
    family = ScoreFamily.ACT,
    publishedScale = PublishedScoreScale.ACT_COMPOSITE,
    lowerColumn = College::actComposite25thPercentileScore,
    upperColumn = College::actComposite75thPercentileScore,
  ),
  ;

  /** This band's 25th-percentile cell on one stored row, or null when the school publishes none. */
  fun lowerOf(college: College): Int? = lowerColumn(college)

  /** This band's 75th-percentile cell on the same row; the `colleges` CHECK pairs it with [lowerOf]. */
  fun upperOf(college: College): Int? = upperColumn(college)
}

/**
 * One published percentile band: what the middle half of a school's admitted
 * class scored on ONE test (RFC 195).
 *
 * [statement] is COMPUTED from the two numbers and the test, on the
 * [CdsCitation.citedAs] and [TestPolicyCopy.statementOf] precedent: the
 * sentence the coach reads aloud and the figures it came from cannot drift
 * apart, because there is only one of them. Nothing here is ever typed beside
 * the data.
 *
 * THE CONSTRUCTOR IS PRIVATE and `copy` goes with it
 * (`@ConsistentCopyVisibility`), so [of] is the only way a band exists. The
 * rules below are then properties of the TYPE rather than of the one call site
 * that remembered them:
 *
 * - a band is a PAIR: half a band is not a range;
 * - a band is ORDERED: `upper >= lower`. `>=`, not `>`, because a school whose
 *   quartiles land on the same score is a real narrow class;
 * - a band is IN ITS PUBLISHED SCALE ([ScoredTest.publishedScale]).
 *
 * The `colleges` CHECKs say all three too, and the ingest drops a band rather
 * than offering half or an inverted one -- but a CHECK does not reach this
 * type, and a reader of a payload cannot see one. [of] is how a row that
 * should not exist stays unrepresentable HERE, where the section is assembled.
 *
 * A band carries NO [ed.unicoach.db.models.FigureStatus] and no RFC 179
 * assurance tier, for the reason [SchoolTestPolicy]'s KDoc already states:
 * that vocabulary belongs to the canonical money tables, whose rows record a
 * publisher's own suppression and imputation flags. The Scorecard publishes
 * none for these columns, and borrowing the words would claim a provenance
 * model this datum does not have.
 */
@ConsistentCopyVisibility
data class ScoreBand private constructor(
  val test: ScoredTest,
  val lower: Int,
  val upper: Int,
) {
  /** The sentence a family hears for this band -- owned by [ScoreBandCopy], never retyped at a call site. */
  val statement: String get() = ScoreBandCopy.statementOf(this)

  companion object {
    /**
     * One stored pair, resolved to what it actually IS (RFC 195): a band, a
     * school that publishes none, or a stored pair that violates the
     * `colleges` CHECKs.
     *
     * It used to return a nullable [ScoreBand], which folded FOUR states into
     * one `null` and left the section unable to tell them apart -- ordinary
     * absence (5,234 of the snapshot's 6,273 colleges), a half pair, an
     * inverted pair and an out-of-scale pair. The last three are not silence:
     * each is a stored row that the database's own CHECKs forbid, so it means
     * the CHECK was dropped or something wrote past it, and that is a corpus
     * defect an operator can act on -- exactly what the other four sections of
     * this tool already log. A refusal that leaves no trace is the one thing
     * this section must not do.
     *
     * THE ARMS ARE A PRECEDENCE, not three independent tests: the FIRST
     * refusal that matches is the one reported, so `of(SAT_READING, 900, 100)`
     * is both inverted and out of scale and comes back [MalformedBand.INVERTED].
     * The order runs from the fault that makes the other questions unanswerable
     * to the fault that needs both numbers read: a half pair has no pair to
     * order or to measure, and an inverted pair is already not a range whatever
     * scale its ends sit on. Reordering these lines changes what an operator is
     * told about a row, silently.
     */
    fun of(
      test: ScoredTest,
      lower: Int?,
      upper: Int?,
    ): ScoreBandResult {
      if (lower == null && upper == null) return ScoreBandResult.Absent
      if (lower == null || upper == null) return ScoreBandResult.Malformed(test, lower, upper, MalformedBand.HALF_A_PAIR)
      if (upper < lower) return ScoreBandResult.Malformed(test, lower, upper, MalformedBand.INVERTED)
      if (lower !in test.publishedScale || upper !in test.publishedScale) {
        return ScoreBandResult.Malformed(test, lower, upper, MalformedBand.OUT_OF_SCALE)
      }
      return ScoreBandResult.Band(ScoreBand(test, lower, upper))
    }

    /** The three bands of one stored row, each resolved by [of], in declaration order. */
    fun resultsOf(college: College): List<ScoreBandResult> = ScoredTest.entries.map { of(it, it.lowerOf(college), it.upperOf(college)) }
  }
}

/**
 * WHY a stored pair is not a band (RFC 195). All three are violations of a
 * `colleges` CHECK, so all three mean the same thing about the corpus and none
 * of them means anything about the school.
 */
enum class MalformedBand(
  val value: String,
) {
  /** One percentile stored without the other. `colleges_*_band_paired_check` forbids it. */
  HALF_A_PAIR("half_a_pair"),

  /** `upper < lower`. `colleges_*_band_ordered_check` forbids it. */
  INVERTED("inverted"),

  /** A score outside [ScoredTest.publishedScale]. The per-column domain CHECK forbids it. */
  OUT_OF_SCALE("out_of_scale"),
}

/**
 * What one stored pair turned out to be (RFC 195) -- a VALUE, so a refusal can
 * be acted on instead of being inferred from a null.
 *
 * The distinction that matters is between [Absent] and [Malformed]. They are
 * one sentence to a family, and the wire rightly cannot tell them apart -- but
 * they are opposite facts about our corpus. Absence is the ordinary case for
 * five schools in six and says only that the federal file carries no score.
 * Malformed is a row the database's own CHECKs say cannot exist, is rare, and
 * is the operator's to fix. A single null said both, silently.
 */
sealed interface ScoreBandResult {
  /** A published, ordered, in-scale pair. */
  data class Band(
    val band: ScoreBand,
  ) : ScoreBandResult

  /** The school publishes no score for this test: both cells are null. */
  data object Absent : ScoreBandResult

  /** A stored pair that violates a `colleges` CHECK -- kept whole so the log can name the figures. */
  data class Malformed(
    val test: ScoredTest,
    val lower: Int?,
    val upper: Int?,
    val reason: MalformedBand,
  ) : ScoreBandResult
}

/**
 * The one admitted-student AVERAGE this section may speak: `SAT_AVG`, inside
 * the published SAT composite scale ([PublishedScoreScale.SAT_COMPOSITE]).
 *
 * It exists because the average was the only rendered figure in this slice
 * with NO scale gate. Every band passes [ScoredTest.publishedScale], while
 * `sat_average_equivalent_score` is loaded `0..Int.MAX_VALUE`, its only
 * database guard is non-negative, and the raw `Int` was forwarded to the wire
 * -- so a stored `0` rendered as "0 was the average score of students admitted
 * for autumn 2024", in front of a family, as a fact.
 *
 * A TYPE rather than a check at the renderer, because the renderer is not the
 * only caller and `statementOf(band.lower)` -- a 200-800 section score
 * spoken as a 1600-scale average -- type-checked before this existed. With the
 * constructor private, an [AdmittedAverage] anywhere in the process has passed
 * the scale.
 *
 * THIS IS THE PUBLISHED SCALE, NOT A PLAUSIBILITY FLOOR. D-D is untouched: the
 * two implausible but in-domain ACT bands (5-7 and 1-5) are bands, not
 * averages, and still render exactly as published. A score outside 400..1600
 * is not an implausible average, it is not an SAT composite at all.
 */
@JvmInline
value class AdmittedAverage private constructor(
  val score: Int,
) {
  companion object {
    /** One stored average, or null when the column holds something that is not one. */
    fun of(score: Int?): AdmittedAverage? {
      if (score == null || score !in PublishedScoreScale.SAT_COMPOSITE) return null
      return AdmittedAverage(score)
    }
  }
}

/**
 * What the scores of ONE school's admitted class actually looked like (RFC
 * 195): the fifth section of `college_admissions_profile`, and the answer to
 * the question an admitted-student AVERAGE cannot answer.
 *
 * A student told that a school's admitted students averaged 1310 hears a bar.
 * What she can locate herself in is the RANGE -- the middle half scored between
 * X and Y -- which is what this section carries for the 1,039 schools that
 * publish one. Every count in this file was measured over the fetched Scorecard
 * snapshot named in `db/seed/scorecard/PROVENANCE.json`, release `06102026`
 * (6,273 institution rows).
 *
 * D9 is inherited and absolute: no test prep, no score prediction, no chancing.
 * The band is a fact about last year's admitted class, never a target, and
 * nothing in this file or its copy compares a student to it.
 *
 * ONE LIST, not a shape per test. [bands] holds every band the school
 * publishes, in [ScoredTest] declaration order, which is the order the wire
 * renders and a family hears. An earlier draft held the two SAT sections in
 * their own type beside a bare ACT band; that structure existed nowhere but in
 * the type -- the wire has always been a flat array -- and it made "the order
 * is the declaration order" a sentence rather than a fact.
 *
 * THE D16 FALLBACK IS PER TEST FAMILY (D-A), and it lives in [fallbackAverageOf]
 * so no call site can re-decide it. 88 colleges publish an SAT average and no
 * SAT band, and every one of them publishes an ACT band -- so a rule of "an
 * average only when the school has no band of any kind" would never fire once.
 *
 * [average] is `sat_average_equivalent_score` (Scorecard `SAT_AVG`), a
 * single blended number, and it is always spoken as an average and explicitly
 * not as a cut-off or a target ([ScoreBandCopy.AVERAGE_LABEL]). It is an
 * [AdmittedAverage] and not an `Int` because it is a RENDERED FIGURE and every
 * other rendered figure in this section passes a published scale; the column
 * itself is loaded `0..Int.MAX_VALUE`.
 *
 * THE CONSTRUCTOR IS PRIVATE, with `copy` (`@ConsistentCopyVisibility`). The
 * two paragraphs above are invariants, and a public constructor made both
 * violable in one expression: `section.copy(average = 1310)` on a
 * banded section produces exactly the payload D-A exists to prevent, and a
 * section with neither band nor average is a citation with no figure under it.
 * [from] is the only way in.
 *
 * The section costs NO new read. Every figure is a column on the `College` row
 * the student's college-list selection has already fetched, so
 * `CollegeAdmissionsService.readInSession` still costs six statements.
 */
@ConsistentCopyVisibility
data class SchoolScoreBands private constructor(
  val bands: List<ScoreBand>,
  val average: AdmittedAverage?,
  val source: ScoreBandCitation,
) {
  companion object {
    /**
     * One college row as a score-band section, or null when the school
     * publishes no band and no average -- the caller then names
     * [AdmissionsField.SCORE_BANDS] in `data_availability`, exactly as it does
     * for the other four sections, rather than rendering a citation with no
     * figure under it.
     *
     * It takes the RESOLVED results rather than resolving them itself, because
     * the caller has to see the [ScoreBandResult.Malformed] arms to log them
     * ([CollegeAdmissionsService]'s `scoreBandsOf`) and resolving twice would
     * be two answers to one question. The one-argument overload is the same
     * call with the same resolution, for every caller that has nothing to log.
     */
    fun from(
      college: College,
      results: List<ScoreBandResult>,
    ): SchoolScoreBands? {
      val bands = results.filterIsInstance<ScoreBandResult.Band>().map { it.band }
      val average = fallbackAverageOf(college, results)
      if (bands.isEmpty() && average == null) return null
      return SchoolScoreBands(bands = bands, average = average, source = ScoreBandCitation)
    }

    /** [from] over the row's own three results, for a caller with nothing to log. */
    fun from(college: College): SchoolScoreBands? = from(college, ScoreBand.resultsOf(college))

    /**
     * D16, as one rule (D-A): the admitted-student average stands in when this
     * school publishes no SAT band, and for nothing else.
     *
     * A NAMED function rather than a `takeIf` tail, because it is the product
     * rule this slice was gated on and a test reads it by name. The family is
     * the subject: `SAT_AVG` is an SAT-equivalent figure, so an ACT band
     * beside it answers a different question and does not suppress it, while
     * either SAT SECTION does -- showing both for one test is how a family
     * comes to read the average as the bar and the band as the detail.
     *
     * A MALFORMED SAT pair suppresses it exactly as a good one does, and this
     * is the sharp edge of the rule. The school HAS a stored SAT range; we
     * refused to read it. Falling back to the average there would answer a
     * question the family did not ask with a different figure on a different
     * scale, and nothing on the wire or in the log would say a range had been
     * swapped for an average. So the section says the ACT band it can say, or
     * nothing at all, and the school is named in `data_availability` -- our
     * gap, said plainly, which is the rule this whole section runs on.
     *
     * The average itself must then pass the published composite scale
     * ([AdmittedAverage]): a stored `0` is not an average of anything.
     */
    private fun fallbackAverageOf(
      college: College,
      results: List<ScoreBandResult>,
    ): AdmittedAverage? {
      val satIsSpokenFor =
        results.any {
          when (it) {
            is ScoreBandResult.Band -> it.band.test.family == ScoreFamily.SAT
            is ScoreBandResult.Malformed -> it.test.family == ScoreFamily.SAT
            ScoreBandResult.Absent -> false
          }
        }
      if (satIsSpokenFor) return null
      return AdmittedAverage.of(college.satAverageEquivalentScore)
    }
  }
}

/**
 * The words a family hears about a score band, and the ONE home of each claim
 * (RFC 195).
 *
 * Every sentence is ASSEMBLED from named clauses, on [TestPolicyCopy]'s
 * precedent and for its reason: a test that has to cut a claim out of a
 * finished sentence makes punctuation a contract, and `substringBefore` is a
 * silent no-op when its delimiter moves. The clauses are constants, so the D9
 * guard and the prompt-agreement tests READ them rather than parsing them, and
 * rewording one fails in the test that names it.
 *
 * The MIDDLE HALF, in words, because "the 25th to 75th percentile" is a
 * statistician's phrase and this sentence is read aloud to a sixteen-year-old.
 * The tense is PAST throughout: these are the scores of a class that has
 * already been admitted, and the tense is the cheapest place to say so -- it
 * survives the sentence being quoted without [NOT_A_CUT_OFF] beside it.
 *
 * NOTHING here says a student needs a score, should score a score, or is
 * competitive at a school; nothing compares a student to a band; and no
 * sentence turns a band into a chance of admission. That is D9, and
 * `ScoreBandCopyTest` asserts it on these constants directly.
 */
object ScoreBandCopy {
  /**
   * Whose class the band describes -- the clause every band statement opens
   * with, carrying the cohort from its one constant so a sentence can never
   * name a year the data does not come from.
   */
  const val MIDDLE_HALF_OF_ADMITTED: String =
    "the middle half of students admitted for ${ScoreBandCitation.COHORT_TERM}"

  /** What the two numbers ARE -- the half of [NOT_A_CUT_OFF] that states the arithmetic. */
  const val A_QUARTER_EACH_SIDE: String =
    "a quarter of admitted students scored below the lower number and a quarter above the higher one"

  /** What the band is NOT -- the half of [NOT_A_CUT_OFF] that states the limit of the claim. */
  const val A_PICTURE_OF_LAST_YEARS_CLASS: String =
    "it is a picture of last year's class and not a cut-off"

  /**
   * The sentence that rides with EVERY rendered band (D17's reasoning, and
   * [TestPolicyCopy.CONFIRM_WITH_SCHOOL]'s pattern).
   *
   * Named by the SECTION rather than by the tool description on purpose: a
   * rule stated once in a tool's prose is a rule the model may or may not have
   * in mind when it reaches the figure, whereas this string arrives in the
   * model's context attached to the very numbers it qualifies. It is emitted
   * only WITH bands, for the same reason: it describes two numbers, and beside
   * an average-only section it would qualify nothing.
   */
  const val NOT_A_CUT_OFF: String = "$A_QUARTER_EACH_SIDE, so $A_PICTURE_OF_LAST_YEARS_CLASS."

  /**
   * What the D16 fallback figure IS, said in full (D-A). It names the measure
   * and the cohort, so the number never arrives as a bare score.
   */
  const val AVERAGE_LABEL: String =
    "the average score of students admitted for ${ScoreBandCitation.COHORT_TERM}"

  /** What the average is NOT -- attached to every rendered average, never optional. */
  const val AVERAGE_IS_NOT_A_TARGET: String = "an average, not a cut-off and not a target"

  /**
   * The sentence for an absent section, carried on the tool DESCRIPTION.
   *
   * It says something about US and never about the school: we hold a band for
   * 1,039 of the snapshot's 6,273 colleges, so "we do not have score data for
   * this school" is the truth and "this school published none" is a claim about
   * the school we cannot support. BOTH borrowed clauses come from
   * [TestPolicyCopy], the one home of those words in this package -- whose gap
   * it is, and where a family goes instead -- so the two sections cannot drift
   * into describing the same silence differently.
   */
  const val NOT_REPORTED: String =
    "we do not have score data for this school, which is ${TestPolicyCopy.POLICY_GAP_IS_OURS} and not a " +
      "statement that the school published none; ${TestPolicyCopy.THE_SCHOOLS_OWN_PAGE_HAS_IT}"

  /**
   * The sentence for one band. COMPUTED from the band, never stored beside it,
   * so the numbers the coach says and the numbers the payload carries are the
   * same numbers.
   */
  fun statementOf(band: ScoreBand): String = "$MIDDLE_HALF_OF_ADMITTED scored between ${band.lower} and ${band.upper} on ${band.test.label}"

  /**
   * The sentence for the D16 average. The figure is spoken WITH its measure
   * and its limit in one breath: an average alone, in a model's context beside
   * three real ranges, is precisely the number that gets read as a bar.
   */
  fun statementOf(average: AdmittedAverage): String = "${average.score} was $AVERAGE_LABEL -- $AVERAGE_IS_NOT_A_TARGET"
}

/**
 * The one home of the score-band section's WIRE KEYS, on [MeritAidWire]'s
 * precedent and for its reason: the renderer emits them and the tool
 * DESCRIPTION points sentences at them ("say it the way
 * `admitted_average_label` says it"), so a literal typed in the description
 * would leave a renamed key pointing at a key that no longer exists, with
 * nothing failing.
 *
 * [ADMITTED_AVERAGE_LABEL_KEY] is NOT `average_label`: [MeritAidWire] already
 * owns that spelling in the SAME payload, and a description sentence naming it
 * would point at two different sub-objects' keys.
 */
object ScoreBandWire {
  /** The array of bands; empty is never emitted, so the key's presence means the school publishes a range. */
  const val BANDS_KEY: String = "bands"

  /** Which test one band is about, and the words for it -- the same pair [ScoredTest] holds. */
  const val TEST_KEY: String = "test"
  const val TEST_LABEL_KEY: String = "test_label"

  // The measure-bearing numeric keys (D-C). Each names its measure in full --
  // a 25th-percentile score, a 75th-percentile score, an admitted average --
  // which is what earns them a place in the RFC 143 allowlist.
  const val SCORE_25TH_KEY: String = "score_25th_percentile"
  const val SCORE_75TH_KEY: String = "score_75th_percentile"
  const val ADMITTED_AVERAGE_SCORE_KEY: String = "admitted_average_score"

  /** The D16 sub-object, present only when the school publishes no SAT band. */
  const val ADMITTED_AVERAGE_KEY: String = "admitted_average"

  /** The spoken key beside each figure: one per band, one for the average. */
  const val STATEMENT_KEY: String = "statement"
  const val ADMITTED_AVERAGE_LABEL_KEY: String = "admitted_average_label"

  /** The standing qualifier, emitted only beside bands. */
  const val NOT_A_CUT_OFF_KEY: String = "not_a_cut_off"

  /** The section's source object, the same key every other section uses. */
  const val SOURCE_KEY: String = "source"

  /** The keys under this section whose value is a NUMBER by contract (the RFC 143 guard's allowlist). */
  val NUMERIC_KEYS: Set<String> = setOf(SCORE_25TH_KEY, SCORE_75TH_KEY, ADMITTED_AVERAGE_SCORE_KEY)

  /**
   * The section. EVERY band carries both its two numbers AND the sentence that
   * says what they mean (D-C): a model asked "is 1250 inside this range" must
   * compare figures rather than parse English, and a pair of bare integers in a
   * model's context is exactly what gets read back as a cut-off.
   *
   * The bands array and [NOT_A_CUT_OFF_KEY] are emitted ONLY when this school
   * publishes a band. The average-only section is reachable and ordinary -- 88
   * colleges in the snapshot -- and an empty array beside a sentence about "the
   * lower number and the higher one" is furniture for figures that are not
   * there.
   */
  fun objectOf(section: SchoolScoreBands): JsonObject =
    buildJsonObject {
      if (section.bands.isNotEmpty()) {
        putJsonArray(BANDS_KEY) { section.bands.forEach { add(bandObject(it)) } }
        put(NOT_A_CUT_OFF_KEY, ScoreBandCopy.NOT_A_CUT_OFF)
      }
      section.average?.let { put(ADMITTED_AVERAGE_KEY, admittedAverageObject(it)) }
      putJsonObject(SOURCE_KEY) { putCitation(section.source) }
    }

  /**
   * One band: which test, the two percentile scores as numbers, and the
   * sentence -- from ONE construct, so the sentence and the numbers under it
   * are the same band's.
   */
  private fun bandObject(band: ScoreBand): JsonObject =
    buildJsonObject {
      put(TEST_KEY, band.test.wireName)
      put(TEST_LABEL_KEY, band.test.label)
      put(SCORE_25TH_KEY, band.lower)
      put(SCORE_75TH_KEY, band.upper)
      put(STATEMENT_KEY, band.statement)
    }

  /**
   * The D16 fallback: the admitted-student average, its measure named, and the
   * statement that it is an average and not a target -- never a band, never
   * beside an SAT band, and never a number on its own.
   *
   * The parameter is an [AdmittedAverage] and not an `Int`, so the figure that
   * reaches the wire has passed the published composite scale. As an `Int`,
   * this function would render whatever the column held -- and
   * `statementOf(band.lower)`, a section score spoken as a composite average,
   * type-checked.
   */
  private fun admittedAverageObject(average: AdmittedAverage): JsonObject =
    buildJsonObject {
      put(ADMITTED_AVERAGE_SCORE_KEY, average.score)
      put(ADMITTED_AVERAGE_LABEL_KEY, ScoreBandCopy.AVERAGE_LABEL)
      put(STATEMENT_KEY, ScoreBandCopy.statementOf(average))
    }
}

/**
 * The citation a score-band section carries: one federal file, one entering
 * cohort, named once (RFC 195 D-B).
 *
 * ONE CONSTANT COHORT, and this is the file that owns it. All six band
 * variables, and `SAT_AVG` with them, are dated by the publisher's own
 * workbook -- `CollegeScorecardDataDictionary.xlsx`, sheet
 * `Most_Recent_Inst_Cohort_Map`:
 *
 * > Fall 2024, reported in IPEDS DCY2024-25
 *
 * and its `Institution_Data_Dictionary` sheet gives `SOURCE = IPEDS` for all
 * six, with `SATVR*` labelled _critical reading_, `SATMT*` _math_ and `ACTCM*`
 * the ACT _cumulative_ score. So the honest date is an entering-cohort TERM,
 * autumn 2024 -- not an academic year, which is why it is a constant here and
 * not a row in the money seam's academic-year vintage machinery. The same cell
 * is recorded in `db/seed/scorecard/FETCH-NOTES.md`, with the reason these six
 * are not added to `dictionary-variable-sources.csv`.
 *
 * NAMED WITHOUT THE PUBLISHER, deliberately. `ScorecardScoreBandCitation`
 * would put the literal "Scorecard" on every line that mentions the type --
 * the field declaration, the factory, two copy constants and the renderer --
 * and RFC 177's ban is stated over LIVE LINES containing a publisher's name,
 * so each of those would need its own `money-attribution-exempt` marker. A
 * guard that has to be waived six times to name a type is a guard being worn
 * down; the publisher is named ONCE, in the sentence below, where the
 * exemption is true and legible.
 *
 * An `object` rather than a data class with a year field, because there is
 * nothing per-school or per-row to carry: one pinned file publishes one cohort
 * for every college in it. When a later snapshot moves the cohort, this
 * constant and the FETCH-NOTES cell move together, in one edit, and every
 * sentence in [ScoreBandCopy] follows.
 *
 * There is deliberately NO url key and no bare numeric year: the term lives
 * INSIDE the sentence, so no `cohort_year` number with no measure reaches the
 * wire and the RFC 143 guard keeps its meaning.
 */
object ScoreBandCitation {
  /**
   * The entering cohort every figure in this section describes, in the words a
   * family hears. The ONE place this term is written down.
   */
  const val COHORT_TERM: String = "autumn 2024"

  /**
   * The Scorecard release [COHORT_TERM] was transcribed from, and the reason
   * that term is allowed to be a literal.
   *
   * The publisher's own release id, in the publisher's own `MMDDYYYY` form:
   * `06102026` is 10 June 2026, not 6 October. It is compared as an opaque
   * STRING to `release` in `db/seed/scorecard/PROVENANCE.json` and is never
   * parsed as a date, so the ambiguity costs nothing at runtime -- it costs a
   * reader, which is what this sentence is for.
   *
   * `bin/fetch-scorecard` discovers the CURRENT release, so the seed directory
   * can move under this constant without a line of Kotlin changing: the data
   * would be a later cohort while the sentence still said autumn 2024, which is
   * a dated claim about a year nobody checked. `ScoreBandCitationPinTest`
   * asserts this equals `release` in `db/seed/scorecard/PROVENANCE.json`.
   *
   * WHEN THAT TEST FAILS the fetch is not wrong and this constant is not
   * wrong -- they simply describe different releases. Re-read
   * `CollegeScorecardDataDictionary.xlsx`, sheet `Most_Recent_Inst_Cohort_Map`,
   * for the six band variables, and update [COHORT_TERM], this release, and the
   * matching section of `db/seed/scorecard/FETCH-NOTES.md` together.
   */
  const val COHORT_SNAPSHOT_RELEASE: String = "06102026"

  /** The sentence fragment the coach says, with the publisher and the cohort in it rather than beside it. */
  val citedAs: String =
    // The publisher is named in a LIVE string and the RFC 177 ban is on naming
    // the publisher of a canonical MONEY row. A percentile score is not a
    // money figure and this seam publishes none, so the line carries its own
    // exemption rather than deriving words from MoneySourceCopy -- reaching
    // into the money seam's copy object is how a testing fact ends up inside
    // the money vocabulary.
    "the U.S. Department of Education's College Scorecard, " + // money-attribution-exempt: a score band is not a money figure
      "for the class that entered in $COHORT_TERM"
}

/**
 * The score-band section's own source object on the wire, beside its type for
 * the reason [putCitation] sits beside [CdsCitation]: one file owns a citation
 * and the words it puts on the wire, so no section reaches into another
 * section's file to cite itself.
 *
 * An OVERLOAD, like the test policy's: both functions put a source object on
 * the wire and the argument says which citation. One key, no bare year.
 */
fun JsonObjectBuilder.putCitation(citation: ScoreBandCitation) {
  put("cited_as", citation.citedAs)
}
