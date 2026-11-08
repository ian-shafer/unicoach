package ed.unicoach.college

import ed.unicoach.college.ScorecardDictionary.Cause
import ed.unicoach.college.ScorecardDictionary.Cohort
import ed.unicoach.college.ScorecardDictionary.Dating
import ed.unicoach.college.ScorecardDictionary.ExpectedPeriod
import ed.unicoach.college.ScorecardDictionary.UnexpectedCohortShapeException
import ed.unicoach.college.ScorecardDictionary.Unreadable
import ed.unicoach.college.ScorecardDictionary.UnreadableCohortCellException
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.common.util.CalendarYear

/**
 * The publisher's grammar for one data dictionary cell (RFC 274): pure, needs no
 * file, and changes only when the publisher's wording does.
 *
 * Reads the LEADING clause of a cell -- the part before `, reported in` the
 * institution sheet appends to every IPEDS row, which names the collection, not
 * the period the figure describes.
 */
internal object ScorecardCohortGrammar {
  /** The clause the institution sheet appends to every IPEDS cell: what follows names the collection. */
  private const val REPORTED_IN = ", reported in"

  private const val ACADEMIC_YEAR_PREFIX = "AcadYr "
  private const val ENTERING_YEAR_PREFIX = "AY "
  private const val FALL_TERM_PREFIX = "Fall "
  private const val POOLED_WORD = " pooled "

  /** `AY <label> cohort`, nothing before and nothing after. */
  private val ENTERING_YEAR = Regex("""AY (\S+) cohort""")

  /** `Fall <yyyy>`, then the end or a space and the publisher's own clause. */
  private val FALL_TERM = Regex("""Fall (\d{4})(?: .+)?""")

  /** `<publisher> pooled AY<label>, AY<label> cohort`: a pooled span with no dollar base. */
  private val POOLED = Regex("""\S+ pooled AY(\S+), AY(\S+) cohort""")

  /**
   * `<publisher> AY<label>, AY<label> pooled earnings cohort measured in
   * CY<yyyy>, CY<yyyy>, inflation adjusted to <yyyy> dollars`: a pooled span
   * WITH a dollar base.
   */
  private val POOLED_EARNINGS =
    Regex("""\S+ AY(\S+), AY(\S+) pooled earnings cohort measured in CY\d{4}, CY\d{4}, inflation adjusted to (\d{4}) dollars""")

  /** [cell], read as a cohort POOLED across academic years, or the refusal naming [variable]. */
  fun readPooled(
    variable: String,
    cell: String,
  ): Dating<Cohort.PooledAcademicYears> =
    read(variable, cell).flatMap { cohort ->
      when (cohort) {
        is Cohort.PooledAcademicYears -> Dating.Dated(cohort)

        is Cohort.StatedAcademicYear,
        is Cohort.StatedTerm,
        is Cohort.StatedEnteringAcademicYear,
        Cohort.NoAcademicYear,
        -> Dating.Undatable(UnexpectedCohortShapeException(variable, cell, cohort, ExpectedPeriod.POOLED))
      }
    }

  /**
   * One cell, read: the empty cell, `AY <label> cohort`, `AcadYr <label>`,
   * `Fall <yyyy>[ …]`, or one of the two pooled shapes -- on the cell's LEADING
   * clause. Anything else is an [UnreadableCohortCellException] naming the
   * variable, the cell and the cause.
   */
  fun read(
    variable: String,
    cell: String,
  ): Dating<Cohort> =
    when (val reading = readingOf(cell)) {
      is Reading.Read -> Dating.Dated(reading.value)
      is Reading.Refused -> Dating.Undatable(UnreadableCohortCellException(variable, cell, reading.unreadable))
    }

  /** What [unreadable] says to a human, rendered here beside the shapes it names. */
  fun describe(unreadable: Unreadable): String {
    val clause = unreadable.clause
    return when (unreadable.cause) {
      Cause.WRONG_CELL_SHAPE -> {
        "[$clause] is not [${ENTERING_YEAR_PREFIX}<label> cohort]"
      }

      Cause.NOT_AN_ENTERING_TERM -> {
        "[$clause] is not [$FALL_TERM_PREFIX<yyyy>] with an optional trailing clause"
      }

      Cause.NOT_A_POOLED_COHORT -> {
        "[$clause] is neither pooled shape the publisher writes"
      }

      Cause.UNKNOWN_SHAPE -> {
        "[$clause] is no period shape the publisher's dictionary writes"
      }

      Cause.LABEL_HAS_NO_YEAR -> {
        "[$clause] does not begin with a calendar year"
      }

      Cause.YEAR_REJECTED -> {
        "[$clause] names a year its type refuses: ${unreadable.rejection?.message}"
      }

      Cause.LABEL_NOT_SPOKEN_THAT_WAY -> {
        "[$clause] is not how the publisher's grammar spells it: it reads " +
          unreadable.spellings.joinToString(" or ") { "[$it]" }
      }
    }
  }

  private fun readingOf(cell: String): Reading<Cohort> {
    val leading = cell.substringBefore(REPORTED_IN)
    return when {
      cell.isEmpty() -> Reading.Read(Cohort.NoAcademicYear)
      leading.startsWith(ENTERING_YEAR_PREFIX) -> enteringCohortOf(leading)
      leading.startsWith(ACADEMIC_YEAR_PREFIX) -> collectionYearOf(leading)
      leading.startsWith(FALL_TERM_PREFIX) -> termOf(leading)
      POOLED_WORD in leading -> pooledOf(cell, leading)
      else -> refused(Cause.UNKNOWN_SHAPE, leading)
    }
  }

  /** One step of reading a cell: the value, or the NAMED reason there is none. */
  private sealed interface Reading<out T> {
    data class Read<T>(
      val value: T,
    ) : Reading<T>

    data class Refused(
      val unreadable: Unreadable,
    ) : Reading<Nothing>

    fun <R> map(transform: (T) -> R): Reading<R> =
      when (this) {
        is Read -> Read(transform(value))
        is Refused -> this
      }
  }

  /** The value, or [early] with the refusal: a step's `.orReturn { return it }` ends the read there. */
  private inline fun <T> Reading<T>.orReturn(early: (Reading.Refused) -> Nothing): T =
    when (this) {
      is Reading.Read -> value
      is Reading.Refused -> early(this)
    }

  private fun refused(
    cause: Cause,
    clause: String,
  ): Reading.Refused = Reading.Refused(Unreadable(cause, clause))

  private fun notSpokenThatWay(
    clause: String,
    vararg spellings: String,
  ): Reading.Refused = Reading.Refused(Unreadable(Cause.LABEL_NOT_SPOKEN_THAT_WAY, clause, spellings.toList()))

  /** [build], or the [Cause.YEAR_REJECTED] refusal keeping what the year type threw about [clause]. */
  private inline fun <T> yearRead(
    clause: String,
    build: () -> T,
  ): Reading<T> =
    try {
      Reading.Read(build())
    } catch (rejection: IllegalArgumentException) {
      Reading.Refused(Unreadable(Cause.YEAR_REJECTED, clause, rejection = rejection))
    }

  private fun enteringCohortOf(leading: String): Reading<Cohort> {
    val label =
      ENTERING_YEAR.matchEntire(leading)?.groupValues?.getOrNull(1)
        ?: return refused(Cause.WRONG_CELL_SHAPE, leading)
    return academicYearLabelOf(label).map { year -> Cohort.StatedEnteringAcademicYear(leading, year) }
  }

  private fun collectionYearOf(leading: String): Reading<Cohort> =
    academicYearLabelOf(leading.removePrefix(ACADEMIC_YEAR_PREFIX)).map(Cohort::StatedAcademicYear)

  private fun termOf(leading: String): Reading<Cohort> {
    val year =
      FALL_TERM
        .matchEntire(leading)
        ?.groupValues
        ?.getOrNull(1)
        ?.toInt()
        ?: return refused(Cause.NOT_AN_ENTERING_TERM, leading)
    return Reading.Read(Cohort.StatedTerm(leading, year))
  }

  private fun pooledOf(
    cell: String,
    leading: String,
  ): Reading<Cohort> {
    val earnings = POOLED_EARNINGS.matchEntire(leading)?.groupValues
    val groups =
      earnings ?: POOLED.matchEntire(leading)?.groupValues
        ?: return refused(Cause.NOT_A_POOLED_COHORT, leading)
    val first = pooledLabelOf(groups[1]).orReturn { return it }
    val last = pooledLabelOf(groups[2]).orReturn { return it }
    if (last < first) {
      return notSpokenThatWay(leading, "AY${last.label}, AY${first.label}")
    }
    val amountUsdYear = earnings?.let { matched -> yearRead(matched[3]) { CalendarYear(matched[3].toInt()) }.orReturn { return it } }
    return Reading.Read(Cohort.PooledAcademicYears(first, last, amountUsdYear, cell))
  }

  /** `2025-26` -> that academic year, held to [AcademicYear.label] exactly. */
  private fun academicYearLabelOf(label: String): Reading<AcademicYear> {
    val year = yearOf(label).orReturn { return it }
    return if (year.label == label) {
      Reading.Read(year)
    } else {
      notSpokenThatWay(label, year.label)
    }
  }

  /**
   * A pooled cell's label: the publisher writes both `2022-23` and
   * `2017-2018` in this one shape, so either width of the second year is
   * read. The first half is exactly the year it parsed to, and the second is
   * the year after it.
   */
  private fun pooledLabelOf(label: String): Reading<AcademicYear> {
    val year = yearOf(label).orReturn { return it }
    val first = label.substringBefore('-')
    val second = label.substringAfter('-', missingDelimiterValue = "")
    val next = (year.firstCalendarYear + 1).toString()
    return if (first == year.firstCalendarYear.toString() && (second == next || second == next.takeLast(2))) {
      Reading.Read(year)
    } else {
      notSpokenThatWay(label, "${year.firstCalendarYear}-${next.takeLast(2)}", "${year.firstCalendarYear}-$next")
    }
  }

  /** The academic year a label BEGINS with, or why it names none. */
  private fun yearOf(label: String): Reading<AcademicYear> {
    val first =
      label.substringBefore('-').toIntOrNull()
        ?: return refused(Cause.LABEL_HAS_NO_YEAR, label)
    return yearRead(label) { AcademicYear(first) }
  }
}
