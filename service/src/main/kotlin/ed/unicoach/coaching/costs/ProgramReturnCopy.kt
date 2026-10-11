package ed.unicoach.coaching.costs

import ed.unicoach.coaching.costs.canonical.CoveredFigure
import ed.unicoach.coaching.costs.canonical.FigureIdentity
import ed.unicoach.coaching.costs.canonical.FigureStatusCopy
import ed.unicoach.college.ScorecardFieldOfStudyColumns
import ed.unicoach.college.ScorecardFieldOfStudyColumns.FieldOfStudyPublisher
import ed.unicoach.common.money.WholeDollars
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.common.util.phraseOf
import ed.unicoach.db.models.ProgramMoneyMeasure
import java.net.URI

/**
 * Every sentence a surface speaks about the major answer's program figures
 * (RFC 251): the figure sentences, the short gap marks, the once-per-page
 * coverage sentence and the sources sentence. The Family Cost Report only lays
 * them out -- the `MoneySourceCopy` / `FigureStatusCopy` precedent.
 *
 * Each figure is its OWN sentence, naming its own publisher and its own cohort:
 * earnings and debt are different cohorts from different publishers, so no
 * sentence here puts two figures together, and none adds, subtracts or divides
 * one figure by another. Every `when` is exhaustive with no `else`, so a new
 * measure, status or publisher fails to compile here before it can reach a
 * family unspoken.
 */
object ProgramReturnCopy {
  /**
   * THE SCHOOL's: no bachelor's program matched the subject's prefixes here.
   * The coach's tool and the report both read it from here (moved from
   * `CollegeCostChatTool` byte for byte).
   */
  const val NO_PROGRAM_STATEMENT = "This school does not run that program."

  /** OURS, at the PROGRAM: the school runs the matched program and we hold no published figure for it. */
  const val NO_PUBLISHED_FIGURES_STATEMENT = "This school runs that program and we hold no published figures for it."

  /** OURS, at ONE MEASURE: we hold no row for this measure of a program whose other measures we do hold. */
  const val NO_FIGURE_STATEMENT = "We hold no published figure for that measure of that program at this school."

  /**
   * What is true of EVERY figure by field of study, said once for a page that
   * prints any (brief 0011 D19).
   *
   * Only the part the kind-of-number sentence does not already say: the
   * administrative-record tier sentence covers "only students who got federal
   * aid" and the blurring, so this adds the program grain and that the
   * publishers withhold a figure when too few graduates received federal aid.
   * The withholding is said at all because it is common -- 60.6% of bachelor's
   * programs for earnings, 56.1% for debt, measured on one snapshot 2026-10-11
   * -- but the share is not spoken: nothing re-checks it after a reload. It can
   * lean on that tier sentence on every page that prints a program because
   * [coveredFiguresOf] puts every figure by field of study in the kinds block,
   * with a number or without one.
   */
  const val COVERAGE_SENTENCE =
    "Figures by field of study come from the graduates of each program, and the publishers withhold a " +
      "program's figure when too few of its graduates received federal aid to keep them anonymous."

  /**
   * The figures by field of study one school's block speaks of, for the
   * kind-of-number block (RFC 191): every registry measure, on its variable's
   * own tier, where the block prints any program -- and none where it prints
   * only [NO_PROGRAM_STATEMENT].
   *
   * WITH A NUMBER OR WITHOUT ONE. A printed program speaks every measure -- a
   * figure sentence, a gap mark, or one mark for them all -- and no gap mark
   * states a tier, so the kinds block is the one place the page says what kind
   * of number a figure by field of study is. Its scope names the figure, not a
   * number: "the median earnings by field of study" comes from federal records
   * whether or not the publisher printed it for this program. Covering the
   * withheld figures too is what keeps the tier sentence said once on a page
   * whose every program figure is withheld (brief 0009 D8).
   */
  fun coveredFiguresOf(
    collegeName: String,
    college: ProgramReturnCollege,
  ): List<CoveredFigure> =
    when (college) {
      is ProgramReturnCollege.NoProgram -> {
        emptyList()
      }

      is ProgramReturnCollege.WithPrograms -> {
        ScorecardFieldOfStudyColumns.VARIABLES.map {
          CoveredFigure(collegeName, FigureIdentity.Program(it.measure), SpokenAssurance(it.assuranceTier))
        }
      }
    }

  /**
   * The sources sentence for the figures by field of study (brief 0011 D34),
   * said only on a page that prints one: whose records the dataset is built
   * from, and the one link to the College Scorecard, where a family can look
   * any program up. Worded as a fact about the DATASET rather than about this
   * page's figures, so it stays true on a page that prints only one publisher's
   * figures, or none with a number.
   */
  val SOURCES: LinkedSentence =
    LinkedSentence(
      lead =
        "The figures by field of study come from the records of " +
          phraseOf(ScorecardFieldOfStudyColumns.VARIABLES.map { spokenPublisherOf(it.publishedBy) }.distinct()) +
          ", and you can look up any program in the ",
      linkText = "College Scorecard",
      link = ScorecardFieldOfStudyColumns.DATA_PAGE,
      tail =
        ". They describe past graduates of each program who received federal aid, not this family, and they " +
          "are never added together and never compared with each other or with any other figure here.",
    )

  /** The line that opens one subject's block at one college: the subject, in the student's own answer. */
  fun subjectLeadOf(subjectName: String): String = "$subjectName, which your student wants to study:"

  /**
   * How one subject's block at one college is laid out: one mark that answers
   * for the whole subject, or its programs one by one. The renderer matches on
   * this once and decides nothing itself.
   */
  sealed interface SubjectLayout {
    /** One statement answers for the whole subject at this college, and no program is laid out. */
    data class Mark(
      val statement: String,
    ) : SubjectLayout

    /** Every matched program, each laid out with [programLabelOf] and [programSentencesOf]. */
    data class Programs(
      val programs: List<ProgramReturnProgram>,
    ) : SubjectLayout
  }

  /**
   * Two cases are one mark: the school runs no matching program, and every
   * figure of every matching program is withheld for privacy -- said once for
   * the subject rather than once per program and measure.
   */
  fun subjectLayoutOf(college: ProgramReturnCollege): SubjectLayout =
    when (college) {
      is ProgramReturnCollege.NoProgram -> {
        SubjectLayout.Mark(NO_PROGRAM_STATEMENT)
      }

      is ProgramReturnCollege.WithPrograms -> {
        layoutOfPrograms(college.programs)
      }
    }

  /** Several programs all withheld are one mark for the subject; one program speaks for itself. */
  private fun layoutOfPrograms(programs: List<ProgramReturnProgram>): SubjectLayout {
    val withheld = withheldPublishersOrNull(programs)
    return if (withheld == null || programs.size == 1) {
      SubjectLayout.Programs(programs)
    } else {
      SubjectLayout.Mark("Every matching program's figures are withheld by ${phraseOf(withheld)} to protect students' privacy.")
    }
  }

  /**
   * A program's published title, as a label to open its paragraph: "Biology, General:".
   * CIPDESC titles end in a period ("Biology, General."), which would collide
   * with the colon, so the page drops it; the chat wire keeps the title verbatim.
   */
  fun programLabelOf(program: ProgramReturnProgram): String = "${program.title.trim().removeSuffix(".")}:"

  /**
   * The sentences that follow [programLabelOf] for one program: one per measure,
   * in measure order, each a figure sentence or that measure's short gap mark.
   * A program whose every figure is withheld for privacy gets one mark.
   */
  fun programSentencesOf(program: ProgramReturnProgram): List<String> =
    when (program) {
      is ProgramReturnProgram.NoPublishedFigures -> {
        listOf(NO_PUBLISHED_FIGURES_STATEMENT)
      }

      is ProgramReturnProgram.WithFigures -> {
        sentencesOfFigures(program)
      }
    }

  /** One mark when every figure is withheld for privacy, else one sentence per figure. */
  private fun sentencesOfFigures(program: ProgramReturnProgram.WithFigures): List<String> {
    val withheld = withheldPublishersOrNull(listOf(program))
    return if (withheld != null) {
      listOf("All of this program's figures are withheld by ${phraseOf(withheld)} to protect students' privacy.")
    } else {
      program.figures.map(::sentenceOf)
    }
  }

  /** One figure, said: its value with its publisher and cohort, or its gap mark. */
  fun sentenceOf(figure: ProgramReturnFigure): String =
    when (figure) {
      is ProgramReturnFigure.Reported -> {
        valuedSentenceOf(figure)
      }

      is ProgramReturnFigure.ImputedByPublisher -> {
        "${valuedSentenceOf(figure)} ${spokenPublisherOf(figure.provenance).replaceFirstChar { it.uppercase() }} " +
          "estimated this figure; the school did not report it."
      }

      is ProgramReturnFigure.Suppressed -> {
        "${markLabelOf(figure.measure)}: withheld by ${spokenPublisherOf(figure.provenance)} to protect students' privacy."
      }

      is ProgramReturnFigure.NotReported, is ProgramReturnFigure.NotApplicable, is ProgramReturnFigure.NotCollectedByUs -> {
        "${markLabelOf(figure.measure)}: ${FigureStatusCopy.agentlessStatementOf(figure.status)}"
      }

      is ProgramReturnFigure.NoFigure -> {
        "${markLabelOf(figure.measure)}: $NO_FIGURE_STATEMENT"
      }
    }

  /**
   * How a family hears one program measure's name inside a sentence -- the
   * label the kind-of-number block names these figures by. "By field of study"
   * keeps the program debt figure apart from the school-wide median federal
   * loan debt the same page speaks.
   */
  fun spokenLabelOf(measure: ProgramMoneyMeasure): String =
    when (measure) {
      ProgramMoneyMeasure.MEDIAN_EARNINGS_4YR_AFTER_ENTRY -> "the median earnings by field of study"
      ProgramMoneyMeasure.MEDIAN_DEBT_AT_COMPLETION -> "the median federal loan debt by field of study"
      ProgramMoneyMeasure.MEDIAN_DEBT_10YR_MONTHLY_PAYMENT -> "the monthly loan payment by field of study"
    }

  /** The short label a gap mark opens with. */
  private fun markLabelOf(measure: ProgramMoneyMeasure): String =
    when (measure) {
      ProgramMoneyMeasure.MEDIAN_EARNINGS_4YR_AFTER_ENTRY -> "Median earnings"
      ProgramMoneyMeasure.MEDIAN_DEBT_AT_COMPLETION -> "Median federal loan debt"
      ProgramMoneyMeasure.MEDIAN_DEBT_10YR_MONTHLY_PAYMENT -> "Monthly payment on a standard 10-year plan"
    }

  /**
   * A figure with a number. Earnings states its dollar base because the
   * publisher names one; debt and the payment state none because the publisher
   * names none, and the payment names no interest rate (the registry's own
   * rule).
   */
  private fun valuedSentenceOf(figure: ProgramReturnFigure.Valued): String {
    val provenance = figure.provenance
    val publisher = spokenPublisherOf(provenance)
    val dollars = WholeDollars.spoken(figure.amountUsd)
    val cohort = cohortOf(provenance)
    return when (figure.measure) {
      ProgramMoneyMeasure.MEDIAN_EARNINGS_4YR_AFTER_ENTRY -> {
        "Graduates working four years after finishing earned a median of $dollars a year, according to $publisher ($cohort)."
      }

      ProgramMoneyMeasure.MEDIAN_DEBT_AT_COMPLETION -> {
        "Graduates who borrowed federal loans finished with a median federal loan debt of $dollars, according to " +
          "$publisher ($cohort)."
      }

      ProgramMoneyMeasure.MEDIAN_DEBT_10YR_MONTHLY_PAYMENT -> {
        "For graduates who borrowed federal loans, the median monthly payment on a standard 10-year plan is " +
          "$dollars, by $publisher's own estimate ($cohort)."
      }
    }
  }

  /** "students who finished in 2017-18 and 2018-19, in 2024 dollars" -- the cohort, and the dollar base where one is named. */
  private fun cohortOf(provenance: ProgramFigureProvenance): String {
    val years = spokenYearsOf(provenance.cohortFirstYear, provenance.cohortLastYear)
    val base = provenance.inflationBaseYear?.let { ", in ${it.year} dollars" }.orEmpty()
    return "students who finished in $years$base"
  }

  private fun spokenYearsOf(
    first: AcademicYear,
    last: AcademicYear,
  ): String =
    when (last.firstCalendarYear - first.firstCalendarYear) {
      0 -> first.label
      1 -> "${first.label} and ${last.label}"
      else -> "${first.label} through ${last.label}"
    }

  private fun spokenPublisherOf(provenance: ProgramFigureProvenance): String = spokenPublisherOf(provenance.variable.publishedBy)

  /** Who published a figure, as a family hears it -- never the dictionary's abbreviation. */
  private fun spokenPublisherOf(publisher: FieldOfStudyPublisher): String =
    when (publisher) {
      FieldOfStudyPublisher.TREASURY -> "the U.S. Treasury"
      FieldOfStudyPublisher.NSLDS -> "the federal student loan system"
    }

  /**
   * The publishers who withheld EVERY figure of [programs], in order of first
   * appearance, or null when any figure is anything but withheld.
   */
  private fun withheldPublishersOrNull(programs: List<ProgramReturnProgram>): List<String>? {
    val figures =
      programs.flatMap { program ->
        when (program) {
          is ProgramReturnProgram.NoPublishedFigures -> return null
          is ProgramReturnProgram.WithFigures -> program.figures
        }
      }
    val withheld = figures.map { it as? ProgramReturnFigure.Suppressed ?: return null }
    return withheld.map { spokenPublisherOf(it.provenance) }.distinct()
  }

  /** A sentence that carries one link: [lead], then [linkText] as the anchor to [link], then [tail]. */
  data class LinkedSentence(
    val lead: String,
    val linkText: String,
    val link: URI,
    val tail: String,
  )
}
