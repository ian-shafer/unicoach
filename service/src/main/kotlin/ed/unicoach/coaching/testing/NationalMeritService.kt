package ed.unicoach.coaching.testing

import ed.unicoach.coaching.HighSchoolYear
import ed.unicoach.coaching.Residency
import ed.unicoach.coaching.StudentCollegeSelection
import ed.unicoach.coaching.coachToday
import ed.unicoach.coaching.graduationDateOf
import ed.unicoach.common.util.Share
import ed.unicoach.db.Database
import ed.unicoach.db.dao.NationalMeritAwardsDao
import ed.unicoach.db.dao.NationalMeritCyclesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AlternateEntry
import ed.unicoach.db.models.AwardGuarantee
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CycleWindow
import ed.unicoach.db.models.NationalMeritAward
import ed.unicoach.db.models.NationalMeritClaim
import ed.unicoach.db.models.NationalMeritCycle
import ed.unicoach.db.models.NationalMeritFunnel
import ed.unicoach.db.models.QualifyingStanding
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.CancellationException
import java.time.InstantSource
import java.time.LocalDate

/**
 * Where this student stands in her OWN National Merit cycle, on this day.
 *
 * SEVEN ARMS, DECIDED FROM [HighSchoolYear.of] AND THE CYCLE ROW AND NEVER BY
 * THE MODEL: the sitting happens once in a student's life, in one October, and a
 * coach that computed the arm from prose would be doing calendar arithmetic
 * about the one deadline in this brief that cannot be retaken.
 *
 * [NotCurated] IS NOT [CycleClosed]. "We hold no dates for your year" and "your
 * cycle is over" send a family to two different places, and the first one names
 * no date at all -- which is the whole reason the corpus covers the years its
 * publishers have described and no further.
 */
sealed interface CycleTiming {
  /**
   * Before the qualifying year, or a junior before the window opens -- the arm
   * that names the window ahead and what it is worth.
   *
   * IT IS ALSO THE UNDATED CYCLE'S ONLY ANSWER for a student in the qualifying
   * year. [CycleWindow.Described] is the normal shape two years out (measured:
   * no publisher has dated any cycle after 2028), and `window_open` and
   * `alternate_entry_open` are both a comparison with today -- so a cycle with
   * no dates can only be named, in its publisher's own words.
   */
  data class WindowAhead(
    val cycle: NationalMeritCycle,
  ) : CycleTiming

  /**
   * Today is inside the published window: it is happening now, and her school
   * decides how it runs it.
   *
   * THE WINDOW RIDES DATED, by type, for [AlternateEntryOpen]'s own reason:
   * this arm is reachable only through a comparison with today, so the closing
   * date it exists to state cannot be absent from it -- and a renderer that
   * re-derived it by cast could lose it to a fallback.
   */
  data class WindowOpen(
    val cycle: NationalMeritCycle,
    /** The published window today falls inside. Non-null and dated, for this arm's own reason. */
    val window: CycleWindow.Dated,
  ) : CycleTiming

  /**
   * The window has closed and the alternate-entry letter is still in time
   * (brief 0010 correction 1).
   *
   * THE ROUTE RIDES NON-NULL, by type: this arm is reachable only through a
   * dated deadline, so "miss it and it is gone forever" -- the sentence the
   * brief corrected in writing before this slice was designed -- cannot be
   * spoken from a payload that reached this arm with nothing to offer.
   */
  data class AlternateEntryOpen(
    val cycle: NationalMeritCycle,
    val alternateEntry: AlternateEntry,
    /** The day the letter must be received or postmarked by. Non-null, for this arm's own reason. */
    val deadline: LocalDate,
  ) : CycleTiming

  /**
   * The window has closed and the publisher DESCRIBES a letter route it has NOT
   * dated (brief 0010 correction 1).
   *
   * IT IS NOT [CycleClosed], and that is the whole arm. A route we cannot date
   * is one whose deadline is on the sponsor's own page, never one we may call
   * shut -- and calling it shut would make "miss it and it is gone forever"
   * true again for exactly the cycle the correction was written about. The
   * route rides NON-NULL, by type, so the arm cannot be reached with nothing to
   * name.
   */
  data class AlternateEntryUndated(
    val cycle: NationalMeritCycle,
    val alternateEntry: AlternateEntry,
  ) : CycleTiming

  /** Past the alternate-entry deadline, or past the qualifying year with no route left. Said plainly, and no closed route is offered. */
  data class CycleClosed(
    val cycle: NationalMeritCycle,
  ) : CycleTiming

  /**
   * We hold no cycle row for her graduation year. It names NO date: a corpus
   * that covers the cycles their publishers have described cannot cover a year
   * nobody has described yet.
   */
  data class NotCurated(
    /** Her own stored class year, so the payload can say WHICH year we hold nothing for. */
    val graduationYear: Int,
  ) : CycleTiming

  /**
   * We hold her cycle row and we no longer BELIEVE it: today is past its
   * `expires_on`, the day a person's reading of the sponsor's and
   * administrator's pages stops being the answer.
   *
   * IT NAMES NO DATE, which is the whole arm. The window, the letter route and
   * the alternate-entry deadline on a dead row are a stale reading of two
   * pages, and a window spoken from one is a date a family would act on -- so
   * this arm carries the row for its citation and the renderer states no cell
   * of it. The build guard fails ninety days out
   * ([ed.unicoach.db.models.HandTypedReferenceData.REFRESH_LEAD]), and this is
   * what a DEPLOYED read does if one ever gets past it.
   *
   * IT IS NOT [NotCurated], for [NationalMeritSchoolAnswer.Expired]'s reason
   * one layer along: "we read this and our reading is stale" and "we have never
   * held a row for your year" send a family to two different places, and only
   * the first one has a page to go back to.
   */
  data class CycleExpired(
    val cycle: NationalMeritCycle,
    /** The day we stopped believing the reading. Non-null: this arm is a comparison with it. */
    val expiredOn: LocalDate,
  ) : CycleTiming
}

/**
 * The published base rate, and the competition year it BELONGS to (brief 0010
 * correction 4).
 *
 * THE YEAR IS PART OF THE VALUE, and that is the whole contract. NMSC publishes
 * a funnel only for the competition in progress -- measured, not assumed: the
 * 2029 row holds none -- so a sophomore hears the 2028 competition's figures
 * SAID AS the 2028 competition's figures. Carrying a funnel silently into a year
 * that did not publish it would be the fabrication this corpus exists to
 * prevent, and a figure with no year beside it is exactly what a reader carries
 * into the wrong one.
 *
 * [finalistShareOfEntrants] is DERIVED at read time and never stored
 * (CLAUDE.md); it is read off the funnel rather than recomputed here, so this
 * type adds a year to the base rate and no arithmetic of its own. The ONE thing
 * it does to the figure is hand it to [Share], which owns the ratio -> percent
 * conversion and the rounding rule for every share this repo speaks.
 */
data class PublishedBaseRate(
  val competitionYear: Int,
  val funnel: NationalMeritFunnel,
  /**
   * Who published the funnel: the chosen cycle's SPONSOR, which publishes
   * everything on the row but the window dates and the scale.
   *
   * The chat ignores it, because the coach names the competition year and the
   * funnel rides inside its own cycle object. The family cost report cannot: it
   * says the base rate once for the page, in a block that names no publisher of
   * its own, so the sentence carries its own citation or it carries none
   * (RFC 247 D3).
   */
  val publisher: String,
  /** The day a person read the sponsor's page for this cycle -- the read date the report's sentence cites. */
  val checkedOn: LocalDate,
) {
  /**
   * The share of entrants who reach Finalist, as [NationalMeritFunnel] derives
   * it, on the one scale the repo speaks shares in. Null where the funnel
   * publishes neither stage.
   */
  val finalistShareOfEntrants: Share? get() = funnel.finalistShareOfEntrants?.let(Share::ofRatio)

  companion object {
    /**
     * The most recent cycle we hold that publishes a base rate, or null where no
     * cycle does.
     *
     * THE MOST RECENT AND NOT HERS. A cycle two years out publishes no counts at
     * all, and a student in it still needs the ~1% said in the same breath as
     * six figures; naming the year it came from is honest and still answers the
     * question.
     *
     * "PUBLISHES ONE" IS THE SHARE ITSELF, not a non-empty funnel: a cycle with
     * some stages and no Finalist count cannot answer the question this value
     * exists to answer, and picking it would leave the base rate null while a
     * complete funnel sat one row below.
     *
     * AND IT MUST STILL BE BELIEVED TODAY. A funnel read off a page we stopped
     * believing is the counterweight to nothing: it is quoted beside live money
     * as a published figure, so the expiry that silences a cycle's dates
     * ([CycleTiming.CycleExpired]) silences its counts too.
     */
    fun of(
      cycles: Collection<NationalMeritCycle>,
      today: LocalDate,
    ): PublishedBaseRate? =
      cycles
        .filter { today <= it.expiresOn && it.funnel.finalistShareOfEntrants != null }
        .maxByOrNull { it.competitionYear }
        ?.let {
          PublishedBaseRate(
            competitionYear = it.competitionYear,
            funnel = it.funnel,
            publisher = it.sponsor.publisher,
            checkedOn = it.checkedOn,
          )
        }
  }
}

/**
 * What one school pays, AND THE BASE RATE IT MUST BE SAID WITH -- one value, not
 * two fields a renderer is trusted to emit together.
 *
 * THIS TYPE IS THE BASE-RATE CONTRACT. [Priced] is the only place in this read
 * where a dollar figure or a publisher's price in words exists, and it cannot be
 * constructed without a [PublishedBaseRate]: a payload carrying money and no
 * base rate is not a bug to test for, it is a value nothing can build. Every
 * six-figure package in this corpus is a number the family will almost certainly
 * not get, and a coach that says the first without the second is selling a
 * lottery ticket.
 *
 * [Withheld] IS THE HONEST ANSWER WHEN NO CYCLE PUBLISHES A FUNNEL, and it
 * withholds the publisher's WORDS as well as the figures, because the words ARE
 * the price -- half the paying schools in this corpus publish no number at all
 * ("full cost of tuition and required fees, for four years") and one of them
 * prints "Up to $154,500" in a sentence. Withholding the integers and quoting
 * the sentence would be the same lottery ticket with a different type on it.
 */
sealed interface PublishedPackage {
  /** The school's price, as the school expresses it, with the base rate beside it. */
  data class Priced(
    val baseRate: PublishedBaseRate,
    /** Whole dollars per year, where the school prices it that way (0059: the unit is in the name). */
    val perYearUsd: Int?,
    /** The multi-year total, where the school publishes one instead. */
    val totalUsd: Int?,
    /** How many years the total covers, where the school says. */
    val years: Int?,
    /** The school's own sentence for what it pays. Never empty, and never absent from a paying row. */
    val asPublished: String,
  ) : PublishedPackage

  /** No cycle we hold publishes a base rate, so this school's price is not spoken at all. */
  data object Withheld : PublishedPackage

  companion object {
    /**
     * THE ONE WAY A PRICE IS BUILT: with a base rate, or not at all.
     *
     * It takes the CLAIM and not the row, so the publisher's own sentence
     * arrives non-null: [NationalMeritClaim.Paying] is the only thing a price
     * can be built from, and a paying row with no words is a state that type
     * cannot hold.
     */
    fun of(
      claim: NationalMeritClaim.Paying,
      baseRate: PublishedBaseRate?,
    ): PublishedPackage =
      if (baseRate == null) {
        Withheld
      } else {
        Priced(
          baseRate = baseRate,
          perYearUsd = claim.perYearUsd,
          totalUsd = claim.totalUsd,
          years = claim.years,
          asPublished = claim.asPublished,
        )
      }
  }
}

/**
 * Whether one stored row's residency basis agrees with the state we hold for
 * this family -- a STATED COMPARISON and never a determination.
 *
 * AUBURN IS WHY THIS EXISTS: it cut a $44,000 award to Alabama residents only,
 * and a payload that handed that row to a family in Oregon without saying whose
 * it is would be naming money no school will pay them. The row still rides --
 * "this award exists and is not yours" is a different answer from "this school
 * publishes nothing", which is precisely what
 * [NationalMeritAwardsDao.listByColleges] declines to collapse.
 *
 * IT PICKS NOTHING, on [AutomaticAwardService]'s standing ruling: residency for
 * tuition purposes is a legal status the school determines, not something
 * derivable from a state on a money profile. So every row is served, each
 * labelled, and this value says only what the two facts we hold are -- the
 * school's own state and the state the family told us.
 */
enum class ResidencyScope(
  /** The code the wire carries. */
  val value: String,
) {
  /** The basis agrees with the state on file: an in-state row at a school in that state, or an out-of-state row anywhere else. */
  MATCHES_STATE_ON_FILE("matches_state_on_file"),

  /** The basis disagrees with the state on file. The row is served, and it is not hers. */
  DIFFERS_FROM_STATE_ON_FILE("differs_from_state_on_file"),

  /** We hold no state for this family, so there is no comparison to state -- never "the row is not hers". */
  NO_STATE_ON_FILE("no_state_on_file"),

  /**
   * A state we could not READ, or one stored in a shape we could not use.
   *
   * NOT AN ABSENCE, and that is the whole member: we do not know what this
   * family told us, so nothing said from this row may claim we hold no state.
   */
  STATE_UNAVAILABLE("state_unavailable"),

  /** The school publishes one package and it does not vary by residency. A real answer, not an absence. */
  NOT_RESIDENCY_SCOPED("not_residency_scoped"),
  ;

  companion object {
    /**
     * The comparison for one row: its basis, the school's own state, and what we
     * know about where this family lives.
     *
     * IT TAKES THE WHOLE ROW so a refusal can LOCATE the corrupt one: an
     * operator handed a basis code and no id is sent to a corpus of thousands.
     * The `when` subject is still the basis, so the exhaustiveness the closed
     * vocabulary buys is unchanged.
     *
     * AND IT TAKES THE TYPED [Residency], not a nullable code, for the reason
     * `fafsa_deadlines` takes it: "nobody has told us" and "we could not read
     * it" are different facts, and a read that FAULTED served as the first one
     * is a sentence about this family that we do not know to be true --
     * residency is what decides whose money an in-state award is.
     *
     * [ResidencyBasis.IN_DISTRICT] and [ResidencyBasis.UNKNOWN] THROW, exactly as
     * [AutomaticAwardChatTool.residencyLabelOf] does one corpus along:
     * `college_national_merit_awards_residency_basis_check` admits three members,
     * so a fourth is a corrupt row -- and the one thing not to do with a corrupt
     * row is put a sentence about a family's legal residency on the wire. Naming
     * the two arms is still not an `else`: a sixth member of the shared
     * vocabulary cannot compile until this function says what it is.
     */
    fun of(
      award: NationalMeritAward,
      collegeState: String,
      residency: Residency,
    ): ResidencyScope =
      when (award.residencyBasis) {
        ResidencyBasis.NOT_APPLICABLE -> {
          NOT_RESIDENCY_SCOPED
        }

        ResidencyBasis.IN_STATE -> {
          scopeOf(residency, collegeState, audienceLivesInCollegeState = true)
        }

        ResidencyBasis.OUT_OF_STATE -> {
          scopeOf(residency, collegeState, audienceLivesInCollegeState = false)
        }

        ResidencyBasis.IN_DISTRICT, ResidencyBasis.UNKNOWN -> {
          throw CorruptAwardResidencyBasisException(award)
        }
      }

    /**
     * ONE BASIS AGAINST THE RESIDENCY WE RESOLVED.
     *
     * [audienceLivesInCollegeState] is whether living IN the school's own state
     * is what this basis is published for, which is the only difference between
     * the two scoped bases -- so the four residency arms are answered once
     * rather than once per basis.
     *
     * Exhaustive over [Residency] with no `else`, so a fourth residency arm has
     * to say what a family is told rather than defaulting into an absence.
     */
    private fun scopeOf(
      residency: Residency,
      collegeState: String,
      audienceLivesInCollegeState: Boolean,
    ): ResidencyScope =
      when (residency) {
        Residency.NotAsked -> {
          NO_STATE_ON_FILE
        }

        is Residency.Unavailable -> {
          scopeOfUnusable(residency.cause)
        }

        is Residency.OnFile -> {
          if ((residency.familyResidencyState == collegeState) == audienceLivesInCollegeState) {
            MATCHES_STATE_ON_FILE
          } else {
            DIFFERS_FROM_STATE_ON_FILE
          }
        }
      }

    /**
     * A residency we cannot use: a DECISION the family made, or a fault of ours
     * -- and only one of the two is an absence we may speak of as ours.
     */
    private fun scopeOfUnusable(cause: Residency.Cause): ResidencyScope =
      when (cause) {
        // THEY WERE ASKED AND CHOSE NOT TO SAY, so we hold no state and saying
        // so is true. It is not restated as a decline on every package either:
        // `ResidencyOffer.noteFor` says nothing at all about a decline, because
        // repeating it back on a result is re-raising a closed topic in all but
        // name.
        Residency.Cause.DECLINED -> {
          NO_STATE_ON_FILE
        }

        // A FAULT OF OURS IS NOT A FACT ABOUT THEM. We do not know what this
        // family told us -- the state may be on file and unreadable this
        // request -- so the comparison says it was unavailable and never that
        // there is none.
        Residency.Cause.READ_FAILED, Residency.Cause.CORRUPT_PROFILE -> {
          STATE_UNAVAILABLE
        }
      }
  }
}

/**
 * A `college_national_merit_awards` row whose `residency_basis` is outside the
 * three members its own CHECK admits.
 *
 * THE ROW AND THE BASIS RIDE TYPED rather than flattened into a sentence: the
 * one thing a caller does with this is tell a corrupt row apart from a read that
 * failed -- [NationalMeritService.getForStudent] hands every exception to the
 * same generic refusal -- and a sentence cannot be switched on. The message
 * LOCATES the row for the operator who has to go and fix it.
 */
class CorruptAwardResidencyBasisException(
  /** The corrupt row. Its id, college and entry year are what locate the defect. */
  val award: NationalMeritAward,
) : IllegalStateException(
    "college_national_merit_awards row [${award.id.value}] for college [${award.collegeId.value}] entry year " +
      "[${award.entryYear.label}] is on residency_basis [${award.residencyBasis.value}], which " +
      "[college_national_merit_awards_residency_basis_check] does not admit: this row is corrupt, and an award " +
      "whose audience we cannot name is not one we describe to a family",
  ) {
  /** What is wrong with the row, read off it rather than passed in beside it: the inadmissible basis. */
  val basis: ResidencyBasis get() = award.residencyBasis
}

/**
 * What ONE school PUBLISHED for a National Merit standing, for one entry year
 * and one residency basis, on this day -- and nothing whatever about a student.
 *
 * THE SCOPE-FREE HALF OF [NationalMeritSchoolAnswer], and the family cost
 * report's answer type (RFC 247 D1). There is no [ResidencyScope] on it, no
 * timing arm and no standing of hers, so the shared report -- which a
 * logged-out parent reads -- cannot print a comparison with the family it was
 * never handed. Passing [Residency.NotAsked] to the chat door instead would put
 * `no_state_on_file` on every row: a false sentence about a family whose state
 * may well be on file.
 *
 * FOUR ARMS AND NO [NationalMeritSchoolAnswer.NotCurated]. Every arm is built
 * from a stored row, so a school we have not read is simply absent from a list
 * of these -- the report's door never claims a school pays nothing.
 *
 * EVERY ARM'S CONSTRUCTOR IS INTERNAL, so outside this module [of] is the only
 * door: an [Automatic] around a competitive claim, or around another row's
 * claim, is not a value a caller can make. `@ConsistentCopyVisibility` closes
 * `copy()` with the constructor (the `FafsaFilingDeadline.State` precedent).
 * Internal and not private, because a nested class's private constructor is
 * closed to the companion's [of].
 */
sealed interface NationalMeritPublishedAnswer {
  /** The stored row: its citation, its audience and its entry year are owed by every arm. */
  val award: NationalMeritAward

  val collegeId: CollegeId get() = award.collegeId

  /** We read the page and this school publishes NO National Merit award at all. */
  @ConsistentCopyVisibility
  data class NonePublished internal constructor(
    override val award: NationalMeritAward,
    /** The absence, with the sentence that proves it. */
    val claim: NationalMeritClaim.NonePublished,
  ) : NationalMeritPublishedAnswer

  /** The row is no longer believed: `expires_on` has passed. No price is spoken from it. */
  @ConsistentCopyVisibility
  data class Expired internal constructor(
    override val award: NationalMeritAward,
  ) : NationalMeritPublishedAnswer {
    /** The day we stopped believing the row: read off it, so it cannot disagree with it. */
    val expiredOn: LocalDate get() = award.expiresOn
  }

  /** The page GUARANTEES the package to anyone holding the standing. */
  @ConsistentCopyVisibility
  data class Automatic internal constructor(
    override val award: NationalMeritAward,
    val claim: NationalMeritClaim.Paying,
    val published: PublishedPackage,
  ) : NationalMeritPublishedAnswer

  /** The standing is CONSIDERED and not paid. */
  @ConsistentCopyVisibility
  data class Competitive internal constructor(
    override val award: NationalMeritAward,
    val claim: NationalMeritClaim.Paying,
    val published: PublishedPackage,
  ) : NationalMeritPublishedAnswer

  companion object {
    /**
     * THE ONE HOME OF THE ARM RULE, for both doors: what the school published,
     * for one award row, on this day -- and the base rate, which travels inside
     * the price or the price is not built.
     *
     * EXPIRY OUTRANKS THE KIND. A stale reading is not a cheaper answer about the
     * same money; it is a page somebody has to open again, and no price is
     * spoken from it.
     *
     * The chat door adds its residency comparison to what comes back
     * ([NationalMeritService]'s `answerFor`); the report door renders it as it
     * is. A second `when` over the claim in either place would be a second home
     * for "expired outranks the kind" to be decided differently.
     */
    fun of(
      award: NationalMeritAward,
      today: LocalDate,
      baseRate: PublishedBaseRate?,
    ): NationalMeritPublishedAnswer {
      // `>` AND NOT `>=`: a row is believed THROUGH its expires_on, the predicate
      // the two landed corpora already share.
      if (today > award.expiresOn) return Expired(award)
      return when (val claim = award.claim) {
        is NationalMeritClaim.NonePublished -> {
          NonePublished(award, claim)
        }

        is NationalMeritClaim.Paying -> {
          val published = PublishedPackage.of(claim, baseRate)
          when (claim.guarantee) {
            AwardGuarantee.AUTOMATIC -> Automatic(award, claim, published)
            AwardGuarantee.COMPETITIVE -> Competitive(award, claim, published)
          }
        }
      }
    }
  }
}

/**
 * What we can say about ONE school's National Merit package, for one entry year,
 * one residency basis and one standing.
 *
 * FIVE ARMS, and the family is sent to five different places. [Expired] is not
 * [NotCurated] -- "we read this school and our reading is stale" and "we never
 * read this school" are different facts -- and [Competitive] is not [Automatic],
 * which is the costliest confusion this corpus can make: UCF says a Finalist is
 * CONSIDERED for more than $25,000 a year, and a coach that read that as money
 * she will receive has promised six figures the school never did.
 */
sealed interface NationalMeritSchoolAnswer {
  val collegeId: CollegeId

  /** We have not read this school. A gap in OUR data, and never a statement that the school pays nothing. */
  data class NotCurated(
    override val collegeId: CollegeId,
  ) : NationalMeritSchoolAnswer

  /**
   * Every arm built from a stored row: the row itself, and whose award it is
   * against the state we hold.
   *
   * A SHARED SUPERTYPE AND NOT FOUR REPEATED FIELDS, because the citation and
   * the residency comparison are owed by every stored row whatever it says --
   * including the absence, whose whole value is the page and the day we read it.
   */
  sealed interface Read : NationalMeritSchoolAnswer {
    val award: NationalMeritAward
    val residencyScope: ResidencyScope

    override val collegeId: CollegeId get() = award.collegeId
  }

  /** We read the page and this school publishes NO National Merit award at all. A verified absence with a citation. */
  data class NonePublished(
    override val award: NationalMeritAward,
    override val residencyScope: ResidencyScope,
    /** The absence, with the sentence that proves it -- non-null by type, because that sentence IS the answer. */
    val claim: NationalMeritClaim.NonePublished,
  ) : Read

  /** The row exists and is no longer believed: `expires_on` has passed (D7/D20). No price is spoken from it. */
  data class Expired(
    override val award: NationalMeritAward,
    override val residencyScope: ResidencyScope,
    val expiredOn: LocalDate,
  ) : Read

  /** The page GUARANTEES the package to anyone holding the standing: no committee, no separate contest. */
  data class Automatic(
    override val award: NationalMeritAward,
    override val residencyScope: ResidencyScope,
    /** What the page publishes. The standing and the words ride non-null on it, by type. */
    val claim: NationalMeritClaim.Paying,
    val published: PublishedPackage,
  ) : Read {
    /** Which standing the school pays, read off the claim rather than re-derived from the row. */
    val standing: QualifyingStanding get() = claim.standing
  }

  /** The standing is CONSIDERED and not paid. Never spoken as money the student will receive. */
  data class Competitive(
    override val award: NationalMeritAward,
    override val residencyScope: ResidencyScope,
    val claim: NationalMeritClaim.Paying,
    val published: PublishedPackage,
  ) : Read {
    /** Which standing the school CONSIDERS, read off the claim rather than re-derived from the row. */
    val standing: QualifyingStanding get() = claim.standing
  }
}

/**
 * The whole National Merit answer for one student: where she is in her own
 * cycle, what each requested school publishes, and the base rate that rides
 * with any money in it.
 *
 * MORE THAN ONE ANSWER PER COLLEGE IS NORMAL. A school publishes one row per
 * (entry year, residency basis, standing) -- Maine publishes a Semifinalist row
 * AND a Finalist row -- so each answer names its own basis and standing, and no
 * rule of one row is read onto another.
 */
data class NationalMeritReading(
  /** Her year of school today, derived from her stored graduation date (RFC 186) and never stored. */
  val yearOfSchool: HighSchoolYear,
  /** Her class, which is also NMSC's name for her competition. */
  val graduationYear: Int,
  val timing: CycleTiming,
  /**
   * The base rate our corpus publishes: the most recent cycle we hold that
   * publishes a funnel, or null where NO cycle does.
   *
   * THE SAME VALUE every [PublishedPackage.Priced] in [schools] carries, passed
   * rather than recomputed from them, so the head and the money cannot
   * disagree. It is here even where no package is priced -- an empty list, or
   * schools we have not read -- because the cycle object states NMSC's own
   * award, and a figure without this beside it is the one thing this read
   * refuses to serve.
   */
  val baseRate: PublishedBaseRate?,
  val schools: List<NationalMeritSchoolAnswer>,
  /** Requested ids that are not on this student's active list -- reported rather than failing the whole read. */
  val unknownCollegeIds: List<CollegeId>,
)

/**
 * The student-free National Merit read (RFC 247) as a SEAM, for
 * [AutomaticAwardReader]'s reason one corpus along: the family cost report's
 * cost service only RENDERS what comes back, so a test must be able to hand it
 * a stub or a pinned clock, and Kotlin classes are final.
 *
 * ONLY THE REPORT'S DOOR RIDES HERE. The chat tool constructs
 * [NationalMeritService] itself and nothing needs to substitute it, so a
 * student-scoped door on this interface would be surface with no caller.
 *
 * The one implementation is [NationalMeritService], and every rule stays there.
 */
interface NationalMeritReportReader {
  /**
   * What each of [collegeIds] PUBLISHED, with NO STUDENT IN THE ANSWER. The
   * CALLER owns the [SqlSession], exactly as
   * [AutomaticAwardReader.gridAnswersInSession] does: the Family Cost Report
   * resolves a share token and then reads the whole profile on one connection,
   * so one read is one snapshot.
   */
  fun publishedAnswersInSession(
    session: SqlSession,
    collegeIds: Collection<CollegeId>,
  ): List<NationalMeritPublishedAnswer>
}

/**
 * The read behind `national_merit_cycles` and `college_national_merit_awards`
 * (RFC 243): what the PSAT/NMSQT is worth, where this student stands in the one
 * cycle that is hers, and what each school on her list publishes for a National
 * Merit standing.
 *
 * NO COLLEGE EVER SEES A PSAT SCORE. It is not an admissions test, and nothing
 * in this read is a score, a prediction or a cutoff: the corpus stores HOW each
 * bar is set plus NMSC's own sentence saying the qualifying score is not
 * published in advance, and no column anywhere holds a number a family could act
 * on as her bar.
 *
 * THE CLOCK IS AN [InstantSource] and the zone is OURS ([ed.unicoach.coaching.coachToday]).
 * `LocalDate.now(clock)` appears nowhere in this file: it would read the
 * INJECTED clock's zone, so a test pinning a UTC instant would pass for the
 * wrong reason -- RFC 186's first attempt was written that way and its test
 * proved the opposite of what it claimed.
 *
 * IT RENDERS NOTHING. Every arm, refusal and absence is a typed value; the words
 * the coach reads live in [NationalMeritChatTool], where a rendering test can
 * reach them.
 *
 * NOTHING HERE RANKS, SORTS, TOTALS OR COMPARES TWO SCHOOLS' MONEY. Half the
 * paying schools in this corpus publish no number at all, so there is no
 * ordering to compute -- and RFC 239's review found exactly that defect shipping
 * as a $0 ranking of a school's top award.
 */
class NationalMeritService(
  private val database: Database,
  private val clock: InstantSource = InstantSource.system(),
) : NationalMeritReportReader {
  /**
   * This student's cycle, her schools' packages, and the base rate.
   *
   * [residency] is what we know about where this family lives, as
   * [Residency] resolves it -- the state when it is on file, and WHICH of the
   * three non-answers it is otherwise. IT IS NOT AN INPUT THE MODEL CAN SUPPLY:
   * the caller reads it from `money_profiles`, for
   * [ed.unicoach.coaching.ResidencyScopedChatTool]'s standing reason, and a state
   * held only in a tool argument is a fact unicoach never learns.
   *
   * THE WHOLE VALUE AND NOT ITS CODE, because a read that faulted and a family
   * nobody has asked are different facts about different people, and only the
   * first of them is ours ([ResidencyScope.of]).
   */
  suspend fun getForStudent(
    studentId: StudentId,
    collegeIds: List<CollegeId>? = null,
    residency: Residency = Residency.NotAsked,
  ): Result<NationalMeritReading> =
    try {
      Result.success(
        database.withConnection { session -> readInSession(session, studentId, collegeIds, residency) },
      )
    } catch (e: CancellationException) {
      // Cancellation is the caller unwinding, not a read that failed
      // (CollegeAdmissionsService's rule).
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * The whole read on ONE session: her stored graduation date, every cycle we
   * hold, her active list and the award rows for it. [READ_STATEMENTS]
   * statements, whatever the length of the list.
   *
   * THE CYCLE HALF IS READ FIRST AND DEPENDS ON NO LIST. A student with an empty
   * college list still gets her timing arm, the window and the alternate-entry
   * route: "what this test is and when it is" is the answer Route A exists to
   * make unconditional (`bin/test` does db-reset and never ingests), and gating
   * it on a list would put it behind an import.
   *
   * EVERY CYCLE IN ONE STATEMENT, not one lookup for hers and a second for the
   * funnel: the table is a handful of rows by design, and two reads of it could
   * disagree about which year publishes the base rate.
   */
  internal fun readInSession(
    session: SqlSession,
    studentId: StudentId,
    collegeIds: List<CollegeId>?,
    residency: Residency,
  ): NationalMeritReading {
    val today = clock.coachToday()
    val graduation = graduationDateOf(session, studentId, "reading the National Merit cycle")
    val yearOfSchool = HighSchoolYear.of(today, graduation)
    val graduationYear = graduation.year.value

    val cycles = NationalMeritCyclesDao.list(session).getOrThrow()
    val timing = timingOf(today, yearOfSchool, cycles.find { it.competitionYear == graduationYear }, graduationYear)
    val baseRate = PublishedBaseRate.of(cycles, today)

    val selection = StudentCollegeSelection.read(session, studentId, collegeIds)
    val schools = schoolAnswersFor(session, selection, today, baseRate, residency)

    return NationalMeritReading(
      yearOfSchool = yearOfSchool,
      graduationYear = graduationYear,
      timing = timing,
      baseRate = baseRate,
      schools = schools,
      unknownCollegeIds = selection.unknown,
    )
  }

  /**
   * What each of [collegeIds] published, with NO STUDENT IN THE ANSWER -- the
   * family cost report's entry point (RFC 247 D1).
   *
   * NOTHING OF HERS IS READ OR RETURNED. No `students` row (so a college id with
   * no student behind it is answered), no money profile and no timing arm, and
   * [NationalMeritPublishedAnswer] has nowhere to put a residency comparison:
   * every row rides labelled only by the audience the SCHOOL published it for.
   *
   * A COLLEGE WITH NO ROW CONTRIBUTES NOTHING, never a `not_curated` arm: the
   * report says nothing about our own corpus coverage, so there is no arm here
   * for it to have to refuse.
   *
   * THE CYCLES ARE READ ONLY WHEN THERE IS A ROW TO PRICE, the
   * [AutomaticAwardService.gridAnswersInSession] order: a list with no curated
   * school costs one statement, and the base rate is still read in the same
   * snapshot as the rows it prices whenever it is needed.
   *
   * [REPORT_READ_STATEMENTS] statements, whatever the length of the list.
   */
  override fun publishedAnswersInSession(
    session: SqlSession,
    collegeIds: Collection<CollegeId>,
  ): List<NationalMeritPublishedAnswer> {
    if (collegeIds.isEmpty()) return emptyList()
    val today = clock.coachToday()
    val awards = NationalMeritAwardsDao.listByColleges(session, collegeIds).getOrThrow()
    if (awards.isEmpty()) return emptyList()
    val baseRate = PublishedBaseRate.of(NationalMeritCyclesDao.list(session).getOrThrow(), today)
    return awards.map { award -> NationalMeritPublishedAnswer.of(award, today, baseRate) }
  }

  /**
   * One answer per PUBLISHED ROW for every school in the selection, in the
   * list's own order, and [NationalMeritSchoolAnswer.NotCurated] for a school
   * this corpus holds no row for.
   *
   * A SCHOOL WITH NO ROW IS A GAP IN OUR DATA and never a school that pays
   * nothing, which is why it is an arm here and not an omission.
   *
   * The award read goes with it, so [READ_STATEMENTS] still counts every
   * statement this read costs and the schools half has one home.
   */
  private fun schoolAnswersFor(
    session: SqlSession,
    selection: StudentCollegeSelection,
    today: LocalDate,
    baseRate: PublishedBaseRate?,
    residency: Residency,
  ): List<NationalMeritSchoolAnswer> {
    val byCollege =
      NationalMeritAwardsDao
        .listByColleges(session, selection.selected)
        .getOrThrow()
        .groupBy { it.collegeId }
    return selection.selected.flatMap { collegeId ->
      val rows = byCollege[collegeId].orEmpty()
      if (rows.isEmpty()) {
        listOf(NationalMeritSchoolAnswer.NotCurated(collegeId))
      } else {
        val collegeState = selection.collegeOf(collegeId).state
        rows.map { award -> answerFor(award, today, baseRate, collegeState, residency) }
      }
    }
  }

  /**
   * WHICH OF THE SEVEN TIMING ARMS this student is in, decided from her year of
   * school and the cycle row and nothing else.
   *
   * THE QUALIFYING GRADE IS THE ROW'S, not an 11 typed here: it is a published
   * rule of the programme, stored so the coach never speaks it from memory, and
   * a literal here would be a second opinion about it.
   *
   * OUR OWN FACTS ARE DECIDED BEFORE THE CORPUS'S FRESHNESS. The year of school
   * is derived from HER STORED GRADUATION DATE (RFC 186), so it is true whether
   * or not we still believe two publishers' pages -- and the two answers it
   * settles on its own, a person past her expected graduation and a student
   * whose DESCRIBED cycle's qualifying year is behind her, name no date of the
   * row at all. [CycleTiming.CycleExpired] is for the case where we would
   * otherwise SPEAK this row's dates.
   *
   * A ROUTER OVER THE WINDOW SHAPE, and nothing else: which arms a row can
   * answer at all is a property of whether its publisher dated it, and each
   * shape's own precedence is that shape's function ([describedTimingOf],
   * [datedTimingOf]).
   */
  private fun timingOf(
    today: LocalDate,
    yearOfSchool: HighSchoolYear,
    cycle: NationalMeritCycle?,
    graduationYear: Int,
  ): CycleTiming {
    if (cycle == null) return CycleTiming.NotCurated(graduationYear)
    // PAST GRADUATION IS ANSWERED FIRST AND WITHOUT A DATE. On our record that
    // person is no longer a high-school student, so her one qualifying sitting
    // is behind her whatever the row says -- and the row is hers, because the
    // lookup was her own stored graduation year.
    if (yearOfSchool == HighSchoolYear.PAST_GRADUATION) return CycleTiming.CycleClosed(cycle)
    // BEFORE_HIGH_SCHOOL has no grade at all and is below every one of them: the
    // window is ahead for the cycle that matches her graduation year, which is
    // the row we already hold.
    val grade = yearOfSchool.grade

    return when (val window = cycle.window) {
      is CycleWindow.Described -> describedTimingOf(today, grade, cycle)
      is CycleWindow.Dated -> datedTimingOf(today, grade, cycle, window)
    }
  }

  /**
   * The arms an UNDATED cycle can answer, and not one of them states a date.
   *
   * An undated row can never answer a comparison with today, so the arm that
   * names a window without dating it names it in the publisher's own words,
   * while she is still IN the qualifying year.
   *
   * HER YEAR OF SCHOOL IS ANSWERED FIRST HERE: once the qualifying year is
   * behind her an undated row has nothing left to offer, and that is her own
   * record speaking rather than our reading going stale.
   */
  private fun describedTimingOf(
    today: LocalDate,
    grade: Int?,
    cycle: NationalMeritCycle,
  ): CycleTiming {
    if (grade != null && grade > cycle.qualifyingGrade) return CycleTiming.CycleClosed(cycle)
    if (today > cycle.expiresOn) return CycleTiming.CycleExpired(cycle, cycle.expiresOn)
    return CycleTiming.WindowAhead(cycle)
  }

  /**
   * The dated arms, in the order the design's table states them.
   *
   * EXPIRY OUTRANKS EVERY DATED ARM, exactly as it does for an award row
   * ([answerFor]): a stale reading is not a cheaper answer about the same dates,
   * it is two pages somebody has to open again -- and `>` and not `>=`, so the
   * row is believed THROUGH its expires_on. The window ahead names its opening,
   * `window_open` its close and `alternate_entry_open` its own deadline, so all
   * of them sit behind this one gate.
   *
   * THE CLOSED ANSWER HERE IS NOT A YEAR-BASED ONE, which is why the grade is
   * consulted after expiry and not before [describedTimingOf]'s order: the
   * letter route is itself a date, so whether a student past the window has
   * anything left is this row's reading rather than her year of school.
   *
   * THE WINDOW AND THE DEADLINE ARE BOTH INCLUSIVE, the `>` predicate the two
   * landed corpora already share (TestTimelineService, FafsaDeadlineService,
   * [AutomaticAwardService]): a window that closes on October 30 is open ON
   * October 30, and a letter received BY April 1 is in time on April 1. A `>=`
   * here would close both a day early, on the one deadline in this brief that
   * cannot be retaken.
   *
   * AND A ROUTE WITH NO DATE IS ITS OWN ARM
   * ([CycleTiming.AlternateEntryUndated]), never the closed one: a deadline its
   * publisher has not printed is a deadline on the publisher's page, and a door
   * we cannot date is not a door we may call shut.
   */
  private fun datedTimingOf(
    today: LocalDate,
    grade: Int?,
    cycle: NationalMeritCycle,
    window: CycleWindow.Dated,
  ): CycleTiming {
    if (today > cycle.expiresOn) return CycleTiming.CycleExpired(cycle, cycle.expiresOn)
    if (grade == null || grade < cycle.qualifyingGrade) return CycleTiming.WindowAhead(cycle)
    if (today < window.opensOn) return CycleTiming.WindowAhead(cycle)
    if (today <= window.closesOn) return CycleTiming.WindowOpen(cycle, window)
    val alternateEntry = cycle.alternateEntry ?: return CycleTiming.CycleClosed(cycle)
    val deadline = alternateEntry.deadline ?: return CycleTiming.AlternateEntryUndated(cycle, alternateEntry)
    if (today <= deadline) return CycleTiming.AlternateEntryOpen(cycle, alternateEntry, deadline)
    return CycleTiming.CycleClosed(cycle)
  }

  /**
   * WHAT THE SCHOOL PUBLISHED, for one award row, on this day, and whose award
   * it is against the state we hold.
   *
   * THE ARMS ARE [NationalMeritPublishedAnswer.of]'s, mapped one to one: this
   * door adds the residency comparison and decides nothing else, so the chat
   * and the family cost report cannot disagree about which arm a row is.
   *
   * THE SCOPE IS COMPUTED FIRST, as it always was: a corrupt residency basis is
   * refused even on an expired row, because the refusal is about the row and
   * not about the day.
   */
  private fun answerFor(
    award: NationalMeritAward,
    today: LocalDate,
    baseRate: PublishedBaseRate?,
    collegeState: String,
    residency: Residency,
  ): NationalMeritSchoolAnswer {
    val scope = ResidencyScope.of(award, collegeState, residency)
    return when (val published = NationalMeritPublishedAnswer.of(award, today, baseRate)) {
      is NationalMeritPublishedAnswer.NonePublished -> {
        NationalMeritSchoolAnswer.NonePublished(published.award, scope, published.claim)
      }

      is NationalMeritPublishedAnswer.Expired -> {
        NationalMeritSchoolAnswer.Expired(published.award, scope, published.expiredOn)
      }

      is NationalMeritPublishedAnswer.Automatic -> {
        NationalMeritSchoolAnswer.Automatic(published.award, scope, published.claim, published.published)
      }

      is NationalMeritPublishedAnswer.Competitive -> {
        NationalMeritSchoolAnswer.Competitive(published.award, scope, published.claim, published.published)
      }
    }
  }

  companion object {
    /**
     * What this read costs, in prepared statements: her students row, every
     * cycle, the two the college selection takes, and this corpus's award rows.
     *
     * ABSOLUTE, and asserted. "Ten colleges cost what one college costs" stays
     * true when a sixth read is added, so a relative assertion alone would let
     * this read grow silently past the number [readInSession]'s own KDoc
     * promises. A student with NO active list costs one fewer, because
     * `IN ()` is not valid SQL and a query for nothing has nothing to answer.
     */
    const val READ_STATEMENTS: Int = 5

    /**
     * What [publishedAnswersInSession] costs: this corpus's award rows and every
     * cycle. No student is read on that path at all -- the privacy ruling, not
     * the budget. A list with no curated school costs one, and an empty list
     * none.
     *
     * ABSOLUTE, and asserted, for [READ_STATEMENTS]'s reason.
     */
    const val REPORT_READ_STATEMENTS: Int = 2
  }
}
