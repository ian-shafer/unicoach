package ed.unicoach.coaching.costs

import ed.unicoach.coaching.StudentCollegeSelection
import ed.unicoach.coaching.admissions.MeritPractice
import ed.unicoach.coaching.costs.canonical.CanonicalCostReader
import ed.unicoach.coaching.costs.canonical.CohortAddress
import ed.unicoach.coaching.costs.canonical.CollegeFigures
import ed.unicoach.coaching.costs.canonical.DbCanonicalCostReader
import ed.unicoach.coaching.costs.canonical.FigureAddress
import ed.unicoach.coaching.costs.canonical.FigureStatusCopy
import ed.unicoach.coaching.costs.canonical.ResidencyTierBasis
import ed.unicoach.coaching.costs.canonical.ServedFigures
import ed.unicoach.coaching.costs.canonical.figureAddress
import ed.unicoach.coaching.costs.canonical.publishedTuitionTiersOf
import ed.unicoach.coaching.costs.canonical.residencyTiersOf
import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.Database
import ed.unicoach.db.dao.AidPolicyDao
import ed.unicoach.db.dao.BorrowingDao
import ed.unicoach.db.dao.CdsAdmissionsDao
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.BorrowerCounts
import ed.unicoach.db.models.College
import ed.unicoach.db.models.CollegeAidPolicy
import ed.unicoach.db.models.CollegeBorrowing
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.CollegeMeritAid
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.InstitutionControl
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.MoneyProfile
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.ZoneOffset

/**
 * WHICH published net-price figure the family's own answer selected -- the label
 * alone, with no amount anywhere inside it.
 *
 * Its own vocabulary so a [NetPrice.Withheld] can carry the basis without
 * carrying the number it is withholding: the absence is the type, not a nulled
 * field policed by a runtime check, and `amount == null` goes back to meaning
 * exactly one thing (the college reports nothing).
 *
 * [YourIncomeBand] carries the band, because the family answered the income
 * question or they did not, and that fact is theirs whether or not this school's
 * figure is one we can show them -- so the band label survives withholding while
 * the amount does not.
 */
sealed interface NetPriceBasis {
  /** The serialized `basis` label -- derived from the case, never stored beside it. */
  val value: String

  /** The student's answered household income band selected the bracket column. */
  data class YourIncomeBand(
    val band: IncomeBand,
  ) : NetPriceBasis {
    override val value: String get() = "your_income_band"
  }

  /** The band is unanswered or declined; the figure is the all-family average. */
  data object OverallAverage : NetPriceBasis {
    override val value: String get() = "overall_average"
  }
}

/**
 * The net-price answer for one college — the ethos label (RFC 135): the coach
 * can never silently present an overall average as a personal number, because
 * the case says which one it is, and only [BandSpecific] can carry a band —
 * a band on an overall average is unrepresentable.
 *
 * THREE cases, not two, because a missing number has three different causes and
 * only one of them is the college's (RFC 157 D-A): the college reports the
 * selected figure ([Reported] with an [amount]), the college reports nothing
 * for it ([Reported] with a null [amount], which also appears in
 * [CollegeCost.notReported]), or we hold the figure back because it is not this
 * family's ([Withheld], which appears in [CollegeCost.withheld] and in NEITHER
 * reported list).
 *
 * A bare null therefore never says which: a site that has not handled
 * [Withheld] fails to compile rather than printing the school's silence over
 * our own rule.
 */
sealed interface NetPrice {
  val amount: Int?

  /** WHICH figure this answer is, whether or not it carries a number. */
  val publishedBasis: NetPriceBasis

  /** The serialized `basis` label — read from [publishedBasis], so one case cannot label itself twice. */
  val basis: String get() = publishedBasis.value

  /**
   * The two cases that come from the SCHOOL's own row: a figure, or its silence.
   * [Withheld] carries the BASIS of one of these rather than being one, so a
   * withheld figure can never wrap a withheld figure -- or the amount it is
   * withholding.
   */
  sealed interface Reported : NetPrice

  /** The student's answered household income band selected the bracket column. */
  data class BandSpecific(
    val band: IncomeBand,
    override val amount: Int?,
  ) : Reported {
    override val publishedBasis: NetPriceBasis get() = NetPriceBasis.YourIncomeBand(band)
  }

  /** The band is unanswered or declined; the amount is the all-family average. */
  data class OverallAverage(
    override val amount: Int?,
  ) : Reported {
    override val publishedBasis: NetPriceBasis get() = NetPriceBasis.OverallAverage
  }

  /**
   * The school publishes this figure and it is not this family's, so we hold it
   * back (RFC 157 D-A). The reason travels WITH the blank rather than beside it.
   *
   * It carries the BASIS the family's own answer selected and NO amount: there
   * is no field for the withheld number to ride in, so no `require` has to check
   * that it does not.
   */
  data class Withheld(
    override val publishedBasis: NetPriceBasis,
    val reason: WithheldReason,
  ) : NetPrice {
    override val amount: Int? get() = null
  }
}

/**
 * Which published tuition figure applies to this student at a public college,
 * from residency vs the college's state. Carried only by
 * [CollegeControl.Public] — for a private college in-state/out-of-state is not
 * a distinction, and the type makes it uncarryable.
 */
enum class TuitionApplicable(
  val value: String,
) {
  IN_STATE("in_state"),
  OUT_OF_STATE("out_of_state"),

  /** Public college, residency unanswered or declined. */
  UNKNOWN("unknown"),
  ;

  /**
   * Whether a figure published ONLY on the in-state basis
   * ([ResidencyAxis.IN_STATE_ONLY]) describes this family at this public
   * college (RFC 157).
   *
   * The public half of the rule [ComparedTuition.blendedFiguresApply] owns for
   * every kind of school, so the figure that is missing and the sentence
   * explaining it are one decision rather than two.
   *
   * [BlendedFigureApplicability.BASIS_STATED] is NOT
   * [BlendedFigureApplicability.WITHHELD]: an unanswered residency withholds
   * nothing (RFC 157 D-B). The only price we hold is still shown, with its basis
   * said, because hiding it until a question is answered would gate the answer
   * on a completed profile -- which brief 0001 D11/D12 forbids.
   */
  val blendedFiguresApply: BlendedFigureApplicability
    get() =
      when (this) {
        IN_STATE -> BlendedFigureApplicability.APPLIES
        OUT_OF_STATE -> BlendedFigureApplicability.WITHHELD
        UNKNOWN -> BlendedFigureApplicability.BASIS_STATED
      }
}

/**
 * Scorecard control (`colleges.control`) as the cost read renders it: the
 * cost-domain shape around the vocabulary, which lives in one home for every
 * module in [InstitutionControl] (RFC 143). The codes are mapped to these
 * cases in [CollegeCostService]; the [label] each case renders is read from
 * [InstitutionControl], never hand-written here, so search and the cost tool
 * cannot drift apart. Tuition applicability lives only on the [Public] case,
 * so a private college cannot carry an in-state price (RFC 135).
 */
sealed interface CollegeControl {
  /** The wire `control` label the coach reads. */
  val label: String

  /** Code 1 — the only case where residency selects a tuition figure. */
  data class Public(
    val tuitionApplicable: TuitionApplicable,
  ) : CollegeControl {
    override val label: String get() = InstitutionControl.PUBLIC.label
  }

  /** Code 2 — one price, no residency distinction. */
  data object PrivateNonprofit : CollegeControl {
    override val label: String get() = InstitutionControl.PRIVATE_NONPROFIT.label
  }

  /** Code 3 — one price, no residency distinction. */
  data object PrivateForProfit : CollegeControl {
    override val label: String get() = InstitutionControl.PRIVATE_FOR_PROFIT.label
  }

  /**
   * A code the Scorecard vocabulary does not define; the label carries the raw
   * code so it stays observable at the wire. It renders through
   * [InstitutionControl.unknownLabel], NOT the total `labelFor`: this case
   * means "outside the vocabulary" by construction, so it must read as unknown
   * for whatever code it holds rather than picking up a recognised phrase if
   * one were ever passed here.
   */
  data class Unrecognized(
    val code: Int,
  ) : CollegeControl {
    override val label: String get() = InstitutionControl.unknownLabel(code)
  }
}

/**
 * One college's cost facts, composed from its list entry, the `colleges` row,
 * and the money profile.
 *
 * It takes the figures AS PUBLISHED and applies RFC 157 D-A itself. What this
 * family may be SHOWN -- [stickerCostOfAttendancePerYearUsd], [netPrice],
 * [reported], [notReported] and [withheld] -- is derived here from [control] and
 * what the school published, so a caller cannot hand this type a set of fields
 * its own control contradicts, and `copy()` cannot build one for free. The
 * withholding rule therefore has ONE home, inside the only type that can see
 * every fact it needs.
 */
data class CollegeCost(
  val collegeId: CollegeId,
  val name: String,
  val city: String,
  val state: String,
  val control: CollegeControl,
  val listStatus: CollegeListEntryStatus,
  /**
   * The school's own published cost of attendance, AS PUBLISHED -- the INPUT to
   * RFC 157 D-A and never a figure to render, so it is PRIVATE: the constructor
   * stays public and every caller still names it, while the exact number this
   * rule exists to withhold is unreadable from a page, a tool, or a `toString()`
   * in a log line. What this family may be shown is
   * [stickerCostOfAttendancePerYearUsd]; read that one.
   */
  private val publishedStickerCostOfAttendancePerYearUsd: Int?,
  /**
   * This school's canonical price rows AT the one year this answer serves them
   * at (RFC 166 §3) -- the type that replaced `College` as the cost path's
   * input, bound to its served year.
   *
   * PRIVATE: [CollegeFigures] holds every academic year the store carries, and
   * this answer serves exactly one of them. A reader given the whole set could
   * quote a figure from a year no other key beside it describes, which is
   * precisely the mixed-vintage defect the domain refuses at construction.
   * [publishedAmountOf] is the door.
   *
   * ONE field rather than the `(figures, academicYear)` pair it replaced: the
   * year is not a second thing a caller may state, so no assembly can hand this
   * type a year that is not this school's.
   */
  private val served: ServedFigures,
  /** The vintage of the blended averages this school serves, or null when it carries none. */
  val blendedAverageAcademicYear: String?,
  /**
   * Which tuition tiers this school publishes, and the sentence that goes with
   * it (RFC 166 §4) -- including the case where the publisher does not separate
   * an in-district price at all, which is stated rather than guessed at.
   */
  val residencyTiers: ResidencyTierBasis,
  /**
   * The reason beside the silence: one entry per field this answer carries no
   * amount for AND holds a canonical status for (RFC 166 §6).
   *
   * `data_availability` says WHICH figures are blank and keeps its exact shape;
   * this says WHY, in the store's own six statuses. A NULL column could not tell
   * a family whether a number is missing because the publisher withheld it,
   * because the school never reported it, or because we have not collected it.
   */
  val figureStatuses: List<FigureStatusNote>,
  /**
   * The net-price answer AS PUBLISHED, and private for the same reason: what
   * this family may be shown is [netPrice], which is a [NetPrice.Withheld] when
   * D-A holds this figure back.
   */
  private val publishedNetPrice: NetPrice.Reported,
  val medianDebtAtCompletionUsd: Int?,
  val medianEarnings10yAfterEntryUsd: Int?,
  /** True when the college reports at least one `net_price_per_year_income_qN_usd` bracket column. */
  val reportsBandPricing: Boolean,
  /**
   * True when the college publishes at least one tuition figure — the residency
   * twin of [reportsBandPricing]: an answered residency selects between them,
   * so a college that publishes neither has no upgrade to promise.
   */
  val reportsPublishedTuition: Boolean,
  /**
   * The cost fields this college does not report, AS PUBLISHED -- a fact about
   * the school alone. [notReported] is the one a reader wants.
   */
  private val publishedNotReported: List<CostField>,
  /**
   * The cost fields this college DOES carry a figure for, AS PUBLISHED -- the
   * positive twin of [publishedNotReported], and the input the withholding rule
   * reads: only a figure the school published can be held back from anybody.
   *
   * Derived in [CollegeCostService] from [CostField.figureAddress], the one
   * primitive that answers "does this college report this field", so no reader
   * repeats the per-field null checks: a [CostField] added to the vocabulary
   * gains its column there and is classified here without a second edit nobody
   * would fail for forgetting.
   *
   * [reported] is the one a reader wants.
   */
  private val publishedReported: Set<CostField>,
  /**
   * The published price split by living arrangement (RFC 149), or null when
   * this school reports no component at all. The arithmetic lives in
   * [CostBreakdown], reached from [CollegeCostService.costOf], so the totals
   * are assertable without a JSON round trip.
   */
  val breakdown: CostBreakdown?,
  /**
   * Whether this school offers on-campus housing, from IPEDS `IC.ROOM`
   * (`college_ipeds.offers_housing`) -- null when IPEDS does not say.
   *
   * Three explicit states, never inferred from a null `ROOMBOARD_ON`: offers
   * housing, does NOT offer housing ("this school has no residence halls"), and
   * not reported. It is deliberately NOT a [notReported] entry -- that
   * vocabulary means "this college does not report this SCORECARD cost field",
   * and a school with no dorms is answering, not staying silent.
   */
  val offersOnCampusHousing: Boolean?,
  /**
   * What this school reported about the non-need (merit) money it gives, or
   * null when it reports none (RFC 148 D7). Purely additive: a college without
   * it produces exactly the cost answer it produced before this field existed,
   * and nothing in the cost answer depends on it.
   *
   * Deliberately NOT folded into [notReported], whose [CostField] vocabulary
   * means "this college does not report this SCORECARD cost field". Merit aid
   * is a second source with its own silences, and mixing them would misattribute
   * which source is quiet; `college_admissions_profile` owns that report.
   */
  val meritAid: MeritPractice?,
  /**
   * How this school treats need, and which aid forms its Common Data Set lists
   * as required (RFC 170), or null when the corpus carries no filing for it.
   * Purely additive, exactly like [meritAid], and for the same reason kept out
   * of [notReported]: this is a second source with its own silences.
   */
  val aidPolicy: AidPolicyPractice?,
  /**
   * What the students who GRADUATED from this school in one named year
   * borrowed, as the school reports it (RFC 175) -- or WHICH silence stands in
   * its place. Purely additive, and kept out of [notReported] for the
   * [aidPolicy] reason: a second source with its own silences.
   *
   * NOT nullable, unlike [aidPolicy]: there are four states here, not two, and
   * three of them are silences whose owner differs (D7). A null plus two
   * booleans made them a `when` ladder whose ARM ORDER decided whether our own
   * gap was spoken as the school's, so the four are a sealed type and every
   * consumer answers all of them.
   *
   * It sits BESIDE the price conversation and is never part of one. No figure
   * in it is a price, and nothing in this class subtracts it from one.
   */
  val borrowing: BorrowingCoverage,
  /**
   * The way of living this answer LEADS with, resolved once here from the
   * school's own override and the family's usual plan (RFC 152 D2a), rather
   * than re-derived by the renderer.
   *
   * It never narrows [breakdown]: all three arrangements stay in the payload
   * (D2). A resolved plan decides what the coach leads with and what a
   * comparison column holds constant, never what exists.
   */
  val chosen: ChosenLivingPlan,
) {
  /**
   * The figures we HOLD and are not showing this family, each with its reason
   * (RFC 157 D-A) -- a third category beside [reported] and [notReported], and
   * necessarily its own: the school published these numbers, so calling them
   * unreported would blame its price list for our applicability rule.
   *
   * DERIVED, never handed in, from [control] and what this school actually
   * published: empty in every case but one, a public school in a state this
   * family does not live in, and even there only for the in-state-only figures
   * this school reports. The two figures there are in-state figures with no
   * out-of-state counterpart published anywhere, so there is nothing to
   * substitute and nothing is substituted.
   *
   * A getter, unlike [shown] below: this list is at most two entries built from
   * two fields, so recomputing it per reader costs less than a lazy holder --
   * [shown] is cached because a FOLD over it runs per rendered figure.
   */
  val withheld: List<WithheldFigure> get() = withheldFiguresFor(control, publishedReported)

  /**
   * One published-price figure this school carries at the served year, or null.
   *
   * THE door onto [served], and the reason that field is private: every read
   * here is pinned to the ONE served year, so no caller can quote a 2020-21
   * books allowance beside a 2023-24 tuition and call the pair a budget.
   */
  fun publishedAmountOf(field: CostField): Int? = served.amountOf(field)

  /**
   * The ONE academic year this school's published-price figures are served at
   * (RFC 166 §3), read off the rows rather than off a Kotlin constant -- so a
   * Scorecard-only school honestly says 2022-23 where an IC_AY school says
   * 2023-24. Null when the school publishes no price at all
   * ([ServedFigures.servesNoPublishedPrice]).
   */
  val publishedPriceAcademicYear: String? get() = served.academicYear?.label

  /**
   * The academic year THIS school's figures of [group] describe, or null when it
   * serves none of them (RFC 166 §3 rule 4).
   *
   * Per college, because the store carries five academic years across two
   * sources: an IC_AY school says 2023-24 where a Scorecard-only school says
   * 2022-23, and a single Kotlin constant could only ever have said one of them.
   */
  fun academicYearOf(group: FigureGroup): String? =
    when (group) {
      FigureGroup.PUBLISHED_PRICE -> publishedPriceAcademicYear
      FigureGroup.BLENDED_AVERAGE -> blendedAverageAcademicYear
    }

  /**
   * True when this answer SHOWS the at-home arrangement, and so carries the `$0`
   * food-and-housing line that unicoach assumes (RFC 166 §7).
   *
   * The gate on the seventh basis fact, and it follows the LINE, not the total.
   * The `$0` is printed, and named on the wire as `assumed_by_unicoach`, the
   * moment the arrangement is rendered -- whether or not a total was settled for
   * it. Gated on a total, an at-home arrangement missing one other component
   * shipped the zero to a coach with nothing anywhere saying whose zero it was,
   * which is the one thing D17 exists to prevent.
   */
  val showsAtHomeArrangement: Boolean
    get() =
      breakdown
        ?.arrangements
        .orEmpty()
        .any { arrangement ->
          arrangement.arrangement == LivingArrangement.WITH_FAMILY &&
            arrangement.lines.any { it.origin == LineOrigin.ASSUMED_BY_UNICOACH }
        }

  /**
   * The tuition tiers this school actually publishes, in declaration order (RFC
   * 166 §4) -- the ONE derivation of "which tuition prices does this school
   * have", read from the rows through [publishedTuitionTiersOf].
   *
   * A door onto the private [served], pinned to its one year exactly as
   * [publishedAmountOf] is. Every surface that emits a tuition key,
   * counts the tiers, or words an invitation about them reads THIS -- a second
   * derivation is how the payload came to carry `residency_tiers` saying "not
   * all three tiers" beside an offer promising three prices.
   */
  val publishedTuitionTiers: List<CostField> get() = publishedTuitionTiersOf(served)

  /** The published in-state tuition and fees, as this school publishes it. */
  val tuitionAndFeesInStatePerYearUsd: Int? get() = publishedAmountOf(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD)

  /** The published out-of-state tuition and fees, as this school publishes it. */
  val tuitionAndFeesOutOfStatePerYearUsd: Int?
    get() = publishedAmountOf(CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD)

  /**
   * Every field this answer DATES: the published figures it reports, plus the
   * one line whose amount is OURS wherever it is shown (RFC 166 §7).
   *
   * NOT simply [reported]. The at-home food-and-housing line belongs to neither
   * published list -- the school neither reported it nor failed to -- but it IS
   * printed, and it IS dated: it takes the academic year of the budget it sits
   * in. A figure the payload renders with no academic year beside it is exactly
   * the defect the vintage labels exist against, and nothing else would have
   * caught it.
   */
  val datedFields: Set<CostField>
    get() =
      reported +
        breakdown
          ?.arrangements
          .orEmpty()
          .flatMap { it.lines }
          .filter { it.origin == LineOrigin.ASSUMED_BY_UNICOACH }
          .map { it.field }

  /** The withheld figures as a field set -- the shape every reader of [withheld] actually asks for. */
  val withheldFields: Set<CostField> get() = withheld.mapTo(mutableSetOf()) { it.field }

  /** The membership test [notReported] and [reported] both subtract with, so neither writes its own. */
  fun isWithheld(field: CostField): Boolean = field in withheldFields

  /**
   * WHY [field] carries no number for this family, or null when the answer is
   * not ours to give -- the school's own silence, or a figure that is shown.
   *
   * The lookup lives here rather than in each renderer, so no surface scans
   * [withheld] with its own predicate and none of them can disagree about which
   * blank belongs to which reason.
   */
  fun withheldReasonFor(field: CostField): WithheldReason? = withheld.firstOrNull { it.field == field }?.reason

  /**
   * WHY [field] carries no number in the STORE's own words, or null when this
   * answer holds no status for it (RFC 166 §6).
   *
   * The twin of [withheldReasonFor], and beside it for the same reason: the
   * lookup lives here rather than in each renderer, so no surface scans
   * [figureStatuses] with its own predicate. Every reason a blank has is now
   * reachable from ONE type by a surface that has a [CostField] -- which is what
   * the parent-facing report lacked while it printed "Not reported by this
   * school" over the publisher's suppression, over a gap of OURS, and over a
   * figure the school published in another academic year.
   */
  fun statusNoteFor(field: CostField): FigureStatusNote? = figureStatuses.firstOrNull { it.field == field }

  /**
   * Every field this answer carries NO number for, in enum declaration order:
   * the school's silence and our own withholding together (RFC 157 D-A).
   *
   * ONE list because the instruction it drives is one instruction -- say so
   * plainly, never estimate. WHICH reason applies is [withheld]'s to say, and
   * naming the union here is what stops a renderer and a wire builder each
   * deriving their own.
   */
  val fieldsWithNoAmount: List<CostField> get() = CostField.listInDeclarationOrder(notReported + withheldFields)

  /**
   * The school's own published cost of attendance as this family may see it:
   * null when the school reports none, and null when D-A holds it back.
   */
  val stickerCostOfAttendancePerYearUsd: Int? get() = shown.stickerCostOfAttendancePerYearUsd

  /**
   * The net price as this family may see it -- a [NetPrice.Withheld] carrying
   * the reason when D-A holds it back, so no reader can mistake our rule for the
   * school's silence.
   */
  val netPrice: NetPrice get() = shown.netPrice

  /**
   * The cost fields this college does not report, so the coach says so instead
   * of improvising.
   *
   * A figure in [withheld] is in NEITHER this list nor [reported]: the college
   * published it and we are holding it back from this family (RFC 157 D-A), so
   * calling it unreported would blame the price list for our own rule.
   */
  val notReported: List<CostField> get() = publishedNotReported.filterNot(::isWithheld)

  /**
   * The cost fields this college DOES carry a figure for and this family may be
   * shown -- the positive twin of [notReported], and exactly the set
   * [CollegeCostChatTool] renders for it.
   *
   * Not the complement of [notReported], and TWICE not: the two on-campus
   * components a no-dorms school suppresses (RFC 149 D-B) are in neither list,
   * and neither is a figure in [withheld]. Both are inapplicable to this reader
   * rather than silent, and neither is rendered.
   */
  val reported: Set<CostField> get() = publishedReported.filterNot(::isWithheld).toSet()

  /**
   * Whether the two BLENDED figures -- [stickerCostOfAttendancePerYearUsd] and
   * [netPrice] -- describe THIS family at this school (RFC 157 D-A/D-B).
   *
   * DERIVED from [control] by [blendedFigureApplicabilityOf], the rule that sits beside
   * [applicableTuitionFor], so ONE rule decides both which tuition column fills
   * an arrangement and whether the in-state-only figures are this family's.
   *
   * NOT the same question as [withheld]. That list says which figures were taken
   * away; this says whether the in-state basis describes this family at all,
   * which is still [BlendedFigureApplicability.WITHHELD] at a school that
   * publishes neither figure and therefore withholds nothing.
   */
  val blendedFiguresApply: BlendedFigureApplicability get() = blendedFigureApplicabilityOf(control)

  /**
   * The published figures with the withheld list folded through them, once.
   *
   * `by lazy` rather than a getter, so the fold runs once per answer however
   * many times a renderer asks.
   */
  private val shown: ShownFigures by lazy {
    withheld.fold(
      ShownFigures(collegeId, publishedStickerCostOfAttendancePerYearUsd, publishedNetPrice),
      ShownFigures::deleteFigure,
    )
  }
}

/**
 * The two blended amounts as this family may see them -- the ONE place a
 * withheld field is turned into an absent number.
 */
private data class ShownFigures(
  /**
   * The school this fold is for, so a refusal below names the ROW it fired on
   * -- the house standard [CollegeCostService] already holds itself to.
   */
  val collegeId: CollegeId,
  val stickerCostOfAttendancePerYearUsd: Int?,
  val netPrice: NetPrice,
) {
  /**
   * The same figures with this one's AMOUNT gone and every fact around it kept
   * (RFC 157 D-A).
   *
   * Exhaustive with no `else`: a field added to [CostField] must say what
   * withholding means for it HERE -- the one place the withheld list becomes an
   * absent number -- rather than being named in [CollegeCost.withheld] and in
   * `data_availability` while its figure is still printed.
   */
  fun deleteFigure(figure: WithheldFigure): ShownFigures =
    when (figure.field) {
      CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD -> {
        copy(stickerCostOfAttendancePerYearUsd = null)
      }

      CostField.NET_PRICE -> {
        copy(netPrice = netPriceWithheldFor(figure.reason))
      }

      // No IN_STATE_ONLY axis, so nothing here is ever withheld for one --
      // [WithheldFigure.of] cannot even build the pair.
      CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
      CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD,
      CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD,
      CostField.FEES_ONLY_IN_DISTRICT_PER_YEAR_USD,
      CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD,
      CostField.FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD,
      CostField.MEDIAN_DEBT_AT_COMPLETION_USD,
      CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD,
      CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD,
      CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD,
      CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD,
      CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD,
      CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD,
      CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD,
      CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD,
      -> {
        error(
          "this cost field carries no in-state-only residency axis and is never withheld: " +
            "college_id=[${collegeId.value}] field=[${figure.field.wireName}] " +
            "field_axis=[${figure.field.residency}] reason=[${figure.reason.value}] " +
            "reason_axis=[${figure.reason.axis}]",
        )
      }
    }

  /**
   * The net price with its number held back and the basis the family's own
   * answer selected kept (RFC 157 D-A) -- the net-price vocabulary one level
   * down, so the `when` above stays a field router.
   *
   * A figure already held back cannot be held back a SECOND time: the second
   * reason would vanish here without a word, and the withheld list naming one
   * field twice is a defect in the list, not a fact about the family.
   */
  private fun netPriceWithheldFor(reason: WithheldReason): NetPrice =
    when (netPrice) {
      is NetPrice.Reported -> {
        NetPrice.Withheld(netPrice.publishedBasis, reason)
      }

      is NetPrice.Withheld -> {
        error(
          "a net price is already withheld and cannot be withheld twice: " +
            "college_id=[${collegeId.value}] held=[${netPrice.reason.value}] second=[${reason.value}]",
        )
      }
    }
}

/**
 * WHY one field carries no amount, in the store's own words (RFC 166 §6).
 *
 * The code and the sentence travel together, from one home -- the `income_band`
 * + `income_band_label` convention RFC 151 D-D established and every coded fact
 * on this surface has followed since. A renderer never re-words a status, and a
 * status added to the vocabulary cannot ship without words, because
 * [FigureStatusCopy] refuses to compile without them.
 */
data class FigureStatusNote(
  val field: CostField,
  val status: FigureStatus,
  /** The sentence a coach says this status in; never null here, because a plainly reported figure has no note. */
  val statement: String,
  /**
   * The academic year this school's price is quoted at, and the year we hold
   * THIS figure for -- both non-null for a YEAR GAP and both null for every
   * other note (RFC 166 §3).
   *
   * They are fields because they are DATA, not decoration on a sentence. The
   * note used to carry the two years only inside
   * [FigureStatusCopy.yearGapStatementOf]'s prose, so a renderer or a coach
   * could learn which year we hold the figure for by substring-matching our own
   * English and no other way -- while the code beside the sentence said
   * `not_collected_by_us`, which reads as "we hold nothing". Carried here, the
   * payload can say "we hold Berkeley's books allowance for 2021-22" from data,
   * and a renderer can give the case its own treatment instead of printing a
   * thirty-word sentence into a table cell.
   */
  val servedAcademicYear: String? = null,
  val heldAcademicYear: String? = null,
) {
  init {
    require((servedAcademicYear == null) == (heldAcademicYear == null)) {
      "a year gap names BOTH years or neither: field=[${field.wireName}] " +
        "served_academic_year=[$servedAcademicYear] held_academic_year=[$heldAcademicYear]"
    }
  }

  /**
   * True when this note is a YEAR GAP: this school published the figure, at a
   * year that is not the one its price is quoted at.
   *
   * A named question rather than a [status] comparison, because [status] cannot
   * answer it -- a year gap and a cell we have genuinely never collected both
   * carry [FigureStatus.NOT_COLLECTED_BY_US].
   */
  val isYearGap: Boolean get() = heldAcademicYear != null
}

/** The money-profile field statuses echoed with every result, so the coach knows the history. */
data class MoneyProfileStatuses(
  val incomeBandStatus: AnswerStatus,
  val incomeBand: IncomeBand?,
  val residencyStatus: AnswerStatus,
  val residencyState: String?,
  /**
   * The family's USUAL plan (RFC 152) -- where the student would live when they
   * have the choice -- as the closed vocabulary rather than a status beside a
   * nullable plan: a reader cannot state a plan nobody gave, and a corrupt
   * answered-with-no-plan row cannot be re-labelled "never asked" here, which is
   * exactly the harm [CollegeCostService.requireIntactAnswers] exists to refuse.
   *
   * It is never the whole answer for a given school: a school with its own
   * `CollegeListEntry.livingPlan` overrides it, and the resolution lives in
   * exactly one helper, [CollegeCostService.plannedLivingPlanOf].
   */
  val living: ComparedLivingPlan,
)

/**
 * The full cost read for one student (RFC 135). [ingestYear] is the most recent
 * `colleges.updated_at` ingest year among the returned rows (null when
 * [colleges] is empty).
 */
data class CollegeCostProfile(
  val colleges: List<CollegeCost>,
  val unknownCollegeIds: List<CollegeId>,
  val moneyProfile: MoneyProfileStatuses,
  val ingestYear: Int?,
  /**
   * The assumptions a side-by-side holds constant (RFC 151), or NULL below two
   * colleges: a one-school answer is already fully labelled by its per-college
   * keys, and a comparison object on it would invite the coach to narrate a
   * comparison it is not making.
   *
   * See [ComparisonBasis] for why assembling it costs no query.
   */
  val comparisonBasis: ComparisonBasis?,
) {
  /**
   * The in-answer upgrade invitations (RFC 135, RFC 145) for one returned
   * [college] — derived, never stored, and in [PrecisionOffer]'s declaration
   * order, which is the order the coach should raise them: residency first,
   * because it is the cheaper question and the bigger correction (a median
   * $6,300/yr at a public college against ~$1,376 for a middle-band income
   * correction). Empty when this college has no upgrade to promise.
   *
   * The order is the enum's rather than this function's on purpose: filtering
   * [PrecisionOffer.entries] means adding a member in its intended slot is the
   * whole of adding an offer's position, and each member owns the rule for when
   * it applies ([PrecisionOffer.appliesTo]).
   */
  fun precisionOffersFor(college: CollegeCost): List<PrecisionOffer> = PrecisionOffer.entries.filter { it.appliesTo(moneyProfile, college) }
}

/**
 * How many arrangements a school must PRICE before asking where the family
 * plans to live can move a number they can see (RFC 152 D4).
 *
 * One priced arrangement -- or none -- leaves the family nothing to choose
 * between, so the offer is not made. Named rather than a digit inside the gate,
 * because the threshold IS the rule of the offer and a test asserting the
 * boundary must not have to repeat a literal.
 */
private const val MIN_PRICED_ARRANGEMENTS_FOR_LIVING_PLAN_OFFER = 2

/**
 * The upgrade invitations a cost result can carry (RFC 145), declared in the
 * order the coach should raise them — residency first, and that IS the wire
 * order, because [CollegeCostProfile.precisionOffersFor] filters [entries].
 * Each case names the `money_profiles` [field] it would fill and owns the rule
 * for when it is on offer; the sentence the coach may say lives with the
 * rendering, in [ed.unicoach.coaching.costs.CollegeCostChatTool]. The third
 * upgrade (RFC 152's living plan) arrived exactly that way -- a member here
 * plus a copy string there: it could not compile without deciding its own
 * [appliesTo], and could not ship without words.
 *
 * Every rule is keyed off [AnswerStatus.UNANSWERED] rather than off the absence
 * of a value, and that is the whole point: an offer derived from a missing
 * value would re-raise a closed topic on every cost answer, because a decline
 * leaves the value missing too. Keying off the status makes a decline permanent
 * for residency exactly as it already is for the income band. Each rule also
 * requires that this college reports the figure the upgrade would sharpen, so
 * an offer never rests on a college that reports nothing for it — but residency
 * is admitted on EITHER published tuition figure
 * ([CollegeCost.reportsPublishedTuition]), so it is the copy, not the rule, that
 * keeps that offer's promise no wider than the data.
 */
enum class PrecisionOffer(
  /**
   * The wire `field` name — the `update_money_profile` parameter this offer
   * would fill. `CollegeCostChatToolTest` binds these to that tool's own input
   * schema, so a rename there fails here rather than shipping an invitation
   * naming a parameter nothing accepts.
   */
  val field: String,
) {
  /**
   * Residency is on offer only at a public college (a private college has one
   * price, so the question buys nothing there), with residency
   * [AnswerStatus.UNANSWERED], and only when the college publishes tuition for
   * the answer to select.
   *
   * The [TuitionApplicable.UNKNOWN] term is deliberate redundancy, not logic
   * the status leaves undecided: it binds the offer to the `tuition_applicable`
   * label the SAME payload renders, so the coach's cue and its stated
   * justification can never diverge. [AnswerStatus.UNANSWERED] is the authority
   * — UNKNOWN covers unanswered AND declined alike, so keying off it would
   * reopen a declined topic on every cost answer.
   */
  RESIDENCY("residency_state") {
    /**
     * Exhaustive with no `else`, exactly as [CollegeCostService]'s `controlOf`
     * is: a safe cast would fold three sealed cases into one unstated default,
     * so a control added to the vocabulary would silently lose the offer. Here
     * it must fail to compile until it says whether residency selects a
     * tuition figure for it.
     */
    override fun appliesTo(
      moneyProfile: MoneyProfileStatuses,
      college: CollegeCost,
    ): Boolean =
      when (val control = college.control) {
        // The only case with two published prices for residency to choose between.
        is CollegeControl.Public -> {
          control.tuitionApplicable == TuitionApplicable.UNKNOWN &&
            moneyProfile.residencyStatus == AnswerStatus.UNANSWERED &&
            college.reportsPublishedTuition
        }

        // One price each, so the question buys the family nothing.
        CollegeControl.PrivateNonprofit -> {
          false
        }

        CollegeControl.PrivateForProfit -> {
          false
        }

        // Outside the Scorecard vocabulary: we cannot promise which price applies.
        is CollegeControl.Unrecognized -> {
          false
        }
      }
  },

  /** Unchanged from RFC 135: an unanswered band, and a college that reports at least one bracket column. */
  INCOME_BAND("income_band") {
    override fun appliesTo(
      moneyProfile: MoneyProfileStatuses,
      college: CollegeCost,
    ): Boolean = moneyProfile.incomeBandStatus == AnswerStatus.UNANSWERED && college.reportsBandPricing
  },

  /**
   * Where the family plans to live (RFC 152 D4), declared LAST: declaration
   * order is wire order, and residency and the income band change the NUMBER
   * more often, so they are still raised first.
   *
   * Keyed off [AnswerStatus.UNANSWERED] like its two siblings, for the reason
   * this enum's doc gives: a rule keyed off the missing VALUE would re-raise a
   * declined topic on every cost answer.
   *
   * And gated on this school having at least two arrangements that carry a
   * TOTAL -- priced, not merely present. An offer must never rest on a school
   * with nothing to choose between -- the [reportsBandPricing] /
   * [CollegeCost.reportsPublishedTuition] precedent -- and three arrangements
   * whose totals are all null give the family nothing to choose between just as
   * surely as one arrangement does: the answer would move no number they can
   * see.
   *
   * A consequence worth naming, because it is a behaviour and not an accident:
   * at a public college an unanswered or declined residency leaves the tuition
   * line null, so no arrangement carries a total and this offer does not apply.
   * That is the right order anyway -- residency is declared first here for
   * exactly the reason that it is the cheaper question and the bigger
   * correction -- but it means the living-plan question follows residency at a
   * public school rather than riding beside it.
   *
   * Deliberately NOT gated on this school's own override: the offer fills the
   * family's USUAL plan, which is a different fact from what they decided about
   * one school, and a school-level answer never closes the global question.
   */
  LIVING_PLAN("living_plan") {
    override fun appliesTo(
      moneyProfile: MoneyProfileStatuses,
      college: CollegeCost,
    ): Boolean =
      moneyProfile.living is ComparedLivingPlan.Unanswered &&
        college.breakdown
          ?.arrangements
          ?.count { it.totalPerYearUsd != null }
          .let { it != null && it >= MIN_PRICED_ARRANGEMENTS_FOR_LIVING_PLAN_OFFER }
  },
  ;

  /** True when this upgrade is on offer for [college], given the student's [moneyProfile]. */
  abstract fun appliesTo(
    moneyProfile: MoneyProfileStatuses,
    college: CollegeCost,
  ): Boolean
}

/**
 * Chat-free composition of the pieces RFC 133/134 landed (RFC 135): the
 * student's active college list, their money profile, and each college's cost
 * columns, folded into one [CollegeCostProfile]. Read-only — this service
 * writes nothing, ever.
 *
 * - An absent money-profile row is simply all-unanswered (RFC 134's
 *   NotFoundException-as-absence convention), not an error.
 * - The band -> `net_price_per_year_income_qN_usd` selection stays in its one home,
 *   [IncomeBand.netPriceFor].
 * - [collegeIds] filters to a subset of the active list; ids not on the list
 *   (unknown or another student's) are reported in
 *   [CollegeCostProfile.unknownCollegeIds] while known ones still answer —
 *   best-effort read, never all-or-nothing.
 */
class CollegeCostService(
  private val database: Database,
  /**
   * The canonical money store's read side, injected (RFC 166): every cost
   * figure this service answers with comes from here, so a caller -- above all
   * a test -- must be able to substitute the store rather than only Postgres.
   * Defaulted, so no root has to name it.
   */
  private val canonicalCostReader: CanonicalCostReader = DbCanonicalCostReader(database),
) {
  suspend fun getForStudent(
    studentId: StudentId,
    collegeIds: List<CollegeId>? = null,
  ): Result<CollegeCostProfile> =
    try {
      Result.success(database.withConnection { session -> readInSession(session, studentId, collegeIds) })
    } catch (e: CancellationException) {
      // Cancellation is the caller unwinding, not a read that failed: a
      // cancelled chat turn must not be logged as a database fault, reported to
      // the model as a read error, or stop propagating (the same rule as
      // [ed.unicoach.coaching.admissions.CollegeAdmissionsService]).
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * The same-session read RFC 155's report path names: the CALLER owns the
   * connection, so a caller that has already opened one — the Family Cost
   * Report resolves a share token first — reads the whole profile on that one
   * connection.
   *
   * The reason is ONE READ, ONE SNAPSHOT. Two connections are two points in
   * time, and the report would then be free to render a list that changed, or a
   * share that was revoked, between the token resolving and the figures being
   * read. What the caller authorised is what the caller reads. (It also avoids
   * nesting a second pool checkout inside the first, which is untidy — but that
   * page serves roughly one request per second, so a claim about pool
   * exhaustion would not be an honest reason.)
   *
   * Public because the only caller outside `:service` is `public-web`'s
   * [CostReportSource] adapter, which is an in-process port by D-E rather than
   * an HTTP hop. The full three-argument form stays `internal`: a college-id
   * filter is a chat concern, and the report always reads the whole list.
   */
  fun readInSession(
    session: SqlSession,
    studentId: StudentId,
  ): CollegeCostProfile = readInSession(session, studentId, collegeIds = null)

  /**
   * The whole read on ONE session, extracted so the batching contract above is
   * assertable: a test can hand this a session that counts the statements it
   * prepares and prove that a five-college list costs the same statements as a
   * one-college list. [getForStudent] is this function plus the connection and
   * the `Result` wrapper, and nothing else.
   */
  internal fun readInSession(
    session: SqlSession,
    studentId: StudentId,
    collegeIds: List<CollegeId>?,
  ): CollegeCostProfile {
    val selection = StudentCollegeSelection.read(session, studentId, collegeIds)
    val moneyProfile = moneyProfileOf(session, studentId)

    // One extra query for the whole answer, inside the SAME connection as
    // the cost read (RFC 148 D7): batched over the ids already selected,
    // so a fifty-school list still costs one merit read and not fifty.
    val meritById =
      CdsAdmissionsDao
        .listLatestMeritAid(session, selection.selected)
        .getOrThrow()
        .associateBy { it.collegeId }

    // The RFC 170 aid-policy read, batched the same way and on the same
    // connection: one query for the whole answer, whatever the list's size.
    val aidPolicyById =
      AidPolicyDao
        .listLatest(session, selection.selected)
        .getOrThrow()
        .associateBy { it.collegeId }

    // The RFC 175 borrowing read, batched the same way and on the same
    // connection. A sibling read rather than a widening of the aid-policy one:
    // that query is narrowed to the single aid scope its two averages share,
    // and borrowing has five -- one denominator per loan type.
    val borrowingById =
      BorrowingDao
        .listLatest(session, selection.selected)
        .getOrThrow()
        .associateBy { it.collegeId }

    // The no-dorms fact (RFC 149 D-B), on the SAME connection and batched over
    // the units already selected -- one IPEDS read for the whole answer, so a
    // fifty-school list still costs one statement here and not fifty. Joined by
    // ipeds_unit_id, which is the natural key both tables carry.
    val offersHousingByUnitId =
      CollegeIpedsDao
        .housingFlagsByIpedsUnitId(session, selection.colleges.map { it.ipedsUnitId })
        .getOrThrow()

    // The canonical money store, on the SAME connection and batched over the ids
    // already selected (RFC 166 §2): TWO statements for the whole answer,
    // whatever the size of the list, so a fifty-school list still costs two
    // reads here and not a hundred. This is the read that replaced
    // `SELECT * FROM colleges` as the source of every cost figure.
    val figuresById = canonicalCostReader.read(session, selection.selected)

    val costs =
      selection.map { college, entry ->
        costOf(
          college,
          entry,
          moneyProfile,
          meritById[college.id],
          aidPolicyById[college.id],
          borrowingById[college.id],
          offersHousingByUnitId[college.ipedsUnitId],
          // `getValue`, never a fabricated empty `CollegeFigures`: the reader's
          // contract is that every selected id gets an entry, empty or not
          // (`CanonicalCostReader.read`). Synthesising "no money data" at a
          // lookup miss would render EVERY figure for that college as "we have
          // not collected this yet" and say it to a family with no log line;
          // a contract that ever slips must fail loudly instead.
          figuresById.getValue(college.id),
        )
      }

    return CollegeCostProfile(
      colleges = costs,
      unknownCollegeIds = selection.unknown,
      moneyProfile = moneyProfile,
      ingestYear = ingestYearOf(selection.colleges),
      // Reads the per-college list above, so it is built after it. Why it costs
      // no query of its own is stated once, on [ComparisonBasis].
      comparisonBasis = ComparisonBasis.of(costs, moneyProfile),
    )
  }

  /** RFC 134's fallback convention: an absent money-profile row reads as all-unanswered, not an error. */
  private fun moneyProfileOf(
    session: SqlSession,
    studentId: StudentId,
  ): MoneyProfileStatuses {
    val result = MoneyProfilesDao.findActiveByStudent(session, studentId)
    return when {
      result.isSuccess -> {
        val p = result.getOrThrow()
        requireIntactAnswers(p)
        MoneyProfileStatuses(
          incomeBandStatus = p.incomeBandStatus,
          incomeBand = p.incomeBand,
          residencyStatus = p.residencyStatus,
          residencyState = p.residencyState,
          // Read into the closed vocabulary at the one boundary that can still
          // refuse a corrupt row: requireIntactAnswers above has already thrown
          // for an answered status with no stored plan, so no case here has to
          // invent a fallback.
          living = comparedLivingPlanOf(p),
        )
      }

      result.exceptionOrNull() is NotFoundException -> {
        ALL_UNANSWERED
      }

      else -> {
        throw result.exceptionOrNull()!!
      }
    }
  }

  /**
   * The stored (status, plan) pair read into the closed vocabulary, at the one
   * boundary that still holds both halves (RFC 152).
   *
   * An ANSWERED status with no stored plan is row corruption, and
   * [requireIntactAnswers] has already refused it one line above; the throw
   * here is this function's totality guard, never a second opinion -- and
   * deliberately never a fallback to [ComparedLivingPlan.Unanswered], which
   * would tell a family that answered that we never asked.
   *
   * It takes the whole row rather than the pair, and refuses in the shape the
   * DAOs and [requireStoredValueWhenAnswered] already use
   * ([CorruptPersistedValueException] naming the column AND the row): a guard
   * that fires because the impossible happened is exactly when an operator
   * needs the row id, so all three messages about this column read the same.
   */
  private fun comparedLivingPlanOf(profile: MoneyProfile): ComparedLivingPlan =
    when (profile.livingPlanStatus) {
      AnswerStatus.ANSWERED -> {
        ComparedLivingPlan.Answered(
          profile.livingPlan
            ?: throw CorruptPersistedValueException(
              "null",
              ValidationError.InvalidFormat(expected = "a value present when status is 'answered'"),
              location = "money_profiles.living_plan (row [${profile.id.value}])",
            ),
        )
      }

      AnswerStatus.UNANSWERED -> {
        ComparedLivingPlan.Unanswered
      }

      AnswerStatus.DECLINED -> {
        ComparedLivingPlan.Declined
      }
    }

  /**
   * Guards the `*_value_iff_answered_check` constraints of `db/schema/0046`
   * and `db/schema/0070` in code, for ALL THREE money-profile fields: an
   * answered status with no stored
   * value is row corruption, surfaced as [CorruptPersistedValueException]
   * naming the column and row (the DAO convention,
   * [ed.unicoach.coaching.CoachingService]'s `renderMoneyField` precedent).
   *
   * Residency is audited alongside the band because RFC 145 made its status
   * decision-bearing: a corrupt answered-with-no-state row would otherwise
   * render `tuition_applicable: "unknown"` AND withhold the residency offer
   * that exists to resolve it — the one state the coach cannot talk its way
   * out of. Never folded into an unknown label or a silently missing offer.
   *
   * The living plan is audited for the same reason (RFC 152): an answered row
   * with no stored plan would degrade to "no plan chosen", which reads exactly
   * like "never asked" -- and the coach would then ASK a family a question they
   * have already answered. A corrupt row is refused, never relabelled.
   */
  private fun requireIntactAnswers(profile: MoneyProfile) {
    requireStoredValueWhenAnswered(profile.incomeBandStatus, profile.incomeBand, "income_band", profile)
    requireStoredValueWhenAnswered(profile.residencyStatus, profile.residencyState, "residency_state", profile)
    requireStoredValueWhenAnswered(profile.livingPlanStatus, profile.livingPlan, "living_plan", profile)
  }

  /** One status/value pair, in the shared message shape: the column that is corrupt, and the row it is in. */
  private fun requireStoredValueWhenAnswered(
    status: AnswerStatus,
    storedValue: Any?,
    column: String,
    profile: MoneyProfile,
  ) {
    if (status == AnswerStatus.ANSWERED && storedValue == null) {
      throw CorruptPersistedValueException(
        "null",
        ValidationError.InvalidFormat(expected = "a value present when status is 'answered'"),
        location = "money_profiles.[$column] (row [${profile.id.value}])",
      )
    }
  }

  /** Assembles one college's [CollegeCost]; every rule lives in its named helper. */
  private fun costOf(
    college: College,
    entry: CollegeListEntry,
    moneyProfile: MoneyProfileStatuses,
    merit: CollegeMeritAid?,
    aidPolicyRow: CollegeAidPolicy?,
    borrowingRow: CollegeBorrowing?,
    offersOnCampusHousing: Boolean?,
    figures: CollegeFigures,
  ): CollegeCost {
    // The family's own answer, resolved ONCE: it selects the net-price row, the
    // vintage label that dates it, and the status spoken beside it, and those
    // three must describe the same row.
    val band = answeredBandOf(moneyProfile)
    val published = netPriceOf(figures, band)
    val control = controlOf(college, moneyProfile)
    // ONE year for this school's whole published price, chosen before a single
    // line is read (RFC 166 §3), and BOUND to the figures here: every read below
    // this line goes through [ServedFigures], so the composer cannot assemble a
    // total out of two reporting years and cannot be handed a year that is not
    // this school's. [MixedVintageArrangementException] is unreachable from the
    // read path by construction rather than by luck.
    val served = CostBreakdown.servedFiguresOf(figures, applicableTuitionFor(control))
    warnOnHousingContradiction(college, served, offersOnCampusHousing)
    warnOnBorrowingContradiction(college, borrowingRow)
    val breakdown = CostBreakdown.of(served, tuitionLineOf(served, control), offersOnCampusHousing)
    return CollegeCost(
      collegeId = college.id,
      name = college.name,
      city = college.city,
      state = college.state,
      control = control,
      listStatus = entry.status,
      // Every figure here is the one the SCHOOL published. RFC 157 D-A -- which
      // of them this family may be shown -- is applied by [CollegeCost] itself,
      // from the same `control` this call hands it, so this assembly reads
      // exactly as it did before that rule existed and no rule is smeared across
      // three argument positions.
      publishedStickerCostOfAttendancePerYearUsd =
        served.cohortOf(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, band = null)?.amountUsd,
      served = served,
      blendedAverageAcademicYear = served.blendedAverageVintage(band)?.label,
      residencyTiers = residencyTiersOf(served),
      figureStatuses = figureStatusesOf(served, published, band),
      publishedNetPrice = published,
      medianDebtAtCompletionUsd = served.cohortOf(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, band = null)?.amountUsd,
      medianEarnings10yAfterEntryUsd =
        served.cohortOf(CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD, band = null)?.amountUsd,
      reportsBandPricing = reportsBandPricing(served),
      reportsPublishedTuition = reportsPublishedTuition(served),
      // Both lists are statements about what this SCHOOL reports, and that does
      // not change with who is reading. [CollegeCost] subtracts the withheld
      // fields from both -- a withheld field belongs to neither, exactly as the
      // on-campus components suppressed at a no-dorms school belong to neither
      // (RFC 149 D-B) -- and reads the published set to decide what can be
      // withheld at all.
      publishedNotReported = notReportedOf(served, published, offersOnCampusHousing, band),
      publishedReported = reportedOf(served, published),
      breakdown = breakdown,
      offersOnCampusHousing = offersOnCampusHousing,
      // A row with no merit measure under it is a citation with no facts, which
      // is not data: [MeritPractice.from] returns null and the result degrades
      // to no merit sub-object at all, exactly like a school with no row. The
      // rule lives there, so both tools cannot disagree about a school's silence.
      meritAid = merit?.let { MeritPractice.from(college.name, it) },
      // A filing with no aid-policy fact under it is a citation with nothing to
      // cite: [AidPolicyPractice.from] returns null and the section is absent,
      // exactly as a school with no filing at all is.
      aidPolicy = aidPolicyRow?.let { AidPolicyPractice.from(college.name, it) },
      // The same rule for the same reason, in a type that carries the answer:
      // a filing whose borrowing block is empty is still a filing, and one
      // whose cells we could not read is our gap and not its silence.
      // [BorrowingCoverage.of] decides which of the four, once.
      borrowing = BorrowingCoverage.of(college.name, borrowingRow),
      // Two rules, two helpers, orchestrated here: WHICH plan applies (the
      // entry and the profile) is a different question from whether THIS
      // school prices it (the breakdown and the housing flag).
      chosen =
        pricedLivingPlanOf(
          plannedLivingPlanOf(entry, moneyProfile),
          breakdown,
          offersOnCampusHousing,
          control,
          served,
        ),
    )
  }

  /**
   * The ONE home for RFC 152 D2a's resolution: **override -> default -> none**.
   *
   * A living plan is two different facts wearing one name. _Preference_ ("we'd
   * rather he lived at home") is global and lives on the money profile.
   * _Feasibility_ ("he can only live at home if the school is commutable") is a
   * fact about the student-college PAIR and lives on the list entry. Our data
   * cannot decide feasibility and never will -- the Scorecard prices a commuter
   * category at essentially every school whether or not THIS student could
   * commute to it -- so the school's own plan wins wherever the family set one.
   *
   * It also reports WHERE the plan came from ([LivingPlanSource]), because the
   * two cases are two different sentences: "you told us this for this school"
   * versus "this is your usual plan, assumed here". `with_family` is never
   * inferred by us, so the assumed case must stay nameable.
   *
   * Pricing that plan is a SEPARATE job ([pricedLivingPlanOf]); [costOf]
   * orchestrates the two. The resolution reads only what the family said, so it
   * can be read and tested without dragging one school's price data through it.
   */
  private fun plannedLivingPlanOf(
    entry: CollegeListEntry,
    moneyProfile: MoneyProfileStatuses,
  ): PlannedLivingPlan? {
    entry.livingPlan?.let { return PlannedLivingPlan(it, LivingPlanSource.PER_COLLEGE) }
    // Exhaustive over the closed vocabulary, with no `else`: the answered case
    // is the only one that carries a plan, and a declined plan must stay
    // distinguishable from a plan nobody has been asked for.
    val default =
      when (val answer = moneyProfile.living) {
        is ComparedLivingPlan.Answered -> answer.plan
        ComparedLivingPlan.Unanswered, ComparedLivingPlan.Declined -> return null
      }
    return PlannedLivingPlan(default, LivingPlanSource.PROFILE_DEFAULT)
  }

  /**
   * Whether THIS school prices the resolved plan, and the reason when it does
   * not (RFC 152 D2a) -- the pricing half of the resolution [costOf] runs.
   *
   * THREE outcomes, because a school can fail to price a plan in two different
   * ways and they are not the same fact:
   *
   * - the arrangement carries a total: [ChosenLivingPlan.Priced], the one shape
   *   the coach leads with, and it always has a number;
   * - the arrangement is here but carries no total (RFC 149 D-C's labelled
   *   blank, or a tuition line waiting on residency):
   *   [ChosenLivingPlan.NoTotalHere]. A chosen plan with neither a number nor a
   *   statement about the missing one is the payload shape the tool description
   *   never describes, so the silence is stated rather than shipped blank;
   * - the arrangement is not here at all: [ChosenLivingPlan.NotPricedHere] with
   *   the [ArrangementGap] reason, which is a claim about what the SCHOOL
   *   published. Only this case may make that claim: saying "no published price
   *   for it" because a part is missing, or because WE do not yet know which
   *   tuition applies, would blame the school for our own gap (RFC 149 D-B).
   *
   * It never filters the breakdown: every arrangement stays in the payload, and
   * a school is never given a substituted arrangement or a neighbour's figure.
   */
  private fun pricedLivingPlanOf(
    planned: PlannedLivingPlan?,
    breakdown: CostBreakdown?,
    offersOnCampusHousing: Boolean?,
    control: CollegeControl,
    served: ServedFigures,
  ): ChosenLivingPlan {
    val (plan, source) = planned ?: return ChosenLivingPlan.NotChosen
    val arrangement =
      breakdown?.arrangements?.find { it.arrangement == plan }
        ?: return ChosenLivingPlan.NotPricedHere(plan, source, ArrangementGap.of(plan, offersOnCampusHousing))
    return if (arrangement.totalPerYearUsd != null) {
      ChosenLivingPlan.Priced(arrangement, source)
    } else {
      ChosenLivingPlan.NoTotalHere(arrangement, source, noTotalReasonOf(arrangement, control, served))
    }
  }

  /**
   * WHY a shown arrangement carries no total (RFC 152) -- decided here, where
   * the school's [control] and the family's own residency answer are both in
   * hand, and never by the renderer.
   *
   * Four causes, and only one of them is the school's:
   *
   * - a part of the price is missing: whose gap it is is decided by
   *   [missingPartReasonOf] -- [NoTotalReason.PART_NOT_COLLECTED_BY_US] when any
   *   missing part is ours, otherwise [NoTotalReason.PART_NOT_PUBLISHED];
   * - no published tuition figure applies because OUR residency question is
   *   still open at a public school: [NoTotalReason.AWAITING_RESIDENCY_ANSWER],
   *   a gap of ours that one question closes;
   * - no figure applies because this school's control is outside the vocabulary
   *   (RFC 143): [NoTotalReason.TUITION_APPLICABILITY_UNKNOWN], also ours, and
   *   no question the family can answer closes it.
   *
   * A missing line for an applicable tuition figure is a missing PART, so it is
   * routed through [missingPartReasonOf] like any other: the school's phrase only
   * when none of the blanks is ours.
   */
  private fun noTotalReasonOf(
    arrangement: ArrangementCost,
    control: CollegeControl,
    served: ServedFigures,
  ): NoTotalReason =
    when {
      arrangement.tuitionLine != null -> missingPartReasonOf(arrangement, served)
      applicableTuitionFor(control) != null -> missingPartReasonOf(arrangement, served)
      control is CollegeControl.Public -> NoTotalReason.AWAITING_RESIDENCY_ANSWER
      else -> NoTotalReason.TUITION_APPLICABILITY_UNKNOWN
    }

  /**
   * WHOSE gap the missing part is (RFC 166 §6 rule 1): the school's, or ours.
   *
   * The parts of this arrangement with no line are looked at one by one, and
   * the answer is [NoTotalReason.PART_NOT_COLLECTED_BY_US] as soon as one of
   * them is OURS -- a `not_collected_by_us` row, or a figure the school
   * published only in another academic year. Otherwise the part really is
   * absent from what was published and the school's phrase is the true one.
   *
   * OURS WINS OVER THE SCHOOL'S when both kinds of part are missing: telling a
   * family "the school does not publish it" while we are also holding a gap of
   * our own would put our name on none of it.
   */
  private fun missingPartReasonOf(
    arrangement: ArrangementCost,
    served: ServedFigures,
  ): NoTotalReason {
    val present = arrangement.lines.map { it.field }.toSet()
    val ours =
      arrangement.arrangement.components
        .filterNot { it in present }
        .any { field ->
          served.yearGapOf(field) != null ||
            // band = null: every component of an arrangement is a published
            // price, and no price address is band-selected.
            statusOf(field, served, band = null)?.let(FigureStatusCopy::noTotalReasonOf) ==
            NoTotalReason.PART_NOT_COLLECTED_BY_US
        }
    return if (ours) NoTotalReason.PART_NOT_COLLECTED_BY_US else NoTotalReason.PART_NOT_PUBLISHED
  }

  /**
   * Says the IPEDS/Scorecard disagreement out loud (RFC 149 D-B): the published
   * figures win and the flag still rides beside them, so a systematic
   * divergence must stay visible rather than merely be absorbed.
   *
   * A named step rather than ten lines inside [costOf], whose own contract is
   * that every rule lives in a helper: the guard, the wording and the evidence
   * are one subject and belong one level down from the composition.
   */
  private fun warnOnHousingContradiction(
    college: College,
    served: ServedFigures,
    offersOnCampusHousing: Boolean?,
  ) {
    if (!CostBreakdown.publishedOnCampusContradictsFlag(served, offersOnCampusHousing)) return
    logger.warn(
      "college=[{}] ipeds_unit_id=[{}] IPEDS offers_housing=false but the Scorecard publishes on-campus " +
        "figures [{}]; rendering the published on-campus arrangement and reporting the flag beside it",
      college.id.value,
      college.ipedsUnitId,
      publishedOnCampusFieldNames(served),
    )
  }

  /**
   * Says a filing's own borrowing contradiction out loud (RFC 175 D6), on the
   * [warnOnHousingContradiction] rule: the pair is withheld from the FAMILY --
   * that decision stands -- and never from the operator.
   *
   * `bin/fetch-cds-seed` drops such a block at ingest, so a pair that reaches
   * this read means that guard did not hold; a state nothing records is a state
   * nobody fixes.
   */
  private fun warnOnBorrowingContradiction(
    college: College,
    borrowingRow: CollegeBorrowing?,
  ) {
    val row = borrowingRow ?: return
    row.byLoanType.forEach { (loanType, figures) ->
      val contradiction = figures.borrowers as? BorrowerCounts.ContradictsGraduatingClass ?: return@forEach
      logger.warn(
        "college=[{}] cds_year=[{}] loan_type=[{}] the filing reports borrowers=[{}] against a graduating " +
          "class of [{}]; withholding the pair and rendering this loan type's average alone",
        college.id.value,
        row.academicYear.firstCalendarYear,
        loanType.slug,
        contradiction.borrowers,
        contradiction.graduatingClass,
      )
    }
  }

  /** The on-campus components this college publishes in spite of the flag -- the warning's evidence. */
  private fun publishedOnCampusFieldNames(served: ServedFigures): List<String> =
    LivingArrangement.ON_CAMPUS.exclusiveComponents
      .filter { served.amountOf(it) != null }
      .map { it.wireName }

  /** The basis selection (RFC 135): an answered band picks its bracket column; anything else is the overall average. */
  private fun netPriceOf(
    figures: CollegeFigures,
    band: IncomeBand?,
  ): NetPrice.Reported {
    // The band selects the ROW now, where it used to select a column (RFC 166
    // §8): the NPT4 band series files one `cohort_money_stats` row per band under
    // one measure. `value` is NUMERIC and may be negative by design -- aid
    // exceeding cost, which the lowest bands do most often -- so it is rounded to
    // whole dollars and never clamped.
    return if (band != null) {
      NetPrice.BandSpecific(band, figures.cohortOf(CostField.NET_PRICE, band)?.amountUsd)
    } else {
      NetPrice.OverallAverage(figures.cohortOf(CostField.NET_PRICE, band = null)?.amountUsd)
    }
  }

  /**
   * The income band the family ANSWERED, or null -- the one home for that
   * question, so the row served, the year printed beside it and the status
   * spoken about it are all selected by the same fact.
   */
  private fun answeredBandOf(moneyProfile: MoneyProfileStatuses): IncomeBand? =
    moneyProfile.incomeBand.takeIf { moneyProfile.incomeBandStatus == AnswerStatus.ANSWERED }

  /**
   * The cost domain's reading of a control: [InstitutionControl] (the one home
   * for the codes themselves) -> [CollegeControl], residency resolved on the
   * public case. Branching on the enum rather than the raw integers keeps the
   * literals 1/2/3 in that one file, and the `when` is exhaustive with no
   * `else` on purpose: a member added to the vocabulary must fail to compile
   * here — the one site that owes it a cost decision — instead of quietly
   * falling through to [CollegeControl.Unrecognized].
   */
  private fun controlOf(
    college: College,
    moneyProfile: MoneyProfileStatuses,
  ): CollegeControl =
    when (InstitutionControl.fromCode(college.control)) {
      InstitutionControl.PUBLIC -> CollegeControl.Public(tuitionApplicabilityOf(college, moneyProfile))
      InstitutionControl.PRIVATE_NONPROFIT -> CollegeControl.PrivateNonprofit
      InstitutionControl.PRIVATE_FOR_PROFIT -> CollegeControl.PrivateForProfit
      null -> CollegeControl.Unrecognized(college.control)
    }

  /**
   * Which published tuition figure applies at a public college, from residency
   * vs the college's state. The plain string equality is exact because both
   * sides are already the same normalised vocabulary: USPS two-letter codes —
   * the money profile normalises residency on write
   * ([ed.unicoach.coaching.moneyprofile.MoneyProfileService]'s `parseResidencyState`:
   * trim, uppercase, membership), and `colleges.state` is the ingested
   * Scorecard `STABBR`, which is canonical. Do not add ad-hoc case folding
   * here: a mismatch means a writer skipped normalisation, and hiding it would
   * misprice a family's tuition.
   */
  private fun tuitionApplicabilityOf(
    college: College,
    moneyProfile: MoneyProfileStatuses,
  ): TuitionApplicable {
    val residency = moneyProfile.residencyState.takeIf { moneyProfile.residencyStatus == AnswerStatus.ANSWERED }
    return when {
      residency == null -> TuitionApplicable.UNKNOWN
      residency == college.state -> TuitionApplicable.IN_STATE
      else -> TuitionApplicable.OUT_OF_STATE
    }
  }

  /** True when the college reports any bracket column, via the band -> column home ([IncomeBand.netPriceFor]). */
  private fun reportsBandPricing(served: ServedFigures): Boolean =
    IncomeBand.entries.any { served.cohortOf(CostField.NET_PRICE, it)?.amountUsd != null }

  /**
   * True when the college publishes at least one tuition figure — the residency
   * upgrade has something to select. EITHER figure admits the offer, not both,
   * and that is deliberate: residency decides WHICH price applies, so the
   * answer is worth having even at a half-reporting college, and a family
   * sorted onto the side this school does not report gets the ordinary
   * `data_availability` answer said plainly — which
   * [ed.unicoach.coaching.costs.CollegeCostChatTool.RESIDENCY_OFFER] promises in
   * words rather than promising a number. Tightening this to `&&` would drop
   * the offer for the majority of families it can still answer.
   */
  private fun reportsPublishedTuition(served: ServedFigures): Boolean =
    // Read off the ONE derivation of which tiers this school publishes
    // ([publishedTuitionTiersOf]), and then "not the district tier": a family's
    // answered STATE cannot select an in-district price, so a school publishing
    // only that tier has no upgrade to promise (RFC 166 §4). Expressed as the
    // rule rather than as a hand-listed pair, so a fourth tier is admitted or
    // excluded by what it IS.
    publishedTuitionTiersOf(served).any { it.residency != ResidencyAxis.IN_DISTRICT }

  /**
   * The recency the attribution quotes. `colleges.updated_at` is the row's
   * modification time — the last ingest that touched it — used as a proxy for
   * data vintage; it is *not* the Scorecard release year, which we do not
   * store. Taken over the returned rows only, so a subset read may report an
   * older year than the whole list: honest for what was answered. If a
   * non-ingest write ever touches `colleges`, this stops being a vintage at
   * all and the attribution must move to a real ingest column.
   */
  private fun ingestYearOf(colleges: Collection<College>): Int? = colleges.maxOfOrNull { it.updatedAt.atZone(ZoneOffset.UTC).year }

  companion object {
    private val logger = LoggerFactory.getLogger(CollegeCostService::class.java)

    private val ALL_UNANSWERED =
      MoneyProfileStatuses(
        incomeBandStatus = AnswerStatus.UNANSWERED,
        incomeBand = null,
        residencyStatus = AnswerStatus.UNANSWERED,
        residencyState = null,
        living = ComparedLivingPlan.Unanswered,
      )
  }
}

/*
 * The cost-domain derivations published for the ONE out-of-module caller,
 * exactly as [CollegeCostService.readInSession] was published for it: the
 * `public-web` report fixtures must build a `CollegeCost` this service could
 * really have produced. Top-level rather than members, because they are
 * questions about a `colleges` row and a control, not about a service
 * instance — and because the copies they replace had already begun to drift.
 */

/**
 * The tuition line for one arrangement (RFC 149 D-C): the published figure
 * this student's residency selects, reusing the existing
 * [TuitionApplicable] decision rather than making a second one.
 *
 * Null -- and so no arrangement total -- in exactly two cases:
 *
 * - residency is unanswered or declined at a PUBLIC college. A total that
 *   silently picked one residency would be a lie, and the payload already
 *   carries the residency [PrecisionOffer] that fixes it.
 * - the applicable figure is not published. A total missing its largest part
 *   is not a total.
 *
 * A private college has ONE price, so there is no residency question to
 * answer there and no `tuition_applicable` label on its result; the in-state
 * column is the one the Scorecard publishes it in. An [CollegeControl.Unrecognized]
 * control gets no line at all: outside the vocabulary we cannot say which
 * price applies, and inventing one is the failure this whole file is against.
 */
fun tuitionLineOf(
  served: ServedFigures,
  control: CollegeControl,
): CostLine? {
  val field = applicableTuitionFor(control) ?: return null
  // A school that publishes no price at all has no served year
  // ([ServedFigures.servesNoPublishedPrice]) and so no line -- stated as an
  // early return so the line this function builds carries a non-null year by
  // construction (RFC 166 §3).
  val year = served.academicYear?.label ?: return null
  return served.amountOf(field)?.let { CostLine(field, it, year, origin = LineOrigin.PUBLISHED) }
}

/**
 * Whether the two BLENDED figures -- the published price and the price after a
 * financial aid offer -- describe this family at this school (RFC 157 D-A/D-B).
 *
 * Beside [applicableTuitionFor] and not inside a renderer, because it is the
 * same decision about the same student and the same school: one rule decides
 * which tuition column fills an arrangement and whether the in-state-only
 * figures are this family's, so the two answers cannot drift apart.
 *
 * ONE expression, not a second `when`: the rule itself lives on
 * [ComparedTuition.blendedFiguresApply], the vocabulary [CollegeBlendedFigureBasis]
 * speaks, reached here through [comparedTuitionOf] -- the control -> vocabulary
 * map that already exists. The number withheld and the sentence that explains it
 * are therefore the same decision, and a control added to the vocabulary fails
 * to compile in exactly one place.
 *
 * THREE outcomes, and [BlendedFigureApplicability.BASIS_STATED] is not
 * [BlendedFigureApplicability.WITHHELD]: an open residency question withholds
 * NOTHING (D-B). Both figures print with their basis stated, because no answer
 * of ours is gated on a completed profile.
 */
internal fun blendedFigureApplicabilityOf(control: CollegeControl): BlendedFigureApplicability =
  comparedTuitionOf(control).blendedFiguresApply

/**
 * The figures this answer holds back from THIS family, and why (RFC 157 D-A).
 *
 * Empty except at a public school in a state the family does not live in, and
 * even there only for the figures this school actually REPORTS ([reported], as
 * [reportedOf] read them from the row BEFORE any withholding): a figure the row
 * does not carry is the school's own silence, and calling it withheld would tell
 * a family "this school publishes this figure" about a figure that does not
 * exist -- and would delete that silence from [CollegeCost.notReported], where
 * it belongs.
 *
 * The fields come from [CostField.IN_STATE_ONLY_FIELDS], derived from the
 * residency axis itself and already in declaration order, so a third
 * in-state-only figure added to the vocabulary is withheld by this same rule
 * rather than needing to be remembered here.
 */
internal fun withheldFiguresFor(
  control: CollegeControl,
  reported: Set<CostField>,
): List<WithheldFigure> =
  when (blendedFigureApplicabilityOf(control)) {
    BlendedFigureApplicability.WITHHELD -> {
      CostField.IN_STATE_ONLY_FIELDS.filter { it in reported }.map { field ->
        // A null here is a reason MISSING from the vocabulary, and dropping the
        // item would un-withhold the figure: it would print this school's
        // in-state number to a family the in-state basis does not describe, the
        // exact defect RFC 157 exists against. It is refused, as the sibling
        // impossible branch in [ShownFigures.deleteFigure] refuses its own.
        checkNotNull(WithheldFigure.of(field)) {
          "no withholding reason names this field's residency basis, so its figure would be shown to a " +
            "family it does not describe: field=[${field.wireName}] field_axis=[${field.residency}]"
        }
      }
    }

    // Shown: the basis describes this family, or the residency question is open
    // and an open question hides nothing (D-B).
    BlendedFigureApplicability.APPLIES, BlendedFigureApplicability.BASIS_STATED -> {
      emptyList()
    }
  }

/**
 * WHICH published tuition figure applies, decided from the control alone --
 * split out from [tuitionLineOf] because it answers a different question about
 * a different subject: this one is about the school and the student, the caller
 * is about what the school published.
 *
 * Null here means "no figure applies to this reader" -- an unanswered residency
 * at a public college, or a control outside the vocabulary. Null in
 * [tuitionLineOf] can also mean "the applicable figure is not published". Both
 * produce no line and no total, but they are not the same fact, and folding
 * them into one function made the payload's `data_availability` and
 * `precision_offer` answers look like one decision when they are two.
 *
 * Exhaustive with no `else`, exactly as `controlOf` is: a control added to the
 * vocabulary must fail to compile here rather than quietly lose its tuition line.
 */
fun applicableTuitionFor(control: CollegeControl): CostField? =
  when (control) {
    is CollegeControl.Public -> {
      when (control.tuitionApplicable) {
        TuitionApplicable.IN_STATE -> CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD
        TuitionApplicable.OUT_OF_STATE -> CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD
        TuitionApplicable.UNKNOWN -> null
      }
    }

    // One price, published in the in-state column, and no residency question
    // to answer.
    CollegeControl.PrivateNonprofit -> {
      CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD
    }

    CollegeControl.PrivateForProfit -> {
      CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD
    }

    // Outside the vocabulary we cannot say which price applies, and inventing
    // one is the failure this whole file is against.
    is CollegeControl.Unrecognized -> {
      null
    }
  }

/**
 * Every field this college carries a figure for, read through the ONE
 * primitive that owns the question ([CostField.figureAddress]).
 *
 * The SAME per-field decision [notReportedOf] makes, read the other way round
 * -- never a second ladder of null checks, and deliberately not
 * `entries - notReported`: the on-campus components suppressed at a no-dorms
 * school are absent from [CollegeCost.notReported] because they are
 * inapplicable, and they carry no figure either, so they belong in neither
 * list. [CostField.figureAddress] underneath is exhaustive, so a field
 * added tomorrow must gain a column there and cannot silently drop out of the
 * figures this call is said to report.
 */
fun reportedOf(
  served: ServedFigures,
  netPrice: NetPrice,
): Set<CostField> =
  CostField.entries
    .filter { isPublisherAnswerable(it, served) }
    .filterNot { isNotReported(it, served, computedAmountsOf(netPrice)) }
    .toSet()

/**
 * The unreported cost fields, in the shared field vocabulary ([CostField]).
 *
 * [offersOnCampusHousing] is read for one reason only (RFC 149 D-B): at a
 * school with no residence halls AND nothing on-campus published, the two
 * on-campus components are not silence, they are inapplicable -- the school
 * answered by having no dorms. Listing them would tell the coach "this school
 * does not report its on-campus housing cost" when the truth is "there is no
 * on-campus". That answer rides `offers_on_campus_housing` instead.
 *
 * When the school publishes an on-campus figure in spite of the flag, the
 * arrangement is rendered and nothing here is suppressed: a part missing from
 * a rendered arrangement is ordinary silence, and calling a published figure
 * unreported would be false about it either way.
 *
 * Top-level and public beside [reportedOf], its positive twin, for exactly the
 * same caller: `public-web`'s `FakeCostReportSource` built this list as
 * `entries - reported`, the complement [reportedOf] says in words is WRONG --
 * an inapplicable on-campus component, an assumed line and a price cell with no
 * row belong to NEITHER list. A fixture that re-decides what the read makes of
 * a school's figures is evidence about itself, not about the read.
 */
fun notReportedOf(
  served: ServedFigures,
  netPrice: NetPrice,
  offersOnCampusHousing: Boolean?,
  band: IncomeBand?,
): List<CostField> {
  val computed = computedAmountsOf(netPrice)

  // The two on-campus components are inapplicable -- not silent -- only at a
  // school the no-dorms flag actually suppresses: one with no residence halls
  // AND nothing on-campus published (RFC 149 D-B). When the school publishes
  // an on-campus figure anyway the arrangement IS rendered, so a part still
  // missing from it is ordinary silence and must be named. The rule is read
  // from [CostBreakdown], the one home for it, so the payload can never render
  // an arrangement it also calls inapplicable. Books and supplies is shared by
  // every arrangement and is never in this set.
  val inapplicable =
    if (CostBreakdown.isOnCampusSuppressed(served, offersOnCampusHousing)) {
      LivingArrangement.ON_CAMPUS.exclusiveComponents
    } else {
      emptySet()
    }

  // Enum declaration order, and every member considered: adding a CostField is
  // one edit (its address in `figureAddress`), not one edit plus a null check here
  // that nothing would have failed for forgetting.
  // A silence that is not the SCHOOL's is not `data_availability`'s to claim
  // (RFC 166 §6 rule 1). `data_availability` means "this college does not
  // report this cost field", so a figure the publisher suppressed, or one we
  // have simply not collected, is excluded from it and speaks through
  // [CollegeCost.figureStatuses] instead -- the reason beside the blank, in the
  // right owner's words. Folding either into this list is the misattribution
  // RFC 149 D-B forbids.
  return CostField.entries.filter { field ->
    field !in inapplicable &&
      isPublisherAnswerable(field, served) &&
      isNotReported(field, served, computed) &&
      isSchoolsOwnSilence(field, served, band)
  }
}

/**
 * Every field this answer carries no amount for AND holds a canonical status
 * for, with the status spoken (RFC 166 §6).
 *
 * A field with NO ROW AT ALL gets no entry: we hold no reason for it, and
 * inventing `not_collected_by_us` on its behalf would state a fact about our
 * own pipeline that no row supports. `data_availability` still names the
 * silence; this list names the ones we can explain.
 *
 * An IMPUTED figure is not here, and that is the point of it being a separate
 * list rather than a subset of the blanks: `imputed_by_publisher` is
 * value-bearing, so the figure is SHOWN -- with the publisher's-estimate
 * sentence beside it, never hidden.
 *
 * Top-level and public beside [reportedOf] and [notReportedOf], and for the same
 * reason: `public-web`'s `FakeCostReportSource` carried its own copy of this
 * walk, without the year-gap arm, without the shown/imputed rule and without the
 * band -- so the page's no-regression evidence was green against rules the real
 * read no longer applies.
 */
fun figureStatusesOf(
  served: ServedFigures,
  netPrice: NetPrice,
  band: IncomeBand?,
): List<FigureStatusNote> {
  val computed = computedAmountsOf(netPrice)
  return CostField.entries.mapNotNull { field ->
    // A figure this school published in ANOTHER academic year is not shown
    // here -- no total may mix years -- and it is not the school's silence
    // either. It is stated as ours, naming both years (RFC 166 §3). Checked
    // FIRST, because such a field has no row at the served year and would
    // otherwise fall out of every list this function walks.
    yearGapOf(field, served)?.let { return@mapNotNull it }
    val status = statusOf(field, served, band) ?: return@mapNotNull null
    val shown = !isNotReported(field, served, computed)
    // A shown figure needs a note only when its status qualifies the number
    // itself -- an imputed figure is the publisher's estimate, and a family
    // reading it as the school's own would be reading it wrong.
    if (shown && status != FigureStatus.IMPUTED_BY_PUBLISHER) return@mapNotNull null
    FigureStatusCopy.statementOf(status)?.let { FigureStatusNote(field, status, it) }
  }
}

/**
 * The note a YEAR GAP produces, or null when this field has no year gap (RFC
 * 166 §3).
 *
 * A year gap is a figure this college DOES publish, at an academic year that
 * is not the one its price is quoted at. Nothing about it is the school's
 * silence, and nothing about it may be dropped: the field appears in neither
 * published list, so without this note it would leave the payload with no key
 * and no reason -- the one shape this surface may never emit.
 */
private fun yearGapOf(
  field: CostField,
  served: ServedFigures,
): FigureStatusNote? {
  val servedYear = served.academicYear?.label ?: return null
  val held = served.yearGapOf(field) ?: return null
  return FigureStatusNote(
    field = field,
    status = FigureStatusCopy.YEAR_GAP_STATUS,
    statement = FigureStatusCopy.yearGapStatementOf(servedYear, held.academicYear.label),
    // The two years travel as DATA as well as inside the sentence: they are the
    // fact that distinguishes this case from a cell we have never collected,
    // and a consumer that can only reach them by parsing our English cannot
    // render the case at all (RFC 166 §3).
    servedAcademicYear = servedYear,
    heldAcademicYear = held.academicYear.label,
  )
}

/**
 * The canonical status behind one field for this college, or null when no row
 * answers for it -- the assumed at-home line, or a cell the fill has never
 * written.
 *
 * A COHORT field answers here too, through [CollegeFigures.statusOf]'s sealed
 * dispatch. Read through the price-only door it returned null for all four of
 * them, so a net price the Scorecard suppressed for privacy -- the commonest
 * absence in that series -- was published as the school's own silence with its
 * sentence dropped. [band] selects the row for the band-selected address, so
 * the status spoken is the status of the row this family was served.
 */
private fun statusOf(
  field: CostField,
  served: ServedFigures,
  band: IncomeBand?,
): FigureStatus? = served.statusOf(field, band)

/**
 * Whether this field's blank is the SCHOOL's own silence, and so nameable in
 * `data_availability`.
 *
 * A field with no row at all keeps today's meaning -- the answer is a silence
 * we cannot attribute, and the list has always carried it -- so only a row
 * whose status says the gap is the publisher's or ours is excluded.
 */
private fun isSchoolsOwnSilence(
  field: CostField,
  served: ServedFigures,
  band: IncomeBand?,
): Boolean {
  val status = statusOf(field, served, band) ?: return true
  return FigureStatusCopy.isSchoolsOwnSilence(status)
}

/**
 * The band-selected cohort fields, whose amount is computed here rather than
 * read from a row, and the computed figure that answers for each.
 *
 * [cohortIsNotReported] refuses rather than guesses: a band-selected field
 * missing from this map fails loudly on the first read -- naming the field
 * and the row -- instead of reporting a computed figure as a silence the
 * college never kept. One table, read by both the silence list and its
 * positive twin, so the two can never disagree about one figure.
 */
private fun computedAmountsOf(netPrice: NetPrice): Map<CostField, Int?> = mapOf(CostField.NET_PRICE to netPrice.amount)

/**
 * Whether this field belongs in either published list at all -- the third
 * category [CollegeCost] already knows how to hold (RFC 149 D-B's inapplicable
 * on-campus components are the precedent).
 *
 * FALSE in exactly two cases, and both would otherwise be a claim we cannot
 * make:
 *
 * - the at-home food-and-housing line, whose amount is OURS. It is not a figure
 *   the school published and not a figure the school withheld, so naming it in
 *   either list would attribute our assumption to somebody else.
 * - a price cell with NO CANONICAL ROW AT ALL. `data_availability` means "this
 *   college does not report this cost field", and a cell the fill has never
 *   written for this college does not license that sentence -- most of the
 *   corpus has no `fees_only` or `in_district` row, and listing every one of
 *   them as the school's silence would blame ~6,000 price lists for our own
 *   source coverage.
 *
 * NO ROW AT ALL is the test, not "no row at the served year". A cell with a
 * VALUE-FREE row in another year IS answered for -- [CollegeFigures.statusOf]
 * falls through to that row's own status ([CollegeFigures.yearGapOf] declines
 * it, because we hold no figure for that year either) -- so we do hold a
 * publisher's word about the cell and it belongs in the lists this predicate
 * gates. Reading only the served year here dropped that recovered status:
 * [isSchoolsOwnSilence] never got to route it, and a `not_reported_by_institution`
 * one year over left the field in neither published list at all.
 *
 * A COHORT field with no row keeps today's meaning and stays in the list: those
 * four cells are written for every college the Scorecard fill touches, so an
 * absent one really is a college the source is silent about.
 */
private fun isPublisherAnswerable(
  field: CostField,
  served: ServedFigures,
): Boolean =
  when (val address = field.figureAddress) {
    // band = null: every price address is a published price, and no price
    // address is band-selected, so the band cannot change a price's status.
    is FigureAddress.Price -> served.priceAt(address.address) != null || served.statusOf(field, band = null) != null

    is FigureAddress.Cohort -> true

    FigureAddress.AssumedByUnicoach -> false
  }

/**
 * Whether this college is silent about ONE field -- a question about a field,
 * split out from the list-shaped decisions above it.
 *
 * A ROUTER over [CostField.figureAddress], and no more than that: a price field
 * answers from its row at the served year, a cohort field through
 * [cohortIsNotReported], and our own assumed line is never a silence. The rule
 * for each address lives one level down, where a reader looking for it can see
 * the whole of it.
 */
private fun isNotReported(
  field: CostField,
  served: ServedFigures,
  computed: Map<CostField, Int?>,
): Boolean =
  when (val address = field.figureAddress) {
    // A published price: the row at the served year answers, and a reading that
    // bears no value IS the blank -- whoever's blank it is.
    is FigureAddress.Price -> {
      served.priceAt(address.address)?.amountUsd == null
    }

    // A cohort statistic has no price row to be silent with, so the rule for one
    // lives one level down.
    is FigureAddress.Cohort -> {
      cohortIsNotReported(field, address.address, served, computed)
    }

    // Ours, and always present: the at-home food-and-housing zero is never a
    // silence, because there is no publisher whose silence it could be.
    FigureAddress.AssumedByUnicoach -> {
      false
    }
  }

/**
 * A cohort figure's blank: the BAND-SELECTED ones answer from [computed], and
 * the rest from their own row.
 *
 * The two paths read two different things, and neither may read the other's:
 * the band-less row served where the band's row was meant is the exact defect
 * this file's band discipline exists against. Written as one `if` with a single
 * read on each side rather than a read taken before the branch and discarded on
 * one of them -- a discarded read is a live wrong value kept dead only by the
 * branch above it.
 */
private fun cohortIsNotReported(
  field: CostField,
  address: CohortAddress,
  served: ServedFigures,
  computed: Map<CostField, Int?>,
): Boolean {
  if (!address.bandSelected) return served.cohortOf(address, band = null)?.amountUsd == null
  requireComputedAnswer(field, served, computed)
  return computed[field] == null
}

/**
 * Refuses a band-selected cohort field that no computed figure answers for: a
 * MISSING key is a programming error, not a silence, and is named as one.
 *
 * `containsKey` rather than `?:`, because a present null is the legitimate "the
 * computed figure is not reported" case -- and the message carries the field,
 * the college and what the map did hold, because the stdlib "Key X is missing in
 * the map" says nothing about which cost read produced it.
 */
private fun requireComputedAnswer(
  field: CostField,
  served: ServedFigures,
  computed: Map<CostField, Int?>,
) {
  require(computed.containsKey(field)) {
    "cost field [${field.wireName}] is band-selected and no computed figure answers for it: " +
      "college_id=[${served.collegeId.value}] " +
      "computed_fields=[${computed.keys.joinToString(", ") { it.wireName }}]"
  }
}
