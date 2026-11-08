package ed.unicoach.coaching.costs

import ed.unicoach.coaching.costs.canonical.CoveredFigure
import ed.unicoach.coaching.costs.canonical.FigureIdentity
import ed.unicoach.coaching.costs.canonical.FigureStatusCopy
import ed.unicoach.college.ScorecardFieldOfStudyColumns
import ed.unicoach.college.ScorecardFieldOfStudyColumns.FieldOfStudyPublisher
import ed.unicoach.college.ScorecardFieldOfStudyColumns.FieldOfStudyVariable
import ed.unicoach.common.money.WholeDollars
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.common.util.phraseOf
import ed.unicoach.db.models.ProgramMoneyMeasure
import java.net.URI

/**
 * Everything a surface speaks about the major answer's program figures (RFC
 * 251): the table each subject's programs print in (its caption, headers, cells
 * and notes), the whole-row and whole-subject marks, and the once-per-page
 * coverage, cohort and sources sentences. The Family Cost Report only lays them
 * out -- the `MoneySourceCopy` / `FigureStatusCopy` precedent.
 *
 * Each figure is its OWN cell, under a header naming its own publisher: earnings
 * and debt are different cohorts from different publishers, so nothing here puts
 * two figures together, and nothing adds, subtracts or divides one figure by
 * another. Every `when` is exhaustive with no `else`, so a new measure, status
 * or publisher fails to compile here before it can reach a family unspoken.
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

  /** The header over a program table's row headers. */
  const val PROGRAM_COLUMN_HEADER = "Program"

  /** Directly after a figure the publisher estimated, and opening the note under the table that says so. */
  private const val IMPUTED_MARKER = "*"

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
   * figure, a gap mark, or one mark for them all -- and no gap mark states a
   * tier, so the kinds block is the one place the page says what kind
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
   * The caption of one subject's table at one college. It names no publisher:
   * every money column's header already does (brief 0016 D2).
   */
  fun subjectCaptionOf(
    subjectName: String,
    collegeName: String,
  ): String = "$subjectName programs at $collegeName, which your student wants to study"

  /**
   * How one subject's block at one college is laid out. The renderer matches on
   * this once and decides nothing itself.
   */
  sealed interface SubjectLayout {
    /** One statement answers for the whole subject at this college, and no program is laid out. */
    data class Mark(
      val statement: String,
    ) : SubjectLayout

    /** Every program is one sentence with no figure in it -- no figure, so no table. */
    data class Statements(
      val programs: List<ProgramRow.WholeRow>,
    ) : SubjectLayout

    /** At least one program has a cell per measure. */
    data class Table(
      val table: ProgramTable,
    ) : SubjectLayout
  }

  /** One subject's programs at one college, as rows under [columns]. No total: the rows are never added together. */
  @ConsistentCopyVisibility
  data class ProgramTable private constructor(
    val columns: List<ProgramColumn>,
    /** In the answer's own order: nobody's favourite is promoted or sorted by value. */
    val rows: List<ProgramRow>,
  ) {
    /** Each said once for the table, one per publisher with a [ProgramCell.Estimate] in its rows; empty when there is none. */
    val imputedNotes: List<String> =
      rows
        .filterIsInstance<ProgramRow.Cells>()
        .flatMap { it.cells.values }
        .filterIsInstance<ProgramCell.Estimate>()
        .map { it.publisher }
        .distinct()
        .map(::imputedNoteOf)

    companion object {
      /** Under the registry's columns, always: every measure a row holds a cell for is printed, so no estimate loses its column. */
      fun ofRows(rows: List<ProgramRow>): ProgramTable = ProgramTable(PROGRAM_COLUMNS, rows)
    }
  }

  /** One money column: the measure its cells are looked up by, and the header naming its publisher. */
  data class ProgramColumn(
    val measure: ProgramMoneyMeasure,
    val header: String,
  )

  /** One program's row, headed by its published title with the publisher's trailing period dropped. */
  sealed interface ProgramRow {
    val title: String

    /** One [ProgramCell] per measure, read by [ProgramColumn.measure] and never by position. */
    @ConsistentCopyVisibility
    data class Cells private constructor(
      override val title: String,
      val cells: Map<ProgramMoneyMeasure, ProgramCell>,
    ) : ProgramRow {
      companion object {
        /** A cell for every measure, in [ProgramMoneyMeasure] declaration order, so a column's lookup always finds one. */
        fun of(
          title: String,
          cellOf: (ProgramMoneyMeasure) -> ProgramCell,
        ): Cells = Cells(title, ProgramMoneyMeasure.entries.associateWith(cellOf))
      }
    }

    /** One sentence answers for the whole program, across every column. */
    data class WholeRow(
      override val title: String,
      val statement: String,
    ) : ProgramRow
  }

  /** What one cell of a [ProgramRow.Cells] holds: a figure, or the sentence that stands where one is not. Never empty. */
  sealed interface ProgramCell {
    data class Figure(
      val text: String,
    ) : ProgramCell

    /** A figure [publisher] estimated: its cell carries the marker, and [ProgramTable.imputedNotes] says who estimated it. */
    data class Estimate(
      val amount: String,
      val publisher: FieldOfStudyPublisher,
    ) : ProgramCell {
      val text: String get() = "$amount$IMPUTED_MARKER"
    }

    data class Gap(
      val statement: String,
    ) : ProgramCell
  }

  /**
   * The school runs no matching program, or every figure of several matching
   * programs is withheld for privacy: one mark, said once for the subject rather
   * than once per program and measure. Otherwise the programs are laid out.
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

  /** Several programs all withheld are one mark; a row of only whole-row sentences has no figure to tabulate. */
  private fun layoutOfPrograms(programs: List<ProgramReturnProgram>): SubjectLayout {
    val withheld = withheldPublishersOrNull(programs)
    if (withheld != null && programs.size > 1) {
      return SubjectLayout.Mark("Every matching program's figures are withheld by ${phraseOf(withheld)} to protect students' privacy.")
    }
    val rows = programs.map(::rowOf)
    val statements = rows.filterIsInstance<ProgramRow.WholeRow>()
    return if (statements.size == rows.size) {
      SubjectLayout.Statements(statements)
    } else {
      SubjectLayout.Table(ProgramTable.ofRows(rows))
    }
  }

  /** A whole-row program as a label to open its paragraph: "Biology, General:". */
  fun programLabelOf(program: ProgramRow.WholeRow): String = "${program.title}:"

  /**
   * CIPDESC titles end in a period ("Biology, General."), which would read as a
   * sentence in a row header and collide with [programLabelOf]'s colon, so the
   * page drops it; the chat wire keeps the title verbatim.
   */
  private fun rowOf(program: ProgramReturnProgram): ProgramRow {
    val title = program.title.trim().removeSuffix(".")
    return when (program) {
      is ProgramReturnProgram.NoPublishedFigures -> {
        ProgramRow.WholeRow(title, NO_PUBLISHED_FIGURES_STATEMENT)
      }

      is ProgramReturnProgram.WithFigures -> {
        rowOfFigures(title, program)
      }
    }
  }

  /** One sentence when every figure is withheld for privacy, else one cell per figure. */
  private fun rowOfFigures(
    title: String,
    program: ProgramReturnProgram.WithFigures,
  ): ProgramRow {
    val withheld = withheldPublishersOrNull(listOf(program))
    return if (withheld != null) {
      ProgramRow.WholeRow(title, "All of this program's figures are withheld by ${phraseOf(withheld)} to protect students' privacy.")
    } else {
      val figures = program.figures.associateBy { it.measure }
      ProgramRow.Cells.of(title) { measure -> cellOf(figures.getValue(measure)) }
    }
  }

  /** One figure, as its cell: the amount alone, or the sentence alone, because the header is the label. */
  private fun cellOf(figure: ProgramReturnFigure): ProgramCell =
    when (figure) {
      is ProgramReturnFigure.Reported -> {
        ProgramCell.Figure(WholeDollars.spoken(figure.amountUsd))
      }

      is ProgramReturnFigure.ImputedByPublisher -> {
        ProgramCell.Estimate(WholeDollars.spoken(figure.amountUsd), figure.provenance.variable.publishedBy)
      }

      is ProgramReturnFigure.Suppressed -> {
        ProgramCell.Gap(withheldStatementOf(figure))
      }

      is ProgramReturnFigure.NotReported, is ProgramReturnFigure.NotApplicable, is ProgramReturnFigure.NotCollectedByUs -> {
        ProgramCell.Gap(absenceStatementOf(figure))
      }

      is ProgramReturnFigure.NoFigure -> {
        ProgramCell.Gap(NO_FIGURE_STATEMENT)
      }
    }

  private fun withheldStatementOf(figure: ProgramReturnFigure.Suppressed): String =
    "Withheld by ${spokenPublisherOf(figure.provenance)} to protect students' privacy."

  private fun absenceStatementOf(figure: ProgramReturnFigure.Held): String =
    checkNotNull(FigureStatusCopy.agentlessStatementOf(figure.status)) {
      "[${figure.status}] is an absence, so it has a sentence: only a reported figure has none"
    }

  /** One note per publisher, not per figure: each marked cell sits under a header naming its publisher, so one marker suffices. */
  private fun imputedNoteOf(publisher: FieldOfStudyPublisher): String =
    "$IMPUTED_MARKER ${spokenPublisherOf(
      publisher,
    ).replaceFirstChar { it.uppercase() }} estimated this figure; the school did not report it."

  /** The money columns, in registry order. */
  private val PROGRAM_COLUMNS: List<ProgramColumn> =
    ScorecardFieldOfStudyColumns.VARIABLES.map { ProgramColumn(it.measure, columnHeaderOf(it)) }

  /** Unit, moment and publisher: everything every cell under it would otherwise repeat. */
  private fun columnHeaderOf(variable: FieldOfStudyVariable): String {
    val publisher = spokenPublisherOf(variable.publishedBy)
    return when (variable.measure) {
      ProgramMoneyMeasure.MEDIAN_EARNINGS_4YR_AFTER_ENTRY -> {
        "Median earnings a year, four years after finishing, according to $publisher"
      }

      ProgramMoneyMeasure.MEDIAN_DEBT_AT_COMPLETION -> {
        "Median federal loan debt at finishing, according to $publisher"
      }

      ProgramMoneyMeasure.MEDIAN_DEBT_10YR_MONTHLY_PAYMENT -> {
        "Median monthly payment on a standard 10-year plan, by $publisher's own estimate"
      }
    }
  }

  /**
   * Who each printed kind of figure describes and which cohort, said once for
   * the page because it is a property of the release, identical on every row --
   * or null where no figure on the page has a number.
   *
   * Read off the provenance of the figures with a number and no others, so the
   * page never names the cohort of a figure it does not print. Measures sharing a
   * population and a cohort are one clause; a registry re-pin that separates them
   * makes two.
   */
  fun cohortsSentenceOf(colleges: List<ProgramReturnCollege>): String? =
    colleges
      .flatMap(::programsOf)
      .flatMap(::figuresOf)
      .filterIsInstance<ProgramReturnFigure.Valued>()
      .map { it.provenance }
      .distinct()
      .sortedBy { ScorecardFieldOfStudyColumns.VARIABLES.indexOf(it.variable) }
      .groupBy { populationOf(it.variable.measure) to cohortOf(it) }
      .map { (scope, provenances) -> cohortClauseOf(scope.first, scope.second, provenances) }
      .takeIf { it.isNotEmpty() }
      ?.joinToString(" ")

  private fun cohortClauseOf(
    population: String,
    cohort: String,
    provenances: List<ProgramFigureProvenance>,
  ): String {
    val nouns = provenances.map { cohortNounOf(it.variable.measure) }
    val names = phraseOf(nouns.map { it.words }).replaceFirstChar { it.uppercase() }
    val verb = nouns.singleOrNull()?.verbAlone ?: "describe"
    return "$names by field of study $verb $population ($cohort)."
  }

  /** How the cohort sentence names a measure, and the verb that name takes when it stands alone. */
  private class CohortNoun(
    val words: String,
    val verbAlone: String,
  )

  private fun cohortNounOf(measure: ProgramMoneyMeasure): CohortNoun =
    when (measure) {
      ProgramMoneyMeasure.MEDIAN_EARNINGS_4YR_AFTER_ENTRY -> CohortNoun("earnings", "describe")
      ProgramMoneyMeasure.MEDIAN_DEBT_AT_COMPLETION -> CohortNoun("federal loan debt", "describes")
      ProgramMoneyMeasure.MEDIAN_DEBT_10YR_MONTHLY_PAYMENT -> CohortNoun("monthly payment", "describes")
    }

  /** Which graduates a measure is about. */
  private fun populationOf(measure: ProgramMoneyMeasure): String =
    when (measure) {
      ProgramMoneyMeasure.MEDIAN_EARNINGS_4YR_AFTER_ENTRY -> {
        "graduates working four years after finishing"
      }

      ProgramMoneyMeasure.MEDIAN_DEBT_AT_COMPLETION, ProgramMoneyMeasure.MEDIAN_DEBT_10YR_MONTHLY_PAYMENT -> {
        "graduates who borrowed federal loans"
      }
    }

  private fun programsOf(college: ProgramReturnCollege): List<ProgramReturnProgram> =
    when (college) {
      is ProgramReturnCollege.NoProgram -> emptyList()
      is ProgramReturnCollege.WithPrograms -> college.programs
    }

  private fun figuresOf(program: ProgramReturnProgram): List<ProgramReturnFigure> =
    when (program) {
      is ProgramReturnProgram.NoPublishedFigures -> emptyList()
      is ProgramReturnProgram.WithFigures -> program.figures
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

  /** "students who finished in 2017-18 and 2018-19, in 2024 dollars" -- the cohort, and the dollar base where one is named. */
  private fun cohortOf(provenance: ProgramFigureProvenance): String {
    val years = spokenYearsOf(provenance.cohortFirstYear, provenance.cohortLastYear)
    val base = provenance.amountUsdYear?.let { ", in ${it.year} dollars" }.orEmpty()
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
