package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.HandTypedReferenceData.REFRESH_LEAD
import ed.unicoach.db.models.NewAdmissionTestStateProgramme
import ed.unicoach.db.models.SchoolYearBasis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The committed state school-day corpus under `db/seed/test-state-programmes/`,
 * read FROM DISK (RFC 208, brief 0010 D20). Nothing here touches the database:
 * the subject is the committed FILE, before any migration has a chance to load
 * it.
 *
 * STALENESS IS A BUILD FAILURE, and that is the whole point of this class. These
 * programmes are re-funded yearly and two states in this very corpus changed test
 * vendor within two cycles (Illinois SAT to ACT, Kentucky ACT to SAT), so the only
 * thing between a family and "your state pays for this" being false is somebody
 * noticing the year turned over.
 *
 * THE GUARD READS THE NEWEST ROW PER STATE, and both halves of that phrase are
 * load bearing. NEWEST, because the corpus is APPEND-ONLY: a re-read adds a row
 * for the new school year and never edits a landed one, so last year's row keeps
 * its own `expires_on` forever and a minimum over ALL rows could never be cleared
 * -- the defect RFC 197's review caught one table along. PER STATE, because a
 * corpus-wide "something is fresh" bar would let a state nobody re-checks hide
 * behind a fresher neighbour. `a stale state cannot hide behind a fresh
 * neighbour` is the test that says so.
 *
 * IT LIVES HERE AND NOT BESIDE THE COACHING TESTS, deliberately (spec, "the unit
 * of contention"): a guard inside the artifact under rewrite is not a guard. It
 * lives in `:db` because `:db` owns `db/seed/test-state-programmes/` and the
 * fixture that reads it, on the [AdmissionTestCalendarSeedTest] precedent.
 */
class AdmissionTestStateProgrammeSeedTest {
  private val rows = AdmissionTestStateProgrammeFixture.committedRows()

  /**
   * The refresh instruction, in full, because this test's failure IS the
   * instruction: whoever hits it is being told to re-read state pages and
   * regenerate a migration, and a failure message that names neither is a failure
   * message that gets ignored for a week.
   */
  private val refreshInstruction =
    "re-read that state's own education-agency page, APPEND a row for the new school year to " +
      "${AdmissionTestStateProgrammeFixture.CSV_NAME} (never edit a landed row) with its checked_on, expires_on " +
      "and the year the page names, update PROVENANCE.json, and regenerate the seed with " +
      "`nix develop -c bin/test-state-programme-seed`"

  @Test
  fun `every state we hold a programme for has a current reading of it`() {
    assertEquals(
      emptyList(),
      guardFailures(rows, today()),
      "the committed state school-day corpus has rotted, so the coach is about to tell a family their state " +
        "pays for a sitting we last checked too long ago; $refreshInstruction",
    )
  }

  /**
   * THE REFRESH PATH, EXECUTED. The guard has to be clearable by the only action
   * its own message asks for, and the action is an APPEND: a landed row is never
   * edited, so the committed rows stay exactly where they are.
   */
  @Test
  fun `appending next year's reading for every state clears the guard`() {
    val afterTheCommittedReading = rows.maxOf { it.expiresOn }.plusDays(1)
    assertTrue(
      guardFailures(rows, afterTheCommittedReading).isNotEmpty(),
      "past every committed expiry there is no current reading left, so the guard must fail",
    )

    val refreshed = rows + rows.map { nextYearsReading(it, afterTheCommittedReading) }

    assertEquals(
      emptyList(),
      guardFailures(refreshed, afterTheCommittedReading),
      "appending next year's reading is the refresh the failure message asks for, so it must clear the guard",
    )
    assertEquals(
      rows,
      refreshed.take(rows.size),
      "the landed rows are still there, unedited -- an append never rewrites a committed row",
    )
  }

  /**
   * A STALE STATE CANNOT HIDE BEHIND A FRESH NEIGHBOUR, which is the whole reason
   * the bar is per state rather than over the corpus. Refresh every state but one
   * and the corpus is overwhelmingly current; the one left behind still fails, by
   * name.
   */
  @Test
  fun `a stale state cannot hide behind a fresh neighbour`() {
    val afterTheCommittedReading = rows.maxOf { it.expiresOn }.plusDays(1)
    val leftBehind =
      rows
        .map { it.state }
        .distinct()
        .sorted()
        .first()
    val refreshed =
      rows + rows.filterNot { it.state == leftBehind }.map { nextYearsReading(it, afterTheCommittedReading) }

    val failures = guardFailures(refreshed, afterTheCommittedReading)

    assertTrue(
      failures.any { it.contains("[$leftBehind]") },
      "the one state nobody re-read must be named by the guard; it said [$failures]",
    )
    assertTrue(
      failures.none { failure -> rows.any { it.state != leftBehind && failure.contains("[${it.state}]") } },
      "no refreshed state may be reported stale; it said [$failures]",
    )
  }

  /**
   * The manifest and the file, recomputed against each other: a hand edit or a
   * half-regeneration fails here (the CDS corpus precedent).
   *
   * BOTH KEY SETS ARE ASSERTED WHOLE, not probed for the one name we expect:
   * `getValue(CSV_NAME)` passes just as happily on a manifest that also records a
   * file nobody committed. And the manifest's own dates are asserted against the
   * ROWS, because `sha256` recomputes over new bytes perfectly well while
   * `checked_on` still describes last year's reading session.
   */
  @Test
  fun `the committed corpus matches its PROVENANCE manifest`() {
    val provenance =
      Json.parseToJsonElement(AdmissionTestStateProgrammeFixture.PROVENANCE_FILE.readText()) as JsonObject
    val digests = provenance.getValue("sha256").jsonObject
    val counts = provenance.getValue("rows_by_file").jsonObject
    val onlyCommittedFile = setOf(AdmissionTestStateProgrammeFixture.CSV_NAME)

    assertEquals(onlyCommittedFile, digests.keys, "PROVENANCE.json records a sha256 for a file this reader does not read")
    assertEquals(onlyCommittedFile, counts.keys, "PROVENANCE.json records a row count for a file this reader does not read")

    assertEquals(
      sha256Of(AdmissionTestStateProgrammeFixture.CSV_FILE.readBytes()),
      digests.getValue(AdmissionTestStateProgrammeFixture.CSV_NAME).jsonPrimitive.content,
      "[${AdmissionTestStateProgrammeFixture.CSV_NAME}] does not match its recorded sha256; $refreshInstruction",
    )
    assertEquals(
      rows.size,
      counts
        .getValue(AdmissionTestStateProgrammeFixture.CSV_NAME)
        .jsonPrimitive.content
        .toInt(),
      "[${AdmissionTestStateProgrammeFixture.CSV_NAME}] has [${rows.size}] data rows, which is not its recorded count",
    )

    // The reading SESSION's own dates, against the rows they describe. They are
    // one fact about one session -- a human read pages on a day, and that reading
    // expires on a day -- so a refresh that missed a row is a fault and not a
    // mixture.
    assertEquals(
      setOf(LocalDate.parse(provenance.getValue("checked_on").jsonPrimitive.content)),
      rows.map { it.checkedOn }.toSet(),
      "PROVENANCE.json's checked_on is not the checked_on of the committed rows; $refreshInstruction",
    )
    assertEquals(
      setOf(LocalDate.parse(provenance.getValue("expires_on").jsonPrimitive.content)),
      rows.map { it.expiresOn }.toSet(),
      "PROVENANCE.json's expires_on is not the expires_on of the committed rows; $refreshInstruction",
    )
  }

  /**
   * THE HEADER IS AUTHORED TWICE, in two languages: as
   * [AdmissionTestStateProgrammeFixture.HEADER] here and as the awk generator's
   * own `EXPECTED_HEADER` line in `bin/test-state-programme-seed`. Neither can
   * read the other, so a column renamed in one would leave the other refusing the
   * file the first one just wrote -- or, worse, reading it by position.
   */
  @Test
  fun `the fixture's header is the generator's own EXPECTED_HEADER`() {
    val generator = File(AdmissionTestStateProgrammeFixture.COMMITTED_DIR, "../../../bin/test-state-programme-seed").canonicalFile
    assertTrue(generator.isFile, "the generator was not found at [${generator.path}]")

    val declaration =
      generator
        .readLines()
        .single { it.startsWith("readonly EXPECTED_HEADER=") }
    val expectedHeader = declaration.substringAfter("'").substringBeforeLast("'")

    assertEquals(
      AdmissionTestStateProgrammeFixture.HEADER.joinToString(","),
      expectedHeader,
      "[${generator.path}] and AdmissionTestStateProgrammeFixture.HEADER disagree about the committed CSV's columns",
    )
  }

  /**
   * Every state code is one `us_states` publishes, asserted against the SAME
   * authored codebook the table's foreign key is loaded from -- so a typo is
   * caught in the file rather than at apply time in somebody else's db-reset. A
   * two-letter shape check would pass 'ZZ', which is nobody's state.
   */
  @Test
  fun `every state is a published jurisdiction, and each one is read once per school year`() {
    val published =
      Json
        .parseToJsonElement(codebooksFile().readText())
        .jsonObject
        .getValue("us_states")
        .jsonObject
        .getValue("codes")
        .jsonArray
        .map {
          it.jsonObject
            .getValue("code")
            .jsonPrimitive.content
        }.toSet()

    assertEquals(
      emptyList(),
      rows.map { it.state }.filterNot(published::contains).sorted(),
      "a row names a jurisdiction db/data/codebooks.json does not publish, so the foreign key would refuse it",
    )
    assertEquals(
      emptyList(),
      rows
        .groupBy { it.state to it.schoolYear }
        .filterValues { it.size > 1 }
        .keys
        .map { "[${it.first}] [${it.second.label}]" },
      "one state is read once per school year: a second row for the same pair is a contradiction, not a correction",
    )
    assertTrue(
      rows.map { it.state }.distinct().size >= MINIMUM_SOURCED_STATES,
      "the corpus covers [${rows.map { it.state }.distinct().size}] jurisdictions, fewer than the " +
        "[$MINIMUM_SOURCED_STATES] it shipped with, so it has been truncated rather than refreshed",
    )
  }

  /**
   * The two rules a row's SCHOOL YEAR has to obey, and the second one exists
   * because of what NEWEST-per-state means: a typo'd future year would become
   * that state's permanent answer AND would never be stale.
   */
  @Test
  fun `a school year is either the page's own or the year we read it in, and never beyond next`() {
    val faults =
      rows.flatMap { row ->
        val readingYear = academicYearOf(row.checkedOn)
        listOfNotNull(
          "[${row.state}] says the year is ours but names [${row.schoolYear.label}], not [${readingYear.label}]"
            .takeIf { row.schoolYearBasis == SchoolYearBasis.READING_YEAR && row.schoolYear != readingYear },
          "[${row.state}] names [${row.schoolYear.label}], which is beyond the school year after [${readingYear.label}]"
            .takeIf { row.schoolYear.firstCalendarYear > readingYear.firstCalendarYear + 1 },
        )
      }
    assertEquals(emptyList(), faults, "a school year on a committed row is not one this corpus can mean")
  }

  /**
   * Everything wrong with [rows] as of [today], as sentences. Empty is the pass.
   *
   * ONE function, called by the guard test and by the refresh tests, so "the
   * guard" is a thing that can be run against a hypothetical corpus rather than a
   * shape only the committed rows can be in.
   */
  private fun guardFailures(
    rows: List<NewAdmissionTestStateProgramme>,
    today: LocalDate,
  ): List<String> {
    val newestPerState =
      rows
        .groupBy { it.state }
        .mapValues { (_, forState) -> forState.maxWith(compareBy({ it.schoolYear }, { it.checkedOn })) }
    val halfWritten =
      if (newestPerState.size >= MINIMUM_SOURCED_STATES) {
        emptyList()
      } else {
        listOf(
          "[${AdmissionTestStateProgrammeFixture.CSV_FILE.path}] covers [${newestPerState.size}] jurisdictions, " +
            "fewer than the [$MINIMUM_SOURCED_STATES] it shipped with, so it is half-written",
        )
      }
    val stale =
      newestPerState.values
        .filter { ChronoUnit.DAYS.between(today, it.expiresOn) < REFRESH_LEAD.days }
        .sortedBy { it.state }
        .map { "[${it.state}] newest reading [${it.schoolYear.label}] checked_on=[${it.checkedOn}] expires_on=[${it.expiresOn}]" }
    return halfWritten + stale
  }

  /**
   * One landed row read again a year on: the shape of an APPENDED refresh, for
   * the refresh tests only. It is a hypothesis about what a re-read looks like and
   * is never written anywhere -- a real refresh is retyped from the state's page,
   * because a year-shifted programme is not a programme any state published.
   */
  private fun nextYearsReading(
    row: NewAdmissionTestStateProgramme,
    checkedOn: LocalDate,
  ): NewAdmissionTestStateProgramme =
    row.copy(
      schoolYear = academicYearOf(checkedOn),
      schoolYearBasis = SchoolYearBasis.READING_YEAR,
      checkedOn = checkedOn,
      expiresOn = checkedOn.plusYears(1),
    )

  /**
   * Today, in the zone this whole feature's dates are in.
   *
   * `LocalDate.now()` would read `ZoneId.systemDefault()`, and the refresh bar
   * would then fire a day apart on a developer's machine and on CI. `:db` cannot
   * see `COACH_ZONE` in `:service`, which is why the zone is a literal here and
   * why it carries this comment (the [AdmissionTestCalendarSeedTest] rule).
   */
  private fun today(): LocalDate = LocalDate.ofInstant(Instant.now(), ZoneId.of("America/New_York"))

  /** The academic year a date falls in, on the 1 July boundary the schema's own reading-year CHECK uses. */
  private fun academicYearOf(date: LocalDate): AcademicYear = AcademicYear(if (date.monthValue < JULY) date.year - 1 else date.year)

  private fun codebooksFile(): File = File(AdmissionTestStateProgrammeFixture.COMMITTED_DIR, "../../data/codebooks.json").canonicalFile

  private fun sha256Of(bytes: ByteArray): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }

  private companion object {
    /**
     * The number of jurisdictions the corpus shipped with (RFC 208). It is a
     * FLOOR over distinct states and not an equality: a re-read may add a state
     * whose page finally became readable -- Delaware, Texas, Utah and Virginia
     * were all unreadable on the first pass and sourced on the second -- and no
     * documented action ever removes one, because a programme that ends is a new
     * row saying so and not a deletion.
     */
    const val MINIMUM_SOURCED_STATES = 36

    /** The month the US school year turns over in, shared by the schema's reading-year CHECK and by this test. */
    const val JULY = 7
  }
}
