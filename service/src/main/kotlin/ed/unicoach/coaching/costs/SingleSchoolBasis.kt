package ed.unicoach.coaching.costs

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.util.phraseOf
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement

/**
 * The basis for a ONE-SCHOOL answer (RFC 155): the typed facts, and the
 * statements derived from them.
 *
 * [ComparisonBasis] deliberately builds nothing below two colleges — a
 * "comparison" over one school would invite a reader to narrate a comparison it
 * is not making — but a single school still owes a family every assumption its
 * figures rest on. This is that shape, and it is its twin rather than its echo:
 * the same `{fact, derived statement}` pairing, so the two carry the same
 * EVIDENCE and not merely the same prose.
 *
 * The residency fact is the typed [CollegeResidencyBasis] the caller already
 * holds, not a sentence it was forced to flatten first. Flattening it at this
 * boundary meant nothing downstream could switch on the tuition case, and no
 * test or renderer could read a code for it.
 *
 * These sentences live in the cost DOMAIN, beside every other basis statement,
 * because the money vocabulary is the domain's: tuition and fees, housing and
 * food, the published price, a financial aid offer. A renderer that authored its
 * own sentences would be a second, unreviewed copy of the contract.
 */
data class SingleSchoolBasis(
  val population: PopulationBasis,
  val residency: CollegeResidencyBasis,
  val blendedFigures: CollegeBlendedFigureBasis,
  val arrangements: List<LivingArrangement>,
  val academicYears: List<DatedFigures>,
  val aid: AidBasis,
) {
  /**
   * Every statement a one-school answer must carry, in the order they are said
   * — the single-school twin of [ComparisonBasis.statements], so neither shape
   * lets a renderer print a subset.
   *
   * Read off the facts rather than stored beside them: a statement can never
   * disagree with the fact it labels.
   */
  val statements: List<String>
    get() =
      buildList {
        add(population.statement)
        add(residency.statement)
        // Immediately after the residency line, for the reason its comparison
        // twin gives: that line is exactly what a reader carries onto the two
        // blended rows below it (RFC 157).
        add(blendedFigures.statement)
        add(arrangementStatement)
        academicYears.forEach { add(yearStatement(it.vintage, it.academicYear)) }
        add(aid.statement)
      }

  /** The ways of living this one school is priced for, in the domain's own words for each. */
  val arrangementStatement: String
    get() =
      if (arrangements.isEmpty()) {
        "This school publishes none of the parts of its price, so there is no way of living to quote it for."
      } else {
        "This school is priced for ${phraseOf(arrangements.map { it.label })}, and each way of living is " +
          "quoted on its own below."
      }

  companion object {
    fun of(
      cost: CollegeCost,
      residency: CollegeResidencyBasis,
    ): SingleSchoolBasis =
      SingleSchoolBasis(
        population = PopulationBasis,
        residency = residency,
        // The SAME [ComparedTuition] fact the residency line above is built
        // from, so one school cannot be called public for its tuition and
        // something else for its published price (RFC 157 D-C).
        blendedFigures = CollegeBlendedFigureBasis.of(residency),
        arrangements =
          cost.breakdown
            ?.arrangements
            ?.map { it.arrangement }
            .orEmpty(),
        academicYears = DatedFigures.of(listOf(cost)),
        aid = AidBasis,
      )

    /**
     * The academic year of one family of figures, said for a single school.
     *
     * Exhaustive on purpose: a vintage added to [ScorecardVintage] must fail to
     * compile here rather than ship a year with no sentence saying what it dates.
     */
    fun yearStatement(
      vintage: ScorecardVintage,
      academicYear: String,
    ): String =
      when (vintage) {
        ScorecardVintage.PUBLISHED_PRICE -> {
          "The published price figures shown for this school, which are tuition and fees and the parts of living " +
            "cost it publishes, come from the $academicYear academic year."
        }

        ScorecardVintage.BLENDED_AVERAGE -> {
          "The published price and the price after a financial aid offer shown for this school come from the " +
            "$academicYear academic year, and are averages blended across the ways of living."
        }
      }
  }
}

/**
 * WHOSE family the price after a financial aid offer is for (RFC 155 D-F), as
 * one closed vocabulary: the income band rides on the answered case and nowhere
 * else.
 *
 * Never a silent overall average dressed as a personal one — and never the
 * in-chat invitation to answer the question, which a logged-out parent cannot
 * act on. Each case carries a stable CODE and the sentence derived from it, the
 * `income_band` + `income_band_label` convention every other basis fact follows
 * (RFC 151 D-D), so a reader never has to parse English for the decision.
 *
 * A three-branch boolean ladder stood here, and its `else` absorbed ANSWERED
 * with a null band: a family that answered the income question was told the
 * question was unanswered. That is exactly the fold [ResidencyBasis] refuses for
 * the residency twin in this same slice, and it is refused here the same way.
 */
sealed interface MoneyBasis {
  /** The stable wire code, derived from the case rather than stored beside it. */
  val code: String

  /** The sentence the page may say, derived from the case for the same reason. */
  val statement: String

  /** The family answered, and [band] is the bracket the price after aid is for. */
  data class AnsweredBand(
    val band: IncomeBand,
  ) : MoneyBasis {
    override val code: String get() = "answered_band"

    override val statement: String
      get() =
        "The price after a financial aid offer below is the average for families with a household income of " +
          "${band.bracket}, which is the band on file."
  }

  /** The family said no. A closed topic, never reopened by the coach (RFC 145). */
  data object Declined : MoneyBasis {
    override val code: String get() = "declined"

    override val statement: String
      get() =
        "The household income band is not on file, so the price after a financial aid offer below is an overall " +
          "average across all families rather than a figure for this family."
  }

  /** Not asked yet, or asked and not answered: a question still open. */
  data object Unanswered : MoneyBasis {
    override val code: String get() = "unanswered"

    override val statement: String
      get() =
        "The household income question is unanswered, so the price after a financial aid offer below is an " +
          "overall average across all families rather than a figure for this family."
  }

  companion object {
    /**
     * The money-profile echo read into the closed vocabulary.
     *
     * An ANSWERED status with no stored band is row corruption, refused HERE in
     * the same shape [ResidencyBasis] refuses its residency twin
     * ([CorruptPersistedValueException] naming the column) rather than folded
     * into [Unanswered]: relabelling it would tell a family that answered that
     * we never asked.
     */
    fun of(moneyProfile: MoneyProfileStatuses): MoneyBasis =
      when (moneyProfile.incomeBandStatus) {
        AnswerStatus.ANSWERED -> {
          AnsweredBand(
            moneyProfile.incomeBand
              ?: throw CorruptPersistedValueException(
                "null",
                ValidationError.InvalidFormat(expected = "a value present when status is 'answered'"),
                location = "money_profiles.[income_band] (assembling a money basis)",
              ),
          )
        }

        AnswerStatus.DECLINED -> {
          Declined
        }

        AnswerStatus.UNANSWERED -> {
          Unanswered
        }
      }
  }
}

/**
 * Where the cost and price figures come from.
 *
 * A NEUTRAL home in the cost domain rather than a constant on one boundary
 * class: the chat tool and the parent-facing report page both cite this source,
 * and a page reaching into a chat tool for its attribution couples a family's
 * web page to the shape of the model's tool surface. The tool keeps a
 * delegating alias, so its own wire contract is unchanged.
 */
object CostSources {
  const val SCORECARD_ATTRIBUTION = "U.S. Department of Education College Scorecard"
}
