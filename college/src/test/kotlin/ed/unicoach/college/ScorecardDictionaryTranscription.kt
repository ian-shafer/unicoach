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
 * and empty where the publisher pools a measure across cohorts. A
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

  /** `variable_name -> most_recent_cohort`, parsed: a stated academic year, a stated entering term, or the named no-year state. */
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

    /** The publisher pools the measure across cohorts and states no academic year at all. */
    data object NoAcademicYear : Cohort
  }

  /**
   * One `most_recent_cohort` cell, read. An empty cell is the publisher's "no
   * academic year"; an `AcadYr <label>` sentence is the academic year that
   * label speaks; anything else must name an entering fall term (`Fall 2024`),
   * carried verbatim as [Cohort.StatedTerm] with its year parsed once -- so a
   * snapshot that re-words the sheet, or a cell this reader cannot parse,
   * fails HERE, naming the cell, instead of comparing unequal for a reason no
   * assertion states.
   */
  private fun cohortOf(
    variable: String,
    cell: String,
  ): Cohort {
    if (cell.isEmpty()) return Cohort.NoAcademicYear
    if (cell.startsWith(ACADEMIC_YEAR_PREFIX)) {
      return when (val read = academicYearOf(cell)) {
        is AcademicYearCell.Year -> {
          Cohort.StatedAcademicYear(read.year)
        }

        is AcademicYearCell.NotAYear -> {
          error(
            "[$FILE_NAME] states [most_recent_cohort=$cell] for [$variable], which is not an academic year the " +
              "way AcademicYear.label speaks one ([${ACADEMIC_YEAR_PREFIX}2024-25]): ${read.reason}",
          )
        }
      }
    }
    val autumnYear = autumnYearOf(cell)
    require(autumnYear != null) {
      "[$FILE_NAME] states [most_recent_cohort=$cell] for [$variable], which is neither an academic-year " +
        "sentence, an empty cell, nor an entering term naming a fall year ([${FALL_TERM_PREFIX}2024])"
    }
    return Cohort.StatedTerm(cell, autumnYear)
  }

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
   * not be read without reading a parser.
   */
  private fun academicYearOf(cell: String): AcademicYearCell {
    val label = cell.removePrefix(ACADEMIC_YEAR_PREFIX)
    val firstYear =
      label.substringBefore('-').toIntOrNull()
        ?: return AcademicYearCell.NotAYear("[$label] does not begin with a calendar year")
    val year =
      runCatching { AcademicYear(firstYear) }
        .getOrElse { rejection -> return AcademicYearCell.NotAYear(rejection.message ?: "$rejection") }
    return if (year.label == label) {
      AcademicYearCell.Year(year)
    } else {
      AcademicYearCell.NotAYear("[$label] is not how AcademicYear speaks [$firstYear]: it says [${year.label}]")
    }
  }

  /** One `AcadYr` label, read: the year it names, or WHY it names none. */
  private sealed interface AcademicYearCell {
    data class Year(
      val year: AcademicYear,
    ) : AcademicYearCell

    data class NotAYear(
      val reason: String,
    ) : AcademicYearCell
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

  /** `"Fall 2018 or AcadYr 2018-19 cohort (…)"` -> 2018; `"Fall 2024"` -> 2024; any other shape -> null. */
  private fun autumnYearOf(cell: String): Int? =
    FALL_TERM
      .matchEntire(cell)
      ?.groupValues
      ?.get(1)
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
