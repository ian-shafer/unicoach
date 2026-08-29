package ed.unicoach.college

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.File
import java.io.Reader

/** The UTF-8 byte-order mark, as the single char a UTF-8 decoder yields for `EF BB BF`. */
private const val BYTE_ORDER_MARK = '\uFEFF'

/**
 * Opens [file] as a header-mapped CSV parser (the header row names the columns
 * and is not itself a record) -- the one CSV dialect every ingester in this
 * module reads, declared once so the Scorecard ingester, the IPEDS ingester and
 * the CDS seed loader can never drift on what a source file is. The caller owns
 * the returned parser and must `use` it.
 *
 * Two source-shape defences the IPEDS files forced (RFC 144), both no-ops for
 * the Scorecard and CDS sources:
 *
 * - the UTF-8 **BOM** is stripped, or the first column name would be
 *   `\uFEFFUNITID` and every header assertion on HD/IC/C_A would fail;
 * - header names are **trimmed**, because `adm2023.csv`'s header line ends in
 *   trailing spaces, making its last column literally `ACTMT75   `.
 *
 * The names are read first and passed back in explicitly rather than trusting
 * the parser's own header handling, so what a header assertion checks and what
 * a mapper looks up are the same trimmed strings.
 *
 * The reader is opened before `parse` validates the header, so this function
 * owns it until the parser takes ownership: a blank header cell
 * (IllegalArgumentException) or an unreadable file (IOException) would
 * otherwise leave a descriptor no caller's `use` could ever close -- and
 * asserting headers against possibly-malformed seeds is exactly what
 * CdsSeedLoader does first.
 */
internal fun parseCsv(file: File): CSVParser {
  val header = headerNames(file)
  val reader = bomStrippedReader(file)
  try {
    return CSVFormat.DEFAULT
      .builder()
      .setHeader(*header)
      .setSkipHeaderRecord(true)
      .get()
      .parse(reader)
  } catch (e: Throwable) {
    reader.close()
    throw e
  }
}

/**
 * [file]'s first CSV record, read as the header and trimmed name by name.
 *
 * Unlike [parseCsv] this function never hands the reader out, so `use` owns it:
 * it closes on both paths and records a failing `close()` as a SUPPRESSED
 * exception on the original, instead of replacing "this file has a blank header
 * cell" with an IOException about closing a broken stream.
 */
private fun headerNames(file: File): Array<String> =
  bomStrippedReader(file).use { reader ->
    CSVFormat.DEFAULT.parse(reader).use { parser ->
      val records = parser.iterator()
      require(records.hasNext()) { "source file [${file.path}] is empty; it has no CSV header row" }
      records
        .next()
        .map { it.trim() }
        .toTypedArray()
    }
  }

/** [file] as a UTF-8 reader positioned past a byte-order mark, if it has one. */
private fun bomStrippedReader(file: File): Reader {
  val reader = file.bufferedReader()
  try {
    reader.mark(1)
    if (reader.read() != BYTE_ORDER_MARK.code) reader.reset()
  } catch (e: Throwable) {
    // The probe is real I/O and can throw. Nothing has been handed to a caller
    // yet, so this function is still the reader's only owner: letting the
    // failure out unclosed would leak a descriptor no `use` could ever reach.
    reader.close()
    throw e
  }
  return reader
}
