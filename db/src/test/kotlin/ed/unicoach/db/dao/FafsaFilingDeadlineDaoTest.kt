package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AidDeadlineEvent
import ed.unicoach.db.models.AidDeadlineKind
import ed.unicoach.db.models.DeadlineJurisdiction
import ed.unicoach.db.models.NewExternalDocument
import ed.unicoach.db.models.NewFafsaFilingDeadline
import ed.unicoach.db.models.PublishedDeadline
import ed.unicoach.db.models.SourceUrlStatus
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FafsaFilingDeadlineDao] against the real database (RFC 215).
 *
 * THE MIGRATION IS UNDER TEST HERE TOO. `bin/test` re-inits and migrates the
 * test database before Gradle runs, so a suite that connects at all has proved
 * `create-fafsa-filing-deadlines.sql` applies to a freshly recreated database --
 * and this is the file that says so out loud.
 *
 * It does NOT save and restore the table, unlike
 * [AdmissionTestStateProgrammeDaoTest]: this slice ships NO rows, so a freshly
 * migrated `fafsa_filing_deadlines` is empty and emptying it between cases
 * leaves the database exactly as `db-reset` left it. The day `fafsa/02` seeds
 * rows, this suite grows that sibling's temp-table save and restore -- and the
 * empty-table case below is what will fail first if it does not.
 */
class FafsaFilingDeadlineDaoTest {
  companion object {
    private lateinit var connection: Connection

    /** The cycle every row in this suite belongs to: the 2027-28 FAFSA, opened in October 2026. */
    private val AWARD_YEAR = AcademicYear(2027)

    /** A day inside that cycle's filing window, and the day every row here was "read". */
    private val CHECKED_ON: LocalDate = LocalDate.of(2026, 9, 14)

    /** After [CHECKED_ON], as `fafsa_filing_deadlines_expiry_after_checked_check` requires. */
    private val EXPIRES_ON: LocalDate = LocalDate.of(2027, 7, 31)

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        ed.unicoach.db.DatabaseConfig
          .from(config)
          .getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) {
        connection.createStatement().use { stmt -> stmt.execute("DELETE FROM fafsa_filing_deadlines") }
        connection.close()
      }
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  @BeforeEach
  fun setup() {
    // The citations are NOT deleted: `external_documents` is shared with every
    // other corpus, and a suite that emptied it would take their citations with
    // it.
    connection.createStatement().use { stmt -> stmt.execute("DELETE FROM fafsa_filing_deadlines") }
    // RFC 229: `state` now keys into us_states, and the migration-seeded rows
    // may have been truncated by an earlier suite in this module, so this suite
    // makes its own precondition true instead of depending on test order.
    CodebookReferenceFixture.seed(connection)
  }

  @Test
  fun `the migration left an empty table, and no committed row is deleted by this suite`() {
    // fafsa/01 ships the table and no rows (fafsa/02 ships the rows). If this
    // ever fails, the suite above is deleting somebody's committed corpus and
    // needs the save-and-restore its sibling has.
    assertEquals(0, countRows(), "fafsa/01 ships no rows; a non-empty table means this suite is destroying seeded data")
  }

  @Test
  fun `a date, a wording and a none row all round-trip with their citations`() {
    FafsaFilingDeadlineDao
      .upsert(
        session,
        deadline(
          jurisdiction = DeadlineJurisdiction.State.of("CA")!!,
          program = "Cal Grant",
          deadline = PublishedDeadline.OnDate(LocalDate.of(2027, 3, 2), "March 2, 2027, 11:59 p.m. Pacific"),
          publisher = "California Student Aid Commission",
          archiveUrl = "https://web.archive.org/ca",
        ),
      ).getOrThrow()
    FafsaFilingDeadlineDao
      .upsert(
        session,
        deadline(
          jurisdiction = DeadlineJurisdiction.State.of("TX")!!,
          program = "TEXAS Grant",
          deadline = PublishedDeadline.InWords("as soon as possible after the FAFSA opens; awards are made until funds are exhausted"),
        ),
      ).getOrThrow()
    FafsaFilingDeadlineDao
      .upsert(
        session,
        deadline(
          jurisdiction = DeadlineJurisdiction.State.of("WY")!!,
          program = "Hathaway Scholarship",
          deadline = PublishedDeadline.NonePublished("no state filing deadline is published"),
        ),
      ).getOrThrow()

    val rows = FafsaFilingDeadlineDao.listByAwardYear(session, AWARD_YEAR).getOrThrow()
    assertEquals(3, rows.size)

    val california = rows.single { it.jurisdiction == DeadlineJurisdiction.State.of("CA")!! }
    assertEquals(AWARD_YEAR, california.awardYear)
    assertEquals("Cal Grant", california.program)
    assertEquals(AidDeadlineEvent.PRIORITY_DEADLINE, california.event)
    // BOTH halves come back: our parsed day, and the publisher's own sentence --
    // which carries the hour and the time zone a DATE cannot hold.
    assertEquals(PublishedDeadline.OnDate(LocalDate.of(2027, 3, 2), "March 2, 2027, 11:59 p.m. Pacific"), california.deadline)
    assertEquals("California Student Aid Commission", california.publisher)
    // The citation survives the move into external_documents (RFC 213): the
    // read joins both urls back.
    assertEquals(sourceUrlFor("Cal Grant"), california.sourceUrl)
    assertEquals("https://web.archive.org/ca", california.archive?.url)
    assertEquals(CHECKED_ON, california.checkedOn)
    assertEquals(EXPIRES_ON, california.expiresOn)

    val texas = rows.single { it.jurisdiction == DeadlineJurisdiction.State.of("TX")!! }
    // A published RULE stays a rule. Nothing may complete it with a date.
    assertEquals(
      PublishedDeadline.InWords("as soon as possible after the FAFSA opens; awards are made until funds are exhausted"),
      texas.deadline,
    )
    // There is no `date` to read on a wording row, and that is the point of the
    // sealed type -- this asserts the ARM, which is the only thing that could
    // carry one.
    assertTrue(
      texas.deadline !is PublishedDeadline.OnDate,
      "a wording row must read back as words, never as a guessed date",
    )
    assertNull(texas.archive, "a row that needed no archive must read back as null, not as its own source")

    val wyoming = rows.single { it.jurisdiction == DeadlineJurisdiction.State.of("WY")!! }
    // A VERIFIED absence, with a citation -- which is a different thing from a
    // missing row, and the public page prints them differently (D9).
    assertEquals(PublishedDeadline.NonePublished("no state filing deadline is published"), wyoming.deadline)
    assertEquals(sourceUrlFor("Hathaway Scholarship"), wyoming.sourceUrl)
  }

  /**
   * RFC 200 reaches this table: a row's two urls are read back WITH the link
   * sweep's verdict on each, because the public page cannot obey "never link to
   * a grave" without it and must not decide it for itself.
   *
   * A citing write records FOUND on the day it read the page
   * ([ExternalDocumentsDao.citationIds]); a SWEEP is what changes that, so the
   * sweep is what this case runs.
   */
  @Test
  fun `each citation reads back with the link sweep's verdict, and no archive means no archive verdict`() {
    FafsaFilingDeadlineDao
      .upsert(
        session,
        deadline(
          jurisdiction = DeadlineJurisdiction.State.of("WY")!!,
          program = "Hathaway Scholarship",
          deadline = PublishedDeadline.NonePublished("no state filing deadline is published"),
        ),
      ).getOrThrow()
    ExternalDocumentsDao
      .upsert(
        session,
        NewExternalDocument(
          url = sourceUrlFor("Hathaway Scholarship"),
          urlStatus = SourceUrlStatus.NOT_FOUND,
          urlCheckedOn = LocalDate.of(2026, 9, 16),
        ),
      ).getOrThrow()

    val wyoming = FafsaFilingDeadlineDao.listAll(session).getOrThrow().single()

    assertEquals(
      SourceUrlStatus.NOT_FOUND,
      wyoming.sourceUrlStatus,
      "the sweep's verdict did not reach the row that cites the swept page",
    )
    assertNull(wyoming.archive, "a row with no archived address must carry no verdict about one either")
  }

  /** A row read through an archive carries a verdict for that address too, separately from its source's. */
  @Test
  fun `an archived citation carries its own verdict`() {
    FafsaFilingDeadlineDao
      .upsert(
        session,
        deadline(
          jurisdiction = DeadlineJurisdiction.State.of("CA")!!,
          program = "Cal Grant",
          archiveUrl = "https://web.archive.org/ca-verdict",
        ),
      ).getOrThrow()

    val california = FafsaFilingDeadlineDao.listAll(session).getOrThrow().single()

    // Both were written by the CITING path, which records what it knows on the
    // day it read the page and never overwrites a sweep (RFC 182).
    assertEquals(SourceUrlStatus.FOUND, california.sourceUrlStatus)
    assertEquals(SourceUrlStatus.FOUND, california.archive?.urlStatus)
  }

  @Test
  fun `federal rows lead, and only the named cycle is returned`() {
    FafsaFilingDeadlineDao
      .upsert(session, deadline(jurisdiction = DeadlineJurisdiction.State.of("CA")!!, program = "Cal Grant"))
      .getOrThrow()
    FafsaFilingDeadlineDao
      .upsert(
        session,
        deadline(
          jurisdiction = DeadlineJurisdiction.Federal,
          program = "Federal student aid",
          publisher = "Federal Student Aid",
        ),
      ).getOrThrow()
    // Last cycle's row, same state, same program, same event -- the row that is
    // not merely old but a DIFFERENT date that looks like this cycle's.
    FafsaFilingDeadlineDao
      .upsert(
        session,
        deadline(
          awardYear = AcademicYear(2026),
          jurisdiction = DeadlineJurisdiction.State.of("CA")!!,
          program = "Cal Grant",
          deadline = PublishedDeadline.OnDate(LocalDate.of(2026, 3, 2), "March 2, 2026"),
          checkedOn = CHECKED_ON.minusYears(1),
          expiresOn = EXPIRES_ON.minusYears(1),
        ),
      ).getOrThrow()

    val rows = FafsaFilingDeadlineDao.listByAwardYear(session, AWARD_YEAR).getOrThrow()

    assertEquals(3, countRows(), "a second cycle is a second row; nothing here overwrites another year")
    assertEquals(
      listOf(DeadlineJurisdiction.Federal, DeadlineJurisdiction.State.of("CA")!!),
      rows.map { it.jurisdiction },
      "the federal rows lead (state ASC NULLS FIRST), and last cycle's row is not in this cycle's read",
    )
  }

  @Test
  fun `two federal rows for one event collide on the natural key`() {
    val first =
      FafsaFilingDeadlineDao
        .upsert(
          session,
          deadline(
            jurisdiction = DeadlineJurisdiction.Federal,
            program = "Federal student aid",
            event = AidDeadlineEvent.CORRECTIONS_DEADLINE,
            deadline = PublishedDeadline.OnDate(LocalDate.of(2028, 9, 11), "September 11, 2028"),
            publisher = "Federal Register",
          ),
        ).getOrThrow()
    val second =
      FafsaFilingDeadlineDao
        .upsert(
          session,
          deadline(
            jurisdiction = DeadlineJurisdiction.Federal,
            program = "Federal student aid",
            event = AidDeadlineEvent.CORRECTIONS_DEADLINE,
            deadline = PublishedDeadline.OnDate(LocalDate.of(2028, 9, 12), "September 12, 2028"),
            publisher = "Federal Register",
          ),
        ).getOrThrow()

    // THIS IS WHAT `NULLS NOT DISTINCT` BUYS. A plain unique index passes every
    // other case in this file and lets these two disagreeing federal rows BOTH
    // insert, because NULL <> NULL -- and the page would then print two federal
    // corrections deadlines.
    assertEquals(1, countRows(), "two federal rows for one event must collide, not both insert")
    assertEquals(first.id, second.id, "the second write must be a correction of the same row")
    val stored = FafsaFilingDeadlineDao.listByAwardYear(session, AWARD_YEAR).getOrThrow().single()
    assertEquals(PublishedDeadline.OnDate(LocalDate.of(2028, 9, 12), "September 12, 2028"), stored.deadline)
  }

  @Test
  fun `all four events are writable, corrections_deadline included`() {
    AidDeadlineEvent.entries.forEach { event ->
      FafsaFilingDeadlineDao
        .upsert(
          session,
          deadline(
            jurisdiction = DeadlineJurisdiction.Federal,
            program = "Federal student aid",
            event = event,
            publisher = "Federal Student Aid",
          ),
        ).getOrThrow()
    }

    val rows = FafsaFilingDeadlineDao.listByAwardYear(session, AWARD_YEAR).getOrThrow()
    assertEquals(AidDeadlineEvent.entries.toSet(), rows.map { it.event }.toSet(), "every enum member must be a writable row")
    assertEquals(AidDeadlineEvent.entries.size, rows.size)
  }

  @Test
  fun `all three kinds are writable, and a fourth would not compile here`() {
    // THE SAME GUARD THE EVENTS HAVE, and it is the reason this walks
    // `entries` through a REAL insert rather than asserting a list: a member
    // added to AidDeadlineKind that the CHECK does not know, that
    // PublishedDeadline has no arm for, and that the mapper can only call
    // corruption, would otherwise leave a green build. The `when` below is
    // exhaustive over the enum, so the fourth member is a COMPILE error here
    // and the insert then proves the schema admits it.
    AidDeadlineKind.entries.forEach { kind ->
      val published =
        when (kind) {
          AidDeadlineKind.DATE -> PublishedDeadline.OnDate(LocalDate.of(2027, 3, 2), "March 2, 2027")
          AidDeadlineKind.WORDING -> PublishedDeadline.InWords("as soon as possible after the form opens")
          AidDeadlineKind.NONE -> PublishedDeadline.NonePublished("no state filing deadline is published")
        }
      FafsaFilingDeadlineDao
        .upsert(
          session,
          deadline(
            jurisdiction = DeadlineJurisdiction.State.of("CA")!!,
            program = "Cal Grant ${kind.value}",
            deadline = published,
          ),
        ).getOrThrow()
    }

    val stored = FafsaFilingDeadlineDao.listByAwardYear(session, AWARD_YEAR).getOrThrow()
    assertEquals(
      AidDeadlineKind.entries.toSet(),
      stored.map { it.deadline.kind }.toSet(),
      "every kind must survive a round trip through the database",
    )
  }

  /** A legal row, with everything a case is not about already filled -- the [AdmissionTestStateProgrammeFixture] idiom. */
  private fun deadline(
    awardYear: AcademicYear = AWARD_YEAR,
    jurisdiction: DeadlineJurisdiction = DeadlineJurisdiction.Federal,
    program: String = "Federal student aid",
    event: AidDeadlineEvent = AidDeadlineEvent.PRIORITY_DEADLINE,
    deadline: PublishedDeadline = PublishedDeadline.OnDate(LocalDate.of(2027, 3, 2), "March 2, 2027"),
    publisher: String = "A state aid agency",
    sourceUrl: String = sourceUrlFor(program),
    archiveUrl: String? = null,
    checkedOn: LocalDate = CHECKED_ON,
    expiresOn: LocalDate = EXPIRES_ON,
  ): NewFafsaFilingDeadline =
    NewFafsaFilingDeadline(
      awardYear = awardYear,
      jurisdiction = jurisdiction,
      program = program,
      event = event,
      deadline = deadline,
      publisher = publisher,
      sourceUrl = sourceUrl,
      archiveUrl = archiveUrl,
      checkedOn = checkedOn,
      expiresOn = expiresOn,
    )

  /** The page a fixture row cites -- named, so a case can assert the citation came back through the join. */
  private fun sourceUrlFor(program: String): String = "https://example-aid.gov/${program.replace(" ", "-").lowercase()}"

  private fun countRows(): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT COUNT(*) FROM fafsa_filing_deadlines").use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }
}
