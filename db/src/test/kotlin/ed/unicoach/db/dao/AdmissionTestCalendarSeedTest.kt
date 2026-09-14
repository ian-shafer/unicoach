package ed.unicoach.db.dao

import ed.unicoach.db.models.AdmissionTestAdministration.Companion.CALENDAR_REFRESH_LEAD
import ed.unicoach.db.models.NewAdmissionTestAdministration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * The committed SAT/ACT calendar under `db/seed/test-calendar/`, read FROM DISK
 * (RFC 197, brief 0010 D20). Nothing here touches the database: the subject is
 * the committed FILE, before any migration has a chance to load it.
 *
 * STALENESS IS A BUILD FAILURE, and that is the whole point of this class. The
 * rows are hand-typed from two publishers' HTML tables because no machine-readable
 * feed exists for either, so the only thing standing between a family and a
 * missed deadline is somebody noticing the year turned over. Rot is the
 * competitor's failure mode -- CollegeVine's 2016 sequence article still
 * outranks everyone -- and ours has to be found here rather than by a family who
 * acted on last year's date.
 *
 * THE GUARD READS THE LIVE ROWS AND NOT ALL OF THEM, and that is what makes it
 * clearable. The documented refresh is "append the next cycle, never edit a
 * landed seed", so the 2026-27 rows stay in this file forever. A guard over
 * every row would therefore still be reading their 2027-07-31 expiry in 2029,
 * and appending -- the one action the failure message asks for -- would not
 * clear it. `a refresh that appends the next cycle clears the guard` is the test
 * that says so.
 *
 * IT LIVES HERE AND NOT IN THE TOOL'S OWN TEST FILE, deliberately (spec, "the
 * unit of contention"): a guard inside the artifact under rewrite is not a
 * guard. The next run to rewrite `AdmissionTestTimelineChatToolTest` would be
 * free to rewrite the thing that was supposed to stop it, and would not even
 * know it had. It lives in `:db` because `:db` owns `db/seed/test-calendar/`
 * and the fixture that reads it; the committed-corpus precedent it is modelled
 * on is `CdsSeedLoaderTest` over in `:college`, which owns the CDS corpus for
 * the same reason. No other committed-corpus test sits in this package. It
 * reads the committed file through the fixture's own reader -- the same reader
 * the suites that seed these rows use, so a header change cannot leave this
 * sweep silently finding nothing.
 */
class AdmissionTestCalendarSeedTest {
  private val rows = AdmissionTestCalendarFixture.committedRows()

  /**
   * The refresh instruction, in full, because this test's failure IS the
   * instruction: whoever hits it is being told to re-read two pages and
   * regenerate a migration, and a failure message that names neither is a
   * failure message that gets ignored for a week.
   */
  private val refreshInstruction =
    "re-read the publishers' own pages -- " +
      "https://satsuite.collegeboard.org/sat/dates-deadlines and " +
      "https://satsuite.collegeboard.org/scores/score-release-dates for the SAT, " +
      "https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html for the " +
      "ACT -- APPEND the next cycle to ${AdmissionTestCalendarFixture.CSV_NAME} and its PROVENANCE.json from " +
      "them (never edit a landed row), and regenerate the seed migration with " +
      "`nix develop -c bin/test-calendar-seed`"

  @Test
  fun `the calendar still ahead of today is a whole cycle, and none of it is near expiring`() {
    assertEquals(
      emptyList(),
      guardFailures(rows, today()),
      "the committed SAT/ACT calendar has rotted, so the coach is about to refuse to name any test date; " +
        refreshInstruction,
    )
  }

  /**
   * THE REFRESH PATH, EXECUTED. The guard has to be clearable by the only action
   * its own message asks for, and the action is an APPEND: the generator's header
   * forbids editing a landed seed, so the 2026-27 rows can never be deleted.
   *
   * On a clock past the committed cycle the guard fails -- there is no live
   * calendar left. Append a next cycle and it passes, with the landed rows still
   * present and still carrying their own expired `expires_on`. A guard computed
   * over ALL rows would fail both halves of this test forever.
   */
  @Test
  fun `a refresh that appends the next cycle clears the guard`() {
    val afterTheCommittedCycle = LocalDate.parse("2027-08-01")
    assertTrue(
      guardFailures(rows, afterTheCommittedCycle).isNotEmpty(),
      "past the last committed sitting there is no live calendar, so the guard must fail: [$rows]",
    )

    val refreshed = rows + rows.map(::appendedNextCycle)

    assertEquals(
      emptyList(),
      guardFailures(refreshed, afterTheCommittedCycle),
      "appending the next cycle is the refresh the failure message asks for, so it must clear the guard",
    )
    assertEquals(
      rows,
      refreshed.take(rows.size),
      "the landed rows are still there, unedited -- an append never rewrites a committed row",
    )
  }

  /**
   * The manifest and the file, recomputed against each other: a hand edit or a
   * half-regeneration fails here (the CDS corpus precedent).
   *
   * BOTH KEY SETS ARE ASSERTED WHOLE, not just probed for the one name we expect:
   * `getValue(CSV_NAME)` passes just as happily on a manifest that also records a
   * file nobody committed, or that records the digest of a second CSV this reader
   * never opened. And the manifest's own dates are asserted against the ROWS,
   * because `sha256` recomputes over new bytes perfectly well while `cycle`,
   * `checked_on` and `expires_on` still describe last year's file.
   */
  @Test
  fun `the committed calendar matches its PROVENANCE manifest`() {
    val provenance =
      Json.parseToJsonElement(AdmissionTestCalendarFixture.PROVENANCE_FILE.readText()) as JsonObject
    val digests = provenance.getValue("sha256").jsonObject
    val counts = provenance.getValue("rows").jsonObject
    val onlyCommittedFile = setOf(AdmissionTestCalendarFixture.CSV_NAME)

    assertEquals(onlyCommittedFile, digests.keys, "PROVENANCE.json records a sha256 for a file this reader does not read")
    assertEquals(onlyCommittedFile, counts.keys, "PROVENANCE.json records a row count for a file this reader does not read")

    assertEquals(
      sha256Of(AdmissionTestCalendarFixture.CSV_FILE.readBytes()),
      digests.getValue(AdmissionTestCalendarFixture.CSV_NAME).jsonPrimitive.content,
      "[${AdmissionTestCalendarFixture.CSV_NAME}] does not match its recorded sha256; $refreshInstruction",
    )
    assertEquals(
      rows.size,
      counts
        .getValue(AdmissionTestCalendarFixture.CSV_NAME)
        .jsonPrimitive.content
        .toInt(),
      "[${AdmissionTestCalendarFixture.CSV_NAME}] has [${rows.size}] data rows, which is not its recorded count; " +
        refreshInstruction,
    )

    // The manifest's own dates, against the rows they claim to describe. The
    // cycle it names has to BE in the file, and every row of that cycle has to
    // carry the checked_on and expires_on the manifest recorded for it.
    val cycle = provenance.getValue("cycle").jsonPrimitive.content
    val cycleRows = rows.filter { it.cycle == cycle }
    assertTrue(
      cycleRows.isNotEmpty(),
      "PROVENANCE.json names cycle [$cycle], which no row of [${AdmissionTestCalendarFixture.CSV_NAME}] is in",
    )
    assertEquals(
      setOf(LocalDate.parse(provenance.getValue("checked_on").jsonPrimitive.content)),
      cycleRows.map { it.checkedOn }.toSet(),
      "PROVENANCE.json's checked_on is not the checked_on of the [$cycle] rows; $refreshInstruction",
    )
    assertEquals(
      setOf(LocalDate.parse(provenance.getValue("expires_on").jsonPrimitive.content)),
      cycleRows.map { it.expiresOn }.toSet(),
      "PROVENANCE.json's expires_on is not the expires_on of the [$cycle] rows; $refreshInstruction",
    )
  }

  /**
   * THE HEADER IS AUTHORED TWICE, in two languages: as
   * [AdmissionTestCalendarFixture.HEADER] here and as the awk generator's own
   * `EXPECTED_HEADER` line in `bin/test-calendar-seed`. Neither can read the
   * other, so a column renamed in one would leave the other refusing the file the
   * first one just wrote -- or, worse, accepting it and writing the seed from
   * columns it looked up by position.
   *
   * This is the only place the two can be compared, so it is compared here.
   */
  @Test
  fun `the fixture's header is the generator's own EXPECTED_HEADER`() {
    val generator = File(AdmissionTestCalendarFixture.COMMITTED_DIR, "../../../bin/test-calendar-seed").canonicalFile
    assertTrue(generator.isFile, "the generator was not found at [${generator.path}]")

    val declaration =
      generator
        .readLines()
        .single { it.startsWith("readonly EXPECTED_HEADER=") }
    val expectedHeader = declaration.substringAfter("'").substringBeforeLast("'")

    assertEquals(
      AdmissionTestCalendarFixture.HEADER.joinToString(","),
      expectedHeader,
      "[${generator.path}] and AdmissionTestCalendarFixture.HEADER disagree about the committed CSV's columns",
    )
  }

  /**
   * Everything wrong with [rows] as of [today], as sentences. Empty is the pass.
   *
   * ONE function, called by the guard test and by the refresh test, so "the
   * guard" is a thing that can be run against a hypothetical file rather than a
   * shape only the committed rows can be in.
   *
   * THE LIVE ROWS ARE THE SUBJECT of the staleness bar. A sitting whose test
   * date has passed tells a family nothing, its expiry is not a claim about
   * anything the coach would say, and it is never deleted -- so reading its
   * expiry would make the bar permanently unclearable.
   */
  private fun guardFailures(
    rows: List<NewAdmissionTestAdministration>,
    today: LocalDate,
  ): List<String> {
    val live = rows.filter { !it.testDate.isBefore(today) }
    val halfWritten =
      if (rows.size >= MINIMUM_AUTHORED_ROWS) {
        emptyList()
      } else {
        listOf(
          "[${AdmissionTestCalendarFixture.CSV_FILE.path}] carries [${rows.size}] rows in total, fewer than the " +
            "[$MINIMUM_AUTHORED_ROWS] one complete cycle has, so it is half-written",
        )
      }
    val exhausted =
      if (live.isNotEmpty()) {
        emptyList()
      } else {
        listOf(
          "[${AdmissionTestCalendarFixture.CSV_FILE.path}] has no sitting at all still ahead of [$today], so the " +
            "coach can name no test date",
        )
      }
    val stale =
      live
        .filter { ChronoUnit.DAYS.between(today, it.expiresOn) < CALENDAR_REFRESH_LEAD.days }
        .map { "[${it.test.value}] [${it.testDate}] expires_on=[${it.expiresOn}]" }
    return halfWritten + exhausted + stale
  }

  /**
   * One landed row shifted a year on: the shape of an APPENDED next cycle, for
   * the refresh test only. It is a hypothesis about what a refresh looks like and
   * is never written anywhere -- the real next cycle is retyped from the
   * publishers' pages, because a year-shifted SAT date is not a date College
   * Board published.
   */
  private fun appendedNextCycle(row: NewAdmissionTestAdministration): NewAdmissionTestAdministration =
    row.copy(
      cycle = "2027-28",
      testDate = row.testDate.plusYears(1),
      registrationDeadline = row.registrationDeadline.plusYears(1),
      lateRegistrationDeadline = row.lateRegistrationDeadline?.plusYears(1),
      scoreRelease = row.scoreRelease?.let { it.copy(date = it.date.plusYears(1)) },
      checkedOn = row.checkedOn.plusYears(1),
      expiresOn = row.expiresOn.plusYears(1),
    )

  /**
   * Today, in the zone this whole feature's dates are in.
   *
   * `LocalDate.now()` would read `ZoneId.systemDefault()` -- the one implicit
   * global RFC 197 introduced `coachToday()` to remove -- and the refresh bar
   * would then fire a day apart on a developer's machine and on CI. `:db` cannot
   * see `COACH_ZONE` in `:service`, which is why the zone is a literal here and
   * why it carries this comment.
   */
  private fun today(): LocalDate = LocalDate.ofInstant(Instant.now(), ZoneId.of("America/New_York"))

  private fun sha256Of(bytes: ByteArray): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }

  private companion object {
    /**
     * 8 SAT sittings + 7 ACT sittings is one complete cycle: the floor a
     * half-written or truncated file falls under.
     *
     * It is counted over ALL rows and not the live ones, and the two bars above
     * are separate for that reason. A cycle's live set legitimately SHRINKS
     * through the year -- by the second September of a cycle two sittings have
     * already been sat -- so a fifteen-row bar on the live set would fail every
     * autumn with nothing wrong. What the live set has to be is NON-EMPTY: that
     * is the non-vacuity the staleness filter below needs, and an exhausted
     * calendar is a real failure with a real fix.
     */
    const val MINIMUM_AUTHORED_ROWS = 15
  }
}
