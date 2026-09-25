package ed.unicoach.college

import ed.unicoach.common.util.AcademicYear
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.File

/**
 * The committed Scorecard dictionary transcription
 * (`db/seed/scorecard/dictionary-variable-sources.csv`), read ONCE for every
 * test that pins something against it: the tier map
 * (`AssuranceTierClosureTest`) reads its `source` column, the year constants
 * (`ScorecardDictionaryPinTest`) its `most_recent_cohort` column.
 *
 * One reader, because two readers of one committed artifact drift apart
 * quietly: the walk, the CSV builder chain and the file NAME were each written
 * twice, and the second copy re-typed the name the first held as a constant.
 *
 * The CSV cells stay EXACTLY as the publisher writes them -- `AcadYr 2024-25`,
 * `AY 2016-17 cohort`, and empty where the publisher pools a measure across
 * cohorts. A
 * transcription that edited its source would not be a transcription. What is
 * typed is the READER: each cell is parsed ONCE, here, into [Cohort],
 * so no caller re-assembles the `AcadYr ` prefix and none compares a year to
 * the empty string.
 *
 * The seed directory is found by walking UP from the test's working directory,
 * so no test states a depth and none copies the file into resources.
 */
internal object ScorecardDictionaryTranscription {
  const val FILE_NAME = "dictionary-variable-sources.csv"

  /** Where the committed seed lives, relative to any directory on the walk up. */
  private const val SEED_PATH = "db/seed/scorecard"

  /** The publisher's own way of saying "academic year", verbatim, as the cell carries it. */
  private const val ACADEMIC_YEAR_PREFIX = "AcadYr "

  /** The publisher's own way of naming an entering fall term, verbatim. */
  private const val FALL_TERM_PREFIX = "Fall "

  /**
   * The publisher's own way of naming a full ENTERING ACADEMIC YEAR, verbatim
   * (RFC 242): `AY 2016-17 cohort`, as the `Most_Recent_Inst_Cohort_Map` sheet
   * writes the Outcome Measures variables.
   *
   * A THIRD shape, not a loosening of [ACADEMIC_YEAR_PREFIX]. That prefix is
   * how the sheet spells a money cell's collection year and its arm
   * deliberately refuses a trailing clause; this one spells the year a group
   * of students ARRIVED and always carries the word `cohort`. Folding them
   * would let an `AcadYr` money cell read as an entering cohort and the other
   * way round, which is the two-years-apart mistake RFC 183 exists for.
   */
  private const val ENTERING_YEAR_PREFIX = "AY "

  val directory: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, SEED_PATH) }
      // `firstOrNull` plus a named error, because `first { }` throws
      // `NoSuchElementException` naming neither the directory sought nor the
      // one the walk started from -- and it throws during object INIT, so two
      // suites fail as `ExceptionInInitializerError` over a sequence.
      .firstOrNull { it.isDirectory }
      ?: error(
        "no [$SEED_PATH] directory on the walk UP from [${File(".").absoluteFile}] to the filesystem root: " +
          "[$FILE_NAME] is a committed artifact every pin test reads, so a checkout without it cannot be pinned",
      )

  val file: File get() = File(directory, FILE_NAME)

  /** `variable_name -> source`: the publisher's own SOURCE value, verbatim. */
  val sourceByVariable: Map<String, String> by lazy { column("source") }

  /**
   * `variable_name -> most_recent_cohort`, VERBATIM: the cell as the publisher
   * wrote it, unparsed.
   *
   * Published beside [cohortByVariable] so a caller that REFUSES a parsed
   * cohort can name what it refused. A reader's opinion of a cell -- the
   * academic year it read out of it -- is not the cell, and a refusal that
   * prints only the opinion sends the reader to open the CSV to learn what was
   * actually transcribed.
   */
  val cellByVariable: Map<String, String> by lazy { column("most_recent_cohort") }

  /**
   * `variable_name -> most_recent_cohort`, parsed: a stated academic year, a
   * stated entering academic year, a stated entering term, or the named
   * no-year state.
   */
  val cohortByVariable: Map<String, Cohort> by lazy {
    column("most_recent_cohort").mapValues { (variable, cell) -> cohortOf(variable, cell) }
  }

  /**
   * How the publisher's dictionary dates one variable -- a CLOSED type, so
   * "the publisher states no academic year" is a state with a name rather than
   * an empty string of the same type as `AcadYr 2024-25`, and a Fall term is
   * carried as a term rather than parsed into a year it is not.
   */
  sealed interface Cohort {
    /** The dictionary names an academic year for this variable. */
    data class StatedAcademicYear(
      val year: AcademicYear,
    ) : Cohort

    /**
     * The dictionary names an entering TERM for this variable (`Fall 2024`).
     *
     * [term] is the cell VERBATIM. [autumnYear] is the calendar year that term
     * names, parsed ONCE here, at the read boundary, for the pin test only:
     * an entering term, an academic year and the year a figure DESCRIBES are
     * three different facts (RFC 226; RFC 183 found two Scorecard stamps wrong
     * by two years by mixing them), and [autumnYear] is the first only.
     */
    data class StatedTerm(
      val term: String,
      val autumnYear: Int,
    ) : Cohort

    /**
     * The dictionary names a full ENTERING ACADEMIC YEAR for this variable
     * (`AY 2016-17 cohort`) -- the year a group of students arrived, spread
     * across a July-June year rather than starting in one term (RFC 242).
     *
     * [cell] is the cell VERBATIM, on [StatedTerm]'s terms. [year] is the
     * academic year it names, parsed ONCE here.
     *
     * DISTINCT from [StatedAcademicYear] even though both carry an
     * [AcademicYear]: that one is the year a money figure was COLLECTED for,
     * this one is the year a cohort ENTERED, and a type that let one be read
     * as the other is exactly the confusion RFC 183 found two Scorecard stamps
     * wrong by two years over. The spoken forms differ too -- a reader of this
     * arm says the academic year, never an autumn.
     */
    data class StatedEnteringAcademicYear(
      val cell: String,
      val year: AcademicYear,
    ) : Cohort

    /** The publisher pools the measure across cohorts and states no academic year at all. */
    data object NoAcademicYear : Cohort
  }

  /**
   * One `most_recent_cohort` cell, read. An empty cell is the publisher's "no
   * academic year"; an `AY <label> cohort` sentence is the academic year a
   * group of students ENTERED in; an `AcadYr <label>` sentence is the academic
   * year that label speaks; anything else must name an entering fall term
   * (`Fall 2024`),
   * carried verbatim as [Cohort.StatedTerm] with its year parsed once -- so a
   * snapshot that re-words the sheet, or a cell this reader cannot parse,
   * fails HERE, naming the cell, instead of comparing unequal for a reason no
   * assertion states.
   *
   * Internal rather than private, for [column]'s own reason: the committed
   * transcription must never carry a cell this refuses, so the only way to see
   * a refusal fire is to hand the reader one.
   */
  internal fun cohortOf(
    variable: String,
    cell: String,
  ): Cohort =
    when {
      cell.isEmpty() -> Cohort.NoAcademicYear
      cell.startsWith(ENTERING_YEAR_PREFIX) -> enteringCohortOf(variable, cell)
      cell.startsWith(ACADEMIC_YEAR_PREFIX) -> collectionYearCohortOf(variable, cell)
      else -> termCohortOf(variable, cell)
    }

  /**
   * The `AY <label> cohort` shape: the academic year a group of students
   * ENTERED in (RFC 242).
   *
   * Its own function, with [cohortOf] left as the router its doc describes:
   * the routing decision -- which cell shape is this? -- and the three parsers,
   * the three folds into a [Cohort] and the three refusal sentences were one
   * body, so the routing could not be read without reading a parser and two
   * paragraphs of prose.
   */
  private fun enteringCohortOf(
    variable: String,
    cell: String,
  ): Cohort =
    when (val read = enteringAcademicYearOf(cell)) {
      is AcademicYearCell.Year -> {
        Cohort.StatedEnteringAcademicYear(cell, read.year)
      }

      is AcademicYearCell.NotAYear -> {
        throw UnreadableCohortCellException(
          variable,
          cell,
          read,
          "[$FILE_NAME] states [most_recent_cohort=$cell] for [$variable], which is not an entering " +
            "academic-year cohort the way the publisher writes one ([${ENTERING_YEAR_PREFIX}2016-17" +
            "$ENTERING_YEAR_SUFFIX]): ${read.reason}",
        )
      }
    }

  /**
   * The `AcadYr <label>` shape: the year a MONEY figure was collected for,
   * never an entering cohort.
   */
  private fun collectionYearCohortOf(
    variable: String,
    cell: String,
  ): Cohort =
    when (val read = academicYearOf(cell)) {
      is AcademicYearCell.Year -> {
        Cohort.StatedAcademicYear(read.year)
      }

      is AcademicYearCell.NotAYear -> {
        throw UnreadableCohortCellException(
          variable,
          cell,
          read,
          "[$FILE_NAME] states [most_recent_cohort=$cell] for [$variable], which is not an academic year the " +
            "way AcademicYear.label speaks one ([${ACADEMIC_YEAR_PREFIX}2024-25]): ${read.reason}",
        )
      }
    }

  /**
   * The `Fall <yyyy>` shape: an entering term, carried verbatim with its year
   * parsed once.
   *
   * The router's LAST arm, so its refusal is also "this cell is no shape this
   * reader knows". It refuses through [UnreadableCohortCellException] like the
   * other two shapes -- it used to be the one arm that refused through
   * `require`, so a caller wanting "this cell is unreadable" had to catch two
   * unrelated types and could route on a cause for only two shapes of three.
   * Its cause is its OWN ([AcademicYearCell.Cause.NOT_AN_ENTERING_TERM]) and
   * not the `AY` arm's [AcademicYearCell.Cause.WRONG_CELL_SHAPE], so a test
   * asserting the cause can still tell a mis-ROUTED cell from a cell the right
   * parser refused.
   */
  private fun termCohortOf(
    variable: String,
    cell: String,
  ): Cohort {
    val autumnYear =
      autumnYearOf(cell)
        ?: throw UnreadableCohortCellException(
          variable,
          cell,
          AcademicYearCell.NotAYear(
            AcademicYearCell.Cause.NOT_AN_ENTERING_TERM,
            "[$cell] is not [$FALL_TERM_PREFIX<yyyy>] with an optional trailing clause",
          ),
          "[$FILE_NAME] states [most_recent_cohort=$cell] for [$variable], which is neither an academic-year " +
            "sentence, an empty cell, nor an entering term naming a fall year ([${FALL_TERM_PREFIX}2024])",
        )
    return Cohort.StatedTerm(cell, autumnYear)
  }

  /**
   * A committed dictionary cell this reader REFUSES, with the cell, the
   * variable it was transcribed for and the STRUCTURED cause all kept.
   *
   * The cause used to be flattened into the message at the point it was
   * discovered, so a test exercising three genuinely different transcription
   * slips could only assert the one substring all three share -- and a reader
   * who swapped the shape check for the label check would have kept the suite
   * green. A caller routes on [read], never on the message text.
   *
   * ALL THREE cell shapes refuse this way -- the `AY … cohort` sentence, the
   * `AcadYr` sentence and the `Fall <yyyy>` term -- so "this cell is
   * unreadable" is ONE type to catch and every shape's cause is routable.
   */
  internal class UnreadableCohortCellException(
    val variable: String,
    val cell: String,
    val read: AcademicYearCell.NotAYear,
    message: String,
  ) : IllegalStateException(message)

  /**
   * `"AcadYr 2024-25"` -> that academic year, or the NAMED reason the label is
   * not one: a label that names no calendar year, a year [AcademicYear] itself
   * rejects, or a label that type does not speak that way.
   *
   * The reason TRAVELS. `AcademicYear`'s own `init` refuses a year with a
   * sentence naming the bad year and the legal range, and a `runCatching { }
   * .getOrNull()` here threw that away: three different transcription mistakes
   * arrived at [cohortOf] as one `null`, and the refusal could then only recite
   * the legal shape back at a reader who already knew it.
   *
   * One reader per cell shape, beside [autumnYearOf]: [cohortOf] used to
   * delegate the term shape and implement this one inline, so the routing could
   * not be read without reading a parser. The PREFIX is this shape's only
   * business -- reading the label is [academicYearLabelOf]'s, which is where
   * the paragraph above is enforced.
   */
  private fun academicYearOf(cell: String): AcademicYearCell = academicYearLabelOf(cell.removePrefix(ACADEMIC_YEAR_PREFIX))

  /**
   * One academic-year LABEL (`2016-17`), read: the year it names, or the NAMED
   * reason it names none -- a label that names no calendar year, a year
   * [AcademicYear] itself rejects, or a label that type does not speak that
   * way.
   *
   * The ONE home of that reading, shared by [academicYearOf] and
   * [enteringAcademicYearOf]. The two cell SHAPES differ in how the label is
   * cut out of the cell and in nothing else, and this step was written out
   * under each of them: two copies of "what a year is" can disagree while both
   * pass, and the next publisher shape would have made a third copy.
   *
   * Shape-agnostic on purpose: WHICH cell shape carried the label is the
   * caller's concern, and holding the label to [AcademicYear.label] is this
   * function's.
   */
  private fun academicYearLabelOf(label: String): AcademicYearCell {
    val firstYear =
      label.substringBefore('-').toIntOrNull()
        ?: return AcademicYearCell.NotAYear(
          AcademicYearCell.Cause.LABEL_HAS_NO_YEAR,
          "[$label] does not begin with a calendar year",
        )
    val year =
      runCatching { AcademicYear(firstYear) }
        .getOrElse { rejection ->
          return AcademicYearCell.NotAYear(AcademicYearCell.Cause.YEAR_REJECTED, rejection.message ?: "$rejection")
        }
    return if (year.label == label) {
      AcademicYearCell.Year(year)
    } else {
      AcademicYearCell.NotAYear(
        AcademicYearCell.Cause.LABEL_NOT_SPOKEN_THAT_WAY,
        "[$label] is not how AcademicYear speaks [$firstYear]: it says [${year.label}]",
      )
    }
  }

  /**
   * `"AY 2016-17 cohort"` -> that academic year, or the NAMED reason it is not
   * one (RFC 242).
   *
   * `matchEntire` against a stated shape, on [FALL_TERM]'s terms and not
   * `removePrefix`/`removeSuffix`: the publisher's word `cohort` is REQUIRED,
   * so `AY 2016-17` alone, `AY 2016-17 cohort, reported in ...` and any other
   * trailing clause are refusals rather than silent successes. Cutting that
   * label out of the cell is ALL this function does; the label itself is read
   * by [academicYearLabelOf], the same reading `AcadYr` gets, so
   * `AY 2016-2017 cohort` cannot read as 2016-17.
   */
  private fun enteringAcademicYearOf(cell: String): AcademicYearCell =
    ENTERING_YEAR
      .matchEntire(cell)
      ?.groupValues
      // `getOrNull`, not `get`: `List.get` THROWS on a short list, so the one
      // failure state this reader exists to turn into a named refusal -- a
      // regex that no longer yields group 1 -- would escape as an
      // `IndexOutOfBoundsException` naming neither the cell nor the variable.
      // The `?.` chain reads as total and cannot guard an index.
      ?.getOrNull(1)
      ?.let(::academicYearLabelOf)
      ?: AcademicYearCell.NotAYear(
        AcademicYearCell.Cause.WRONG_CELL_SHAPE,
        "[$cell] is not [$ENTERING_YEAR_PREFIX<label>$ENTERING_YEAR_SUFFIX] with nothing else in it",
      )

  /**
   * One `AcadYr` or `AY … cohort` label, read: the year it names, or WHY it
   * names none -- and the same NAMED refusal the `Fall <yyyy>` arm carries, so
   * all three cell shapes refuse through one type with one routable cause.
   */
  internal sealed interface AcademicYearCell {
    data class Year(
      val year: AcademicYear,
    ) : AcademicYearCell

    /**
     * The label names no academic year, with the CAUSE kept beside the
     * sentence.
     *
     * [cause] is what a caller routes on; [reason] is the same fact said to a
     * human, and only [reason] reaches a refusal message. The two were one
     * formatted string, so three different transcription mistakes -- a cell of
     * the wrong shape, a label that names no year, and a label `AcademicYear`
     * does not speak that way -- arrived at a test as one indistinguishable
     * sentence prefix.
     */
    data class NotAYear(
      val cause: Cause,
      val reason: String,
    ) : AcademicYearCell

    /** WHY a cell names no period this reader can read: a closed set, so a further cause is a compile error. */
    enum class Cause {
      /** The cell does not have the `AY <label> cohort` shape this reader accepts at all. */
      WRONG_CELL_SHAPE,

      /**
       * The cell names no entering fall term either, so it is no shape this
       * reader knows: not empty, not `AY … cohort`, not `AcadYr …`, not
       * `Fall <yyyy>`.
       */
      NOT_AN_ENTERING_TERM,

      /** The label does not begin with a calendar year. */
      LABEL_HAS_NO_YEAR,

      /** [AcademicYear] itself refused the year the label names. */
      YEAR_REJECTED,

      /** [AcademicYear] does not spell that year the way the label does. */
      LABEL_NOT_SPOKEN_THAT_WAY,
    }
  }

  /**
   * The ONE cell shape this reader accepts: the publisher's own word, a
   * four-digit year, and then either the end of the cell or a space and the
   * publisher's own clause.
   *
   * `matchEntire` against a stated shape, not a digit walk: `takeWhile { it
   * .isDigit() }` asked only "does a digit run start here", so `Fall 2024-25`
   * read as 2024, `Fall 20245` as the year 20245, and a future cell naming two
   * terms kept the first and silently dropped the second -- each of them a
   * re-dated figure on the one path whose job is to FAIL when a snapshot
   * re-dates something. Its sibling [academicYearOf] has always demanded the
   * whole label back.
   */
  private val FALL_TERM = Regex("""${Regex.escape(FALL_TERM_PREFIX)}(\d{4})(?: .+)?""")

  /** The publisher's own closing word on an entering-cohort cell, verbatim. */
  private const val ENTERING_YEAR_SUFFIX = " cohort"

  /**
   * The ONE entering-academic-year cell shape this reader accepts (RFC 242):
   * the publisher's `AY `, a label, and the publisher's own word `cohort` --
   * nothing before it and nothing after it.
   */
  private val ENTERING_YEAR =
    Regex("""${Regex.escape(ENTERING_YEAR_PREFIX)}(.+)${Regex.escape(ENTERING_YEAR_SUFFIX)}""")

  /** `"Fall 2018 or AcadYr 2018-19 cohort (…)"` -> 2018; `"Fall 2024"` -> 2024; any other shape -> null. */
  private fun autumnYearOf(cell: String): Int? =
    FALL_TERM
      .matchEntire(cell)
      ?.groupValues
      // `getOrNull` for [enteringAcademicYearOf]'s reason: an indexed access
      // inside a safe-call chain is not guarded by it.
      ?.getOrNull(1)
      ?.toInt()

  private fun column(name: String): Map<String, String> = column(file, name)

  /**
   * The reader itself, over an arbitrary [source] file.
   *
   * Not private, and takes the file rather than reading [file], so the
   * duplicate-row refusal below is provable: the committed transcription must
   * never carry a duplicate, so the only way to see the guard fire is to hand
   * the reader a file that does.
   */
  internal fun column(
    source: File,
    name: String,
  ): Map<String, String> =
    CSVParser
      .parse(
        source,
        Charsets.UTF_8,
        CSVFormat.DEFAULT
          .builder()
          .setHeader()
          .setSkipHeaderRecord(true)
          .build(),
      ).use { records ->
        // A repeated variable is a plausible transcription slip, and
        // `associate` would silently keep the LAST of two contradictory rows --
        // the year constants pinned against this file would then be checked
        // against whichever row happened to come second.
        records.fold(mutableMapOf<String, String>()) { rows, record ->
          val variable = record.get("variable_name").trim()
          val previous = rows.put(variable, record.get(name).trim())
          require(previous == null) {
            "[$FILE_NAME] states [$variable] twice: one row per Scorecard variable, or the pinned " +
              "constants are checked against an arbitrary one of them"
          }
          rows
        }
      }
}
