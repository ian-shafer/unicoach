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

  /** `variable_name -> most_recent_cohort`, parsed: a stated academic year, or the named no-year state. */
  val cohortByVariable: Map<String, Cohort> by lazy {
    column("most_recent_cohort").mapValues { (variable, cell) -> cohortOf(variable, cell) }
  }

  /**
   * How the publisher's dictionary dates one variable -- a CLOSED type, so
   * "the publisher states no academic year" is a state with a name rather than
   * an empty string of the same type as `AcadYr 2024-25`.
   */
  sealed interface Cohort {
    /** The dictionary names an academic year for this variable. */
    data class StatedAcademicYear(
      val year: AcademicYear,
    ) : Cohort

    /** The publisher pools the measure across cohorts and states no academic year at all. */
    data object NoAcademicYear : Cohort
  }

  /**
   * One `most_recent_cohort` cell, read. An empty cell is the publisher's "no
   * academic year"; anything else must be its `AcadYr <label>` sentence, whose
   * label is exactly what [AcademicYear] speaks -- so a snapshot that re-words
   * the sheet fails HERE, naming the cell, instead of comparing unequal for a
   * reason no assertion states.
   */
  private fun cohortOf(
    variable: String,
    cell: String,
  ): Cohort {
    if (cell.isEmpty()) return Cohort.NoAcademicYear
    require(cell.startsWith(ACADEMIC_YEAR_PREFIX)) {
      "[$FILE_NAME] states [most_recent_cohort=$cell] for [$variable]: the publisher dates a variable as " +
        "[${ACADEMIC_YEAR_PREFIX}2024-25] or leaves the cell empty, so this is neither a year nor a pooled measure"
    }
    val label = cell.removePrefix(ACADEMIC_YEAR_PREFIX)
    val year = label.substringBefore('-').toIntOrNull()?.let { runCatching { AcademicYear(it) }.getOrNull() }
    require(year != null && year.label == label) {
      "[$FILE_NAME] states [most_recent_cohort=$cell] for [$variable], which is not an academic year the " +
        "way AcademicYear.label speaks one ([${ACADEMIC_YEAR_PREFIX}2024-25])"
    }
    return Cohort.StatedAcademicYear(year)
  }

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
