package ed.unicoach.coaching.costs

import ed.unicoach.coaching.StudentCollegeSelection
import ed.unicoach.coaching.admissions.MeritPractice
import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CdsAdmissionsDao
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.College
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.CollegeMeritAid
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
  val tuitionAndFeesInStatePerYearUsd: Int?,
  val tuitionAndFeesOutOfStatePerYearUsd: Int?,
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
   * Derived in [CollegeCostService] from [CostField.reportedAmountOf], the one
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

      // No residency axis, so nothing here is ever withheld for one --
      // [WithheldFigure.of] cannot even build the pair.
      CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
      CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD,
      CostField.MEDIAN_DEBT_AT_COMPLETION_USD,
      CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD,
      CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD,
      CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD,
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

    // The no-dorms fact (RFC 149 D-B), on the SAME connection and batched over
    // the units already selected -- one IPEDS read for the whole answer, so a
    // fifty-school list still costs one statement here and not fifty. Joined by
    // ipeds_unit_id, which is the natural key both tables carry.
    val offersHousingByUnitId =
      CollegeIpedsDao
        .housingFlagsByIpedsUnitId(session, selection.colleges.map { it.ipedsUnitId })
        .getOrThrow()

    val costs =
      selection.map { college, entry ->
        costOf(college, entry, moneyProfile, meritById[college.id], offersHousingByUnitId[college.ipedsUnitId])
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
    offersOnCampusHousing: Boolean?,
  ): CollegeCost {
    val published = netPriceOf(college, moneyProfile)
    val control = controlOf(college, moneyProfile)
    warnOnHousingContradiction(college, offersOnCampusHousing)
    val breakdown = CostBreakdown.of(college, tuitionLineOf(college, control), offersOnCampusHousing)
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
      publishedStickerCostOfAttendancePerYearUsd = college.costOfAttendancePerYearUsd,
      tuitionAndFeesInStatePerYearUsd = college.tuitionAndFeesInStatePerYearUsd,
      tuitionAndFeesOutOfStatePerYearUsd = college.tuitionAndFeesOutOfStatePerYearUsd,
      publishedNetPrice = published,
      medianDebtAtCompletionUsd = college.medianDebtAtCompletionUsd,
      medianEarnings10yAfterEntryUsd = college.medianEarnings10yAfterEntryUsd,
      reportsBandPricing = reportsBandPricing(college),
      reportsPublishedTuition = reportsPublishedTuition(college),
      // Both lists are statements about what this SCHOOL reports, and that does
      // not change with who is reading. [CollegeCost] subtracts the withheld
      // fields from both -- a withheld field belongs to neither, exactly as the
      // on-campus components suppressed at a no-dorms school belong to neither
      // (RFC 149 D-B) -- and reads the published set to decide what can be
      // withheld at all.
      publishedNotReported = notReportedOf(college, published, offersOnCampusHousing),
      publishedReported = reportedOf(college, published),
      breakdown = breakdown,
      offersOnCampusHousing = offersOnCampusHousing,
      // A row with no merit measure under it is a citation with no facts, which
      // is not data: [MeritPractice.from] returns null and the result degrades
      // to no merit sub-object at all, exactly like a school with no row. The
      // rule lives there, so both tools cannot disagree about a school's silence.
      meritAid = merit?.let { MeritPractice.from(college.name, it) },
      // Two rules, two helpers, orchestrated here: WHICH plan applies (the
      // entry and the profile) is a different question from whether THIS
      // school prices it (the breakdown and the housing flag).
      chosen =
        pricedLivingPlanOf(plannedLivingPlanOf(entry, moneyProfile), breakdown, offersOnCampusHousing, control),
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
  ): ChosenLivingPlan {
    val (plan, source) = planned ?: return ChosenLivingPlan.NotChosen
    val arrangement =
      breakdown?.arrangements?.find { it.arrangement == plan }
        ?: return ChosenLivingPlan.NotPricedHere(plan, source, ArrangementGap.of(plan, offersOnCampusHousing))
    return if (arrangement.totalPerYearUsd != null) {
      ChosenLivingPlan.Priced(arrangement, source)
    } else {
      ChosenLivingPlan.NoTotalHere(arrangement, source, noTotalReasonOf(arrangement, control))
    }
  }

  /**
   * WHY a shown arrangement carries no total (RFC 152) -- decided here, where
   * the school's [control] and the family's own residency answer are both in
   * hand, and never by the renderer.
   *
   * Three causes, and only one of them is the school's:
   *
   * - the tuition line is present, so what is missing is a component the school
   *   did not publish: [NoTotalReason.PART_NOT_PUBLISHED];
   * - no published tuition figure applies because OUR residency question is
   *   still open at a public school: [NoTotalReason.AWAITING_RESIDENCY_ANSWER],
   *   a gap of ours that one question closes;
   * - no figure applies because this school's control is outside the vocabulary
   *   (RFC 143): [NoTotalReason.TUITION_APPLICABILITY_UNKNOWN], also ours, and
   *   no question the family can answer closes it.
   *
   * A missing line for an applicable tuition figure IS the school's silence, so
   * it falls to [NoTotalReason.PART_NOT_PUBLISHED] with the components: the
   * applicable figure was known and the school did not publish it.
   */
  private fun noTotalReasonOf(
    arrangement: ArrangementCost,
    control: CollegeControl,
  ): NoTotalReason =
    when {
      arrangement.tuitionLine != null -> NoTotalReason.PART_NOT_PUBLISHED
      applicableTuitionFor(control) != null -> NoTotalReason.PART_NOT_PUBLISHED
      control is CollegeControl.Public -> NoTotalReason.AWAITING_RESIDENCY_ANSWER
      else -> NoTotalReason.TUITION_APPLICABILITY_UNKNOWN
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
    offersOnCampusHousing: Boolean?,
  ) {
    if (!CostBreakdown.publishedOnCampusContradictsFlag(college, offersOnCampusHousing)) return
    logger.warn(
      "college=[{}] ipeds_unit_id=[{}] IPEDS offers_housing=false but the Scorecard publishes on-campus " +
        "figures [{}]; rendering the published on-campus arrangement and reporting the flag beside it",
      college.id.value,
      college.ipedsUnitId,
      publishedOnCampusFieldNames(college),
    )
  }

  /** The on-campus components this college publishes in spite of the flag -- the warning's evidence. */
  private fun publishedOnCampusFieldNames(college: College): List<String> =
    LivingArrangement.ON_CAMPUS.exclusiveComponents
      .filter { it.amountOn(college) != null }
      .map { it.wireName }

  /** The basis selection (RFC 135): an answered band picks its bracket column; anything else is the overall average. */
  private fun netPriceOf(
    college: College,
    moneyProfile: MoneyProfileStatuses,
  ): NetPrice.Reported {
    val band = moneyProfile.incomeBand.takeIf { moneyProfile.incomeBandStatus == AnswerStatus.ANSWERED }
    return if (band != null) {
      NetPrice.BandSpecific(band, band.netPriceFor(college))
    } else {
      NetPrice.OverallAverage(college.netPricePerYearUsd)
    }
  }

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
  private fun reportsBandPricing(college: College): Boolean = IncomeBand.entries.any { it.netPriceFor(college) != null }

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
  private fun reportsPublishedTuition(college: College): Boolean =
    college.tuitionAndFeesInStatePerYearUsd != null || college.tuitionAndFeesOutOfStatePerYearUsd != null

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
   */
  private fun notReportedOf(
    college: College,
    netPrice: NetPrice,
    offersOnCampusHousing: Boolean?,
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
      if (CostBreakdown.isOnCampusSuppressed(college, offersOnCampusHousing)) {
        LivingArrangement.ON_CAMPUS.exclusiveComponents
      } else {
        emptySet()
      }

    // Enum declaration order, and every member considered: adding a CostField is
    // one edit (its column in `reportedAmountOf`), not one edit plus a null check here
    // that nothing would have failed for forgetting.
    return CostField.entries.filter { field -> field !in inapplicable && isNotReported(field, college, computed) }
  }

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
  college: College,
  control: CollegeControl,
): CostLine? {
  val field = applicableTuitionFor(control) ?: return null
  return field.amountOn(college)?.let { CostLine(field, it) }
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
 * primitive that owns the question ([CostField.reportedAmountOf]).
 *
 * The SAME per-field decision [notReportedOf] makes, read the other way round
 * -- never a second ladder of null checks, and deliberately not
 * `entries - notReported`: the on-campus components suppressed at a no-dorms
 * school are absent from [CollegeCost.notReported] because they are
 * inapplicable, and they carry no figure either, so they belong in neither
 * list. [CostField.reportedAmountOf] underneath is exhaustive, so a field
 * added tomorrow must gain a column there and cannot silently drop out of the
 * figures this call is said to report.
 */
fun reportedOf(
  college: College,
  netPrice: NetPrice,
): Set<CostField> = CostField.entries.filterNot { isNotReported(it, college, computedAmountsOf(netPrice)) }.toSet()

/**
 * The fields that are NOT a `colleges` column, and the computed figure that
 * answers for each.
 *
 * [isNotReported] refuses rather than guesses: a new column-less [CostField]
 * that nobody added here fails loudly on the first read -- naming the field
 * and the row -- instead of reporting a computed figure as a silence the
 * college never kept. One table, read by both the silence list and its
 * positive twin, so the two can never disagree about one figure.
 */
private fun computedAmountsOf(netPrice: NetPrice): Map<CostField, Int?> = mapOf(CostField.NET_PRICE to netPrice.amount)

/**
 * Whether this college is silent about ONE field -- a question about a field,
 * split out from the list-shaped decisions above it.
 *
 * A field with a column answers from it. A field with no column has nothing
 * to be silent with, so [computed] is the only thing that can answer for it;
 * a MISSING key there is a programming error, not a silence, and is named as
 * one. `containsKey` rather than `?:`, because a present null is the
 * legitimate "the computed figure is not reported" case -- and the message
 * carries the field, the row and what the map did hold, because the stdlib
 * "Key X is missing in the map" says nothing about which cost read produced
 * it.
 */
private fun isNotReported(
  field: CostField,
  college: College,
  computed: Map<CostField, Int?>,
): Boolean =
  when (val reported = field.reportedAmountOf(college)) {
    is ReportedAmount.Column -> {
      reported.amountUsd == null
    }

    ReportedAmount.NoColumn -> {
      require(computed.containsKey(field)) {
        "cost field [${field.wireName}] is not a `colleges` column and no computed figure answers for it: " +
          "college_id=[${college.id.value}] ipeds_unit_id=[${college.ipedsUnitId}] " +
          "computed_fields=[${computed.keys.joinToString(", ") { it.wireName }}]"
      }
      computed[field] == null
    }
  }
