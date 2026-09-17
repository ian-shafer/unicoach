package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.DeadlineJurisdiction
import ed.unicoach.db.models.FafsaFilingCycle
import ed.unicoach.db.models.HandTypedReferenceData.REFRESH_LEAD
import ed.unicoach.db.models.NewFafsaFilingDeadline
import ed.unicoach.db.models.PublishedDeadline
import ed.unicoach.db.models.UsStateCodes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The committed FAFSA deadline corpus under `db/seed/fafsa-deadlines/`, read
 * FROM DISK (RFC 223 section 8, brief 0012 D11). Nothing here touches the
 * database: the subject is the committed FILE, before any migration has a
 * chance to load it, on the [AdmissionTestStateProgrammeSeedTest] precedent.
 *
 * STALENESS IS A BUILD FAILURE, and that is the whole point of this class.
 * Every one of these dates moves every year, and a state agency that republishes
 * its page in March leaves our row saying last cycle's day -- a date a family
 * can ACT on, which is worse than the document it replaced.
 *
 * TWO GUARDS, AND THE FIRST ONE IS A CALENDAR ALARM. The corpus must hold rows
 * for the cycle now being filed ([FafsaFilingCycle.currentFilingYear]), which
 * turns over on 1 October; so the build fails every autumn until somebody types
 * the new cycle, deliberately. The second is the sibling's per-jurisdiction
 * freshness bar, and PER JURISDICTION is load bearing: a corpus-wide "something
 * is fresh" bar would let a state nobody re-checks hide behind a fresher
 * neighbour. `a stale jurisdiction cannot hide behind a fresh neighbour` is the
 * test that says so. THE FEDERAL ROWS ARE A JURISDICTION LIKE ANY OTHER.
 *
 * NEWEST-PER-JURISDICTION, because the corpus is APPEND-ONLY: a re-read adds a
 * row with a new `checked_on` and never edits a landed one (a landed generated
 * seed is replayed on every `db-reset` and so must never change), so a minimum
 * over ALL rows could never be cleared.
 */
class FafsaFilingDeadlineSeedTest {
  private val rows = FafsaFilingDeadlineFixture.committedRows()

  /**
   * The refresh instruction, in full, because this test's failure IS the
   * instruction: whoever hits it is being told to re-read agency pages and
   * regenerate a migration, and a failure message that names neither the file
   * nor the command gets ignored for a week.
   */
  private val refreshInstruction =
    "re-read that jurisdiction's own agency page, APPEND rows for the new cycle to " +
      "[${FafsaFilingDeadlineFixture.CSV_NAME}] (never edit a landed row) with a new checked_on, its expires_on and the " +
      "date the page now publishes, update PROVENANCE.json, and regenerate the seeds with " +
      "[${FafsaFilingDeadlineFixture.GENERATOR_COMMAND}]"

  /**
   * D11, AND THE YEARLY REMINDER. `currentFilingYear` turns over on 1 October,
   * so this fails on the first of some October unless the next cycle's rows
   * have been typed by then. That is the intended cost of a corpus of dates
   * fifty-one agencies republish every year.
   */
  @Test
  fun `the committed corpus holds rows for the cycle now being filed`() {
    val cycle = FafsaFilingCycle.currentFilingYear(today())

    assertTrue(
      rows.any { it.awardYear == cycle },
      "the committed FAFSA deadline corpus holds no row for [${cycle.label}], the cycle families are filing now, so the " +
        "page and the coach have nothing to say to a family filling in the form today; $refreshInstruction",
    )
  }

  /**
   * THE FIRST GUARD, PROVED ABLE TO FAIL. A guard asserted only against a
   * corpus that passes it is a guard nobody has ever seen fire; this runs it
   * over a corpus that is missing the current cycle -- built as a FILTERED LIST
   * and never by editing the committed file -- and requires the cycle to be
   * named.
   */
  @Test
  fun `a corpus with no rows for the cycle now being filed fails the guard`() {
    val today = today()
    val cycle = FafsaFilingCycle.currentFilingYear(today)
    val lastCycleOnly = rows.filterNot { it.awardYear == cycle }

    val faults = guardFaults(lastCycleOnly, today)

    assertEquals(
      listOf(GuardFault.NoRowsForCycle(cycle)),
      faults,
      "a corpus holding no row for [${cycle.label}] must fail with exactly that fault, as a value",
    )
    assertTrue(
      faults.all { it.sentence().contains(FafsaFilingDeadlineFixture.GENERATOR_COMMAND) },
      "every guard fault renders a sentence carrying the refresh instruction, command included; it said " +
        "[${faults.map { it.sentence() }}]",
    )
  }

  /**
   * THE SECOND GUARD, over the committed rows as they stand today.
   *
   * It passes today and will stop passing: `expires_on` is a fact about the
   * READING and is `checked_on` plus [READING_LIFE], so this corpus's readings
   * of 14 and 15 September 2026 put the [HandTypedReferenceData.REFRESH_LEAD] bar in mid-June 2027 and the
   * build fails then unless somebody re-reads the pages. An `expires_on` pinned
   * to the end of the award year instead would sit nine months past the cycle
   * it describes, and the bar could never bite inside a filing window at all --
   * a guard that cannot fire is not a guard.
   *
   * The tests below drive the same [guardSentences] over HYPOTHETICAL rows,
   * because a committed corpus that could demonstrate the failure today would
   * be a corpus that fails the build today.
   */
  @Test
  fun `every jurisdiction of the cycle now being filed has a current reading`() {
    assertEquals(
      emptyList(),
      guardSentences(rows, today()),
      "the committed FAFSA deadline corpus has rotted, so the coach is about to tell a family a filing date we last " +
        "checked too long ago; $refreshInstruction",
    )
  }

  /**
   * THE EXPIRY RULE ITSELF, ASSERTED OVER THE COMMITTED ROWS -- not just used
   * to build hypothetical re-reads.
   *
   * `expires_on = checked_on + `[READING_LIFE] is what makes the guard above
   * able to fire at all, and nothing else enforces it: the generator mirrors
   * only the schema's `expires_on > checked_on`, which a row expiring in the
   * year 2099 satisfies. Without this test the next appended refresh could
   * quietly restore the cycle-pinned expiry that made the [HandTypedReferenceData.REFRESH_LEAD] bar
   * unfireable, with every other test still green -- and the failure direction
   * is false REASSURANCE, which is the kind nobody notices.
   */
  @Test
  fun `every committed reading is believed for exactly one year`() {
    val wrong =
      rows
        .filterNot { it.expiresOn == it.checkedOn.plus(READING_LIFE) }
        .map { "[${FafsaFilingDeadlineFixture.label(it.jurisdiction)}] [${it.program}] read [${it.checkedOn}] expires [${it.expiresOn}]" }

    assertEquals(
      emptyList(),
      wrong,
      "every committed row expires [${READING_LIFE.days}] days after it was read, because expires_on is a fact about the " +
        "READING and not about the cycle: an expiry pinned to the end of the award year sits nine months past the " +
        "cycle it describes, and the [HandTypedReferenceData.REFRESH_LEAD] staleness bar could then never bite inside a filing window",
    )
  }

  /**
   * A STALE JURISDICTION CANNOT HIDE BEHIND A FRESH NEIGHBOUR, which is the
   * whole reason the bar is per jurisdiction rather than over the corpus.
   * Refresh every jurisdiction but one and the corpus is overwhelmingly
   * current; the one left behind still fails, BY NAME.
   */
  @Test
  fun `a stale jurisdiction cannot hide behind a fresh neighbour`() {
    val today = today()
    val forCycle = rowsOfCurrentCycle(today)
    val leftBehind =
      forCycle
        .map { FafsaFilingDeadlineFixture.label(it.jurisdiction) }
        .distinct()
        .sorted()
        .first()
    val corpus =
      forCycle.map { row ->
        if (FafsaFilingDeadlineFixture.label(row.jurisdiction) == leftBehind) {
          row.copy(expiresOn = today.plusDays(REFRESH_LEAD.days - 1L))
        } else {
          row.copy(expiresOn = today.plusDays(REFRESH_LEAD.days + 1L))
        }
      }

    val stale = guardFaults(corpus, today).filterIsInstance<GuardFault.StaleReading>().map { it.jurisdiction }

    assertEquals(
      listOf(leftBehind),
      stale.map(FafsaFilingDeadlineFixture::label),
      "exactly the one jurisdiction nobody re-read is stale, by its own typed value and not by a substring of the " +
        "message; the guard said [${guardSentences(corpus, today)}]",
    )
  }

  /**
   * THE REFRESH PATH, EXECUTED. The guard has to be clearable by the only
   * action its own message asks for, and that action is an APPEND: a landed row
   * is never edited, so the rows that were already there stay exactly where
   * they are, unchanged, and the re-read is a new row with a new `checked_on`.
   */
  @Test
  fun `appending a re-read for every jurisdiction clears the guard`() {
    val today = today()
    val stale = rowsOfCurrentCycle(today).map { it.copy(expiresOn = today.plusDays(REFRESH_LEAD.days - 1L)) }

    assertTrue(
      guardSentences(stale, today).isNotEmpty(),
      "a corpus whose every reading expires inside the refresh lead must fail the guard",
    )

    val refreshed = stale + stale.map { reReadOn(it, today) }

    assertEquals(
      emptyList(),
      guardSentences(refreshed, today),
      "appending a re-read is the refresh the failure message asks for, so it must clear the guard",
    )
    assertEquals(
      stale,
      refreshed.take(stale.size),
      "the rows that were already there are still there, unedited -- an append never rewrites a committed row",
    )
  }

  /**
   * The manifest and the file, recomputed against each other: a hand edit or a
   * half-regeneration fails here.
   *
   * THE KEY SETS ARE ASSERTED WHOLE, not probed for the one name we expect:
   * `getValue(CSV_NAME)` passes just as happily on a manifest that also records
   * a file nobody committed.
   *
   * THE MANIFEST'S OWN checked_on IS NOT ASSERTED AGAINST THE ROWS, and that is
   * a real difference from the state-programme sibling. There, one agent read
   * fifty pages in one sitting and every row shares one pair of dates. Here the
   * state rows were read on 2026-09-14 and the federal rows on the day of the
   * run (RFC 223 section 5), so `checked_on` and `expires_on` are PER ROW and a
   * test that forced them to agree would force a false date onto one of them.
   * The freshness guard is per jurisdiction and does not need them to agree.
   */
  @Test
  fun `the committed corpus matches its PROVENANCE manifest`() {
    val digests = provenance().getValue("sha256").jsonObject
    val counts = provenance().getValue("rows_by_file").jsonObject
    val onlyCommittedFile = setOf(FafsaFilingDeadlineFixture.CSV_NAME)

    assertEquals(
      onlyCommittedFile,
      digests.keys,
      "PROVENANCE.json records a sha256 for a file this reader does not read",
    )
    assertEquals(
      onlyCommittedFile,
      counts.keys,
      "PROVENANCE.json records a row count for a file this reader does not read",
    )
    assertEquals(
      sha256Of(FafsaFilingDeadlineFixture.CSV_FILE.readBytes()),
      digests.getValue(FafsaFilingDeadlineFixture.CSV_NAME).jsonPrimitive.content,
      "[${FafsaFilingDeadlineFixture.CSV_NAME}] does not match its recorded sha256; $refreshInstruction",
    )
    assertEquals(
      rows.size,
      recordedCount(counts, FafsaFilingDeadlineFixture.CSV_NAME),
      "[${FafsaFilingDeadlineFixture.CSV_NAME}] has [${rows.size}] data rows, which is not its recorded count",
    )

    // The coverage block counts the same rows a second way -- total, federal,
    // and jurisdictions with rows -- so a refresh that appended rows and
    // recomputed only the digest is a fault here rather than a manifest that
    // describes a corpus nobody has.
    val coverage = provenance().getValue("coverage").jsonObject
    assertEquals(
      rows.size,
      recordedCount(coverage, "rows"),
      "PROVENANCE.json's coverage.rows is not the number of rows in [${FafsaFilingDeadlineFixture.CSV_NAME}]",
    )
    assertEquals(
      rows.count { it.jurisdiction == DeadlineJurisdiction.Federal },
      recordedCount(coverage, "federal_rows"),
      "PROVENANCE.json's coverage.federal_rows is not the number of federal rows in the file",
    )
    assertEquals(
      rows.mapNotNull { it.jurisdiction.uspsCode }.distinct().size,
      recordedCount(coverage, "jurisdictions_with_rows"),
      "PROVENANCE.json's coverage.jurisdictions_with_rows is not the number of states the file holds a row for",
    )
  }

  /**
   * THE PER-CYCLE AND PER-READING COUNTS, recomputed rather than trusted.
   *
   * `cycles` is the measurement brief 0012's D23 turns into a display rule --
   * each jurisdiction is shown the newest cycle we VERIFIED -- so a stale
   * number here is a stale premise for `fafsa/03`. `readings` records how many
   * rows each reading day produced, which is the thing a partial refresh gets
   * wrong: append forty rows, recompute the digest, and every other assertion
   * still passes.
   */
  @Test
  fun `the manifest's cycle and reading counts are the file's own`() {
    val cycles = provenance().getValue("cycles").jsonObject
    val readings = provenance().getValue("readings").jsonObject

    assertEquals(
      rows.groupingBy { it.awardYear.label }.eachCount(),
      cycles.keys.associateWith { recordedCount(cycles.getValue(it).jsonObject, "rows") },
      "PROVENANCE.json's cycles block does not count the cycles the corpus actually holds; $refreshInstruction",
    )
    assertEquals(
      rows.groupingBy { it.awardYear.label }.eachCount().mapValues { (label, _) ->
        rows
          .filter { it.awardYear.label == label }
          .mapNotNull { it.jurisdiction.uspsCode }
          .distinct()
          .size
      },
      cycles.keys.associateWith { recordedCount(cycles.getValue(it).jsonObject, "jurisdictions") },
      "PROVENANCE.json's cycles block does not count the jurisdictions each cycle holds a row for",
    )
    assertEquals(
      rows.groupingBy { it.checkedOn.toString() }.eachCount(),
      readings.keys.associateWith { recordedCount(readings, it) },
      "PROVENANCE.json's readings block does not match the days the committed rows were read on",
    )
  }

  /**
   * THE FOUR JURISDICTIONS WE HOLD NOTHING FOR, named in the manifest (RFC 223
   * section 1): Arkansas, Delaware, North Carolina and New Hampshire all block
   * machine reading and no row for any of them could be confirmed against the
   * agency's own page. A missing row IS the unknown (D9), and `fafsa/04` turns
   * that absence into our own sentence -- which it can only do if the absence
   * is WRITTEN DOWN rather than inferred from a file that happens not to
   * mention them.
   *
   * Asserted BOTH WAYS: the manifest names exactly these four, and the file
   * holds a row for none of them. A manifest that named a state the corpus
   * actually covers would be telling a family we never checked a page we did.
   */
  @Test
  fun `the jurisdictions we hold nothing for are named in the manifest`() {
    val absent =
      provenance()
        .getValue("coverage")
        .jsonObject
        .getValue("absent")
        .jsonArray
        .map { it.jsonPrimitive.content }
        .toSet()

    assertEquals(
      UNSOURCED_JURISDICTIONS,
      absent,
      "PROVENANCE.json's coverage.absent is not the set of jurisdictions this corpus could not source",
    )
    assertEquals(
      emptyList(),
      rows
        .mapNotNull { it.jurisdiction.uspsCode }
        .filter(absent::contains)
        .distinct()
        .sorted(),
      "PROVENANCE.json calls a jurisdiction absent while the committed file holds a row for it",
    )
  }

  /**
   * Every `state` cell is one of the 59 published USPS codes, asserted through
   * [UsStateCodes.parse] -- the one home for that rule -- against the RAW cells
   * rather than the typed rows, because the typed rows have already decided.
   * The schema's `^[A-Z]{2}$` admits 676 strings and 617 of them name nothing.
   *
   * AND THE FEDERAL ROWS ARE NOT A STATE. Their cell is EMPTY, mirroring the
   * nullable column whose NULL means federal, and they must read back as
   * [DeadlineJurisdiction.Federal] -- not as a jurisdiction called "" and not
   * dropped.
   */
  @Test
  fun `every state cell is a published USPS code and an empty cell is the federal government`() {
    val cells = FafsaFilingDeadlineFixture.committedStateCells()

    assertEquals(rows.size, cells.size, "the reader disagrees with itself about how many rows the file has")
    assertEquals(
      emptyList(),
      cells.filter { it.isNotEmpty() && UsStateCodes.parse(it) != it }.distinct().sorted(),
      "a row names a jurisdiction that is not a published USPS code, exactly as typed -- 'ca' and 'ZZ' are both faults here",
    )

    val federalCells = cells.filter { it.isEmpty() }
    assertTrue(federalCells.isNotEmpty(), "the corpus holds the federal rows, whose state cell is empty")
    assertEquals(
      emptyList(),
      rows.filterIndexed { index, row -> cells[index].isEmpty() && row.jurisdiction != DeadlineJurisdiction.Federal },
      "a row with an empty state cell must read back as the federal government",
    )
    assertEquals(
      federalCells.size,
      rows.count { it.jurisdiction == DeadlineJurisdiction.Federal },
      "the federal rows are exactly the rows whose state cell is empty",
    )
  }

  /**
   * THE HEADER THIS READER REQUIRES IS THE HEADER THE FILE HAS, asserted rather
   * than assumed.
   *
   * The shell harness goes to real trouble not to retype the generator's header
   * -- it seds it out of the script -- and this reader retypes it anyway, in a
   * second language. Two hand-typed copies of a column order drift silently in
   * the direction that matters: a reordered column would move an archive url
   * into a source url, and every row would still parse.
   */
  @Test
  fun `the reader's header is the committed file's first line`() {
    assertEquals(
      FafsaFilingDeadlineFixture.CSV_FILE.readLines().first(),
      FafsaFilingDeadlineFixture.HEADER.joinToString(","),
      "this reader names its columns in a different order from the committed corpus, so every cell it reads is " +
        "a cell of some other column",
    )
  }

  /** Every committed day falls inside its own cycle's window, which is the rule `fafsa_filing_deadlines_date_in_cycle_check` states in SQL. */
  @Test
  fun `every committed deadline date falls inside its own cycle window`() {
    assertEquals(
      emptyList(),
      outsideOwnCycle(rows),
      "a committed date belongs to a cycle other than the one its row names, so a family would be shown last " +
        "cycle's day under this cycle's heading; $refreshInstruction",
    )
  }

  /**
   * THE WINDOW GUARD, PROVED ABLE TO FAIL, AND PROBED ONE DAY OUT.
   *
   * A window test probed with a date TWO cycles back is vacuous -- it fails for
   * a reason so gross that a window off by a year would still pass it -- and
   * that exact defect shipped in RFC 215 and three reviewers caught it. So the
   * probe is the tightest one there is: the day BEFORE the window opens, which
   * is 30 June of `award_year - 1` and is the last day of the PREVIOUS cycle,
   * and the day after it closes. Each is paired with the boundary day itself,
   * which must pass -- otherwise "the guard fires" would prove only that the
   * guard fires on everything.
   */
  @Test
  fun `a day one outside its own cycle window is refused and the boundary day is not`() {
    val dated = rows.first { it.deadline is PublishedDeadline.OnDate }
    val opens = FafsaFilingCycle.windowOpens(dated.awardYear)
    val closes = FafsaFilingCycle.windowCloses(dated.awardYear)

    assertEquals(emptyList(), outsideOwnCycle(listOf(dated.on(opens), dated.on(closes))), "the window's own boundary days are inside it")

    assertTrue(
      outsideOwnCycle(listOf(dated.on(opens.minusDays(1)))).isNotEmpty(),
      "[${opens.minusDays(1)}] is the last day of the previous cycle, so a [${dated.awardYear.label}] row carrying it is refused",
    )
    assertTrue(
      outsideOwnCycle(listOf(dated.on(closes.plusDays(1)))).isNotEmpty(),
      "[${closes.plusDays(1)}] is past the end of the [${dated.awardYear.label}] window, so a row carrying it is refused",
    )
  }

  /**
   * Everything wrong with [rows] as of [today], as TYPED faults. Empty is the
   * pass.
   *
   * IT RETURNS VALUES AND NOT SENTENCES, and that is the point of the type. The
   * guard knows the jurisdiction, the cycle, the day the page was read and the
   * day that reading expires; returning one interpolated string threw all four
   * away and left every assertion below recovering state by searching prose for
   * `[XX]`. Such a test asserts a property of the MESSAGE, not of the guard: a
   * programme name that happened to contain another jurisdiction's label would
   * flip it. The sentence is rendered at the edge, by [sentence], inside the
   * assertion message where a human reads it.
   *
   * ONE function, called by the guard tests and by the refresh tests, so "the
   * guard" is a thing that can be run against a hypothetical corpus rather than
   * a shape only the committed rows can be in.
   */
  private fun guardFaults(
    rows: List<NewFafsaFilingDeadline>,
    today: LocalDate,
  ): List<GuardFault> {
    val cycle = FafsaFilingCycle.currentFilingYear(today)
    val forCycle = rows.filter { it.awardYear == cycle }
    if (forCycle.isEmpty()) {
      return listOf(GuardFault.NoRowsForCycle(cycle))
    }

    // NEWEST PER JURISDICTION: by the day it was read, and then by how long
    // that reading is good for, so an appended re-read always outranks the row
    // it refreshes even when both were read on the same day.
    //
    // GROUPED BY THE TYPED JURISDICTION, never by its display label: the label
    // is what a human reads, and using it as the identity would merge two
    // jurisdictions the day two of them ever rendered alike.
    val newestPerJurisdiction =
      forCycle
        .groupBy { it.jurisdiction }
        .mapValues { (_, forJurisdiction) -> forJurisdiction.maxWith(compareBy({ it.checkedOn }, { it.expiresOn })) }

    return newestPerJurisdiction.entries
      .filter { (_, row) -> row.expiresOn < today.plus(REFRESH_LEAD) }
      .map { (jurisdiction, row) -> GuardFault.StaleReading(jurisdiction, row.awardYear, row.checkedOn, row.expiresOn) }
      .sortedBy { FafsaFilingDeadlineFixture.label(it.jurisdiction) }
  }

  /** The same guard, as the sentences a failing assertion prints. */
  private fun guardSentences(
    rows: List<NewFafsaFilingDeadline>,
    today: LocalDate,
  ): List<String> = guardFaults(rows, today).map { it.sentence() }

  /** A fault, rendered for a human -- the ONLY place a guard fault becomes prose. */
  private fun GuardFault.sentence(): String =
    when (this) {
      is GuardFault.NoRowsForCycle -> {
        "[${FafsaFilingDeadlineFixture.CSV_FILE.path}] holds no row for [${cycle.label}], the FAFSA cycle families are " +
          "filing now; $refreshInstruction"
      }

      is GuardFault.StaleReading -> {
        "[${FafsaFilingDeadlineFixture.label(jurisdiction)}] newest reading of [${awardYear.label}] " +
          "checked_on=[$checkedOn] expires_on=[$expiresOn]; $refreshInstruction"
      }
    }

  /** The rows of the cycle being filed on [today] -- the only rows either guard is about. */
  private fun rowsOfCurrentCycle(today: LocalDate): List<NewFafsaFilingDeadline> {
    val cycle = FafsaFilingCycle.currentFilingYear(today)
    return rows.filter { it.awardYear == cycle }
  }

  /**
   * One row read again, TODAY: the shape of an APPENDED refresh, for the
   * refresh test only. It is a hypothesis about what a re-read looks like and
   * is never written anywhere -- a real refresh is retyped from the agency's
   * page, because a date nobody re-read is not a date any agency published.
   */
  private fun reReadOn(
    row: NewFafsaFilingDeadline,
    checkedOn: LocalDate,
  ): NewFafsaFilingDeadline =
    row.copy(
      checkedOn = checkedOn,
      expiresOn = checkedOn.plus(READING_LIFE),
    )

  /** The same row carrying a different day, for the window probe. Only a `date` row has one to change. */
  private fun NewFafsaFilingDeadline.on(date: LocalDate): NewFafsaFilingDeadline =
    copy(deadline = PublishedDeadline.OnDate(date, deadline.deadlineAsPublished))

  /** Every row whose day is outside its own cycle's window, as sentences. */
  private fun outsideOwnCycle(rows: List<NewFafsaFilingDeadline>): List<String> =
    rows.mapNotNull { row ->
      val deadline = row.deadline
      val opens = FafsaFilingCycle.windowOpens(row.awardYear)
      val closes = FafsaFilingCycle.windowCloses(row.awardYear)
      if (deadline !is PublishedDeadline.OnDate || (deadline.date >= opens && deadline.date <= closes)) {
        null
      } else {
        "[${FafsaFilingDeadlineFixture.label(row.jurisdiction)}] [${row.program}] [${row.event.value}] names " +
          "[${deadline.date}], which is outside the [${row.awardYear.label}] window [$opens..$closes]"
      }
    }

  /**
   * The manifest, with its OUTER shape checked before anything traverses it.
   *
   * A bare `as JsonObject` dies with a ClassCastException naming no file, and
   * the file it would be naming is exactly the one a half-done refresh leaves
   * malformed -- which is the case this whole class exists for.
   */
  private fun provenance(): JsonObject =
    Json.parseToJsonElement(FafsaFilingDeadlineFixture.PROVENANCE_FILE.readText()) as? JsonObject
      ?: error("[${FafsaFilingDeadlineFixture.PROVENANCE_FILE.path}] is not a JSON object; $refreshInstruction")

  /** One whole number out of a manifest block, refused by name rather than by NumberFormatException. */
  private fun recordedCount(
    block: JsonObject,
    key: String,
  ): Int {
    val raw = block.getValue(key).jsonPrimitive.content
    return raw.toIntOrNull()
      ?: error("[${FafsaFilingDeadlineFixture.PROVENANCE_FILE.path}] records [$key] as [$raw], which is not a number")
  }

  /**
   * Today, in the zone this whole feature's dates are in.
   *
   * `LocalDate.now()` would read `ZoneId.systemDefault()`, and both guards
   * would then turn over a day apart on a developer's machine and on CI -- and
   * this one turns over on 1 OCTOBER, where `currentFilingYear` changes its
   * answer. `:db` cannot see `COACH_ZONE` in `:service`, which is why the zone
   * is a literal here and why it carries this comment (the
   * [AdmissionTestStateProgrammeSeedTest] rule).
   */
  private fun today(): LocalDate = LocalDate.ofInstant(Instant.now(), ZoneId.of("America/New_York"))

  private fun sha256Of(bytes: ByteArray): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }

  /**
   * What the freshness guard found, as a VALUE. See [guardFaults]: the guard's
   * answer is a fact about a jurisdiction and a cycle, and a test that searched
   * a rendered sentence for `[XX]` was asserting the message template instead.
   */
  private sealed interface GuardFault {
    /** The corpus holds nothing at all for the cycle families are filing now (D11). */
    data class NoRowsForCycle(
      val cycle: AcademicYear,
    ) : GuardFault

    /** This jurisdiction's NEWEST reading of the live cycle is inside the refresh lead. */
    data class StaleReading(
      val jurisdiction: DeadlineJurisdiction,
      val awardYear: AcademicYear,
      val checkedOn: LocalDate,
      val expiresOn: LocalDate,
    ) : GuardFault
  }

  private companion object {
    /**
     * The four jurisdictions this corpus holds NOTHING for (RFC 223 section 1).
     * Named here as well as in the manifest because the public page owes a
     * family our own sentence about each of them, and a set that silently
     * shrank would mean somebody had dropped a state rather than sourced it.
     */
    val UNSOURCED_JURISDICTIONS = setOf("AR", "DE", "NC", "NH")

    /**
     * How long a reading of an agency's page is believed: a year, which is the
     * rule the committed corpus itself follows (`expires_on = checked_on + 365
     * days`). A fact about the READING and not about the cycle, which is why a
     * re-read here dates its own expiry from the day it was read.
     */
    val READING_LIFE: Period = Period.ofDays(365)
  }
}
