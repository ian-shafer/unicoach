package ed.unicoach.db.dao

import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.NewAdmissionTestAdministration
import ed.unicoach.db.models.ScoreRelease
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The `admission_test_administrations` rows (RFC 197) for suites that TRUNCATE.
 *
 * The table is seeded by MIGRATION, so `bin/test`'s `db-reset` leaves the real
 * fifteen rows in the test database -- but a suite that truncates the calendar to
 * control `expires_on` (the expiry refusal is a behaviour, so it has to be
 * executed) has to put rows back, and the coaching suites truncate broadly. This
 * is that seeder.
 *
 * It can seed TWO things, and the difference is the point:
 *
 * - [seedCommitted] writes the real committed calendar, read from
 *   `db/seed/test-calendar/administrations.csv` -- the same file the generator
 *   reads -- so a test about the shipped answer is about the shipped data.
 * - [seed] writes an ARBITRARY set, because a test of the expiry refusal needs
 *   rows whose `expires_on` it chose, and no committed row can ever be one of
 *   those: a committed row within the refresh lead of its expiry fails the build
 *   (D20).
 *
 * THE COMMITTED ROWS ARE READ FROM THE FILE AND NEVER RETYPED HERE, on the
 * [CodebookReferenceFixture] precedent: a hand-typed copy is a second calendar,
 * and a refresh that corrected one date would leave every test green against the
 * date the publisher no longer publishes.
 *
 * A test fixture and not a test helper, because `:db`'s own corpus test (the
 * staleness guard) and `:service`'s timeline suites both need it.
 */
object AdmissionTestCalendarFixture {
  /** The committed seed directory, found by walking up from the test's working directory -- [CodebookReferenceFixture]'s rule. */
  val COMMITTED_DIR: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "db/seed/test-calendar") }
      .firstOrNull { it.isDirectory }
      ?: error("db/seed/test-calendar was not found above [${File(".").absolutePath}]")

  /** The committed, hand-typed calendar. */
  val CSV_FILE: File = File(COMMITTED_DIR, "administrations.csv")

  /** Its manifest: `checked_on` (nothing is fetched -- a human read the page), `source`, `rows` and `sha256`. */
  val PROVENANCE_FILE: File = File(COMMITTED_DIR, "PROVENANCE.json")

  /** The CSV's name as `PROVENANCE.json` keys it -- one spelling for the reader and the manifest assertions. */
  val CSV_NAME: String = CSV_FILE.name

  /**
   * The header this reader requires, exactly and in order (the `CdsSeedLoader`
   * rule): a renamed or reordered column is a broken seed, not a column to look
   * up by chance, and asserting it before the first row means a rename cannot
   * make a sweep silently find nothing.
   *
   * The SAME header is authored a second time, in awk, as
   * `bin/test-calendar-seed`'s `EXPECTED_HEADER`. Two authorings of one contract
   * in two languages cannot be collapsed into one, so
   * `AdmissionTestCalendarSeedTest` asserts this list against that line instead.
   */
  val HEADER: List<String> =
    listOf(
      "test",
      "cycle",
      "test_date",
      "registration_deadline",
      "late_registration_deadline",
      "score_release_date",
      "dates_source_url",
      "score_release_source_url",
      "checked_on",
      "expires_on",
    )

  /**
   * The committed rows, parsed with a REAL CSV reader (`commons-csv`, the
   * dialect every ingester in `:college` already reads through).
   *
   * The reader used to be a hand-rolled `split(",")` guarded by an assertion
   * that the file contains no quote character. That guard was true of today's
   * file and of nothing else: it also mis-read a CRLF line ending as part of the
   * last cell, so a file saved on Windows would have loaded `2027-07-31\r` as an
   * expiry. `commons-csv` is already a dependency of this repo, so there was
   * never a reason to hand-roll one here.
   *
   * Every refusal below names the FILE, the ROW and the COLUMN. A seed reader
   * that throws `Text '2027-31-07' could not be parsed` names nobody, and the
   * person who hits it is looking at fifteen rows of ten date columns.
   */
  fun committedRows(): List<NewAdmissionTestAdministration> =
    CSVFormat.DEFAULT
      .builder()
      .setHeader()
      .setSkipHeaderRecord(true)
      .get()
      .parse(CSV_FILE.bufferedReader())
      .use { parser ->
        require(parser.headerNames == HEADER) {
          "[${CSV_FILE.path}] header is [${parser.headerNames}], and this reader requires exactly [$HEADER] in that order"
        }
        parser.records.map(::rowOf)
      }

  private fun rowOf(record: CSVRecord): NewAdmissionTestAdministration {
    require(record.isConsistent) {
      "row [${record.recordNumber}] of [${CSV_FILE.path}] has [${record.size()}] cells, not [${HEADER.size}]: [$record]"
    }

    fun required(column: String): String =
      record.get(column).ifEmpty {
        error("row [${record.recordNumber}] of [${CSV_FILE.path}] has an empty [$column], and it is required")
      }

    fun optional(column: String): String? = record.get(column).ifEmpty { null }
    return NewAdmissionTestAdministration(
      test =
        AdmissionTest.fromValue(required("test"))
          ?: error(
            "row [${record.recordNumber}] of [${CSV_FILE.path}] names test [${required("test")}], " +
              "which AdmissionTest does not",
          ),
      cycle = required("cycle"),
      testDate = dateAt(record, "test_date", required("test_date")),
      registrationDeadline = dateAt(record, "registration_deadline", required("registration_deadline")),
      lateRegistrationDeadline = optional("late_registration_deadline")?.let { dateAt(record, "late_registration_deadline", it) },
      scoreRelease = scoreReleaseOf(record),
      datesSourceUrl = required("dates_source_url"),
      checkedOn = dateAt(record, "checked_on", required("checked_on")),
      expiresOn = dateAt(record, "expires_on", required("expires_on")),
    )
  }

  /**
   * The announced score release and its citation, together or absent together --
   * the shape [ScoreRelease] makes unrepresentable any other way, refused HERE so
   * the message names the row rather than surfacing as a CHECK violation at
   * insert time.
   */
  private fun scoreReleaseOf(record: CSVRecord): ScoreRelease? {
    val date = record.get("score_release_date").ifEmpty { null }
    val sourceUrl = record.get("score_release_source_url").ifEmpty { null }
    return when {
      date == null && sourceUrl == null -> {
        null
      }

      date != null && sourceUrl != null -> {
        ScoreRelease(dateAt(record, "score_release_date", date), sourceUrl)
      }

      else -> {
        error(
          "row [${record.recordNumber}] of [${CSV_FILE.path}] has score_release_date [$date] and " +
            "score_release_source_url [$sourceUrl]: a date and the page it was read from travel together or not at all",
        )
      }
    }
  }

  /** An ISO date cell, refused with the file, the row and the column named -- never `LocalDate.parse`'s own bare message. */
  private fun dateAt(
    record: CSVRecord,
    column: String,
    raw: String,
  ): LocalDate =
    try {
      LocalDate.parse(raw)
    } catch (e: DateTimeParseException) {
      throw IllegalArgumentException(
        "row [${record.recordNumber}] of [${CSV_FILE.path}] has [$column] = [$raw], which is not an ISO yyyy-mm-dd date",
        e,
      )
    }

  /** Empties the calendar. NAMED and called by the caller, never hidden inside a `seed` verb (`CanonicalCohortFixture`'s rule). */
  fun deleteAll(session: SqlSession) {
    session.prepareStatement("DELETE FROM admission_test_administrations").use { it.executeUpdate() }
  }

  /** Writes [rows] through the DAO's own upsert, so a fixture cannot write a row the read path could not have read. */
  fun seed(
    session: SqlSession,
    rows: List<NewAdmissionTestAdministration>,
  ) {
    rows.forEach { AdmissionTestCalendarDao.upsert(session, it).getOrThrow() }
  }

  /** The real committed calendar, from the committed CSV. */
  fun seedCommitted(session: SqlSession) {
    seed(session, committedRows())
  }
}
