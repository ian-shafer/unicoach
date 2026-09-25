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
 * precedent -- and the arbitrary-row seeder `:service`'s `FafsaDeadlineService`
 * suites write through (RFC 230), on that same precedent's other half.
 *
 * THE READER HALF NEVER TOUCHES THE DATABASE: its subject is the committed
 * FILE, before any migration has a chance to load it. THE SEEDER HALF writes
 * HYPOTHETICAL rows through [FafsaFilingDeadlineDao.upsert], never the
 * committed corpus: a suite that controls which cycle or which `expires_on` a
 * row carries has to write its own rows, and a committed row near its expiry
 * fails the build (D20).
 *
 * It lives in `testFixtures` and not in `:db`'s test source set because the
 * CSV READER lives here: every other
 * committed-corpus reader in this package does ([AdmissionTestCalendarFixture],
 * [AdmissionTestStateProgrammeFixture]), and `commons-csv` is declared on this
 * source set alone. Putting it one source set over would have bought a new
 * `testImplementation` line for nothing. The landed precedent outranks the
 * design doc (CLAUDE.md).
 *
 * `us_states` IS A PRECONDITION for the seeder. Every state row foreign-keys
 * `us_states(usps_code)`, so a caller seeds [CodebookReferenceFixture] first --
 * otherwise every state write fails with a missing reference rather than with
 * anything about deadlines.
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

  /**
   * One arbitrary deadline row, with the defaults a suite does not care about
   * already filled. A field is a PARAMETER when a landed case varies it -- the
   * live set varies [awardYear] and [jurisdiction], expiry varies [expiresOn]
   * and [checkedOn] -- and a fixture's own constant when none does: the
   * publisher is [DEFAULT_PUBLISHER] and the page is [defaultSourceUrl],
   * because no case is about either. [archiveUrl] IS a parameter, and arrived
   * with the case that overrides it: a suite asserting what a door does with an
   * archived address -- offered where the sweep still finds it, withheld where
   * it recorded a grave -- cannot seed the row it needs without one. It defaults
   * to no archive, which is what the committed corpus holds for most rows.
   *
   * The defaults satisfy the schema's CHECKs for a [DEFAULT_AWARD_YEAR] row:
   * [DEFAULT_CHECKED_ON] is inside the cycle's window and before
   * [DEFAULT_EXPIRES_ON], and the published day is inside the cycle's own
   * window.
   */
  fun deadline(
    awardYear: AcademicYear = DEFAULT_AWARD_YEAR,
    jurisdiction: DeadlineJurisdiction = DeadlineJurisdiction.Federal,
    program: String = DEFAULT_PROGRAM,
    event: AidDeadlineEvent = AidDeadlineEvent.PRIORITY_DEADLINE,
    deadline: PublishedDeadline = DEFAULT_DEADLINE,
    archiveUrl: String? = null,
    checkedOn: LocalDate = DEFAULT_CHECKED_ON,
    expiresOn: LocalDate = DEFAULT_EXPIRES_ON,
  ): NewFafsaFilingDeadline =
    NewFafsaFilingDeadline(
      awardYear = awardYear,
      jurisdiction = jurisdiction,
      program = program,
      event = event,
      deadline = deadline,
      publisher = DEFAULT_PUBLISHER,
      sourceUrl = defaultSourceUrl(jurisdiction, program),
      archiveUrl = archiveUrl,
      checkedOn = checkedOn,
      expiresOn = expiresOn,
    )

  /**
   * The page a fixture row cites when the case is not about the citation.
   *
   * NAMED, because since RFC 213 the url is a row of `external_documents` and a
   * suite that wants to assert the citation survived the join has to be able to
   * say which url it expects without re-typing the format.
   */
  fun defaultSourceUrl(
    jurisdiction: DeadlineJurisdiction,
    program: String,
  ): String {
    // [label] IS the `uspsCode ?: the federal word` rule, and [FEDERAL_LABEL] is
    // that word's one home: spelling either again here would be this function
    // and that one able to disagree about the federal government's name.
    val scope = label(jurisdiction).lowercase()
    return "https://example-aid.$scope.gov/${program.replace(" ", "-").lowercase()}"
  }

  /**
   * Empties the corpus. NAMED and called by the caller, never hidden inside a
   * `seed` verb ([AdmissionTestCalendarFixture]'s rule).
   *
   * THE CITATIONS ARE DELIBERATELY LEFT, though [seed] writes them: since
   * RFC 213 `external_documents` is SHARED with every other corpus and
   * de-duplicated on url, so a row this fixture wrote may be the row another
   * corpus cites -- and a teardown that deleted it would take somebody else's
   * citation with it. `FafsaFilingDeadlineDaoTest` and
   * `FafsaFilingDeadlineSchemaTest` state the same rule in the same words. The
   * documents left behind are unreferenced url rows that no read of this table
   * can reach: every read here joins FROM a deadline row, so a document with no
   * deadline row cannot appear in any answer or any count.
   */
  fun deleteAll(session: SqlSession) {
    session.prepareStatement("DELETE FROM fafsa_filing_deadlines").use { it.executeUpdate() }
  }

  /**
   * Writes [rows] through the DAO's own upsert, so a fixture cannot write a row
   * the read path could not have read.
   *
   * A REFUSED WRITE NAMES ITS OWN ROW. This object's whole promise is that a bad
   * row is located, and `us_states` being absent makes every state write fail
   * with a missing reference rather than with anything about deadlines -- so the
   * failure carries the row's natural key and keeps the driver's fault as its
   * cause, instead of leaving a constraint name to be reverse-engineered into
   * one of a whole corpus of writes.
   */
  fun seed(
    session: SqlSession,
    rows: List<NewFafsaFilingDeadline>,
  ) {
    rows.forEachIndexed { index, new ->
      FafsaFilingDeadlineDao.upsert(session, new).getOrElse { cause ->
        throw IllegalStateException(
          "seeding deadline row [$index] of [${rows.size}] failed: " +
            "awardYear=[${new.awardYear.label}] jurisdiction=[${label(new.jurisdiction)}] " +
            "program=[${new.program}] event=[${new.event}]",
          cause,
        )
      }
    }
  }

  /** The award year [deadline] describes by default. */
  val DEFAULT_AWARD_YEAR: AcademicYear = AcademicYear(2027)

  /** A day inside [DEFAULT_AWARD_YEAR]'s filing window, and before [DEFAULT_EXPIRES_ON]. */
  val DEFAULT_CHECKED_ON: LocalDate = LocalDate.of(2026, 9, 14)

  /** The end of that cycle's filing season, after [DEFAULT_CHECKED_ON] as the schema requires. */
  val DEFAULT_EXPIRES_ON: LocalDate = LocalDate.of(2027, 7, 31)

  /** The published day [deadline] carries by default, inside [DEFAULT_AWARD_YEAR]'s window. */
  val DEFAULT_DEADLINE_DATE: LocalDate = LocalDate.of(2027, 3, 2)

  /**
   * The whole published deadline [deadline] carries by default: the day and the
   * publisher's own words for it. Named so a suite can assert what an
   * unremarkable row says without re-typing either half.
   */
  val DEFAULT_DEADLINE: PublishedDeadline = PublishedDeadline.OnDate(DEFAULT_DEADLINE_DATE, "March 2, 2027")

  /** The program a row names when the case is not about the program. */
  const val DEFAULT_PROGRAM = "State student aid"

  /** The agency a row names when the case is not about the publisher. */
  const val DEFAULT_PUBLISHER = "A state aid agency"

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
