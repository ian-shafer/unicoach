package ed.unicoach.college

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import java.io.File
import kotlin.io.path.createTempFile

/**
 * A one-college Scorecard institution CSV carrying every column the canonical
 * money fill reads, for a suite that drives the REAL [CanonicalMoneyLoader.fill]
 * from outside this module (RFC 274). The header is the registry's own, so a
 * column joining the fill joins this file.
 */
object ScorecardInstitutionFile {
  /** What every dollar cell in the file states. */
  const val AMOUNT_USD = 1000

  /** What the file's one share cell states: `PCTPELL` is a rate, and the fill refuses a dollar figure there. */
  private const val SHARE = 0.42

  /** The file, with its one row at [ipedsUnitId] under Scorecard `CONTROL` value [control]. */
  fun forOneCollege(
    ipedsUnitId: Int,
    control: Int,
  ): File {
    val money = ScorecardInstitutionColumns.LOADED_VARIABLES.toList()
    val file = createTempFile("scorecard-institutions", ".csv").toFile().apply { deleteOnExit() }
    CSVPrinter(file.bufferedWriter(), CSVFormat.DEFAULT).use { printer ->
      printer.printRecord(listOf(ScorecardInstitutionColumns.UNITID, ScorecardInstitutionColumns.CONTROL) + money)
      printer.printRecord(
        listOf(ipedsUnitId, control) + money.map { if (it == ScorecardInstitutionColumns.PCTPELL) SHARE else AMOUNT_USD },
      )
    }
    return file
  }
}
