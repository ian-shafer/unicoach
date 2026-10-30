package ed.unicoach.family

import ed.unicoach.coaching.costs.ArrangementCost
import ed.unicoach.coaching.costs.BlendedFigureApplicability
import ed.unicoach.coaching.costs.ChosenLivingPlan
import ed.unicoach.coaching.costs.CollegeCost
import ed.unicoach.coaching.costs.CollegeCostProfile
import ed.unicoach.coaching.costs.CostField
import ed.unicoach.coaching.costs.LivingPlanPricing
import ed.unicoach.coaching.costs.NetPrice
import ed.unicoach.coaching.costs.SpokenAssurance
import ed.unicoach.coaching.costs.WithheldReason
import ed.unicoach.coaching.costs.canonical.ChosenLivingPlanCopy
import ed.unicoach.coaching.costs.canonical.FigureStatusCopy
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeLevel
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.MoneySource

/**
 * The four-year money plan for a student's list (RFC 261): for each school, the
 * all-years figures the server derives from the one-year figures the cost
 * picture already holds -- or, where the data cannot support a number, why
 * there is none.
 *
 * The arithmetic is here, never the model's: an all-years figure is ONE
 * published per-year amount times the school's years
 * ([MoneyPlanCollege.Totalled.allYearsUsdOf]). No two years
 * are added, no two schools are added, and nothing is subtracted from anything
 * -- the price after grants is the school's own published net price, never a
 * published price minus a grant (RFC 149 D-F).
 *
 * Built by [of] from what the cost picture and the level read already hold;
 * nothing here reads a database or writes a JSON key. Each reason a figure is
 * missing carries its own sentence, as the cost picture's do -- and where the
 * cost picture already says one, it is read from the same copy home
 * ([ChosenLivingPlanCopy]), never re-worded. The renderer owns only the keys
 * and the shape.
 */
data class MoneyPlan(
  val colleges: List<MoneyPlanCollege>,
  /** The publishers behind the cost picture's figures, as the cost payload names them. */
  val sources: List<MoneySource>,
) {
  companion object {
    /**
     * The years an all-years figure spans at a four-year school (RFC 261
     * decision 2): the length of the degree these schools award, and a figure
     * WE chose -- the payload says so, and that many students take longer.
     */
    const val FOUR_YEAR_SCHOOL_YEARS = 4

    /**
     * The plan for [profile]'s schools, in the profile's order. [levels] is the
     * level read keyed by college; a school it has no entry for is a school we
     * hold no level for, which is what [CollegeLevel.NOT_HELD] says.
     */
    fun of(
      profile: CollegeCostProfile,
      levels: Map<CollegeId, CollegeLevel>,
    ): MoneyPlan =
      MoneyPlan(
        colleges = profile.colleges.map { collegeOf(it, levels[it.collegeId] ?: CollegeLevel.NOT_HELD) },
        sources = profile.moneySources,
      )

    /** One school: totalled only at a four-year school, and otherwise the reason and no number. */
    private fun collegeOf(
      college: CollegeCost,
      level: CollegeLevel,
    ): MoneyPlanCollege =
      when (level) {
        CollegeLevel.FOUR_YEAR -> {
          MoneyPlanCollege.Totalled(
            college = college,
            years = FOUR_YEAR_SCHOOL_YEARS,
            publishedPrice = publishedPriceOf(college),
            priceAfterGrants = priceAfterGrantsOf(college),
          )
        }

        CollegeLevel.NOT_FOUR_YEAR -> {
          MoneyPlanCollege.NotTotalled(college, NotTotalledReason.NOT_FOUR_YEAR)
        }

        CollegeLevel.NOT_HELD -> {
          MoneyPlanCollege.NotTotalled(college, NotTotalledReason.LEVEL_NOT_HELD)
        }
      }

    /**
     * The published price over all the years, led by the family's plan: the one
     * way of living they chose when this school prices it, every way of living
     * this school totals when they have chosen none, and the cost picture's own
     * statement when the chosen plan has no total here.
     */
    private fun publishedPriceOf(college: CollegeCost): PublishedPricePlan =
      when (val chosen = college.chosen) {
        is ChosenLivingPlan.Priced -> {
          PublishedPricePlan.Chosen(arrangementAllYearsOf(college, chosen.cost))
        }

        is ChosenLivingPlan.NoTotalHere -> {
          PublishedPricePlan.NoTotalHere(chosen)
        }

        is ChosenLivingPlan.NotPricedHere -> {
          PublishedPricePlan.NotPricedHere(chosen)
        }

        ChosenLivingPlan.NotChosen -> {
          everyWayOfLivingOf(college)
        }
      }

    /** No way of living chosen: every way of living this school totals, or that it totals none. */
    private fun everyWayOfLivingOf(college: CollegeCost): PublishedPricePlan {
      val totalled =
        college.breakdown
          ?.arrangements
          .orEmpty()
          .filter { it.totalPerYearUsd != null }
          .map { arrangementAllYearsOf(college, it) }
      return if (totalled.isEmpty()) {
        PublishedPricePlan.NoWayOfLivingTotalled
      } else {
        PublishedPricePlan.EveryWayOfLiving(college.collegeId, totalled)
      }
    }

    private fun arrangementAllYearsOf(
      college: CollegeCost,
      arrangement: ArrangementCost,
    ): ArrangementAllYears =
      ArrangementAllYears(
        arrangement = arrangement,
        // A total exists only when the tuition line does, and the tuition line
        // only at the school's one served year -- so a total with no year is a
        // state the cost read cannot build, refused here rather than shipped
        // undated.
        academicYear =
          checkNotNull(college.publishedPriceAcademicYear) {
            "a totalled arrangement must be dated: college_id=[${college.collegeId.value}] " +
              "arrangement=[${arrangement.arrangement.value}]"
          },
        figure =
          PerYearFigure(
            checkNotNull(arrangement.totalPerYearUsd) {
              "a totalled arrangement must carry a per-year total: college_id=[${college.collegeId.value}] " +
                "arrangement=[${arrangement.arrangement.value}]"
            },
          ),
        provenance = provenanceOf(college, arrangement.lines.map { it.field }),
      )

    /**
     * The price after grants and scholarships over all the years: the school's
     * own published net price times the years, or why there is none -- never a
     * published price minus a grant.
     */
    private fun priceAfterGrantsOf(college: CollegeCost): PriceAfterGrantsPlan =
      when (val netPrice = college.netPrice) {
        is NetPrice.Withheld -> {
          PriceAfterGrantsPlan.Withheld(netPrice.reason)
        }

        is NetPrice.Reported -> {
          reportedPriceAfterGrantsOf(college, netPrice)
        }
      }

    /** A net price this family's basis has: its amount over the years, or why the school gives none. */
    private fun reportedPriceAfterGrantsOf(
      college: CollegeCost,
      netPrice: NetPrice.Reported,
    ): PriceAfterGrantsPlan {
      val amount = netPrice.amount ?: return notReportedOf(college)
      return PriceAfterGrantsPlan.Totalled(
        netPrice = netPrice,
        academicYear = college.blendedAverageAcademicYear,
        applies = college.blendedFiguresApply,
        figure = PerYearFigure(amount),
        provenance = provenanceOf(college, listOf(CostField.NET_PRICE)),
      )
    }

    /**
     * Why the school's net price carries no number: the store's own status for
     * the cell and the sentence `figure_statuses` says it in, and otherwise --
     * the store holds no status for it -- the school's own silence, in the words
     * the cost picture's own `data_availability` claims.
     */
    private fun notReportedOf(college: CollegeCost): PriceAfterGrantsPlan.NotReported {
      val note = college.statusNoteFor(CostField.NET_PRICE)
      val status = note?.status ?: FigureStatus.NOT_REPORTED_BY_INSTITUTION
      return PriceAfterGrantsPlan.NotReported(
        status = status,
        statement =
          note?.statement
            // Only a plainly reported, SHOWN figure has no sentence (RFC 179
            // D6), and a net price with no amount is not shown.
            ?: checkNotNull(FigureStatusCopy.agentlessStatementOf(status)) {
              "a net price with no amount must say why: college_id=[${college.collegeId.value}] " +
                "field=[${CostField.NET_PRICE.wireName}] status=[${status.value}]"
            },
      )
    }

    /**
     * Who published the figures one amount is built from, and what kind of
     * number each is: one entry per distinct (tier, publisher) pair, in field
     * order. A line with no status note -- the at-home `$0` that is OURS -- has
     * no publisher to name, and the renderer says whose it is instead.
     */
    private fun provenanceOf(
      college: CollegeCost,
      fields: List<CostField>,
    ): List<FigureProvenancePair> =
      fields
        .mapNotNull { college.statusNoteFor(it) }
        .map { FigureProvenancePair(SpokenAssurance(it.assurance), it.source) }
        .distinct()
  }
}

/**
 * One published per-year amount, which the plan multiplies by a school's years.
 *
 * It holds no span of its own: how many years every figure at a school spans
 * is that school's one fact ([MoneyPlanCollege.Totalled.years]), so a figure
 * can never disagree with the school it sits in. The multiplication is
 * [MoneyPlanCollege.Totalled.allYearsUsdOf].
 */
data class PerYearFigure(
  val perYearUsd: Int,
)

/** What kind of number a figure is, and who published it: the pair `figure_provenance` says. */
data class FigureProvenancePair(
  val assurance: SpokenAssurance,
  val source: MoneySource,
)

/**
 * One school in the plan. Sealed so a school can never carry a total and a
 * refusal at once.
 */
sealed interface MoneyPlanCollege {
  val college: CollegeCost

  /**
   * A four-year school: its published price and its price after grants, each
   * over [years] -- the ONE home of the span every figure here is multiplied
   * by.
   */
  data class Totalled(
    override val college: CollegeCost,
    val years: Int,
    val publishedPrice: PublishedPricePlan,
    val priceAfterGrants: PriceAfterGrantsPlan,
  ) : MoneyPlanCollege {
    init {
      require(years > 0) { "an all-years figure spans at least one year: college_id=[${college.collegeId.value}] years=[$years]" }
    }

    /**
     * [figure] over this school's [years] -- the ONE place a money-plan figure
     * is multiplied, in whole dollars, with nothing rounded. An overflow throws
     * rather than wrapping into a wrong dollar figure.
     */
    fun allYearsUsdOf(figure: PerYearFigure): Int =
      try {
        Math.multiplyExact(figure.perYearUsd, years)
      } catch (e: ArithmeticException) {
        throw ArithmeticException(
          "an all-years figure overflows Int: college_id=[${college.collegeId.value}] " +
            "per_year_usd=[${figure.perYearUsd}] years=[$years]",
        ).apply { initCause(e) }
      }
  }

  /** A school we give no all-years figure for, and why. It carries no number. */
  data class NotTotalled(
    override val college: CollegeCost,
    val reason: NotTotalledReason,
  ) : MoneyPlanCollege
}

/** Why a school gets no all-years figure: the code and the sentence, from one home. */
enum class NotTotalledReason(
  val value: String,
  val statement: String,
) {
  /** IPEDS says the school's programs run shorter than four years: no total rather than a guessed length. */
  NOT_FOUR_YEAR(
    "not_a_four_year_school",
    "This school's programs run shorter than four years, so we give no total for all its years rather than guess " +
      "how many years the student would pay for. Quote its one-year figures from the cost picture, with their year.",
  ),

  /** We hold no level for the school: no total rather than an assumed length. */
  LEVEL_NOT_HELD(
    "level_not_held",
    "We do not hold how long this school's programs run, so we give no total for all its years rather than guess. " +
      "Quote its one-year figures from the cost picture, with their year.",
  ),
}

/**
 * One way of living's published price, over all the years. Whether it is the
 * family's chosen one is said by the [PublishedPricePlan] case that holds it,
 * never by a flag here.
 */
data class ArrangementAllYears(
  val arrangement: ArrangementCost,
  /** The academic year of the per-year price -- the school's one served year. */
  val academicYear: String,
  val figure: PerYearFigure,
  val provenance: List<FigureProvenancePair>,
)

/** The published price over all the years, or why there is none. */
sealed interface PublishedPricePlan {
  /** The family's chosen way of living, which this school prices. */
  data class Chosen(
    val total: ArrangementAllYears,
  ) : PublishedPricePlan

  /**
   * No way of living chosen: one total per way of living this school prices.
   * [collegeId] is the school's, so a refused construction says which one.
   */
  data class EveryWayOfLiving(
    val collegeId: CollegeId,
    val totals: List<ArrangementAllYears>,
  ) : PublishedPricePlan {
    init {
      require(totals.isNotEmpty()) {
        "every way of living with a total, and at least one -- a school with none is NoTotal: " +
          "college_id=[${collegeId.value}]"
      }
    }
  }

  /**
   * No published-price total: the chosen plan's own case ([pricing]) and the
   * sentence for it, both DERIVED from the case that holds them, so a code can
   * never be paired with another case's sentence and a priced plan can never
   * be one.
   */
  sealed interface NoTotal : PublishedPricePlan {
    val pricing: LivingPlanPricing
    val statement: String
  }

  /** The chosen plan is one this school shows but cannot total: the cost picture's own case and words. */
  data class NoTotalHere(
    val plan: ChosenLivingPlan.NoTotalHere,
  ) : NoTotal {
    override val pricing: LivingPlanPricing get() = plan.pricing
    override val statement: String get() = ChosenLivingPlanCopy.statementOf(plan)
  }

  /** The chosen plan is one this school is not priced for: the cost picture's own case and words. */
  data class NotPricedHere(
    val plan: ChosenLivingPlan.NotPricedHere,
  ) : NoTotal {
    override val pricing: LivingPlanPricing get() = plan.pricing
    override val statement: String get() = ChosenLivingPlanCopy.statementOf(plan)
  }

  /**
   * No way of living chosen, and none this school totals: the cost picture
   * carries the parts, and no part of it is a total to multiply.
   */
  data object NoWayOfLivingTotalled : NoTotal {
    override val pricing: LivingPlanPricing get() = ChosenLivingPlan.NotChosen.pricing
    override val statement: String =
      "The family has not said how the student plans to live, and no way of living at this school has a total in " +
        "the cost picture, so there is no total for all its years here. Quote the parts the cost picture gives, " +
        "with their year, and never add them up."
  }
}

/** The price after grants and scholarships over all the years, or why there is none. */
sealed interface PriceAfterGrantsPlan {
  /** The school's own published net price, with its basis kept. */
  data class Totalled(
    val netPrice: NetPrice.Reported,
    /** The blended figures' year at this school, or null when its blended rows disagree on one. */
    val academicYear: String?,
    val applies: BlendedFigureApplicability,
    val figure: PerYearFigure,
    val provenance: List<FigureProvenancePair>,
  ) : PriceAfterGrantsPlan

  /**
   * The net price carries no number on this family's basis. [status] is WHY,
   * as the store's own typed status for the cell -- the school's silence, the
   * publisher's suppression, or a gap of ours -- so a reader switches on it
   * rather than on [statement], which is that status's sentence.
   */
  data class NotReported(
    val status: FigureStatus,
    val statement: String,
  ) : PriceAfterGrantsPlan

  /** The school publishes one and it is not this family's, so we hold it back (RFC 157 D-A). */
  data class Withheld(
    val reason: WithheldReason,
  ) : PriceAfterGrantsPlan
}
