package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Surface id for a [AutomaticAward] row. Beside the model rather than in its own
 * file because `college_automatic_awards` is hand-typed reference data with no
 * id-keyed read path -- the [FafsaFilingDeadlineId] precedent, which is the same
 * shape one corpus along.
 */
@JvmInline
value class AutomaticAwardId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}

/** Surface id for a [AutomaticAwardBand] row; see [AutomaticAwardId]. */
@JvmInline
value class AutomaticAwardBandId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}

/**
 * What a school publishes about its automatic awards: our OWN enumeration, so
 * the column is `TEXT` + `CHECK IN (...)` and this is the one Kotlin home for it
 * (CLAUDE.md, the [AidDeadlineKind] precedent).
 *
 * IT IS NOT A BOOLEAN, and [GPA_ONLY_GRID] is why. Schools we have read publish
 * an automatic table in which a test score changes NOTHING; "we do not know"
 * would be false for them, and "a score is money here" would be worse.
 *
 * NO COUNT IS RESTATED HERE. This sentence used to say "nine of the schools
 * read", and `create-college-automatic-awards.sql` said it too; the corpus --
 * which is the home of its own size -- holds eight `gpa_only_grid` rows over
 * five schools, so the copy was wrong in stereo. The argument stands on such
 * schools EXISTING, and how many there are this cycle is a fact
 * `db/seed/automatic-awards/awards.csv` answers and `AutomaticAwardSeedTest`
 * asserts.
 */
enum class AutomaticAwardKind(
  val value: String,
) {
  /** The school publishes bands and a score changes the money. */
  SCORE_GRID("score_grid"),

  /** The school publishes an automatic table and a score changes nothing in it. */
  GPA_ONLY_GRID("gpa_only_grid"),

  /**
   * We READ this school's page on `checked_on` and it publishes no automatic
   * award at all. A verified absence with a citation, not our silence -- the
   * argument `fafsa_filing_deadlines` makes for `deadline_kind='none'`.
   */
  NONE_PUBLISHED("none_published"),
  ;

  companion object {
    /** The stored code back to the member, or null -- a miss is row corruption for the caller to locate, never a guess. */
    fun fromValue(value: String): AutomaticAwardKind? = entries.find { it.value == value }
  }
}

/**
 * The SCHOLARSHIP superscore rule (brief 0010 D14.2) -- never the admissions
 * one. The name says `Scholarship` because the two are different facts about one
 * school and must never be one type: Alabama superscores for admission and reads
 * a single sitting for its scholarship grid, and a coach that conflated them
 * would name money the school will not pay.
 *
 * Only [SUPERSCORE_ACCEPTED] permits `AutomaticAwardService` to compute a
 * combined score against the grid (RFC 239 D-H, guardrail 1).
 */
enum class ScholarshipSuperscoreRule(
  val value: String,
) {
  /** The school's page says the scholarship grid reads ONE sitting. */
  SINGLE_SITTING_ONLY("single_sitting_only"),

  /** The school's page says it accepts a combined score for scholarship purposes. */
  SUPERSCORE_ACCEPTED("superscore_accepted"),

  /** The page did not say. No combination is computed, and no rule is quoted. */
  UNSTATED("unstated"),
  ;

  companion object {
    fun fromValue(value: String): ScholarshipSuperscoreRule? = entries.find { it.value == value }
  }
}

/**
 * Whether a score must be SUBMITTED to qualify for any automatic award at this
 * school. The one fact that makes test-optional-for-admission and
 * test-optional-for-money different questions, and it is NOT derivable from
 * [AutomaticAwardKind]: a `gpa_only_grid` school can still demand a score from a
 * home-schooled applicant.
 *
 * THREE STATES AND NOT A BOOLEAN, measured: of the curated score-grid readings
 * all but one quote the school on this and one does not. A boolean would have to
 * answer for that one, and both answers would be a claim the page never made.
 * [UNSTATED] is the third honest state and it is the one the coach refuses on.
 */
enum class ScoreRequirement(
  val value: String,
) {
  REQUIRED("required"),
  NOT_REQUIRED("not_required"),
  UNSTATED("unstated"),
  ;

  companion object {
    fun fromValue(value: String): ScoreRequirement? = entries.find { it.value == value }
  }
}

/**
 * The latest sitting a school will CONSIDER for its automatic award, as ONE
 * value -- a period it can be read through, or words we will not parse.
 *
 * THIS IS NOT THE RECEIPT DEADLINE, and the difference is the slice's whole
 * point. UT Knoxville receives scores "by May 15" but considers "only scores
 * through December 2026", so the February and April sittings cannot raise its
 * award however early the report arrives.
 *
 * The [InWords] arm is a legal and meaningful state, not a degraded one: UAB
 * publishes a cut-off that differs BY TEST -- "February ACT or March SAT scores
 * ... will be the final scores considered" -- which one stored period cannot
 * hold without picking one test's date for both. Such a row carries the school's
 * words and no period, no sitting is excluded by it, and the coach quotes the
 * school instead of computing. The [PublishedDeadline] type one corpus along is
 * the shape this follows, and for the same reason: a nullable period beside a
 * nullable quote would let Kotlin hold a state the schema rejects.
 */
sealed interface LastSittingConsidered {
  /** The school's own sentence for the cut-off. On every arm; never empty. */
  val asPublished: String

  /**
   * The school names a period it reads sittings through. Stored at RFC 209
   * precision because the published bound is usually a MONTH; the service takes
   * the period END, because "through December 2026" admits every December
   * sitting.
   */
  data class Through(
    val through: PartialDate,
    override val asPublished: String,
  ) : LastSittingConsidered

  /** The school published a cut-off we will not parse. No sitting is excluded; the words are the answer. */
  data class InWords(
    override val asPublished: String,
  ) : LastSittingConsidered
}

/**
 * Which cell of the SCORE axis one band sits in.
 *
 * [NoScore] IS A MEMBER AND NOT AN ABSENCE: it is the school's published
 * no-score row -- the tier a test-optional applicant lands in -- and it is what
 * makes "a score is worth $5,000 more than no score" sayable without inventing
 * a zero band. A reader that had to infer that row from a null test would be
 * re-deriving the most meaningful cell in the grid from a missing value.
 *
 * ONE VALUE AND NOT THREE NULLABLES. `test`, `score_low` and `score_high` are
 * three columns that must agree, and the schema says so
 * (`college_automatic_award_bands_score_iff_test_check`). Held as three
 * nullables in Kotlin, the half-stated states -- a score on no scale, a tier
 * with no entry condition -- compile, and every reader pays for it: once in
 * `!!`, once in triple null-guards. Here they cannot be written down at all,
 * which is why [AutomaticAwardBand] no longer carries a score-iff-test
 * `require`: there is no state left for it to refuse.
 */
sealed interface BandScoreAxis {
  /**
   * The published NO-SCORE row: this cell names no test and no range.
   *
   * Legal and meaningful, not degraded. Every school in the corpus that pays on
   * GPA alone publishes its whole grid on this row, and a test-optional
   * applicant at a score grid lands here.
   */
  data object NoScore : BandScoreAxis

  /** A range on the test's own published scale, inclusive both ends. */
  data class Range(
    val test: AdmissionTest,
    val low: Int,
    val high: Int,
  ) : BandScoreAxis {
    init {
      // Mirrored from the schema for `NewFafsaFilingDeadline`'s reason: leaving
      // a stated invariant only to the database enforces it in the weaker
      // place, and a band built in a test fixture never reaches a CHECK until
      // it is written. Unlocated by necessity -- the axis does not know its own
      // row -- so `AutomaticAwardsDao.mapBand` checks the ordering itself and
      // refuses a stored row with its location, and this is the backstop for
      // everything the mapper did not build.
      require(low <= high) {
        "band on [${test.value}] has low [$low] above high [$high] " +
          "(college_automatic_award_bands_score_ordered_check): a STORED row names itself through " +
          "AutomaticAwardsDao.mapBand, which checks this ordering first, so an axis refused here was built by a " +
          "fixture or by a caller that holds the row and can say which one it is"
      }
    }
  }
}

/**
 * One published cell of a school's automatic award grid
 * (`college_automatic_award_bands`, RFC 239).
 *
 * A two-dimensional score x GPA matrix is one row per CELL, which is what makes
 * Oklahoma State's and Northern State's grids expressible; a row-shaped table
 * with one GPA floor per band could not hold them.
 */
data class AutomaticAwardBand(
  override val id: AutomaticAwardBandId,
  val awardId: AutomaticAwardId,
  /**
   * The school's own name for this award -- "Crimson Legends", "Presidential".
   * A source-defined name, stored raw (CLAUDE.md), and part of the natural key
   * because one school publishes several named awards with overlapping bands.
   */
  val awardName: String,
  /**
   * Where this cell sits on the SCORE axis: a range on one test's published
   * scale, or the published NO-SCORE row ([BandScoreAxis]).
   */
  val score: BandScoreAxis,
  /** The GPA cell. Either bound may be absent: a school may publish "3.50 and above", or no GPA axis at all. */
  val gpaLow: BigDecimal?,
  val gpaHigh: BigDecimal?,
  /** Whole dollars per year (0059: the unit is in the name). Null where the school publishes a KIND of award. */
  val awardPerYearUsd: Int?,
  /**
   * The school's own words where the award is not a plain per-year figure --
   * "tuition and fees", "tuition plus housing". These are the TOP bands, so
   * dropping them would make the coach name a school's second-best award as its
   * best.
   */
  val awardAsPublished: String?,
) : Identifiable<AutomaticAwardBandId> {
  init {
    // LOCATED, like the mapper beside them. `AutomaticAwardsDao.mapAward` and
    // `mapBand` reach these on the READ path and surface every other corruption
    // through `corruptValue(... location ...)`; these named neither the row nor
    // the year, so the one failure `create-college-automatic-awards.sql`
    // explicitly warns about -- a CHECK weaker than the model turning a legal
    // row into an IllegalArgumentException out of a data-class constructor --
    // arrived with nothing to look the row up by.
    //
    // The schema says this in SQL (`..._award_has_value_check`). Mirrored here
    // for NewFafsaFilingDeadline's reason: leaving a stated invariant only to
    // the database enforces it in the weaker place, and a band built in a test
    // fixture never reaches a CHECK until it is written.
    //
    // ONE `require` AND NOT TWO: the score-iff-test rule used to be checked
    // here over three nullables and is now carried by [BandScoreAxis], where
    // the states it refused cannot be written down. A rule the type holds is
    // not a rule this constructor may restate -- a second statement of it is a
    // second thing to keep true.
    require(awardPerYearUsd != null || awardAsPublished != null) {
      "row [${id.value}] under award [${awardId.value}]: band [$awardName] states neither an amount nor the " +
        "school's words for what it pays (college_automatic_award_bands_award_has_value_check): a named band with " +
        "no value is a heading over nothing"
    }
  }
}

/**
 * What ONE school publishes about its AUTOMATIC merit awards for one entry year
 * and one residency basis (`college_automatic_awards`, RFC 239, slice
 * `test/07/a-score-is-money`), with the cells of its grid.
 *
 * At a number of public flagships a test score band IS a price, and this row is
 * what lets the coach say so about a named school on the student's list -- and
 * refuse, distinguishably, everywhere else.
 *
 * RESIDENCY IS PART OF THE KEY AND NOT A DISPLAY DETAIL. Iowa State and Kansas
 * State gate an in-state award on a score and pay the out-of-state award on GPA
 * alone; UAB's last usable sitting is December for a resident and the February
 * ACT for a non-resident.
 *
 * HAND-TYPED FROM THE SCHOOL'S OWN PAGE (brief 0010 D19), which is why every row
 * carries its own citation ([sourceUrl]/[archiveUrl], [checkedOn]) and its own
 * [expiresOn] rather than the table carrying one. EXPIRY IS NOT DECIDED HERE:
 * `AutomaticAwardService` owns the runtime refusal past [expiresOn], and
 * `AutomaticAwardSeedTest` fails the build when a committed row is within
 * [HandTypedReferenceData.REFRESH_LEAD] of it. This layer returns rows and their
 * dates, so a caller that wants today's answer says so.
 *
 * NO `FigureStatus` and no assurance tier, for RFC 197's reason: that vocabulary
 * belongs to the canonical MONEY tables, whose rows record a publisher's
 * suppression and imputation flags. A published scholarship grid is on the page
 * with the page it was read from, or it is not here at all.
 */
data class AutomaticAward(
  override val id: AutomaticAwardId,
  val collegeId: CollegeId,
  /** The ENTRY year under the `academic_year` domain: 2027 IS Fall 2027 entry, the 2027-28 year. */
  val entryYear: AcademicYear,
  /** Whose grid this is. `NOT_APPLICABLE` is the real member for a grid that does not vary by residency. */
  val residencyBasis: ResidencyBasis,
  val awardKind: AutomaticAwardKind,
  val scoreRequirement: ScoreRequirement,
  /** The submission requirement in the school's own words. Null exactly when [scoreRequirement] is unstated. */
  val scoreRequirementAsPublished: String?,
  val superscoreRule: ScholarshipSuperscoreRule,
  /** The scholarship superscore rule in the school's own words. Null exactly when [superscoreRule] is unstated. */
  val superscoreAsPublished: String?,
  /** The day official scores must be WITH the school for the award, where the school publishes one. */
  val scoreDeadline: LocalDate?,
  /** The school's own sentence for [scoreDeadline]. Never absent when the date is present. */
  val scoreDeadlineAsPublished: String?,
  /** The latest sitting the school will consider at all -- a different fact from [scoreDeadline]. */
  val lastSittingConsidered: LastSittingConsidered?,
  /** Who published it, as a family would recognise them on the page. */
  val publisher: String,
  /** The school's own page, read through the `external_documents` join. A row cites something or it does not exist. */
  val sourceUrl: String,
  /** The archived address the row was read through, where the school's page opens for no client we have. */
  val archiveUrl: String?,
  val checkedOn: LocalDate,
  val expiresOn: LocalDate,
  /**
   * The published cells of this grid, joined in Kotlin by
   * [ed.unicoach.db.dao.AutomaticAwardsDao] rather than by a row-multiplying SQL
   * join.
   *
   * EMPTY IS THE ONLY LEGAL VALUE FOR A [AutomaticAwardKind.NONE_PUBLISHED] row,
   * and the DAO refuses the other way round with a named error rather than
   * silently rendering an empty grid. A cross-table CHECK needs a trigger in
   * PostgreSQL, and a trigger on a corpus written only by the loader buys less
   * than the loader's refusal plus this read-side assertion (D28).
   */
  val bands: List<AutomaticAwardBand>,
) : Identifiable<AutomaticAwardId> {
  init {
    // NO PARSED CLAIM WITHOUT THE PUBLISHER'S WORDS -- the three CHECKs of the
    // same name, mirrored here for the reason NewFafsaFilingDeadline states.
    // This is what stops a row asserting a rule worth four figures on our
    // authority, in the layer a renderer actually reads.
    require((scoreRequirement == ScoreRequirement.UNSTATED) == (scoreRequirementAsPublished == null)) {
      "row [${id.value}] (entry year [${entryYear.firstCalendarYear}]): a stated score requirement carries the " +
        "school's words and an unstated one carries none " +
        "(college_automatic_awards_requirement_has_words_check): [$scoreRequirement] / [$scoreRequirementAsPublished]"
    }
    require((superscoreRule == ScholarshipSuperscoreRule.UNSTATED) == (superscoreAsPublished == null)) {
      "row [${id.value}] (entry year [${entryYear.firstCalendarYear}]): a stated scholarship superscore rule " +
        "carries the school's words and an unstated one carries none " +
        "(college_automatic_awards_superscore_has_words_check): [$superscoreRule] / [$superscoreAsPublished]"
    }
    require(scoreDeadline == null || scoreDeadlineAsPublished != null) {
      "row [${id.value}] (entry year [${entryYear.firstCalendarYear}]): a stored score deadline carries the " +
        "sentence it was read from (college_automatic_awards_deadline_has_words_check): [$scoreDeadline]"
    }
    require(awardKind != AutomaticAwardKind.NONE_PUBLISHED || scoreDeadline == null) {
      "row [${id.value}] (entry year [${entryYear.firstCalendarYear}]): a school that publishes no automatic " +
        "award cannot publish a deadline for one (college_automatic_awards_none_has_no_deadline_check)"
    }
  }

  /**
   * The last DAY this school will read a sitting through, or null where it
   * published no period -- either because it published nothing at all or because
   * it published words we will not parse.
   *
   * THE PERIOD'S END AND NOT ITS START, because "through December 2026" admits
   * every December sitting and the column stores 2026-12-01. Derived at read
   * time and never stored (CLAUDE.md).
   *
   * THROUGH THE ONE CODEC ([PartialDateColumns.periodEnd]), which is where the
   * period's START already lives. This used to be a second `when` over
   * [PartialDate] on this model -- the end half of a codec whose own KDoc says
   * "one home, so the next table to store a partial date reuses this instead of
   * re-deriving it". The award-specific part, and all that is left here, is
   * WHICH arm has a period at all.
   */
  val lastSittingThroughDay: LocalDate?
    get() = (lastSittingConsidered as? LastSittingConsidered.Through)?.let { PartialDateColumns.periodEnd(it.through) }
}

/**
 * An award row to write, with its bands. Separate from [AutomaticAward] because
 * the ids are the database's (`uuidv7()`), exactly as every other `New*` model in
 * this package works.
 *
 * The school is named by IPEDS unit id and by the name as typed, and the LOADER
 * -- not this type -- refuses a unit id absent from `colleges` and a name that
 * disagrees with `colleges.name`. That guard is not decoration: a fuzzy match of
 * these 48 names returned 8 WRONG schools, and a mis-keyed row here is another
 * school's money under this school's name.
 *
 * The urls ride as STRINGS, and the DAO resolves them through
 * [ed.unicoach.db.dao.ExternalDocumentsDao.citationIds] -- never its upsert,
 * which would overwrite a swept link status with "we never asked" (RFC 182).
 */
data class NewAutomaticAward(
  val collegeId: CollegeId,
  val entryYear: AcademicYear,
  val residencyBasis: ResidencyBasis,
  val awardKind: AutomaticAwardKind,
  val scoreRequirement: ScoreRequirement,
  val scoreRequirementAsPublished: String?,
  val superscoreRule: ScholarshipSuperscoreRule,
  val superscoreAsPublished: String?,
  val scoreDeadline: LocalDate?,
  val scoreDeadlineAsPublished: String?,
  val lastSittingConsidered: LastSittingConsidered?,
  val publisher: String,
  val sourceUrl: String,
  val archiveUrl: String?,
  val checkedOn: LocalDate,
  val expiresOn: LocalDate,
)

/**
 * A band to write, under an award resolved by its natural key. See
 * [NewAutomaticAward].
 *
 * THE SCORE AXIS IS THREE NULLABLES HERE AND ONE [BandScoreAxis] ON THE READ
 * MODEL, deliberately. This type is what a hand-typed CSV row arrives as, and
 * `AutomaticAwardSeedLoader` REFUSES a half-stated score axis at the door with
 * a located defect naming the file, the row and the cell. It can only do that
 * if it can hold the contradiction long enough to name it: a loader whose input
 * type could not express the bad row would report it as a parse failure with no
 * cell in it. The read model, which nothing hand-types, carries the closed value
 * instead.
 */
data class NewAutomaticAwardBand(
  val awardName: String,
  val test: AdmissionTest?,
  val scoreLow: Int?,
  val scoreHigh: Int?,
  val gpaLow: BigDecimal?,
  val gpaHigh: BigDecimal?,
  val awardPerYearUsd: Int?,
  val awardAsPublished: String?,
)
