package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.GradeRange
import ed.unicoach.db.models.NewAdmissionTestStateProgramme
import ed.unicoach.db.models.SchoolDayParticipation
import ed.unicoach.db.models.SchoolDayTestChoice
import ed.unicoach.db.models.SchoolYearBasis
import ed.unicoach.db.models.TestChooser
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * `admission_test_state_programmes` rows (RFC 208) for suites that TRUNCATE.
 *
 * The table is seeded by MIGRATION, so `bin/test`'s `db-reset` leaves the real
 * corpus in the test database -- but a suite that controls which state says
 * what, or that reads an EXPIRED row (the expiry refusal is a behaviour, so it
 * has to be executed), has to write its own rows, and a committed row can never
 * be one of those: a committed row near its expiry fails the build (D20).
 *
 * IT SEEDS ARBITRARY ROWS AND DELIBERATELY NOT THE COMMITTED CORPUS. Its sibling
 * [AdmissionTestCalendarFixture] can do both, because the calendar's committed
 * CSV is read by a reader that lives beside it; the state-programme corpus and
 * its reader belong to the seed generator's own slice, and a hand-typed second
 * copy of 31 jurisdictions here would be a second corpus (the
 * [CodebookReferenceFixture] rule). A suite that needs the SHIPPED answer
 * asserts it through the generated migration, not through this.
 *
 * `us_states` IS A PRECONDITION. Every row foreign-keys `us_states(usps_code)`,
 * so a caller seeds [CodebookReferenceFixture] first -- otherwise every write
 * here fails with a missing reference rather than with anything about test
 * programmes.
 *
 * A test fixture and not a test helper, because `:db`'s own DAO suite and
 * `:service`'s timeline suites both need it.
 */
object AdmissionTestStateProgrammeFixture {
  /**
   * One programme row, with the defaults a suite does not care about already
   * filled: Tennessee's shape -- required, district-chosen, grade 11, state
   * funded, read on [DEFAULT_CHECKED_ON] for the school year that began before
   * it.
   *
   * Every field is overridable because every field is somebody's subject: a
   * suite about who pays overrides [stateFunded] alone and stays readable, which
   * is the whole reason this is a builder and not fourteen positional arguments
   * at each call site.
   */
  fun programme(
    state: String,
    schoolYear: AcademicYear = AcademicYear(DEFAULT_SCHOOL_YEAR),
    schoolYearBasis: SchoolYearBasis = SchoolYearBasis.STATED_BY_SOURCE,
    participation: SchoolDayParticipation = SchoolDayParticipation.REQUIRED,
    schoolMustOffer: Boolean? = null,
    choice: SchoolDayTestChoice = SchoolDayTestChoice.ChosenLocally(TestChooser.DISTRICT),
    grades: GradeRange? = GradeRange(ELEVENTH_GRADE, ELEVENTH_GRADE),
    stateFunded: Boolean? = true,
    sourceUrl: String = defaultSourceUrl(state),
    archiveUrl: String? = null,
    checkedOn: LocalDate = DEFAULT_CHECKED_ON,
    expiresOn: LocalDate = DEFAULT_EXPIRES_ON,
  ): NewAdmissionTestStateProgramme =
    NewAdmissionTestStateProgramme(
      state = state,
      schoolYear = schoolYear,
      schoolYearBasis = schoolYearBasis,
      participation = participation,
      schoolMustOffer = schoolMustOffer,
      choice = choice,
      grades = grades,
      stateFunded = stateFunded,
      sourceUrl = sourceUrl,
      archiveUrl = archiveUrl,
      checkedOn = checkedOn,
      expiresOn = expiresOn,
    )

  /** A state that chose the test itself, which is the other half of the pairing [SchoolDayTestChoice] makes unrepresentable. */
  fun stateChose(test: AdmissionTest): SchoolDayTestChoice = SchoolDayTestChoice.StateChose(test)

  /**
   * The page a fixture row cites when the case is not about the citation.
   *
   * NAMED, because since RFC 213 the url is a row of `external_documents` and a
   * suite that wants to assert the citation survived the join has to be able to
   * say which url it expects without re-typing the format.
   */
  fun defaultSourceUrl(state: String): String = "https://example-education.$state.gov/school-day-testing"

  /** Empties the corpus. NAMED and called by the caller, never hidden inside a `seed` verb ([AdmissionTestCalendarFixture]'s rule). */
  fun deleteAll(session: SqlSession) {
    session.prepareStatement("DELETE FROM admission_test_state_programmes").use { it.executeUpdate() }
  }

  /** Writes [rows] through the DAO's own upsert, so a fixture cannot write a row the read path could not have read. */
  fun seed(
    session: SqlSession,
    rows: List<NewAdmissionTestStateProgramme>,
  ) {
    rows.forEach { AdmissionTestStateProgrammeDao.upsert(session, it).getOrThrow() }
  }

  /** The grade every one of these programmes but Minnesota's names. */
  const val ELEVENTH_GRADE = 11

  /** The school year [programme] describes by default, and the day it was read -- one reading, self-consistently. */
  const val DEFAULT_SCHOOL_YEAR = 2026

  /** A day inside the [DEFAULT_SCHOOL_YEAR] school year, so a `reading_year` row built on these defaults is legal too. */
  val DEFAULT_CHECKED_ON: LocalDate = LocalDate.of(2026, 9, 14)

  /** The end of that school year, which is after [DEFAULT_CHECKED_ON] as the schema requires. */
  val DEFAULT_EXPIRES_ON: LocalDate = LocalDate.of(2027, 7, 31)

  /** The committed seed directory, found by walking up from the test's working directory -- [CodebookReferenceFixture]'s rule. */
  val COMMITTED_DIR: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "db/seed/test-state-programmes") }
      .firstOrNull { it.isDirectory }
      ?: error("db/seed/test-state-programmes was not found above [${File(".").absolutePath}]")

  /** The committed, hand-typed corpus: one row per jurisdiction per school year we read a state page for. */
  val CSV_FILE: File = File(COMMITTED_DIR, "programmes.csv")

  /** Its manifest: the reading session's dates, the coverage, the judgement calls, the capture dates, `rows` and `sha256`. */
  val PROVENANCE_FILE: File = File(COMMITTED_DIR, "PROVENANCE.json")

  /**
   * The sweep `bin/test-state-programme-seed -k` wrote: every url the corpus
   * cites, its link status and the day we asked (RFC 213). A generated artifact
   * of a committed source, like the seed migrations, and deliberately absent
   * from PROVENANCE.json, which describes what a HUMAN typed.
   */
  val LINK_STATUS_FILE: File = File(COMMITTED_DIR, "link-status.csv")

  /** The CSV's name as `PROVENANCE.json` keys it -- one spelling for the reader and the manifest assertions. */
  val CSV_NAME: String = CSV_FILE.name

  /**
   * The header this reader requires, exactly and in order (the `CdsSeedLoader`
   * rule): a renamed or reordered column is a broken seed, not a column to look
   * up by chance, and asserting it before the first row means a rename cannot
   * make a sweep silently find nothing.
   *
   * The SAME header is authored a second time, in awk, as
   * `bin/test-state-programme-seed`'s `EXPECTED_HEADER`. Two authorings of one
   * contract in two languages cannot be collapsed into one, so
   * [AdmissionTestStateProgrammeSeedTest] asserts this list against that line.
   */
  val HEADER: List<String> =
    listOf(
      "state",
      "school_year",
      "school_year_basis",
      "participation",
      "school_must_offer",
      "test_chooser",
      "test",
      "lowest_grade",
      "highest_grade",
      "state_funded",
      "source_url",
      "archive_url",
      "checked_on",
      "expires_on",
    )

  /**
   * The committed link sweep, `url -> (status, the day it was asked)`, from
   * `link-status.csv` (RFC 213).
   *
   * It lives HERE rather than in the suite that asserts over it because the CSV
   * reader is here: `:db`'s test source set does not see commons-csv, and a
   * hand-rolled split would be a second CSV parser in a repo that already
   * refuses second copies of a rule.
   */
  fun committedLinkStatuses(): Map<String, Pair<String, LocalDate?>> =
    CSVFormat.DEFAULT
      .builder()
      .setHeader()
      .setSkipHeaderRecord(true)
      .build()
      .parse(LINK_STATUS_FILE.reader())
      .use { parser ->
        parser.associate { record ->
          record.get("url") to
            Pair(
              record.get("url_status"),
              record.get("url_checked_on").let { if (it.isEmpty()) null else LocalDate.parse(it) },
            )
        }
      }

  /**
   * The committed rows, parsed with a REAL CSV reader (`commons-csv`), on
   * [AdmissionTestCalendarFixture]'s reasoning: a hand-rolled `split(",")` reads
   * a CRLF line ending into the last cell, and this file is edited by hand.
   *
   * Every refusal names the FILE, the ROW and the COLUMN, because the person who
   * hits one is looking at three dozen rows of fourteen columns.
   */
  fun committedRows(): List<NewAdmissionTestStateProgramme> =
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

  /** Writes the committed corpus, so a test about the shipped answer is a test about the shipped data. */
  fun seedCommitted(session: SqlSession) {
    seed(session, committedRows())
  }

  private fun rowOf(record: CSVRecord): NewAdmissionTestStateProgramme {
    require(record.isConsistent) {
      "row [${record.recordNumber}] of [${CSV_FILE.path}] has [${record.size()}] cells, not [${HEADER.size}]: [$record]"
    }

    fun required(column: String): String =
      record.get(column).ifEmpty {
        error("row [${record.recordNumber}] of [${CSV_FILE.path}] has an empty [$column], which is required")
      }

    fun optional(column: String): String? = record.get(column).ifEmpty { null }

    fun date(column: String): LocalDate =
      try {
        LocalDate.parse(required(column))
      } catch (e: DateTimeParseException) {
        throw IllegalStateException(
          "row [${record.recordNumber}] of [${CSV_FILE.path}] has [$column] = [${record.get(column)}], which is not a yyyy-mm-dd date",
          e,
        )
      }

    fun boolean(column: String): Boolean? =
      when (val raw = optional(column)) {
        null -> {
          null
        }

        "true" -> {
          true
        }

        "false" -> {
          false
        }

        else -> {
          error("row [${record.recordNumber}] of [${CSV_FILE.path}] has [$column] = [$raw], which is neither true nor false")
        }
      }

    val state = required("state")
    val chooser =
      TestChooser.fromValue(required("test_chooser"))
        ?: error("row [${record.recordNumber}] of [${CSV_FILE.path}] names test_chooser [${record.get("test_chooser")}]")
    val test = optional("test")
    // The pairing the schema states, refused HERE too, because this reader is
    // what the corpus test reads through: a state-chosen row names its test and
    // a locally-chosen row names none, and either half alone is a claim no page
    // supports.
    val choice =
      when {
        chooser == TestChooser.STATE && test != null -> {
          SchoolDayTestChoice.StateChose(
            AdmissionTest.fromValue(test)
              ?: error("row [${record.recordNumber}] of [${CSV_FILE.path}] names test [$test], which is not sat or act"),
          )
        }

        chooser == TestChooser.STATE -> {
          error("row [${record.recordNumber}] of [${CSV_FILE.path}] says the state chose the test and names none")
        }

        test != null -> {
          error("row [${record.recordNumber}] of [${CSV_FILE.path}] names test [$test] while the chooser is [${chooser.value}]")
        }

        else -> {
          SchoolDayTestChoice.ChosenLocally(chooser)
        }
      }

    val lowest = optional("lowest_grade")?.toInt()
    val highest = optional("highest_grade")?.toInt()
    require((lowest == null) == (highest == null)) {
      "row [${record.recordNumber}] of [${CSV_FILE.path}] has half a grade range: [$lowest]..[$highest]"
    }

    return NewAdmissionTestStateProgramme(
      state = state,
      schoolYear = AcademicYear(required("school_year").toInt()),
      schoolYearBasis =
        SchoolYearBasis.fromValue(required("school_year_basis"))
          ?: error("row [${record.recordNumber}] of [${CSV_FILE.path}] names school_year_basis [${record.get("school_year_basis")}]"),
      participation =
        SchoolDayParticipation.fromValue(required("participation"))
          ?: error("row [${record.recordNumber}] of [${CSV_FILE.path}] names participation [${record.get("participation")}]"),
      schoolMustOffer = boolean("school_must_offer"),
      choice = choice,
      grades = lowest?.let { GradeRange(it, highest!!) },
      stateFunded = boolean("state_funded"),
      sourceUrl = required("source_url"),
      archiveUrl = optional("archive_url"),
      checkedOn = date("checked_on"),
      expiresOn = date("expires_on"),
    )
  }
}
