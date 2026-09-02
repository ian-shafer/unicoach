package ed.unicoach.coaching.costs

import ed.unicoach.common.util.phraseOf
import ed.unicoach.db.models.CollegeId

/**
 * WHY a figure we hold is not in this answer (RFC 157 D-A).
 *
 * A third category beside "the school reports it" and "the school does not
 * report it", and it has to be its own: the school DID publish the number, and
 * we are not showing it, because it is not a price for this family. Folding it
 * into the school's silence would blame a price list for our own applicability
 * rule -- the same error RFC 149 D-B removed from the arrangement gaps.
 *
 * One member today. The vocabulary is open on purpose: the next figure with an
 * axis nobody modelled arrives here rather than inventing a second shape.
 */
enum class WithheldReason(
  /** The stable wire code, so a reader switches on the fact rather than on its sentence. */
  val value: String,
  /**
   * The axis this reason is ABOUT -- the one that makes it true of a field (RFC
   * 157 D-D), so [WithheldFigure.of] can build the pair from the field itself
   * and a figure with no such axis has no way of being withheld for it.
   */
  val axis: ResidencyAxis,
) {
  /**
   * The figure exists only on the in-state basis
   * ([ResidencyAxis.IN_STATE_ONLY]) and this family would pay the
   * out-of-state price at this school, so the figure describes somebody else's
   * family.
   */
  IN_STATE_ONLY_FIGURE("in_state_only_figure", ResidencyAxis.IN_STATE_ONLY),
  ;

  /**
   * The sentence that goes on the wire with the code, the paired-label
   * convention every other fact in the cost domain follows (RFC 151 D-D).
   *
   * It names the reason AND points at the figures that ARE this family's, which
   * is the whole of D-A: a blank with no destination is a number taken away, and
   * the family's own totals -- built from out-of-state tuition and fees plus the
   * residency-free components -- are sitting in the same answer.
   *
   * It names no school, so it reads identically wherever it is attached.
   */
  val statement: String
    get() =
      when (this) {
        IN_STATE_ONLY_FIGURE -> {
          "This school publishes this figure for students paying in-state tuition, and this family would pay " +
            "the out-of-state price here, so it is not shown for them. The source publishes no out-of-state " +
            "version of it. The totals built from this school's out-of-state tuition and fees are the ones " +
            "that apply to this family."
        }
      }

  /**
   * The same reason in the words a TABLE CELL has room for, beside the long form
   * so neither can drift (RFC 157 D-A).
   *
   * DISPLAY COPY, and a FRAGMENT on purpose: it starts lowercase, ends with no
   * stop, and only reads as a sentence once a render site supplies the frame and
   * the pointer at the family's own totals -- where those totals are differs by
   * table, so the pointer is the caller's and the words for the reason are the
   * domain's. Read it at a render site only; the wire says this reason with
   * [statement] and [value]. A page and a coach explain one blank with one
   * vocabulary.
   */
  val cellPhrase: String
    get() =
      when (this) {
        IN_STATE_ONLY_FIGURE -> {
          "this school publishes this figure for in-state students. Your family would pay the out-of-state price"
        }
      }
}

/**
 * WHETHER the in-state-only figures describe this family -- the three outcomes,
 * never a nullable boolean (RFC 157 D-A/D-B).
 *
 * A `Boolean?` made the two wrong readings both compile, and the dangerous one
 * (`!= true`) silently folds the OPEN question into the withheld case and hides
 * the only price we hold, which D-B forbids. Named, each reader owes all three
 * an answer and every `when` is exhaustive without an `else`.
 */
enum class BlendedFigureApplicability(
  /** The stable wire code, for the same reason every other decision in this domain has one. */
  val value: String,
) {
  /** The in-state basis describes this family here: both figures are theirs. */
  APPLIES("applies"),

  /**
   * This family would pay the out-of-state price here and the source publishes
   * no counterpart, so the figures this school reports are held back.
   */
  WITHHELD("withheld"),

  /**
   * The residency question is open, so NOTHING is withheld and the basis is
   * stated instead (RFC 157 D-B). An unanswered question is not licence to hide
   * the only price we hold.
   */
  BASIS_STATED("basis_stated"),
}

/**
 * One figure held back from this family's answer, and why (RFC 157 D-A).
 *
 * The field is carried as a [CostField] rather than as a wire name so a reader
 * can ask the vocabulary about it -- which axis it is isolated on, which year it
 * dates from -- instead of matching a string.
 */
data class WithheldFigure(
  val field: CostField,
  val reason: WithheldReason,
) {
  init {
    // A reason is ABOUT an axis, and the field knows its own. Pairing a debt
    // figure with IN_STATE_ONLY_FIGURE would ship a sentence about in-state
    // tuition beside a figure that is on no residency basis at all.
    require(field.residency == reason.axis) {
      "a withheld figure's field and reason are on different residency axes: " +
        "field=[${field.wireName}] field_axis=[${field.residency}] " +
        "reason=[${reason.value}] reason_axis=[${reason.axis}]"
    }
  }

  companion object {
    /**
     * The withheld pair for [field], or null when no reason's axis describes it
     * -- the ONE constructor callers use, so a mismatched pair is never written.
     *
     * At most ONE reason per axis is what makes this lookup a function rather
     * than a choice. The vocabulary is open, so the day it gains a second reason
     * on one axis, declaration order would silently decide which sentence a
     * family reads; it is refused here instead.
     */
    fun of(field: CostField): WithheldFigure? {
      val reasons = WithheldReason.entries.filter { it.axis == field.residency }
      require(reasons.size <= 1) {
        "a residency axis has more than one withholding reason, so declaration order would pick the " +
          "sentence a family reads: axis=[${field.residency}] field=[${field.wireName}] " +
          "reasons=[${reasons.map { it.value }}]"
      }
      return reasons.singleOrNull()?.let { WithheldFigure(field, it) }
    }
  }
}

/**
 * The blended-figure line ONE school rides under, as a named vocabulary rather
 * than a literal (RFC 157 D-C).
 *
 * An enum for the reason [BlendedFigureScope] is one: a reader that wants the
 * applicability out of a code should read a member, not recognise the `_apply` /
 * `_withheld` / `_basis_stated` tail of a string. The wire form is [value], as
 * everywhere else in this domain.
 */
enum class CollegeBlendedFigureCode(
  /** The stable wire code, so a reader switches on the fact rather than on its sentence. */
  val value: String,
) {
  /** A public school this family would pay in-state tuition at: both figures are theirs. */
  IN_STATE_FIGURES_APPLY("in_state_figures_apply"),

  /** A public school this family would pay the out-of-state price at: both figures are held back. */
  IN_STATE_FIGURES_WITHHELD("in_state_figures_withheld"),

  /** A public school whose residency question is open: both figures are shown, with their basis said. */
  IN_STATE_FIGURES_BASIS_STATED("in_state_figures_basis_stated"),

  /** One published price for everyone, so the two figures are on no residency basis at all. */
  NO_RESIDENCY_BASIS("no_residency_basis"),

  /** A control we could not recognise, so we cannot say which residency the two figures are on. */
  RESIDENCY_BASIS_UNKNOWN("residency_basis_unknown"),
}

/**
 * WHICH residency the two blended figures are on AT ONE SCHOOL, and whether
 * they therefore describe this family (RFC 157 D-C).
 *
 * The per-school half of [BlendedFigureBasis], and the twin of
 * [CollegeResidencyBasis] -- deliberately a SECOND fact rather than a widened
 * first one. [CollegeResidencyBasis] is about the tuition and fees line: which
 * of a PAIR of published figures this family is charged. This one is about the
 * published price and the price after a financial aid offer, which are not a
 * pair at all -- the Scorecard publishes only the in-state version of each -- so
 * the honest answer at a school this family is out-of-state for is that neither
 * figure is theirs, not that the other one is.
 *
 * It reads [ComparedTuition], the vocabulary the residency fact already speaks,
 * so a school's tuition line and its blended-figure line can never disagree
 * about what kind of school it is or which residency applies there.
 */
data class CollegeBlendedFigureBasis(
  val collegeId: CollegeId,
  val name: String,
  val tuition: ComparedTuition,
) {
  /**
   * Whether the published price and the price after a financial aid offer
   * describe THIS family at THIS school.
   *
   * ONE call, to the rule the vocabulary itself owns
   * ([ComparedTuition.blendedFiguresApply]), which [blendedFigureApplicabilityOf] also
   * reads through the control -> vocabulary map. The service decides what to
   * withhold and this object says why; they are one expression, never two rules.
   */
  val applies: BlendedFigureApplicability get() = tuition.blendedFiguresApply

  /**
   * The stable wire code, READ from the vocabulary that owns the decision it
   * names ([ComparedTuition.blendedFigureCode]) rather than re-derived here.
   *
   * Restating the [TuitionApplicable] mapping with literals let the code and
   * [applies] drift apart with nothing to stop them; a case added to
   * [ComparedTuition] now has to answer both to compile.
   */
  val code: CollegeBlendedFigureCode get() = tuition.blendedFigureCode

  /**
   * The sentence the coach may say, and the page may print, for this school.
   *
   * A pure ROUTER over the kinds of school: the residency copy lives one level
   * down, in [publicStatement], so a three-case map is not really five.
   */
  val statement: String
    get() =
      when (val compared = tuition) {
        is ComparedTuition.Public -> {
          publicStatement(compared.tuitionApplicable)
        }

        ComparedTuition.SinglePublishedPrice -> {
          "$name publishes one price for everyone, so its $BLENDED_FIGURES are not on any residency basis."
        }

        is ComparedTuition.PublishedPriceUnknown -> {
          "$name does not say what kind of school it is in this data, so we cannot say which residency its " +
            "$BLENDED_FIGURES are for; both are shown as published."
        }
      }

  /**
   * The sentence at a PUBLIC school, where the family's residency decides
   * whether the two figures are theirs (RFC 157 D-A/D-B).
   *
   * Exhaustive: a residency case added to [TuitionApplicable] must fail to
   * compile here rather than ship a school with no copy.
   */
  private fun publicStatement(applicable: TuitionApplicable): String =
    when (applicable) {
      TuitionApplicable.IN_STATE -> {
        "$name publishes its $BLENDED_FIGURES for students paying in-state tuition, which is what this " +
          "family would pay there, so both figures are theirs."
      }

      TuitionApplicable.OUT_OF_STATE -> {
        "$name publishes its $BLENDED_FIGURES for students paying in-state tuition, and this family would " +
          "pay the out-of-state price there, so neither figure is shown for them: the totals built from " +
          "this school's out-of-state tuition and fees are the ones that apply."
      }

      TuitionApplicable.UNKNOWN -> {
        "$name publishes its $BLENDED_FIGURES for students paying in-state tuition, and the state the " +
          "family lives in is not on file, so both are shown on that basis rather than withheld."
      }
    }

  companion object {
    /**
     * The two figures this fact is about, named once in the domain's own money
     * vocabulary (RFC 141). Every sentence above is about the same two, so the
     * phrase is written once rather than five times.
     */
    const val BLENDED_FIGURES = "published price and its price after a financial aid offer"

    /**
     * The blended-figure line for the school this residency line is about -- the
     * ONE projection, so the comparison fact and the one-school fact cannot build
     * it from different fields (RFC 157 D-C).
     */
    fun of(residency: CollegeResidencyBasis): CollegeBlendedFigureBasis =
      CollegeBlendedFigureBasis(residency.collegeId, residency.name, residency.tuition)
  }
}

/**
 * WHICH schools in this call the two blended figures describe -- the decision
 * the per-call statement speaks, shipped as a code beside it (RFC 151 D-D).
 */
enum class BlendedFigureScope(
  val value: String,
) {
  /** No school here charges by residency, so neither figure is on a residency basis at all. */
  NO_RESIDENCY_BASIS_HERE("no_residency_basis_here"),

  /** The family is in-state at every public school here, so both figures are theirs everywhere in this table. */
  IN_STATE_EVERYWHERE("in_state_everywhere"),

  /** The family is out-of-state at at least one public school here, so both figures are withheld there. */
  WITHHELD_AT_SOME_SCHOOLS("withheld_at_some_schools"),

  /** A public school is in this table and the family's state is not on file, so both figures print with the basis said. */
  RESIDENCY_NOT_ON_FILE("residency_not_on_file"),

  /**
   * A school whose control we could not recognise is in this table, so no
   * residency claim about the whole table is true of it (RFC 157 D-C).
   *
   * Its own scope rather than an `else`: the quantifiers below range over the
   * schools that CHARGE by residency, which an unrecognised school does not, so
   * no other scope's sentence is true of it. It is reached only when nothing is
   * withheld in this table -- a withheld school outranks it, and that school's
   * own `by_college` line keeps saying we cannot place it. The sibling
   * [ResidencyBasis.nonPublicOnlyStatement] splits exactly this case.
   */
  BASIS_UNKNOWN_AT_SOME_SCHOOLS("basis_unknown_at_some_schools"),
}

/**
 * The SIXTH fact that makes a multi-school cost table honest (RFC 157 D-C):
 * which residency the published price and the price after a financial aid offer
 * are on, and which schools in this table they therefore do not describe.
 *
 * [ComparisonBasis] claimed for five facts that residency was one of them. For
 * two of its columns it did not keep that promise: [ResidencyBasis] is scoped to
 * tuition by construction -- its vocabulary is [ComparedTuition] and every one
 * of its sentences says "tuition and fees" -- while `COSTT4_A` and the `NPT4`
 * family were printed with no residency said at all, directly under a correct
 * and emphatic out-of-state tuition claim. This fact keeps the promise.
 *
 * Assembled from facts the service already holds, so it costs no query -- the
 * batching contract of `readInSession` is unchanged.
 */
data class BlendedFigureBasis(
  val answer: ComparedResidency,
  val byCollege: List<CollegeBlendedFigureBasis>,
) {
  init {
    // A comparison fact is about a SIDE-BY-SIDE. With fewer than two schools
    // there is nothing held constant across anything, every quantifier below
    // answers for a table that is not a comparison, and the one-school answer
    // says this in [CollegeBlendedFigureBasis] instead. [ComparisonBasis.of]
    // already refuses below two; the type refuses it too, so the boundary is
    // stated where it is guaranteed rather than only where it is checked.
    require(byCollege.size >= 2) {
      "a blended-figure basis compares fewer than two colleges, so it states a residency basis across no " +
        "side-by-side at all: colleges=[${byCollege.size}] answer=[${answer.status.value}]"
    }
  }

  /** The schools where these figures have a residency basis at all -- the only ones this fact is about. */
  private val publics: List<CollegeBlendedFigureBasis> get() = byCollege.filter { it.tuition.chargesByResidency }

  /** The schools this family is out-of-state for: the ones whose two figures are not shown. */
  private val withheldAt: List<CollegeBlendedFigureBasis>
    get() = byCollege.filter { it.applies == BlendedFigureApplicability.WITHHELD }

  /**
   * The schools whose control we could not recognise: no residency claim about
   * this table is true of them, and [publics] cannot see them, because they do
   * not charge by residency as far as we know.
   */
  private val basisUnknownAt: List<CollegeBlendedFigureBasis>
    get() = byCollege.filter { it.tuition is ComparedTuition.PublishedPriceUnknown }

  /**
   * WHICH schools these figures describe -- decided once, said aloud in
   * [statement] AND shipped as a code, so no reader has to recover the decision
   * by reading the sentence (RFC 151 D-D).
   */
  val scope: BlendedFigureScope
    get() =
      // ORDER IS LOAD-BEARING. A table can hold BOTH a school these figures are
      // withheld at and a school whose control we could not recognise, and only
      // one sentence is said per call. Withholding wins: it is the fact that
      // removes a number a parent was about to read, and the unrecognised
      // school still says its own caveat on its `by_college` line. Reordering
      // these two branches changes which of the two a family is told.
      when {
        withheldAt.isNotEmpty() -> {
          BlendedFigureScope.WITHHELD_AT_SOME_SCHOOLS
        }

        basisUnknownAt.isNotEmpty() -> {
          BlendedFigureScope.BASIS_UNKNOWN_AT_SOME_SCHOOLS
        }

        publics.isEmpty() -> {
          BlendedFigureScope.NO_RESIDENCY_BASIS_HERE
        }

        publics.all { it.applies == BlendedFigureApplicability.APPLIES } -> {
          BlendedFigureScope.IN_STATE_EVERYWHERE
        }

        publics.all { it.applies == BlendedFigureApplicability.BASIS_STATED } -> {
          BlendedFigureScope.RESIDENCY_NOT_ON_FILE
        }

        // No `else` with a sentence: one answer selects EVERY public school's
        // applicability here, so a table cannot hold an in-state school and an
        // unanswered one, and no scope has a true sentence for a mixture.
        else -> {
          error(
            "no scope states a mixed blended-figure basis: " +
              "answer=[${answer.status.value}] applies=[${publics.map { it.applies }}] " +
              "colleges=[${publics.map { it.collegeId.value }}]",
          )
        }
      }

  /**
   * Exhaustive on purpose: a scope added here must fail to compile until it says
   * itself in words, rather than shipping a code the coach has no sentence for.
   */
  val statement: String
    get() =
      when (scope) {
        BlendedFigureScope.NO_RESIDENCY_BASIS_HERE -> {
          "No school here is recorded as a public school, so the published price and the price after a " +
            "financial aid offer in this table are not on any residency basis."
        }

        BlendedFigureScope.IN_STATE_EVERYWHERE -> {
          "$PUBLIC_SCHOOLS_PUBLISH_IN_STATE This family would pay in-state tuition at every public school " +
            "here, so both figures are theirs in this table."
        }

        BlendedFigureScope.WITHHELD_AT_SOME_SCHOOLS -> {
          "$PUBLIC_SCHOOLS_PUBLISH_IN_STATE This family would pay the out-of-state price at " +
            "${phraseOf(withheldAt.map { it.name })}, so neither figure is shown there: the totals built from " +
            "out-of-state tuition and fees are the ones that apply to them."
        }

        BlendedFigureScope.RESIDENCY_NOT_ON_FILE -> {
          "$PUBLIC_SCHOOLS_PUBLISH_IN_STATE The state the family lives in is not on file, so both figures are " +
            "shown here on that in-state basis rather than withheld."
        }

        BlendedFigureScope.BASIS_UNKNOWN_AT_SOME_SCHOOLS -> {
          "$PUBLIC_SCHOOLS_PUBLISH_IN_STATE ${phraseOf(basisUnknownAt.map { it.name })} does not say what " +
            "kind of school it is in this data, so we cannot say which residency its published price and its " +
            "price after a financial aid offer are for; read that school's own line before comparing them."
        }
      }

  companion object {
    /**
     * The fact every per-call sentence but the all-private one opens with,
     * written once: the source's own basis, in the domain's money vocabulary.
     */
    const val PUBLIC_SCHOOLS_PUBLISH_IN_STATE =
      "A public school's published price and its price after a financial aid offer are figures for students " +
        "paying in-state tuition."

    /**
     * The basis for one call, from the same [CollegeCost] list [ResidencyBasis]
     * reads and the same control -> vocabulary map, so the fifth fact and the
     * sixth can never disagree about one school.
     */
    fun of(residency: ResidencyBasis): BlendedFigureBasis =
      BlendedFigureBasis(
        answer = residency.answer,
        byCollege = residency.byCollege.map(CollegeBlendedFigureBasis::of),
      )
  }
}
