package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AidDeadlineEvent
import ed.unicoach.db.models.AidDeadlineKind
import ed.unicoach.db.models.DeadlineJurisdiction
import ed.unicoach.db.models.NewFafsaFilingDeadline
import ed.unicoach.db.models.PublishedDeadline
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The committed FAFSA deadline corpus under `db/seed/fafsa-deadlines/`, read
 * FROM DISK (RFC 223 section 8), on the [AdmissionTestStateProgrammeFixture]
 * precedent. NOTHING HERE TOUCHES THE DATABASE: the subject is the committed
 * FILE, before any migration has a chance to load it.
 *
 * IT READS THE CORPUS AND DOES NOT SEED ARBITRARY ROWS, which is the half of
 * the precedent that does NOT carry over: the state-programme fixture writes
 * hypothetical rows for `:service` suites and deliberately does NOT read its
 * corpus, and this one does exactly the opposite. A suite that wants an
 * arbitrary deadline row builds one from the model directly.
 *
 * It lives in `testFixtures` and not in `:db`'s test source set because the
 * CSV READER lives here: every other
 * committed-corpus reader in this package does ([AdmissionTestCalendarFixture],
 * [AdmissionTestStateProgrammeFixture]), and `commons-csv` is declared on this
 * source set alone. Putting it one source set over would have bought a new
 * `testImplementation` line for nothing. The landed precedent outranks the
 * design doc (CLAUDE.md).
 *
 * THE CSV IS RFC 4180 AND IS PARSED BY A REAL CSV READER (`commons-csv`),
 * because the cells carry the publishers' own sentences and those sentences
 * carry commas ("March 2, 2027, 11:59 p.m. Pacific") and double quotes. A
 * hand-rolled `split(",")` cannot read this file at all, and would also read a
 * CRLF line ending into the last cell of a file that is edited by hand.
 *
 * Every refusal names the FILE, the ROW and the COLUMN, because whoever hits
 * one is looking at a corpus of twelve columns that grows at every refresh --
 * the row count is deliberately not written here, since an append-only corpus
 * makes any number in a comment wrong, and a wrong count in THIS file would be
 * indistinguishable from the corpus having lost rows.
 */
object FafsaFilingDeadlineFixture {
  /** The committed seed directory, found by walking up from the test's working directory -- [AdmissionTestStateProgrammeFixture]'s rule. */
  val COMMITTED_DIR: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "db/seed/fafsa-deadlines") }
      .firstOrNull { it.isDirectory }
      ?: error("db/seed/fafsa-deadlines was not found above [${File(".").absolutePath}]")

  /** The committed, hand-typed corpus: one row per (award year, jurisdiction, program, event). */
  val CSV_FILE: File = File(COMMITTED_DIR, "deadlines.csv")

  /** Its manifest: the reading dates, the `sha256`, the coverage block, the known limits and the judgement calls. */
  val PROVENANCE_FILE: File = File(COMMITTED_DIR, "PROVENANCE.json")

  /** The CSV's name as `PROVENANCE.json` keys it -- one spelling for the reader and for the manifest assertions. */
  val CSV_NAME: String = CSV_FILE.name

  /**
   * The command that regenerates the seed migrations from the CSV, named here
   * so the refresh instruction every failure message carries cannot drift from
   * the script it names.
   */
  const val GENERATOR_COMMAND = "nix develop -c bin/fafsa-deadline-seed"

  /**
   * How the federal jurisdiction is SAID in a failure message. The stored cell
   * is empty and the typed value is [DeadlineJurisdiction.Federal], which has
   * no USPS code; the guard groups by jurisdiction and so needs a name for it,
   * and "the federal government has no state code" must not read as a blank.
   */
  const val FEDERAL_LABEL = "federal"

  /**
   * The header this reader requires, exactly and in order (the
   * [AdmissionTestStateProgrammeFixture] rule): a renamed or reordered column
   * is a broken seed, not a column to look up by chance.
   */
  val HEADER: List<String> =
    listOf(
      "state",
      "award_year",
      "program",
      "event",
      "deadline_kind",
      "deadline_date",
      "deadline_as_published",
      "publisher",
      "source_url",
      "archive_url",
      "checked_on",
      "expires_on",
    )

  /** The committed rows, typed. Row order is the file's order, so a caller may line them up with [committedStateCells]. */
  fun committedRows(): List<NewFafsaFilingDeadline> = parse(::rowOf)

  /**
   * The RAW `state` cell of every committed row, in the file's order -- empty
   * for the four federal rows.
   *
   * It exists because [committedRows] has already DECIDED: an empty cell became
   * [DeadlineJurisdiction.Federal] and a code became a
   * [DeadlineJurisdiction.State], and a membership test run over the typed
   * value would be a test of this reader rather than of the file. The seed test
   * asserts the cells themselves against [ed.unicoach.db.models.UsStateCodes].
   */
  fun committedStateCells(): List<String> = parse { it.get("state") }

  /** How a jurisdiction is named in a guard message: its USPS code, or [FEDERAL_LABEL]. */
  fun label(jurisdiction: DeadlineJurisdiction): String = jurisdiction.uspsCode ?: FEDERAL_LABEL

  private fun <T> parse(of: (CSVRecord) -> T): List<T> =
    // The reader is opened INSIDE its own `use`, because `parse` reads the
    // header line and can throw: a reader created as an argument to it would
    // never be closed on that path, and the throwing path is the one this file
    // exists to exercise.
    CSV_FILE.bufferedReader().use { reader ->
      CSVFormat.RFC4180
        .builder()
        .setHeader()
        .setSkipHeaderRecord(true)
        .get()
        .parse(reader)
        .use { parser ->
          require(parser.headerNames == HEADER) {
            "[${CSV_FILE.path}] header is [${parser.headerNames}], and this reader requires exactly [$HEADER] in that order"
          }
          parser.records.map(of)
        }
    }

  private fun rowOf(record: CSVRecord): NewFafsaFilingDeadline {
    require(record.isConsistent) {
      "row [${record.recordNumber}] of [${CSV_FILE.path}] has [${record.size()}] cells, not [${HEADER.size}]: [$record]"
    }

    fun required(column: String): String =
      record.get(column).ifEmpty {
        error("row [${record.recordNumber}] of [${CSV_FILE.path}] has an empty [$column], which is required")
      }

    fun optional(column: String): String? = record.get(column).ifEmpty { null }

    fun date(
      raw: String,
      column: String,
    ): LocalDate =
      try {
        LocalDate.parse(raw)
      } catch (e: DateTimeParseException) {
        throw IllegalStateException(
          "row [${record.recordNumber}] of [${CSV_FILE.path}] has [$column] = [$raw], which is not a yyyy-mm-dd date",
          e,
        )
      }

    // The SAME located-fault contract as [date], for the one numeric cell. It
    // used to be a bare `.toInt()`, which throws a NumberFormatException naming
    // no file, no row and no column -- in a reader whose whole promise is that
    // a bad cell is named.
    fun year(
      raw: String,
      column: String,
    ): Int =
      raw.toIntOrNull()
        ?: error("row [${record.recordNumber}] of [${CSV_FILE.path}] has [$column] = [$raw], which is not a year")

    val stateCell = record.get("state")
    // The nullable column whose NULL MEANS federal, read back the way the model
    // states it: an empty cell is the federal government and never a state, and
    // a code that is not one of the 59 published USPS codes is a located fault
    // here rather than a foreign-key failure in somebody else's db-reset.
    val jurisdiction =
      if (stateCell.isEmpty()) {
        DeadlineJurisdiction.Federal
      } else {
        DeadlineJurisdiction.State.of(stateCell)
          ?: error("row [${record.recordNumber}] of [${CSV_FILE.path}] names state [$stateCell], which is not a published USPS code")
      }

    val asPublished = required("deadline_as_published")
    val dayCell = optional("deadline_date")
    val kind =
      AidDeadlineKind.fromValue(required("deadline_kind"))
        ?: error(
          "row [${record.recordNumber}] of [${CSV_FILE.path}] names deadline_kind " +
            "[${record.get("deadline_kind")}], which is not one of [${AidDeadlineKind.entries.map { it.value }}]",
        )
    // The value-iff-kind pairing the schema states
    // (`fafsa_filing_deadlines_date_iff_kind_check`), refused HERE too: a
    // `date` row with no day and a `wording` row carrying one are both rows the
    // typed model cannot hold, so the reader says which row of the file is
    // wrong instead of dying inside a `when`.
    val deadline =
      when (kind) {
        AidDeadlineKind.DATE -> {
          PublishedDeadline.OnDate(
            date(
              dayCell ?: error("row [${record.recordNumber}] of [${CSV_FILE.path}] is kind [date] and names no deadline_date"),
              "deadline_date",
            ),
            asPublished,
          )
        }

        AidDeadlineKind.WORDING -> {
          require(dayCell == null) {
            "row [${record.recordNumber}] of [${CSV_FILE.path}] is kind [wording] and names deadline_date [$dayCell]"
          }
          PublishedDeadline.InWords(asPublished)
        }

        AidDeadlineKind.NONE -> {
          require(dayCell == null) {
            "row [${record.recordNumber}] of [${CSV_FILE.path}] is kind [none] and names deadline_date [$dayCell]"
          }
          PublishedDeadline.NonePublished(asPublished)
        }
      }

    return NewFafsaFilingDeadline(
      awardYear = AcademicYear(year(required("award_year"), "award_year")),
      jurisdiction = jurisdiction,
      program = required("program"),
      event =
        AidDeadlineEvent.fromValue(required("event"))
          ?: error(
            "row [${record.recordNumber}] of [${CSV_FILE.path}] names event [${record.get("event")}], which is not one " +
              "of [${AidDeadlineEvent.entries.map { it.value }}]",
          ),
      deadline = deadline,
      publisher = required("publisher"),
      sourceUrl = required("source_url"),
      archiveUrl = optional("archive_url"),
      checkedOn = date(required("checked_on"), "checked_on"),
      expiresOn = date(required("expires_on"), "expires_on"),
    )
  }
}
