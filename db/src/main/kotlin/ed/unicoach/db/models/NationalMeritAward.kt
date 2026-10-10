package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear
import java.time.LocalDate
import java.util.UUID

/**
 * Surface id for a [NationalMeritAward] row. Beside the model rather than in its
 * own file because `college_national_merit_awards` is hand-typed reference data
 * with no id-keyed read path -- the [AutomaticAwardId] precedent, which is the
 * same shape one corpus along.
 */
@JvmInline
value class NationalMeritAwardId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}

/**
 * NMSC's first-choice mechanism at ONE school: naming that college first choice
 * with NMSC, which is an act with consequences elsewhere.
 *
 * IT IS THE DIFFERENCE BETWEEN AN AWARD AND A COMMITMENT, which is why it is a
 * value and not a flag. Maine requires nothing and is the one school in the
 * curated set that does not; Alabama wants it by May 1.
 *
 * TWO ARMS AND NOT A FLAG BESIDE A NULLABLE DATE, the [CycleWindow] shape one
 * file along: the schema admits a deadline only where first choice is actually
 * REQUIRED (`college_national_merit_awards_first_choice_trio_check`), so a
 * deadline for a rule the row denies having is a state Kotlin must not be able
 * to write down -- and it was a `require` here until this type had arms. NULL
 * FOR THE WHOLE VALUE is the real answer "the page does not say", which is
 * neither yes nor no.
 */
sealed interface FirstChoiceRule {
  /** The school's own sentence for the rule. Never empty; a requirement with no words is our claim, not theirs. */
  val asPublished: String

  /** The page REQUIRES being named first choice with NMSC. */
  data class Required(
    /** The day NMSC must have the first-choice designation by, where the school dates it. */
    val deadline: LocalDate?,
    override val asPublished: String,
  ) : FirstChoiceRule

  /** The page says first choice is NOT required, and says so in its own words. */
  data class NotRequired(
    override val asPublished: String,
  ) : FirstChoiceRule
}

/**
 * The school's own admission or scholarship deadline for the package, and its
 * own sentence for it.
 *
 * ONE VALUE for [FirstChoiceRule]'s reason: the schema pairs the date with the
 * words it was read from (`college_national_merit_awards_apply_deadline_pair_check`),
 * so a stored date with no sentence is half a claim.
 */
data class ApplyDeadline(
  val on: LocalDate,
  val asPublished: String,
)

/**
 * WHICH of the two PAYING codes a school's page states.
 *
 * A NARROWING OF [NationalMeritAwardKind] AND NOT A SECOND VOCABULARY: the
 * stored codes live in that one enum (CLAUDE.md), and this type carries no code
 * of its own -- it exists so that [NationalMeritClaim.Paying], which is only
 * ever built for a school that publishes something, cannot be handed the
 * absence code.
 */
enum class AwardGuarantee(
  /** The stored `award_kind` code, from the ONE enum for that column. */
  val awardKind: NationalMeritAwardKind,
) {
  /** The page guarantees the award to anyone holding the standing: no committee, no separate contest. */
  AUTOMATIC(NationalMeritAwardKind.AUTOMATIC),

  /** The standing is CONSIDERED and not paid. Never spoken as money the student will receive. */
  COMPETITIVE(NationalMeritAwardKind.COMPETITIVE),
}

/**
 * WHAT one school's page publishes for a National Merit standing: a package it
 * pays or considers, or a verified absence.
 *
 * THE TWO ARMS SHARE NO CELL, which is why they are arms. A paying row with no
 * standing, an absence row carrying a standing, a figure, a name or a deadline,
 * and either row borrowing the other's words are the states
 * `college_national_merit_awards_standing_iff_award_check`,
 * `..._claim_has_words_check`, `..._absence_has_words_check` and
 * `..._none_published_is_empty_check` refuse -- and they were four `require`s on
 * the read model, over nine nullables, until this type had arms. Kotlin must not
 * be able to write them down either.
 */
sealed interface NationalMeritClaim {
  /** The stored `award_kind` code for this claim. */
  val awardKind: NationalMeritAwardKind

  /** The school's own sentence for THIS claim. Never empty, and the two kinds of sentence never cross. */
  val asPublished: String

  /**
   * The school pays or CONSIDERS a package for a named standing.
   *
   * The three figures are optional and [asPublished] is not: Oklahoma publishes
   * a four-year total, Alabama a package of tuition, housing and cash, and Texas
   * Tech "100% of cost of attendance" with no number at all -- so the
   * publisher's own sentence is the price and the figures are what some of them
   * add to it.
   *
   * NOTHING DOWNSTREAM MAY SORT OR RANK ON THE FIGURES. A package priced in
   * words has no comparable number, and RFC 239's review found exactly that
   * defect shipping as a $0 ranking of a school's top award.
   */
  data class Paying(
    val guarantee: AwardGuarantee,
    /** WHICH standing the school pays. Non-null by type: a paying row always names one. */
    val standing: QualifyingStanding,
    override val asPublished: String,
    /** The school's own name for the package, where it names one. A source-defined name, stored raw (CLAUDE.md). */
    val awardName: String?,
    val perYearUsd: Int?,
    val totalUsd: Int?,
    val years: Int?,
    /** NMSC's first-choice mechanism at this school, or null where the page does not say. */
    val firstChoice: FirstChoiceRule?,
    /** The school's own application deadline for the package, with the sentence it was read from. */
    val applyDeadline: ApplyDeadline?,
  ) : NationalMeritClaim {
    override val awardKind: NationalMeritAwardKind get() = guarantee.awardKind
  }

  /**
   * We READ this school's page on `checked_on` and it publishes no National
   * Merit award at all.
   *
   * A verified absence WITH a citation, which is a different answer from "we
   * have not looked" -- and it states nothing else: there is no award for a
   * name, a figure, a deadline or a first-choice rule to describe.
   */
  data class NonePublished(
    override val asPublished: String,
  ) : NationalMeritClaim {
    override val awardKind: NationalMeritAwardKind get() = NationalMeritAwardKind.NONE_PUBLISHED
  }
}

/**
 * What ONE named school pays a National Merit Finalist, Semifinalist or
 * Commended student, for one entry year and one residency basis
 * (`college_national_merit_awards`, RFC 243, slice
 * `test/08/the-psat-is-money`).
 *
 * A SECOND TABLE AND NOT A ROW IN `college_automatic_awards`, because a
 * standing is not a score: that corpus models a price against a published score
 * range on a [BandScoreAxis] with exactly two arms, and a Finalist is neither
 * arm. The natural keys also collide -- Alabama already owns
 * `(college_id, entry_year, residency_basis)` there with its score grid.
 *
 * RESIDENCY IS PART OF THE KEY AND NOT A DISPLAY DETAIL. Auburn is why: it cut
 * a $44,000 award to Alabama residents only, and a row with no residency in its
 * key would still be telling a family in Oregon it was theirs.
 *
 * HAND-TYPED FROM THE SCHOOL'S OWN PAGE (brief 0010 D19), ROUTE B: keyed by
 * `colleges(id)`, which the IMPORT fills, so it loads with `bin/ingest-colleges`
 * and not from a migration. Every row carries its own citation
 * ([sourceUrl]/[archiveUrl], [checkedOn]) and its own [expiresOn] rather than
 * the table carrying one.
 *
 * EXPIRY IS NOT DECIDED HERE, exactly as [AutomaticAward] states: the service
 * owns the runtime refusal past [expiresOn] and the committed-corpus guard
 * fails the build inside [HandTypedReferenceData.REFRESH_LEAD] of it. This
 * layer returns rows and their dates, so a caller that wants today's answer
 * says so.
 */
data class NationalMeritAward(
  override val id: NationalMeritAwardId,
  val collegeId: CollegeId,
  /** The ENTRY year under the `academic_year` domain: 2027 IS Fall 2027 entry, the 2027-28 year. */
  val entryYear: AcademicYear,
  /** Whose award this is. `NOT_APPLICABLE` is the real member for a package that does not vary by residency. */
  val residencyBasis: ResidencyBasis,
  /** What the school's page publishes: a package, or a verified absence. */
  val claim: NationalMeritClaim,
  /** Who published it, as a family would recognise them on the page. */
  val publisher: String,
  /** The school's own page, read through the `external_documents` join. A row cites something or it does not exist. */
  val sourceUrl: String,
  /** The archived address the row was read through, where the school's page opens for no client we have. */
  val archiveUrl: String?,
  val checkedOn: LocalDate,
  val expiresOn: LocalDate,
) : Identifiable<NationalMeritAwardId> {
  init {
    // The day we stop believing the page, never a deadline the row states.
    require(expiresOn.isAfter(checkedOn)) {
      "row [${id.value}] (entry year [${entryYear.label}]): expires [$expiresOn] on or before the day it was " +
        "read [$checkedOn] (college_national_merit_awards_expiry_after_checked_check)"
    }
  }
}

/**
 * An award row to write. Separate from [NationalMeritAward] because the id is
 * the database's (`uuidv7()`), exactly as every other `New*` model in this
 * package works, and because the urls ride as STRINGS here -- the DAO resolves
 * them through [ed.unicoach.db.dao.ExternalDocumentsDao.citationIds], never its
 * upsert, which would overwrite a swept link status with "we never asked"
 * (RFC 182).
 *
 * IT CARRIES NO `require`, BY DESIGN and for the reason [NewAutomaticAwardBand]
 * states: this is what a hand-typed CSV row arrives as, and the ingest loader's
 * job is to REFUSE the contradiction with a located defect naming the file, the
 * row and the cell. It can only do that if it can hold the bad row long enough
 * to name it -- a loader whose input type could not express it would report a
 * curator's crossed columns as a parse failure with no cell in it. The read
 * model, which nothing hand-types, carries the refusals.
 */
data class NewNationalMeritAward(
  val collegeId: CollegeId,
  val entryYear: AcademicYear,
  val residencyBasis: ResidencyBasis,
  val awardKind: NationalMeritAwardKind,
  val qualifyingStanding: QualifyingStanding?,
  val awardName: String?,
  val awardPerYearUsd: Int?,
  val awardTotalUsd: Int?,
  val awardYears: Int?,
  val awardAsPublished: String?,
  val absenceAsPublished: String?,
  val firstChoiceRequired: Boolean?,
  val firstChoiceDeadline: LocalDate?,
  val firstChoiceAsPublished: String?,
  val applyDeadline: LocalDate?,
  val applyDeadlineAsPublished: String?,
  val publisher: String,
  val sourceUrl: String,
  val archiveUrl: String?,
  val checkedOn: LocalDate,
  val expiresOn: LocalDate,
)
