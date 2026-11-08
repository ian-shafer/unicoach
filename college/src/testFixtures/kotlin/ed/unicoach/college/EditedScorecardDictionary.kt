package ed.unicoach.college

import ed.unicoach.common.util.AcademicYear
import kotlinx.coroutines.runBlocking
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * A copy of the COMMITTED data dictionary with chosen cells changed, for the
 * suites that drive what a NEWER release would do (RFC 274): re-date a served
 * column, drop one, back-date one.
 *
 * The rest of every file is the committed file's, byte for byte as parsed and
 * re-printed, so a test changes exactly the cells it names. An override of
 * `null` DROPS that variable's row -- a release that removed or renamed it.
 */
object EditedScorecardDictionary {
  /** The columns the committed dictionary dates to the published-charge year: the eight charges. */
  val publishedChargeColumns: List<String> =
    ScorecardInstitutionColumns.DATED_MONEY_VARIABLES.filter {
      SeedScorecardDictionary.dictionary.academicYearOf(it) == SeedScorecardYears.PUBLISHED_CHARGE
    }

  /** The publisher's own cell shape for a collection-year column, at [year]: what a newer (or older) release would state. */
  fun collectionCell(year: AcademicYear): String = "AcadYr ${year.label}, reported in IPEDS DCY${year.label}"

  /** The committed dictionary with every published charge dated [year]: a release that moved the charges there. */
  fun withChargesDated(year: AcademicYear): ScorecardDictionary =
    of(institutionCells = publishedChargeColumns.associateWith { collectionCell(year) })

  /** The edited copy, read. */
  fun of(
    institutionCells: Map<String, String?> = emptyMap(),
    fieldOfStudyCells: Map<String, String?> = emptyMap(),
  ): ScorecardDictionary = runBlocking { ScorecardDictionary.fromFiles(sourcesOf(institutionCells, fieldOfStudyCells)) }

  /** The edited copy's three files, as the ingest is handed them. */
  fun sourcesOf(
    institutionCells: Map<String, String?> = emptyMap(),
    fieldOfStudyCells: Map<String, String?> = emptyMap(),
  ): ScorecardDictionarySources {
    val directory = createTempDirectory("scorecard-dictionary-edited").toFile().apply { deleteOnExit() }
    val committed = SeedScorecardDictionary.sources
    copyWith(committed.institutionCohorts.file, File(directory, committed.institutionCohorts.file.name), institutionCells)
    copyWith(committed.institutionSources.file, File(directory, committed.institutionSources.file.name), emptyMap())
    copyWith(committed.fieldOfStudyCohorts.file, File(directory, committed.fieldOfStudyCohorts.file.name), fieldOfStudyCells)
    return SeedScorecardDictionary.sourcesIn(directory)
  }

  /**
   * [from] to [to], with the SECOND column of each row named in [overrides]
   * replaced (or the row dropped for `null`). Every unknown override is a test
   * typo, so it is refused rather than silently changing nothing.
   */
  private fun copyWith(
    from: File,
    to: File,
    overrides: Map<String, String?>,
  ) {
    val seen = mutableSetOf<String>()
    CSVParser.parse(from, Charsets.UTF_8, CSVFormat.DEFAULT).use { records ->
      CSVPrinter(
        to.bufferedWriter(),
        CSVFormat.DEFAULT
          .builder()
          .setRecordSeparator("\n")
          .get(),
      ).use { printer ->
        for ((index, record) in records.withIndex()) {
          val cells = record.toList()
          val variable = cells.first()
          if (index == 0 || variable !in overrides) {
            printer.printRecord(cells)
            continue
          }
          seen += variable
          val replacement = overrides.getValue(variable) ?: continue
          printer.printRecord(listOf(variable, replacement) + cells.drop(2))
        }
      }
    }
    require(seen == overrides.keys) { "no committed row for ${overrides.keys - seen} in [${from.name}]" }
  }
}
