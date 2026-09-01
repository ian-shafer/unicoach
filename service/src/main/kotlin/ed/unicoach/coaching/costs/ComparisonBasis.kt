package ed.unicoach.coaching.costs

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.util.phraseOf
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.LivingArrangement

/**
 * The five facts that make a multi-school cost table honest (RFC 151): whose
 * price these figures are, the residency held constant, the living arrangement
 * held constant, the academic year the figures come from, and what "aid" means
 * inside them.
 *
 * PER CALL, not per college (D-A). Four of the five are identical for every
 * school a call answers, and repeating them beside each school invites the coach
 * to read one school's copy as that school's own caveat. Residency is genuinely
 * per school -- a public school in the family's state and one outside it do not
 * share a tuition line -- so it rides as [ResidencyBasis.byCollege] INSIDE the
 * per-call object.
 *
 * Every fact carries a stable CODE and the SPOKEN STATEMENT that goes with it
 * (D-D, the repo's `income_band` + `income_band_label` convention). The
 * statements are computed from the codes rather than stored beside them, so the
 * coach can never be handed a sentence that disagrees with the code it labels.
 * They speak the RFC 141 money vocabulary throughout -- tuition and fees,
 * housing and food, the published price, a financial aid offer -- and nothing in
 * them ever subtracts a loan.
 *
 * Assembled by [of] from facts [CollegeCostService] already holds and rendered
 * by [CollegeCostChatTool] (D-C): no new query, so the batching contract of
 * `readInSession` is unchanged.
 */
data class ComparisonBasis(
  val population: PopulationBasis,
  val residency: ResidencyBasis,
  val livingArrangement: ArrangementBasis,
  val academicYears: List<DatedFigures>,
  val aid: AidBasis,
) {
  /**
   * EVERY per-call statement this basis makes, in the order they must be said.
   *
   * The list exists so a renderer cannot hand-pick a subset. A sixth honesty
   * statement added to this object arrives at every surface that renders the
   * basis — the coach in chat AND the parent-facing report page — rather than
   * reaching only the reader whose file happened to be edited. Withholding one
   * of these from a parent is precisely the failure the basis exists to prevent,
   * so no reader gets to choose which ones it prints.
   *
   * Read off the fields rather than stored beside them: a statement can never
   * disagree with the code it labels.
   */
  val statements: List<String>
    get() =
      buildList {
        add(population.statement)
        add(residency.statement)
        add(livingArrangement.statement)
        academicYears.forEach { add(it.statement) }
        add(aid.statement)
      }

  companion object {
    /**
     * The basis for one call, or NULL below two colleges (D-B).
     *
     * A one-school answer is already fully labelled by RFC 149's per-college
     * keys, and a "comparison" object on it would invite the coach to narrate a
     * comparison it is not making. The count is the gate, and an absent key --
     * never an empty object -- is the payload's own
     * never-emit-an-empty-container convention.
     */
    fun of(
      colleges: List<CollegeCost>,
      moneyProfile: MoneyProfileStatuses,
    ): ComparisonBasis? {
      if (colleges.size < 2) return null
      return ComparisonBasis(
        population = PopulationBasis,
        residency = ResidencyBasis.of(colleges, moneyProfile),
        livingArrangement = ArrangementBasis.of(colleges, moneyProfile),
        academicYears = DatedFigures.of(colleges),
        aid = AidBasis,
      )
    }
  }
}

/**
 * WHOSE price these figures are (RFC 151): the Scorecard's cost and net-price
 * figures describe first-year, full-time students who received federal aid.
 *
 * A constant, because it is a property of the source rather than of this
 * student's list -- but it is stated on every comparison, because a column of
 * dollars with no population attached reads as a quote for this family.
 */
data object PopulationBasis {
  /** A compile-time constant so a test, and the tool description, can name the wire code without a fixture. */
  const val CODE: String = "first_time_full_time_aid_recipients"

  /**
   * The same code under the property name every other basis fact carries
   * ([ComparedTuition.code]), so the renderer reads ONE shape across all five
   * facts rather than a constant here and a property there.
   */
  val code: String get() = CODE

  val statement: String
    get() = "These are averages for first-year, full-time students who received federal aid - not a quote for this family."
}

/**
 * What "aid" means in a net price (RFC 151): grants and scholarships, and
 * nothing else.
 *
 * The one fact the payload has never carried. The definition is the one in
 * `db/schema/0059`'s column comment, said in the RFC 141 vocabulary -- and it
 * states the rule the whole cost domain rests on: loans and work-study change
 * who pays and when, so they never come off a price.
 */
data object AidBasis {
  /** A compile-time constant so a test, and the tool description, can name the wire code without a fixture. */
  const val CODE: String = "grants_and_scholarships_only"

  /** The same code under the property name every other basis fact carries -- see [PopulationBasis.code]. */
  val code: String get() = CODE

  val statement: String
    get() =
      "A net price is the published price minus the average grants and scholarships the school gave. " +
        "Loans and work-study are never subtracted."
}

/**
 * WHICH published tuition figure a comparison holds constant at one school
 * (RFC 151).
 *
 * A sealed vocabulary rather than one enum, for the same reason
 * [CollegeControl] is one: a private school has ONE published price, so a
 * residency-specific code on it is unrepresentable here rather than merely
 * unwritten. [Public] reuses the [TuitionApplicable] decision the payload
 * already renders, so a school's `tuition_applicable` key and its comparison
 * entry can never disagree.
 */
sealed interface ComparedTuition {
  /** The stable wire code, derived from the case rather than stored beside it. */
  val code: String

  /**
   * Whether this school is KNOWN to publish exactly one price for everyone.
   *
   * A member of the vocabulary rather than an equality test at the two sites
   * that ask: a case added here must answer it to compile, where
   * `== SinglePublishedPrice` would have silently answered `false` for it and
   * picked the wrong residency sentence.
   */
  val publishesOnePriceForEveryone: Boolean

  /** A public school: residency selects between two published figures. */
  data class Public(
    val tuitionApplicable: TuitionApplicable,
  ) : ComparedTuition {
    override val code: String get() = tuitionApplicable.value

    override val publishesOnePriceForEveryone: Boolean get() = false
  }

  /** A private school: one published price, so where the family lives changes nothing there. */
  data object SinglePublishedPrice : ComparedTuition {
    override val code: String get() = "single_published_price"

    override val publishesOnePriceForEveryone: Boolean get() = true
  }

  /**
   * A control outside the Scorecard vocabulary ([CollegeControl.Unrecognized]).
   * We cannot say which published price applies, and inventing one is the
   * failure the cost domain exists against -- so the comparison says exactly
   * that, rather than dropping the school from the array, which would be a
   * silence with no words on it (D-F).
   *
   * [sourceControl] is the control case that arrived, kept because WHICH value
   * defeated this school's residency line is the whole of what an operator can
   * act on -- and the rest of this payload keeps it too, as
   * [CollegeControl.Unrecognized.label] ("unknown (control [9])"), which is the
   * form it goes on the wire in: a bare code under a key of its own is the one
   * thing RFC 143's guard exists to stop.
   */
  data class PublishedPriceUnknown(
    val sourceControl: CollegeControl.Unrecognized,
  ) : ComparedTuition {
    override val code: String get() = "published_price_unknown"

    /** Unknown is not one price: we never recognised what kind of school this is. */
    override val publishesOnePriceForEveryone: Boolean get() = false
  }
}

/** One school's residency line inside the comparison: the code, and the sentence the coach may say. */
data class CollegeResidencyBasis(
  val collegeId: CollegeId,
  val name: String,
  val tuition: ComparedTuition,
) {
  /**
   * Exhaustive on purpose: a case added to [ComparedTuition] must fail to
   * compile here -- the one site that owes it copy -- rather than ship a code
   * the coach has no sentence for.
   */
  val statement: String
    get() =
      when (val compared = tuition) {
        is ComparedTuition.Public -> {
          when (compared.tuitionApplicable) {
            TuitionApplicable.IN_STATE -> {
              "$name is a public school, and the tuition and fees figure that applies to this family is its " +
                "in-state one."
            }

            TuitionApplicable.OUT_OF_STATE -> {
              "$name is a public school, and the tuition and fees figure that applies to this family is its " +
                "out-of-state one."
            }

            TuitionApplicable.UNKNOWN -> {
              "$name is a public school that publishes two tuition and fees figures, and the state the family " +
                "lives in is not on file, so neither figure can be shown as theirs."
            }
          }
        }

        ComparedTuition.SinglePublishedPrice -> {
          "$name publishes one price for everyone, so where the family lives changes nothing there."
        }

        is ComparedTuition.PublishedPriceUnknown -> {
          "$name does not say what kind of school it is in this data, so we cannot say which of its published " +
            "prices applies to this family."
        }
      }
}

/**
 * WHAT THE FAMILY ANSWERED about where they live (RFC 151), as one closed
 * vocabulary: the state rides on the answered case and nowhere else.
 *
 * A `status` plus a nullable state re-encoded a disjoint fact: answered with no
 * state, and declined with one, both compiled and both had to be re-checked
 * wherever the pair was read. Here the state is reachable only through
 * [Answered], so no reader can state a residency nobody gave -- the same reason
 * [ComparedTuition] is a sealed vocabulary rather than a code plus a nullable
 * qualifier.
 */
sealed interface ComparedResidency {
  /** The money-profile status this case echoes, derived from the case rather than stored beside it. */
  val status: AnswerStatus

  /** The family said where they live, and [state] is the answer that selects a public school's tuition figure. */
  data class Answered(
    val state: String,
  ) : ComparedResidency {
    override val status: AnswerStatus get() = AnswerStatus.ANSWERED
  }

  /** Not asked yet, or asked and not answered: a question still open. */
  data object Unanswered : ComparedResidency {
    override val status: AnswerStatus get() = AnswerStatus.UNANSWERED
  }

  /** The family said no. A closed topic, never reopened by the coach (RFC 145). */
  data object Declined : ComparedResidency {
    override val status: AnswerStatus get() = AnswerStatus.DECLINED
  }
}

/**
 * WHICH schools in a call charge tuition and fees by where the family lives
 * (RFC 151) -- the fact the per-call residency statement is about.
 *
 * A code beside the sentence, like every other fact in this object (D-D): the
 * decision is the domain's, so a test, a later renderer, or the model itself
 * reads it rather than parsing English for it.
 */
enum class ResidencyScope(
  val value: String,
) {
  /** Every school here charges tuition and fees by residency. */
  ALL_PUBLIC("all_public"),

  /** No school here charges by residency, so the caveat is about none of them. */
  NO_PUBLIC("no_public"),

  /** Some do and some do not, so the statement names the schools it is about. */
  MIXED("mixed"),
}

/**
 * The residency a comparison holds constant (RFC 151 D-A): what the family
 * answered, and one [byCollege] entry per school in the call.
 *
 * Every school gets an entry, public and private alike: "where the family lives
 * changes nothing here" is an answer, and a school missing from the array would
 * be a gap the coach has to fill from memory.
 *
 * The [statement] is computed from [byCollege] rather than from [answer] alone,
 * because a basis line must be true of THIS table: an all-private comparison
 * given a caveat about public tuition, or a mixed one claiming a residency basis
 * for a school with a single published price, states something the table does
 * not do -- which is the one failure this slice exists to prevent.
 */
data class ResidencyBasis(
  val answer: ComparedResidency,
  val byCollege: List<CollegeResidencyBasis>,
) {
  init {
    // A residency basis with no school in it states a residency held constant
    // across no table at all -- and every quantifier below would answer for the
    // empty set, so `others.all { ... }` would assert that every school here
    // publishes one price. The type refuses it, the same way
    // [IncompleteArrangement] refuses an entry with nothing missing, rather
    // than the one caller's count gate remembering to.
    require(byCollege.isNotEmpty()) {
      "a residency basis with no college states a residency held constant across no school: " +
        "answer=[${answer.status.value}]"
    }
  }

  /** The schools where residency actually selects a figure -- the only ones a residency caveat is about. */
  private val publics: List<CollegeResidencyBasis> get() = byCollege.filter { it.tuition is ComparedTuition.Public }

  /** The rest: one published price, or a control we could not recognise. */
  private val others: List<CollegeResidencyBasis> get() = byCollege.filterNot { it.tuition is ComparedTuition.Public }

  /**
   * WHICH schools in this call charge tuition and fees by residency -- decided
   * once, said aloud in [statement] AND shipped as a code, so no reader has to
   * recover the decision by reading the sentence (D-D: every fact carries both).
   */
  val scope: ResidencyScope
    get() =
      when {
        // Order-independent: [byCollege] is non-empty by construction, so the
        // two tests below can never both hold.
        publics.isEmpty() -> ResidencyScope.NO_PUBLIC

        others.isEmpty() -> ResidencyScope.ALL_PUBLIC

        else -> ResidencyScope.MIXED
      }

  val statement: String
    get() =
      when (scope) {
        ResidencyScope.NO_PUBLIC -> nonPublicOnlyStatement
        ResidencyScope.ALL_PUBLIC -> allPublicStatement
        ResidencyScope.MIXED -> "$mixedPublicClause; $mixedOtherClause"
      }

  private val nonPublicOnlyStatement: String
    get() =
      if (others.all { it.tuition.publishesOnePriceForEveryone }) {
        "No school here charges tuition and fees by where the family lives: each publishes one price for " +
          "everyone, so residency changes nothing in this table."
      } else {
        // A control outside the vocabulary is not a school we know charges one
        // price -- so the sentence says only what is true of the whole set, and
        // sends the coach to the school's own line for the rest.
        "No school here is recorded as a public school, so residency selects no tuition and fees figure in this " +
          "table; each school's own line says which published price applies to it."
      }

  private val allPublicStatement: String
    get() =
      when (val residency = answer) {
        is ComparedResidency.Answered -> {
          "Every school here is public, and every tuition and fees figure here is the one a family living in " +
            "${residency.state} would be charged."
        }

        ComparedResidency.Unanswered, ComparedResidency.Declined -> {
          "Every school here is public and publishes two tuition and fees figures, and the state the family lives " +
            "in is not on file, so neither figure can be shown as theirs."
        }
      }

  private val mixedPublicClause: String
    get() =
      when (val residency = answer) {
        is ComparedResidency.Answered -> {
          "The public schools here - ${phraseOf(publics.map { it.name })} - are shown at the tuition and fees a " +
            "family living in ${residency.state} would be charged"
        }

        ComparedResidency.Unanswered, ComparedResidency.Declined -> {
          "The public schools here - ${phraseOf(publics.map { it.name })} - each publish two tuition and fees " +
            "figures, and the state the family lives in is not on file, so neither figure can be shown as theirs"
        }
      }

  private val mixedOtherClause: String
    get() =
      if (others.all { it.tuition.publishesOnePriceForEveryone }) {
        "the other schools here publish one price for everyone, so residency changes nothing there."
      } else {
        "the other schools here are not recorded as public, so each school's own line says which published price " +
          "applies to it."
      }

  companion object {
    fun of(
      colleges: List<CollegeCost>,
      moneyProfile: MoneyProfileStatuses,
    ): ResidencyBasis =
      ResidencyBasis(
        answer = comparedResidencyOf(moneyProfile),
        byCollege = colleges.map { CollegeResidencyBasis(it.collegeId, it.name, comparedTuitionOf(it.control)) },
      )

    /**
     * The money-profile echo read into the closed vocabulary.
     *
     * An ANSWERED status with no stored state is row corruption, refused HERE
     * in the same shape [CollegeCostService.requireIntactAnswers] refuses it
     * ([CorruptPersistedValueException] naming the column) rather than folded
     * into [ComparedResidency.Unanswered]: relabelling it would tell a family
     * that answered that we never asked, and would state "the state the family
     * lives in is not on file" on every public school's line.
     */
    private fun comparedResidencyOf(moneyProfile: MoneyProfileStatuses): ComparedResidency =
      when (moneyProfile.residencyStatus) {
        AnswerStatus.ANSWERED -> {
          ComparedResidency.Answered(
            moneyProfile.residencyState
              ?: throw CorruptPersistedValueException(
                "null",
                ValidationError.InvalidFormat(expected = "a value present when status is 'answered'"),
                location = "money_profiles.[residency_state] (assembling a comparison basis)",
              ),
          )
        }

        AnswerStatus.UNANSWERED -> {
          ComparedResidency.Unanswered
        }

        AnswerStatus.DECLINED -> {
          ComparedResidency.Declined
        }
      }

    /**
     * The control -> comparison vocabulary map. Exhaustive with no `else`, as
     * every other `when` over [CollegeControl] is: a control added to the
     * vocabulary must fail to compile here rather than quietly lose its
     * residency line.
     */
    private fun comparedTuitionOf(control: CollegeControl): ComparedTuition =
      when (control) {
        is CollegeControl.Public -> ComparedTuition.Public(control.tuitionApplicable)
        CollegeControl.PrivateNonprofit -> ComparedTuition.SinglePublishedPrice
        CollegeControl.PrivateForProfit -> ComparedTuition.SinglePublishedPrice
        is CollegeControl.Unrecognized -> ComparedTuition.PublishedPriceUnknown(control)
      }
  }
}

/**
 * Why one school cannot be compared on a way of living (RFC 151 D-E).
 *
 * TWO reasons, and they are not the same fact: a school with no residence halls
 * has ANSWERED (the IPEDS `offers_on_campus_housing` fact), while a school that
 * publishes no figure for a way of living has stayed silent about it. Folding
 * them together is exactly the misreading RFC 149 D-B removed from the
 * per-college keys, so the comparison does not reintroduce it.
 */
enum class ArrangementGap(
  val value: String,
  /**
   * The gap in the words a coach says it -- the spoken twin of [value], beside
   * it in the one home for this vocabulary ([LivingArrangement.label] is the
   * precedent). It reads as the object of "this school has", so the comparison
   * and the per-college answer cannot word one silence two ways.
   */
  val phrase: String,
) {
  /** IPEDS reports this school has no residence halls, so there is no on-campus price to compare. */
  NO_ON_CAMPUS_HOUSING("no_on_campus_housing", "no residence halls"),

  /**
   * The school publishes no figure at all for this way of living, so the
   * arrangement is absent from its breakdown. An arrangement it SHOWS with no
   * settled total is NOT this code -- it carries a [NoTotalReason] instead,
   * because that blank may be a gap of ours rather than the school's silence.
   *
   * A claim about the SCORECARD's silence, and true whatever IPEDS says: it is
   * the reason a school with no housing fact on file (an absent
   * `offers_on_campus_housing`) carries, because the only thing anyone knows
   * about that school is that it published no on-campus figure. Reading an
   * absent flag as "no residence halls" is exactly what RFC 149 D-B forbids,
   * and a third code for "we have no housing fact" would state the gap in OUR
   * data as a fact about the school's price list, which is not what an entry
   * here means.
   */
  NOT_REPORTED("not_reported", "no published price for it"),
  ;

  companion object {
    /**
     * Why a school is not priced for [plan] -- the ONE home for the rule, read
     * by both `gapsOf` (the comparison) and `CollegeCostService`'s
     * chosen-plan resolver (the per-college answer), so the two can never give
     * a family two different reasons for the same silence.
     *
     * ONLY a known `false` is the no-dorms answer. A school with no housing
     * fact on file falls to [NOT_REPORTED], which is the true statement about
     * it: it published no on-campus figure. An absent IPEDS row is a gap in OUR
     * data, never a fact about the school's price list (RFC 149 D-B).
     */
    fun of(
      plan: LivingArrangement,
      offersOnCampusHousing: Boolean?,
    ): ArrangementGap =
      if (plan == LivingArrangement.ON_CAMPUS && offersOnCampusHousing == false) {
        NO_ON_CAMPUS_HOUSING
      } else {
        NOT_REPORTED
      }
  }
}

/**
 * WHY a school's cell in a compared column cannot be filled -- one code and the
 * words a coach says it in, over the TWO vocabularies that answer that question
 * (RFC 152).
 *
 * The two are deliberately not merged: [ArrangementGap] states what the SCHOOL
 * published, and [NoTotalReason] states a blank whose cause may be OURS. This
 * type carries either of them to the one site that formats them, so the
 * comparison never flattens a code into its rendered wording on the way there.
 */
sealed interface PlanSilence {
  /** The wire code, so a reader switches on the fact rather than on its sentence. */
  val value: String

  /** The same fact in the words a coach says it, reading on its own after a school's name. */
  val phrase: String

  /** This school publishes no price for the plan at all, for the [gap]'s reason. */
  data class NotPriced(
    val gap: ArrangementGap,
  ) : PlanSilence {
    override val value: String get() = gap.value

    override val phrase: String get() = gap.phrase
  }

  /** This school shows the plan but no total is settled for it, for the [reason]'s cause. */
  data class NoTotal(
    val reason: NoTotalReason,
  ) : PlanSilence {
    override val value: String get() = reason.value

    override val phrase: String get() = reason.phrase
  }
}

/**
 * One school's gap in the arrangements the other schools are priced for -- the
 * arrangements it is [missing], and the one [reason] they are missing for.
 *
 * A school with two gaps for two different reasons appears TWICE, once per
 * reason, rather than once under a reason that is only half true: the entry
 * means "these arrangements, for this reason", and a merged entry would tell the
 * coach a school has no residence halls because it did not report its at-home
 * costs.
 */
data class IncompleteArrangement(
  val collegeId: CollegeId,
  val name: String,
  val missing: List<LivingArrangement>,
  val reason: ArrangementGap,
) {
  init {
    // An entry means "these arrangements, for this reason", so an entry with no
    // arrangement names a school as incomplete and then says nothing it lacks.
    // The type refuses it, rather than one private factory remembering to.
    require(missing.isNotEmpty()) {
      "an entry with no missing arrangement is an absent entry, never an empty one: " +
        "college_id=[${collegeId.value}] name=[$name] reason=[${reason.value}]"
    }
  }
}

/**
 * WHAT THE FAMILY ANSWERED about where the student plans to live (RFC 152), as
 * one closed vocabulary -- the living-plan twin of [ComparedResidency].
 *
 * The plan rides on the answered case and nowhere else, for the same reason: a
 * status plus a nullable plan re-encodes a disjoint fact, and a reader could
 * then state a plan nobody gave. This is the family's USUAL plan; a school they
 * decided differently about carries its own, which is why the per-school
 * resolution below is read off each [CollegeCost] rather than from here.
 */
sealed interface ComparedLivingPlan {
  /** The money-profile status this case echoes, derived from the case rather than stored beside it. */
  val status: AnswerStatus

  /** The family said where the student plans to live when they have the choice. */
  data class Answered(
    val plan: LivingArrangement,
  ) : ComparedLivingPlan {
    override val status: AnswerStatus get() = AnswerStatus.ANSWERED
  }

  /** Not asked yet, or asked and not answered: a question still open. */
  data object Unanswered : ComparedLivingPlan {
    override val status: AnswerStatus get() = AnswerStatus.UNANSWERED
  }

  /** The family said no. A closed topic, never reopened by the coach (RFC 145/152). */
  data object Declined : ComparedLivingPlan {
    override val status: AnswerStatus get() = AnswerStatus.DECLINED
  }
}

/**
 * WHAT a column can hold constant about where the student lives (RFC 152 D5) --
 * a code beside the sentence, like every other fact in this object (D-D).
 *
 * [ArrangementBasis] was the ONE fact here with no code, only lists. Resolving
 * a family's plan is the moment it needs one: four of these five cases did not
 * exist before, and a coach parsing English for them is exactly what a code
 * exists to prevent. One code per statement, so neither can drift.
 */
enum class ArrangementScope(
  val value: String,
) {
  /** One plan resolved for every school here, and every school is priced for it: the column holds that one way of living. */
  ONE_PLAN_EVERY_SCHOOL("one_plan_every_school"),

  /**
   * The schools here are not all on the same plan -- the per-college override
   * case (at home for the in-state school, on campus for the far one), or a
   * school with a plan of its own beside schools with none.
   *
   * NOT a breach of the RFC 151 comparison contract; it is the contract
   * working. The contract requires the assumption to be stated before the
   * numbers, and here the assumption is per school. The column then holds the
   * family's actual situation rather than one arrangement.
   */
  PLAN_VARIES_BY_SCHOOL("plan_varies_by_school"),

  /** One plan resolved for every school, but some school is not priced for it: those schools are named with their reason. */
  PLAN_NOT_PRICED_EVERYWHERE("plan_not_priced_everywhere"),

  /** No plan on file and no override, but every school is priced for at least one common way of living. */
  NO_PLAN_COMPARABLE("no_plan_comparable"),

  /** No plan on file and no override, and no one way of living is priced at every school here. */
  NO_PLAN_NOTHING_COMPARABLE("no_plan_nothing_comparable"),
}

/**
 * One school's resolved living plan inside a comparison (RFC 152) -- the
 * per-school half of [ArrangementBasis], so a statement that names a plan per
 * school reads it from the same place the per-college answer did.
 */
data class CollegeLivingPlan(
  val collegeId: CollegeId,
  val name: String,
  val chosen: ChosenLivingPlan,
) {
  /** The plan resolved for this school, or null when nothing was resolved for it. */
  val plan: LivingArrangement?
    get() =
      when (val resolved = chosen) {
        is ChosenLivingPlan.Priced -> resolved.plan
        is ChosenLivingPlan.NoTotalHere -> resolved.plan
        is ChosenLivingPlan.NotPricedHere -> resolved.plan
        ChosenLivingPlan.NotChosen -> null
      }
}

/**
 * The living arrangement a comparison can hold constant (RFC 151 D-E, RFC 152
 * D5).
 *
 * [comparable] is the INTERSECTION over every college in the call, in
 * [LivingArrangement] declaration order: "held constant" is only truthful if the
 * arrangement exists for every school in the column set. [incompleteByCollege]
 * names each school that lacks a way of living some other school in the call is
 * priced for, and why -- so a dropped arrangement is a stated fact rather than a
 * quiet narrowing of the table.
 *
 * RFC 152 adds the family's own answer. [answer] is their USUAL plan;
 * [byCollege] is what each school actually resolved to, read off
 * [CollegeCost.chosen] rather than re-derived here, so the comparison
 * and the per-college answer can never disagree about which way of living a
 * school is being quoted at. [comparable] is deliberately NOT narrowed by a
 * resolved plan (D2): the other arrangements stay comparable facts, and a
 * "what if we lived at home instead?" stays answerable from the same result.
 */
data class ArrangementBasis(
  val comparable: List<LivingArrangement>,
  val incompleteByCollege: List<IncompleteArrangement>,
  val answer: ComparedLivingPlan,
  val byCollege: List<CollegeLivingPlan>,
) {
  init {
    // The two lists that ARE populated when this fires say why a call with
    // colleges produced no per-college row; the answer status alone is the one
    // field guaranteed not to explain it.
    require(byCollege.isNotEmpty()) {
      "an arrangement basis with no college states a way of living held constant across no school: " +
        "answer=[${answer.status.value}] " +
        "comparable=[${comparable.joinToString(", ") { it.value }}] " +
        "incomplete_colleges=[${incompleteByCollege.joinToString(", ") { it.collegeId.value.toString() }}]"
    }
  }

  /** The schools a plan actually resolved for, and the ones it did not -- the two halves every statement below is about. */
  private val planned: List<CollegeLivingPlan> get() = byCollege.filter { it.plan != null }

  /** The distinct plans in play across the call, in [LivingArrangement] declaration order (never a set's iteration order). */
  private val distinctPlans: List<LivingArrangement>
    get() = LivingArrangement.entries.filter { arrangement -> byCollege.any { it.plan == arrangement } }

  /**
   * The schools whose cell this call cannot fill, each carrying the phrase that
   * says why -- never given a substitute.
   *
   * Two different silences, each kept as a CODE rather than as the sentence it
   * renders to ([PlanSilence]): a school not priced for the plan at all carries
   * its [ArrangementGap], and a school that shows the way of living but settles
   * no total for it carries its [NoTotalReason]. The wording is read where it is
   * formatted, from the one home for that wording, so this property never
   * discards a fact into English on the way to the renderer.
   *
   * The case is proved once, exhaustively, and the code carried out with it:
   * selecting the schools and then casting them back would turn a new
   * [ChosenLivingPlan] case, or one reordered filter, into a
   * `ClassCastException` inside a live cost answer.
   */
  private val notPriced: List<Pair<CollegeLivingPlan, PlanSilence>>
    get() =
      byCollege.mapNotNull { row ->
        when (val chosen = row.chosen) {
          is ChosenLivingPlan.NotPricedHere -> row to PlanSilence.NotPriced(chosen.reason)
          is ChosenLivingPlan.NoTotalHere -> row to PlanSilence.NoTotal(chosen.reason)
          is ChosenLivingPlan.Priced, ChosenLivingPlan.NotChosen -> null
        }
      }

  /**
   * WHAT this column can hold constant -- decided once, said aloud in
   * [statement] AND shipped as a code, so no reader has to recover the decision
   * by reading the sentence (D-D: every fact carries both).
   */
  val scope: ArrangementScope
    get() =
      when {
        distinctPlans.isEmpty() -> {
          if (comparable.isEmpty()) ArrangementScope.NO_PLAN_NOTHING_COMPARABLE else ArrangementScope.NO_PLAN_COMPARABLE
        }

        // More than one plan in play, or a school with a plan of its own beside
        // a school with none: either way the schools here are not all on the
        // same plan, so the column cannot claim one.
        distinctPlans.size > 1 || planned.size != byCollege.size -> {
          ArrangementScope.PLAN_VARIES_BY_SCHOOL
        }

        notPriced.isNotEmpty() -> {
          ArrangementScope.PLAN_NOT_PRICED_EVERYWHERE
        }

        else -> {
          ArrangementScope.ONE_PLAN_EVERY_SCHOOL
        }
      }

  /**
   * The schools worth naming one by one -- only when they are NOT all on one
   * plan, or one of them is not priced for it.
   *
   * A fact about the comparison, so it lives with the comparison rather than in
   * the renderer: with one plan everywhere the statement and the per-college
   * keys already say it, and a second list would invite the coach to narrate a
   * difference there is none of. Exhaustive with no `else`, so a sixth scope
   * cannot default into silence.
   */
  val planNamedPerCollege: List<CollegeLivingPlan>
    get() =
      when (scope) {
        ArrangementScope.PLAN_VARIES_BY_SCHOOL, ArrangementScope.PLAN_NOT_PRICED_EVERYWHERE -> byCollege

        ArrangementScope.ONE_PLAN_EVERY_SCHOOL,
        ArrangementScope.NO_PLAN_COMPARABLE,
        ArrangementScope.NO_PLAN_NOTHING_COMPARABLE,
        -> emptyList()
      }

  val statement: String
    get() =
      when (scope) {
        // Byte-for-byte the two statements this fact carried before a plan
        // could be on file (RFC 151), and deliberately so: an unanswered or
        // declined plan renders exactly as it did, forever (RFC 152 D3).
        ArrangementScope.NO_PLAN_NOTHING_COMPARABLE -> {
          "No one way of living is priced at every school here, so a column cannot hold the living arrangement " +
            "constant: quote each school for the ways of living it does publish, and say which one you are quoting."
        }

        ArrangementScope.NO_PLAN_COMPARABLE -> {
          "Every school here is priced for ${arrangementPhraseOf(comparable)}, so a column may hold the living " +
            "arrangement constant; name the one you are quoting."
        }

        ArrangementScope.ONE_PLAN_EVERY_SCHOOL -> {
          onePlanStatement
        }

        ArrangementScope.PLAN_VARIES_BY_SCHOOL -> {
          variesBySchoolStatement
        }

        ArrangementScope.PLAN_NOT_PRICED_EVERYWHERE -> {
          notPricedEverywhereStatement
        }
      }

  /** One plan, priced everywhere: the column holds that one way of living, and it is named. */
  private val onePlanStatement: String
    get() {
      val plan = distinctPlans.single()
      return "The student plans on ${plan.label}, and every school here is priced for it, so the column holds " +
        "that one way of living; say so above the table."
    }

  /**
   * Different plans across the compared schools -- the override case. The
   * column then holds the family's actual situation rather than one
   * arrangement, and the statement says so and names the plan used for each
   * school (RFC 152 D5 case 2).
   */
  private val variesBySchoolStatement: String
    get() {
      val named =
        byCollege.joinToString("; ") { row ->
          val plan = row.plan
          if (plan == null) "${row.name}: no plan on file" else "${row.name}: ${plan.label}"
        }
      return "The schools here are not all on the same plan, so the column holds the family's actual situation " +
        "rather than one way of living: $named. Say which plan each school is quoted at, before the numbers."
    }

  /**
   * One plan resolved, but some school is not priced for it. Those schools are
   * named with their reason, reusing the [ArrangementGap] vocabulary -- never a
   * substituted arrangement (RFC 152 D5 case 3).
   */
  private val notPricedEverywhereStatement: String
    get() {
      val plan = distinctPlans.single()
      // The gap's own spoken phrase, never a second wording of it: the same
      // silence must not be explained one way here and another way in the
      // per-college answer.
      val named = notPriced.joinToString("; ") { (row, silence) -> "${row.name}: ${silence.phrase}" }
      return "The student plans on ${plan.label}, but not every school here is priced for it - $named. Say that " +
        "plainly above the table and leave those cells blank; never quote another way of living in their place " +
        "and never carry a figure across from another school."
    }

  companion object {
    fun of(
      colleges: List<CollegeCost>,
      moneyProfile: MoneyProfileStatuses,
    ): ArrangementBasis {
      // Pairs rather than a map keyed by the college: [CollegeCost] is a data
      // class, so two schools that happened to report identical facts would
      // collapse into one key and silently drop a column from the intersection.
      val arrangementsByCollege = colleges.map { it to arrangementsOf(it) }
      // Declaration order on both lists, never the order a set iterates in: they
      // are facts about the call, so they must not depend on how they were
      // computed.
      val comparable = LivingArrangement.entries.filter { a -> arrangementsByCollege.all { (_, own) -> a in own } }
      val inPlay = LivingArrangement.entries.filter { a -> arrangementsByCollege.any { (_, own) -> a in own } }
      return ArrangementBasis(
        comparable = comparable,
        incompleteByCollege = arrangementsByCollege.flatMap { (college, own) -> gapsOf(college, inPlay - own) },
        answer = moneyProfile.living,
        // Read off the per-college answer, never re-resolved: override ->
        // default -> none has exactly one home
        // ([CollegeCostService.plannedLivingPlanOf]), and a second copy here
        // would be free to disagree with the number the same payload renders.
        byCollege = colleges.map { CollegeLivingPlan(it.collegeId, it.name, it.chosen) },
      )
    }

    /**
     * The ways of living this school is actually priced for, read off the
     * breakdown the payload renders.
     *
     * A school that reports no component at all has a NULL breakdown (RFC 149)
     * and is priced for NOTHING here, on purpose: it empties [comparable] for
     * the WHOLE call and takes one [IncompleteArrangement] entry per
     * arrangement in play. That is the truthful reading -- "held constant" is
     * false the moment one school in the column set publishes no figure for it
     * -- and the statement then tells the coach to quote each school for what
     * it does publish.
     */
    private fun arrangementsOf(cost: CollegeCost): Set<LivingArrangement> =
      cost.breakdown
        ?.arrangements
        ?.map { it.arrangement }
        ?.toSet()
        .orEmpty()

    /**
     * One school's [missing] arrangements, split into one entry per reason.
     *
     * The no-dorms fact is read from `offers_on_campus_housing` -- the school's
     * own answer -- and never inferred from the absent arrangement, which is the
     * rule RFC 149 D-B established and this file only reuses.
     */
    private fun gapsOf(
      cost: CollegeCost,
      missing: List<LivingArrangement>,
    ): List<IncompleteArrangement> {
      // The reason rule has one home, [ArrangementGap.of]: the same
      // silence must not be explained one way in a comparison and another way
      // in the per-college answer.
      val noDorms =
        missing.filter {
          ArrangementGap.of(it, cost.offersOnCampusHousing) == ArrangementGap.NO_ON_CAMPUS_HOUSING
        }
      val unreported = missing.filterNot { it in noDorms }
      return listOfNotNull(
        entryOf(cost, noDorms, ArrangementGap.NO_ON_CAMPUS_HOUSING),
        entryOf(cost, unreported, ArrangementGap.NOT_REPORTED),
      )
    }

    /** An entry with no missing arrangement is an ABSENT entry, never an empty one. */
    private fun entryOf(
      cost: CollegeCost,
      missing: List<LivingArrangement>,
      reason: ArrangementGap,
    ): IncompleteArrangement? =
      if (missing.isEmpty()) {
        null
      } else {
        IncompleteArrangement(cost.collegeId, cost.name, missing, reason)
      }

    /**
     * The arrangements in the words a student says, joined into one phrase.
     *
     * The VOCABULARY is [LivingArrangement.label]'s, beside the arrangement it
     * names, so this construct owns no copy and cannot call an arrangement
     * something the rest of the domain does not; only the English grammar of
     * listing them is this file's business.
     */
    private fun arrangementPhraseOf(arrangements: List<LivingArrangement>): String = phraseOf(arrangements.map { it.label })
  }
}

/**
 * One academic year in the call, and the figures it dates (RFC 151): the same
 * `{academic_year, figures}` shape the per-college vintage labels already carry,
 * plus the sentence that says the year aloud.
 *
 * Only the vintages actually PRESENT in the call are listed, and each lists only
 * the figures some college in the call really carries -- read off the same
 * [CostField] classifier [CollegeCostChatTool] derives its per-college labels
 * from, so the comparison can never date a figure the payload does not render.
 */
data class DatedFigures(
  val vintage: ScorecardVintage,
  val figures: List<CostField>,
) {
  val basis: String get() = vintage.wireName

  val academicYear: String get() = vintage.label

  /**
   * Exhaustive on purpose: a vintage added to the enum must fail to compile
   * here -- the one site that owes it copy -- rather than ship a year with no
   * sentence saying what kind of figure it dates.
   */
  val statement: String
    get() =
      when (vintage) {
        ScorecardVintage.PUBLISHED_PRICE -> {
          "The published price figures here, which are tuition and fees and the parts of living cost a school " +
            "publishes, all come from the $academicYear academic year, for every school in this comparison."
        }

        ScorecardVintage.BLENDED_AVERAGE -> {
          "The blended averages here, which are the published cost of attendance and the net price, all come " +
            "from the $academicYear academic year, for every school in this comparison."
        }
      }

  companion object {
    fun of(colleges: List<CollegeCost>): List<DatedFigures> {
      // [CollegeCost.reported] and nothing else: it is the service's one
      // answer to "does this college report this field", and exactly the set
      // [CollegeCostChatTool] renders -- so a CostField added tomorrow cannot
      // silently drop out of the dated figures here, which a null ladder of our
      // own would have let it do.
      //
      // A figure with no vintage (median debt, median earnings) is admitted and
      // dropped by the `it.vintage == vintage` test below: which year dates
      // what is [CostField]'s own fact.
      val reported = colleges.flatMap { it.reported }.toSet()
      // Declaration order on both axes, so the list is a fact about the call
      // rather than about set iteration.
      return ScorecardVintage.entries.mapNotNull { vintage ->
        val dated = CostField.entries.filter { it.vintage == vintage && it in reported }
        if (dated.isEmpty()) null else DatedFigures(vintage, dated)
      }
    }
  }
}
