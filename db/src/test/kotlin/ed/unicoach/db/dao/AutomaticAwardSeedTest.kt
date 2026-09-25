package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.dao.AutomaticAwardFixture.CommittedAward
import ed.unicoach.db.dao.AutomaticAwardFixture.CommittedBand
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AutomaticAwardKind
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.HandTypedReferenceData.REFRESH_LEAD
import ed.unicoach.db.models.HandTypedReferenceData.cycleExpiryFor
import ed.unicoach.db.models.LastSittingConsidered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The committed automatic-award corpus under `db/seed/automatic-awards/`, read
 * FROM DISK (RFC 239 D20/D-G). Every case but the last one touches no database:
 * the subject is the committed FILES, before any loader has a chance to read
 * them.
 *
 * STALENESS IS A BUILD FAILURE, and that is this class's whole point. Every row
 * is hand-typed from a school's own scholarship page because no
 * machine-readable feed exists for any of them, and at these schools a score
 * band IS a price -- so the only thing standing between a family and a
 * four-figure wrong number is somebody noticing the cycle turned over. Rot has
 * to be found here rather than by a family who acted on last year's grid.
 *
 * IT READS EVERY COMMITTED ROW AND NOT A LIVE SUBSET, which is the one way it
 * differs from [AdmissionTestCalendarSeedTest]. That corpus is APPENDED to (the
 * next cycle is added, a landed row is never edited), so its guard has to read
 * the rows still ahead of today or an append could never clear it. This corpus
 * is RE-READ IN PLACE -- `PROVENANCE.json`'s own refresh instruction says edit
 * the two CSVs and re-run the loader -- so every committed row is a current
 * claim about a school, and the guard reads all of them. A re-read for the next
 * entry year is what clears it, and `the 90-day bar fires before a row expires`
 * executes exactly that.
 *
 * IT LIVES HERE AND NOT BESIDE THE LOADER, deliberately: a guard inside the
 * artifact under rewrite is not a guard, and `:db` owns both
 * `db/seed/automatic-awards/` and the reader
 * ([AutomaticAwardFixture.committedAwards]) that this test and the loader's own
 * suites share -- so a renamed column cannot leave this sweep silently finding
 * nothing.
 *
 * THE SHARED NUMBERS ARE [REFRESH_LEAD] (90 days) and [cycleExpiryFor]'s
 * `CYCLE_EXPIRY` (July 31 of the entry year). Never a private copy of either:
 * the lead used to be a bare `90L` in one test and the words "90 days" in three
 * prose blocks, and moving it would have left the prose asserting a lead time
 * nothing enforced -- and the expiry was a bare `7, 31` typed at each of the two
 * sites below, which is the same failure one corpus later.
 */
class AutomaticAwardSeedTest {
  private val awards: List<CommittedAward> = AutomaticAwardFixture.committedAwards()
  private val bands: List<CommittedBand> = AutomaticAwardFixture.committedBands()
  private val linkStatuses: Map<String, Pair<String, LocalDate?>> = AutomaticAwardFixture.committedLinkStatuses()
  private val provenance: JsonObject =
    Json.parseToJsonElement(AutomaticAwardFixture.PROVENANCE_FILE.readText()) as JsonObject

  /**
   * The refresh instruction, in full, because this test's failure IS the
   * instruction: whoever hits it is being told to re-read forty schools' pages,
   * and a failure message that names neither the files nor the sweep is a failure
   * message that gets ignored for a week. It is the corpus's OWN recorded
   * instruction, read from the file, so the two cannot drift.
   */
  private val refreshInstruction: String
    get() = provenance.getValue("refresh_instruction").jsonPrimitive.content

  @Test
  fun `the committed corpus is neither near expiring nor half-written`() {
    assertEquals(
      emptyList(),
      guardFailures(awards, bands, today()),
      "the committed automatic-award corpus has rotted, so the coach is about to quote a superseded grid; $refreshInstruction",
    )
  }

  /**
   * THE REFRESH PATH, EXECUTED. A guard has to be clearable by the only action
   * its own message asks for, and here that action is a RE-READ for the next
   * entry year: D-G puts `expires_on` at July 31 of the entry year, so the
   * refreshed corpus carries the next year's key, the day it was re-read, and the
   * next July 31.
   */
  @Test
  fun `the 90-day bar fires before a row expires, and a re-read clears it`() {
    val expiry = awards.map { it.expiresOn }.distinct().single()
    val justInsideTheBar = expiry.minusDays(REFRESH_LEAD.days.toLong() - 1)

    val failures = guardFailures(awards, bands, justInsideTheBar)
    assertTrue(
      failures.size == awards.size,
      "every committed row expires on [$expiry], so [$justInsideTheBar] is inside the bar for all of them: $failures",
    )
    // The day before that, nothing fires: the bar is REFRESH_LEAD and not
    // "some time before expiry", and a bar nobody can date is a bar nobody can
    // clear.
    assertEquals(
      emptyList(),
      guardFailures(awards, bands, expiry.minusDays(REFRESH_LEAD.days.toLong())),
      "the bar must fire exactly [$REFRESH_LEAD] out, not earlier",
    )

    // THE CELLS ARE RE-KEYED WITH THEIR ROWS, because a band is filed under its
    // school's CYCLE: a refresh that moved the award rows to the next entry year
    // and left the bands under the old one would be a corpus whose grids belong
    // to no school, and the guard would (rightly) call it half-written.
    val nextYear =
      AcademicYear(
        awards
          .map { it.key.entryYear }
          .distinct()
          .single()
          .firstCalendarYear + 1,
      )
    val refreshed = awards.map { reRead(it, nextYear, on = justInsideTheBar) }
    val refreshedCells = bands.map { cell -> cell.copy(key = cell.key.copy(entryYear = nextYear)) }
    assertEquals(
      emptyList(),
      guardFailures(refreshed, refreshedCells, justInsideTheBar),
      "a re-read for the next entry year is the refresh the failure message asks for, so it must clear the guard",
    )
  }

  /**
   * The manifest and the rows, against each other: a hand edit or a
   * half-regeneration fails here (the CDS and calendar corpora's precedent).
   *
   * THE WHOLE COUNTS OBJECT IS ASSERTED, not probed for the keys we expect:
   * `getValue("award_rows")` passes just as happily on a manifest that also
   * records a count nobody can derive from the files. Both maps are therefore
   * compared whole, and a new key fails by name until this test knows how to
   * compute it.
   *
   * ONE key needs its derivation written down, because it is not the one a
   * reader guesses: `source_urls` is the number of distinct urls the CORPUS
   * CITES -- 51 cited by award rows (no award row needed an archive) plus the 2
   * in `cited_documents_without_an_award_row` (ACT's Composite-arithmetic page
   * and Nebraska's second award page) -- which is why it equals
   * `link_status_urls`. `cited_urls` would have been the clearer name; the files
   * are frozen, so the derivation lives here instead.
   */
  @Test
  fun `PROVENANCE json agrees with the committed rows`() {
    assertEquals(
      countsFromRows(),
      provenance
        .getValue("counts")
        .jsonObject
        .mapValues { (_, value) -> value.jsonPrimitive.content.toInt() },
      "PROVENANCE.json's counts are not the rows'; $refreshInstruction",
    )

    val entryYear =
      AcademicYear(
        provenance
          .getValue("entry_year")
          .jsonPrimitive.content
          .toInt(),
      )
    assertEquals(
      setOf(entryYear),
      awards.map { it.key.entryYear }.toSet(),
      "PROVENANCE.json names entry year [${entryYear.label}], which is not the cycle the rows are keyed to",
    )
    assertEquals(
      setOf(LocalDate.parse(provenance.getValue("expires_on").jsonPrimitive.content)),
      awards.map { it.expiresOn }.toSet(),
      "PROVENANCE.json's expires_on is not the rows'; $refreshInstruction",
    )
    assertEquals(
      setOf(LocalDate.parse(provenance.getValue("read_on").jsonPrimitive.content)),
      awards.map { it.checkedOn }.toSet(),
      "PROVENANCE.json's read_on is not the day the rows say they were read",
    )
    // D-G, asserted over the rows and not only over the manifest: every row
    // expires on July 31 of its OWN entry year. A row keyed to one cycle and
    // expiring with another would be stale or immortal, and neither is a date
    // anybody can act on.
    assertEquals(
      emptyList(),
      awards
        .filter { it.expiresOn != cycleExpiryFor(it.key.entryYear) }
        .map { "[${it.key}] expires_on=[${it.expiresOn}]" },
      "D-G: a row expires on July 31 of its entry year",
    )
  }

  @Test
  fun `every url the corpus cites has a committed link status, and every status says when we asked`() {
    // THE SET, BOTH WAYS. "Every cited url has a status" alone passes on a sweep
    // carrying a url nothing cites -- and the two urls that no AWARD row cites
    // are cited by the service's arithmetic (ACT's Composite rule, D-H) and by
    // Nebraska's second award, so they are named in the manifest rather than
    // dropped from the sweep.
    assertEquals(
      citedUrls().sorted(),
      linkStatuses.keys.sorted(),
      "the cited urls and the committed link sweep must be the same set (RFC 220: a citation states its evidence)",
    )

    // You cannot claim to know without saying when you looked (RFC 200). Three of
    // these urls answered 403 in both passes and are committed as 'unknown' WITH
    // the date we asked, which is the honest state -- and is exactly why the DATE
    // is what this asserts rather than the verdict.
    assertEquals(
      emptyList(),
      linkStatuses.filterValues { it.second == null }.keys.toList(),
      "a committed link status with no date is a check that never ran looking like one that passed",
    )
  }

  /**
   * The three headers, against the committed files' own first records.
   *
   * [AutomaticAwardFixture]'s reader already `require`s each one before it maps a
   * row, so this test is not what makes a rename safe -- it is what makes a
   * rename fail BY THE FILE'S NAME, here, instead of surfacing as a
   * mapping failure inside whichever unrelated suite happened to read the corpus
   * first.
   */
  @Test
  fun `the readers' headers are the committed files' own headers, in order`() {
    assertEquals(
      AutomaticAwardFixture.AWARDS_HEADER.joinToString(","),
      headerOf(AutomaticAwardFixture.AWARDS_CSV),
      "[${AutomaticAwardFixture.AWARDS_CSV.path}] does not carry the columns its reader requires, in that order",
    )
    assertEquals(
      AutomaticAwardFixture.BANDS_HEADER.joinToString(","),
      headerOf(AutomaticAwardFixture.BANDS_CSV),
      "[${AutomaticAwardFixture.BANDS_CSV.path}] does not carry the columns its reader requires, in that order",
    )
    assertEquals(
      AutomaticAwardFixture.LINK_STATUS_HEADER.joinToString(","),
      headerOf(AutomaticAwardFixture.LINK_STATUS_FILE),
      "[${AutomaticAwardFixture.LINK_STATUS_FILE.path}] does not carry the columns its reader requires, in that order",
    )
  }

  /**
   * EVERY COMMITTED ROW IS WRITABLE, and this is the one case here that needs the
   * database -- deliberately, because it is the one question a disk-only test
   * cannot answer: whether the schema admits what we typed. A corpus that parses
   * and cannot be stored is a corpus that fails in the ingest run, at a school
   * nobody chose, after the rows before it have already landed.
   *
   * THE COLLEGES ARE CREATED HERE. `awards.csv` names the school by IPEDS unit id
   * and by the name as typed, and resolving that pair against a loaded `colleges`
   * table -- refusing an unknown unit id and a name that disagrees -- is the
   * LOADER's job and is tested end to end there. What this case needs is only
   * that a college with that unit id exists, so the foreign key resolves.
   *
   * IT READS BACK IDENTICALLY, and the GPA bounds are compared as NUMBERS rather
   * than as BigDecimals: 3.5 and the stored 3.500 are the same bound at two
   * scales, while 3.749 and 3.750 are two different prices one cell apart. Both
   * sides are therefore rendered through `stripTrailingZeros()`, which is
   * `compareTo` semantics with a readable failure message.
   */
  @Test
  fun `every committed row is writable, and reads back identically`() {
    val config =
      ed.unicoach.common.config.AppConfig
        .load("common.conf", "db.conf")
        .getOrThrow()
    val dbConfig =
      ed.unicoach.db.DatabaseConfig
        .from(config)
        .getOrThrow()
    val connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    val session =
      object : SqlSession {
        override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
      }
    try {
      connection.autoCommit = true
      // The corpus is loaded by the INGEST run and not by a migration (D-C), so a
      // freshly migrated database has no rows here and no colleges to key them
      // to. external_documents is shared with every other cited corpus and is
      // never emptied.
      connection.createStatement().use { stmt -> stmt.execute("TRUNCATE TABLE colleges, college_automatic_awards CASCADE") }
      CodebookReferenceFixture.seed(session)
      MoneyVocabularyFixture.seed(session)

      val colleges: Map<String, CollegeId> =
        awards
          .distinctBy { it.key.unitId }
          .associate { row ->
            row.key.unitId to
              CollegesDao
                .upsert(session, newCollegeFixture(row.key.unitId.toInt(), row.collegeName))
                .getOrThrow()
                .id
          }
      val cellsByKey = bands.groupBy { it.key }

      val mismatches =
        awards.flatMap { row ->
          val expected = cellsByKey[row.key].orEmpty().map { it.band }
          val stored =
            AutomaticAwardsDao
              .upsert(session, row.toNew(colleges.getValue(row.key.unitId)), expected)
              .getOrThrow()
          differences(row, expected, stored)
        }

      assertEquals(emptyList<String>(), mismatches, "a committed row did not survive a round trip through its own schema")
      assertEquals(awards.size, countRows(connection, "college_automatic_awards"))
      assertEquals(bands.size, countRows(connection, "college_automatic_award_bands"))
      // A none_published school has no grid, and the loader's door refuses one --
      // so a corpus that typed a band under one would fail the write above rather
      // than land a contradiction the read then has to refuse.
      assertEquals(
        emptyList(),
        awards
          .filter { it.awardKind == AutomaticAwardKind.NONE_PUBLISHED && cellsByKey.containsKey(it.key) }
          .map { it.key.toString() },
        "a school recorded as publishing no automatic award cannot carry bands",
      )
    } finally {
      if (!connection.isClosed) {
        connection.createStatement().use { stmt -> stmt.execute("TRUNCATE TABLE colleges, college_automatic_awards CASCADE") }
        connection.close()
      }
    }
  }

  // ---------------------------------------------------------------------------
  // The guard
  // ---------------------------------------------------------------------------

  /**
   * Everything wrong with [rows] as of [today], as sentences. Empty is the pass.
   *
   * ONE function, called by the guard test and by the refresh test, so "the
   * guard" is a thing that can be run against a hypothetical corpus rather than a
   * shape only the committed rows can be in.
   */
  private fun guardFailures(
    rows: List<CommittedAward>,
    cells: List<CommittedBand>,
    today: LocalDate,
  ): List<String> {
    val halfWritten = shortfalls(rows, cells)
    val stale =
      rows
        .filter { ChronoUnit.DAYS.between(today, it.expiresOn) < REFRESH_LEAD.days }
        .map { "[${it.collegeName}] [${it.key.residencyBasis.value}] expires_on=[${it.expiresOn}]" }
    return halfWritten + stale
  }

  /**
   * The minimum authored size, as sentences.
   *
   * THE FLOORS ARE NOT THE MEASURED COUNTS. `PROVENANCE.json` already pins the
   * exact numbers, asserted above; what these catch is the other failure, the one
   * an exact count cannot distinguish from an honest re-read: a file truncated by
   * a half-finished edit, a generator that wrote a header and no rows, a merge
   * that kept one side. They sit at roughly two thirds of the measured corpus, so
   * a re-read that legitimately drops a school or two passes and a truncation
   * fails.
   */
  private fun shortfalls(
    rows: List<CommittedAward>,
    allCells: List<CommittedBand>,
  ): List<String> {
    // Only the cells that belong to a row of THIS corpus count: a band filed
    // under a key no award row carries is a grid belonging to no school, and it
    // must not make a truncated file look complete.
    val cells = allCells.filter { cell -> rows.any { it.key == cell.key } }
    return listOfNotNull(
      shortfall("award rows", AutomaticAwardFixture.AWARDS_CSV.path, rows.size, MINIMUM_AWARD_ROWS),
      shortfall("band rows", AutomaticAwardFixture.BANDS_CSV.path, cells.size, MINIMUM_BAND_ROWS),
      shortfall("schools", AutomaticAwardFixture.AWARDS_CSV.path, rows.distinctBy { it.key.unitId }.size, MINIMUM_SCHOOLS),
      shortfall(
        "score-bearing band rows",
        AutomaticAwardFixture.BANDS_CSV.path,
        cells.count { it.band.test != null },
        MINIMUM_SCORE_BEARING_BANDS,
      ),
    )
  }

  private fun shortfall(
    what: String,
    path: String,
    actual: Int,
    floor: Int,
  ): String? =
    if (actual >= floor) {
      null
    } else {
      "[$path] carries [$actual] $what, fewer than the [$floor] the curated corpus was measured at, so it is half-written"
    }

  /**
   * One committed row as a RE-READ for the next entry year: the next cycle's key,
   * the day it was re-read, and July 31 of the new entry year (D-G).
   *
   * It is a hypothesis about what a refresh looks like, for the refresh test
   * only, and is never written anywhere: the real next cycle is retyped from
   * forty schools' pages, because a year-shifted band is not a number any school
   * published.
   */
  private fun reRead(
    row: CommittedAward,
    nextYear: AcademicYear,
    on: LocalDate,
  ): CommittedAward =
    row.copy(
      key = row.key.copy(entryYear = nextYear),
      checkedOn = on,
      expiresOn = cycleExpiryFor(nextYear),
    )

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Every count `PROVENANCE.json` records, computed from the files it describes. */
  private fun countsFromRows(): Map<String, Int> =
    mapOf(
      "award_rows" to awards.size,
      "band_rows" to bands.size,
      "score_grid_rows" to awards.count { it.awardKind == AutomaticAwardKind.SCORE_GRID },
      "gpa_only_grid_rows" to awards.count { it.awardKind == AutomaticAwardKind.GPA_ONLY_GRID },
      "none_published_rows" to awards.count { it.awardKind == AutomaticAwardKind.NONE_PUBLISHED },
      "distinct_score_grid_colleges" to
        awards.filter { it.awardKind == AutomaticAwardKind.SCORE_GRID }.distinctBy { it.key.unitId }.size,
      "distinct_colleges" to awards.distinctBy { it.key.unitId }.size,
      "source_urls" to citedUrls().size,
      "link_status_urls" to linkStatuses.size,
      "bands_with_words_not_a_figure" to bands.count { it.band.awardPerYearUsd == null },
      "rows_with_receipt_deadline" to awards.count { it.scoreDeadline != null },
      "rows_with_last_sitting_date" to awards.count { it.lastSittingConsidered is LastSittingConsidered.Through },
      "rows_with_last_sitting_words_only" to awards.count { it.lastSittingConsidered is LastSittingConsidered.InWords },
    )

  /** Every url the corpus cites: the award rows' pages, their archives, and the documents the manifest names with no award row. */
  private fun citedUrls(): List<String> =
    (
      awards.map { it.sourceUrl } +
        awards.mapNotNull { it.archiveUrl } +
        (provenance.getValue("cited_documents_without_an_award_row") as JsonArray)
          .map { (it as JsonObject).getValue("url").jsonPrimitive.content }
    ).distinct()

  /**
   * The committed file's own first line.
   *
   * Read as a LINE rather than through `commons-csv`, which is on the
   * test-fixtures classpath and deliberately not on this one: the subject here is
   * the header's spelling and ORDER, these column names contain no comma and no
   * quote, and the fixture's reader -- which does use a real CSV parser -- is
   * what reads the rows.
   */
  private fun headerOf(file: java.io.File): String = file.bufferedReader().use { it.readLine() }

  /** Every way [stored] differs from the row and cells that were written, as sentences. */
  private fun differences(
    row: CommittedAward,
    expected: List<ed.unicoach.db.models.NewAutomaticAwardBand>,
    stored: ed.unicoach.db.models.AutomaticAward,
  ): List<String> {
    val at = "[${row.collegeName}] [${row.key.entryYear.label}] [${row.key.residencyBasis.value}]"
    val fields =
      listOf<Triple<String, Any?, Any?>>(
        Triple("entry_year", row.key.entryYear, stored.entryYear),
        Triple("residency_basis", row.key.residencyBasis, stored.residencyBasis),
        Triple("award_kind", row.awardKind, stored.awardKind),
        Triple("score_requirement", row.scoreRequirement, stored.scoreRequirement),
        Triple("score_requirement_as_published", row.scoreRequirementAsPublished, stored.scoreRequirementAsPublished),
        Triple("superscore_rule", row.superscoreRule, stored.superscoreRule),
        Triple("superscore_as_published", row.superscoreAsPublished, stored.superscoreAsPublished),
        Triple("score_deadline", row.scoreDeadline, stored.scoreDeadline),
        Triple("score_deadline_as_published", row.scoreDeadlineAsPublished, stored.scoreDeadlineAsPublished),
        Triple("last_sitting", row.lastSittingConsidered, stored.lastSittingConsidered),
        Triple("publisher", row.publisher, stored.publisher),
        Triple("source_url", row.sourceUrl, stored.sourceUrl),
        Triple("archive_url", row.archiveUrl, stored.archiveUrl),
        Triple("checked_on", row.checkedOn, stored.checkedOn),
        Triple("expires_on", row.expiresOn, stored.expiresOn),
      )
    val changedFields =
      fields.mapNotNull { (column, wrote, read) ->
        if (wrote == read) null else "$at [$column]: wrote [$wrote], read back [$read]"
      }
    val wroteCells = expected.map(::cellOf).toSet()
    val readCells = stored.bands.map(::cellOf).toSet()
    val changedCells =
      listOfNotNull(
        (wroteCells - readCells).takeIf { it.isNotEmpty() }?.let { "$at wrote cells that did not come back: $it" },
        (readCells - wroteCells).takeIf { it.isNotEmpty() }?.let { "$at read back cells it never wrote: $it" },
        "$at wrote [${expected.size}] cells and read back [${stored.bands.size}]"
          .takeIf { expected.size != stored.bands.size },
      )
    return changedFields + changedCells
  }

  /** One cell, comparable: the GPA bounds as NUMBERS, so a scale difference is not a difference and a rounded bound is. */
  private fun cellOf(band: ed.unicoach.db.models.NewAutomaticAwardBand): Cell =
    Cell(
      awardName = band.awardName,
      test = band.test,
      scoreLow = band.scoreLow,
      scoreHigh = band.scoreHigh,
      gpaLow = band.gpaLow.asNumber(),
      gpaHigh = band.gpaHigh.asNumber(),
      awardPerYearUsd = band.awardPerYearUsd,
      awardAsPublished = band.awardAsPublished,
    )

  /**
   * The same cell read back. The score axis is UNPACKED to the three columns
   * the write side still carries, so the two `cellOf`s compare the same shape
   * and a round trip is compared column by column -- not one shape against its
   * own re-encoding, which would pass however the encoding drifted.
   */
  private fun cellOf(band: ed.unicoach.db.models.AutomaticAwardBand): Cell {
    val range = band.score as? ed.unicoach.db.models.BandScoreAxis.Range
    return Cell(
      awardName = band.awardName,
      test = range?.test,
      scoreLow = range?.low,
      scoreHigh = range?.high,
      gpaLow = band.gpaLow.asNumber(),
      gpaHigh = band.gpaHigh.asNumber(),
      awardPerYearUsd = band.awardPerYearUsd,
      awardAsPublished = band.awardAsPublished,
    )
  }

  private fun BigDecimal?.asNumber(): String? = this?.stripTrailingZeros()?.toPlainString()

  private fun countRows(
    connection: Connection,
    table: String,
  ): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  /**
   * Today, in the zone this whole feature's dates are in.
   *
   * `LocalDate.now()` would read `ZoneId.systemDefault()` -- the implicit global
   * RFC 197 introduced `coachToday()` to remove -- and the refresh bar would then
   * fire a day apart on a developer's machine and on CI. `:db` cannot see
   * `COACH_ZONE` in `:service`, which is why the zone is a literal here and why
   * it carries this comment.
   */
  private fun today(): LocalDate = LocalDate.ofInstant(Instant.now(), ZoneId.of("America/New_York"))

  /** One published cell, reduced to what a round trip must preserve. */
  private data class Cell(
    val awardName: String,
    val test: AdmissionTest?,
    val scoreLow: Int?,
    val scoreHigh: Int?,
    val gpaLow: String?,
    val gpaHigh: String?,
    val awardPerYearUsd: Int?,
    val awardAsPublished: String?,
  )

  private companion object {
    /** Forty schools' worth of rows was the measured corpus; a file under this is truncated, not merely smaller. */
    const val MINIMUM_AWARD_ROWS = 40

    /** 571 cells were measured. */
    const val MINIMUM_BAND_ROWS = 380

    /** 40 schools were measured. */
    const val MINIMUM_SCHOOLS = 27

    /** 483 of the measured cells name a test, and they are the ones that make "a score is money" sayable at all. */
    const val MINIMUM_SCORE_BEARING_BANDS = 320
  }
}
