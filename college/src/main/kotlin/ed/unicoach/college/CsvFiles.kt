package ed.unicoach.college

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.File

/**
 * Opens [file] as a header-mapped CSV parser (the header row names the columns
 * and is not itself a record) -- the one CSV dialect every ingester in this
 * module reads, declared once so the Scorecard ingester and the CDS seed loader
 * can never drift on what a source file is. The caller owns the returned parser
 * and must `use` it.
 *
 * The reader is opened before `parse` validates the header, so this function
 * owns it until the parser takes ownership: a blank header cell
 * (IllegalArgumentException) or an unreadable file (IOException) would
 * otherwise leave a descriptor no caller's `use` could ever close -- and
 * asserting headers against possibly-malformed seeds is exactly what
 * CdsSeedLoader does first.
 */
internal fun parseCsv(file: File): CSVParser {
  val reader = file.bufferedReader()
  try {
    return CSVFormat.DEFAULT
      .builder()
      .setHeader()
      .setSkipHeaderRecord(true)
      .get()
      .parse(reader)
  } catch (e: Throwable) {
    reader.close()
    throw e
  }
}
