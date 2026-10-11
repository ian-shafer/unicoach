package ed.unicoach.coaching.costs.canonical

import ed.unicoach.coaching.costs.CostField
import ed.unicoach.coaching.costs.ProgramReturnCopy
import ed.unicoach.coaching.costs.SpokenAssurance
import ed.unicoach.coaching.costs.spokenLabel
import ed.unicoach.common.util.phraseOf
import ed.unicoach.db.models.AssuranceTier
import ed.unicoach.db.models.ProgramMoneyMeasure

/**
 * A tier sentence and the SET of figures it covers, said once (RFC 191, brief
 * 0009 D3).
 *
 * [AssuranceTierCopy] owns the three sentences; this owns how often they are
 * said. RFC 179 attached a tier to each FIGURE, so a page showing one federal
 * figure per school printed the same thirty words once per school, and a payload
 * carried one distinct sentence up to seventeen times. The tier is a property of
 * a set of figures, not of a printed cell, and this seam is that sentence said
 * once for the set.
 *
 * It groups and it NAMES the group. Grouping alone would be the dangerous half:
 * a family reading one tier sentence has to be able to tell WHICH figures it is
 * about, and a header that covers a figure it does not describe is a worse
 * failure than the repetition this removes.
 */
object AssuranceTierGrouping {
  /**
   * Every distinct tier in [figures], each said ONCE, with the figures it covers
   * named.
   *
   * [spokenElsewhere] is never rendered: it is the figures the SURFACE states a
   * tier for outside this block (a withheld figure keeps its own sentence in its
   * own cell), handed in so that a label this block names bare cannot be read as
   * covering one of them. See [ambiguousFieldsOf].
   *
   * Order is FIRST APPEARANCE in [figures] and is not a ranking: RFC 179 D10
   * admits no number, score, rating or ordering of the three tiers, and a list
   * sorted by anything else -- frequency, the enum's declaration order, a
   * "strength" -- would be read as one. The caller walks its figures in its own
   * order and the groups come back in that order.
   *
   * An empty input is an empty list, never a group with no figures: a tier
   * sentence said over nothing describes nothing.
   */
  fun groupsOf(
    figures: List<CoveredFigure>,
    spokenElsewhere: List<CoveredFigure> = emptyList(),
  ): List<SpokenTierGroup> {
    // One (college, field) is one figure, and one figure is on ONE tier. Two
    // tiers for the same pair would render two groups with the IDENTICAL scope
    // above contradictory sentences, with nothing for a reader to tell them
    // apart -- so it is refused here rather than printed. A caller that holds a
    // second reading of one figure has a fault upstream of this seam.
    (figures + spokenElsewhere)
      .groupBy { it.collegeName to it.figure }
      .forEach { (pair, readings) ->
        require(readings.map { it.assurance.tier }.distinct().size == 1) {
          "one figure is on one tier: college=[${pair.first}] figure=[${pair.second.wireName}] " +
            "tiers=[${readings.map { it.assurance.tier.value }.distinct()}]"
        }
      }
    // `groupBy` preserves first-encounter order for both the groups and the
    // members, which is the whole of the ordering rule above.
    val byTier = figures.groupBy { it.assurance.tier }
    val ambiguous = ambiguousFieldsOf(figures, spokenElsewhere)
    return byTier.map { (tier, covered) ->
      SpokenTierGroup(assurance = SpokenAssurance(tier), scope = scopeOf(covered, ambiguous))
    }
  }

  /**
   * The fields a group may NOT name bare, because a bare label reads across the
   * whole surface and not only across the figures this seam was handed.
   *
   * [spokenElsewhere] is the half that is easy to miss and was missed: a figure
   * this seam does not cover can still have its tier stated somewhere else on
   * the same page -- a withheld figure keeps its own sentence in its own cell --
   * and if that tier differs, an unqualified "the median federal loan debt"
   * above one sentence claims the other figure too. A header covering a figure
   * it does not describe is a worse fault than the repetition this object
   * removes, so ambiguity is computed over everything the surface SAYS A TIER
   * FOR, not over what it groups.
   *
   * Nothing in [spokenElsewhere] is ever rendered here. It only widens the set
   * of fields that must name their schools.
   */
  private fun ambiguousFieldsOf(
    figures: List<CoveredFigure>,
    spokenElsewhere: List<CoveredFigure>,
  ): Set<FigureIdentity> =
    (figures + spokenElsewhere)
      .groupBy { it.figure }
      .filterValues { withField -> withField.map { it.assurance.tier }.distinct().size > 1 }
      .keys

  /**
   * The figures this group covers, named as a family reads them.
   *
   * A field is named BARE where it is on this tier everywhere it appears, and
   * WITH ITS SCHOOLS where the page's schools put it on different tiers -- which
   * is what makes a mixed-tier page produce two right headers instead of one
   * wrong one. The qualification is derived from the figures themselves, so a
   * page that happens to be uniform never pays for the general case.
   */
  private fun scopeOf(
    covered: List<CoveredFigure>,
    ambiguous: Set<FigureIdentity>,
  ): String {
    val phrases =
      covered
        .groupBy { it.figure }
        .map { (figure, withFigure) ->
          if (figure !in ambiguous) {
            figure.spokenLabel
          } else {
            "${figure.spokenLabel} at ${phraseOf(withFigure.map { it.collegeName }.distinct())}"
          }
        }
    return "${phraseOf(phrases).replaceFirstChar { it.uppercase() }}:"
  }
}

/**
 * One figure a surface shows, as the grouping sees it: whose it is, which figure
 * it is, and what KIND of number.
 *
 * The college is carried as its NAME rather than its id because the only thing
 * done with it is saying it, and a grouping that held ids would need the caller
 * to hand it a second map to render one header.
 *
 * A school figure with NO NUMBER is never one of these, and neither is a line
 * we authored ourselves: the caller builds them from figures it actually shows,
 * so a blank cell keeps its own status sentence with its own tier and the
 * at-home `$0` housing line (RFC 166 §7) -- which carries no published cell at
 * all -- cannot be swept under a publisher's tier. A figure by field of study
 * IS one with or without a number, because its gap mark states no tier of its
 * own ([ProgramReturnCopy.coveredFiguresOf]).
 */
data class CoveredFigure(
  val collegeName: String,
  val figure: FigureIdentity,
  val assurance: SpokenAssurance,
)

/**
 * WHICH figure a [CoveredFigure] is (RFC 251): one of the school's canonical
 * money figures, or one measure of the major answer's figures by field of
 * study. Sealed, so the grouping names both kinds and a third cannot reach a
 * header unnamed.
 */
sealed interface FigureIdentity {
  /** How a family hears the figure's name mid-sentence -- what a group's scope is built from. */
  val spokenLabel: String

  /** The figure's wire name, for a refusal an operator reads. */
  val wireName: String

  /** One of the school's canonical money figures. */
  data class Cost(
    val field: CostField,
  ) : FigureIdentity {
    override val spokenLabel: String get() = this.field.spokenLabel
    override val wireName: String get() = this.field.wireName
  }

  /** One measure of the figures by field of study, across every program it is shown for. */
  data class Program(
    val measure: ProgramMoneyMeasure,
  ) : FigureIdentity {
    override val spokenLabel: String get() = ProgramReturnCopy.spokenLabelOf(measure)
    override val wireName: String get() = measure.value
  }
}

/**
 * One tier sentence and the figures it covers -- the unit a surface renders.
 *
 * [statement] is DERIVED through [SpokenAssurance], never passed in, for the
 * reason RFC 189 made that type a `value class`: a group holding one tier's code
 * beside another tier's words would otherwise compile, and the words are what a
 * family reads.
 */
data class SpokenTierGroup(
  val assurance: SpokenAssurance,
  /** The figures this sentence covers, named and punctuated, ready to sit above it. */
  val scope: String,
) {
  val tier: AssuranceTier get() = assurance.tier

  /** [AssuranceTierCopy]'s sentence for [tier], byte for byte. */
  val statement: String get() = assurance.statement
}
