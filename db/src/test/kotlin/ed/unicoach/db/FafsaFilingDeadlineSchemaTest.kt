package ed.unicoach.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every named constraint on `fafsa_filing_deadlines` (RFC 215), executed against
 * the real database.
 *
 * IT WRITES LITERAL SQL AND GOES AROUND THE MODEL ON PURPOSE, the
 * [ed.unicoach.db.dao.AdmissionTestStateProgrammeDaoTest] idiom one table along.
 * `PublishedDeadline` is a sealed value, so a `wording` row carrying a date and a
 * `date` row carrying none are both unconstructable in Kotlin -- which is the
 * point of that type, and exactly why the CHECKS still have to be executed here.
 * The database is what a generated seed, a migration and `psql` all write
 * through, and none of those typecheck.
 *
 * EVERY CASE ASSERTS THE CONSTRAINT BY NAME. A test that asserted only "the
 * insert failed" would pass when an unrelated constraint refused the row, and
 * would keep passing after the constraint it was written for had been dropped.
 *
 * It empties the table between cases and leaves it empty, as
 * [ed.unicoach.db.dao.FafsaFilingDeadlineDaoTest] does and for the same reason:
 * `fafsa/01` ships NO rows, so a freshly migrated table is empty and there is
 * nothing to save and restore yet.
 */
class FafsaFilingDeadlineSchemaTest {
  companion object {
    private lateinit var connection: Connection

    /** The page every literal-SQL row in this suite cites (RFC 213). */
    private const val SOURCE_URL = "https://example-aid.gov/fafsa-deadlines"

    /** A DIFFERENT document, so the archive/source cases are about the ids and not about a missing row. */
    private const val ARCHIVE_URL = "https://web.archive.org/example-aid"

    private const val SOURCE_ID = "(SELECT id FROM external_documents WHERE url = '$SOURCE_URL')"
    private const val ARCHIVE_ID = "(SELECT id FROM external_documents WHERE url = '$ARCHIVE_URL')"

    /**
     * A legal 2027-28 row as SQL literals, which every bad row below is one or
     * two overrides away from: a case that had to restate twelve columns would
     * hide which column it is actually about.
     *
     * The cycle window this row sits in is `[2026-07-01, 2028-12-31]` --
     * `award_year - 1` July 1 to `award_year + 1` December 31.
     */
    private val DEADLINE_COLUMNS: Map<String, String> =
      linkedMapOf(
        "award_year" to "2027",
        "state" to "'CA'",
        "program" to "'Cal Grant'",
        "event" to "'priority_deadline'",
        "deadline_kind" to "'date'",
        "deadline_date" to "DATE '2027-03-02'",
        "deadline_as_published" to "'March 2, 2027, 11:59 p.m. Pacific'",
        "publisher" to "'California Student Aid Commission'",
        "source_document_id" to SOURCE_ID,
        "archive_document_id" to "NULL",
        "checked_on" to "DATE '2026-09-14'",
        "expires_on" to "DATE '2027-07-31'",
      )

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        DatabaseConfig
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

  @BeforeEach
  fun setup() {
    connection.createStatement().use { stmt ->
      stmt.execute("DELETE FROM fafsa_filing_deadlines")
      // The two documents the literal-SQL rows cite. They are created and never
      // deleted: external_documents is shared with every other corpus, so a
      // suite that emptied it would take their citations with it.
      // They state their evidence like every other writer since RFC 220: a
      // fixture is not exempt, and a bare insert here is refused by the column
      // exactly as one in production would be.
      listOf(SOURCE_URL, ARCHIVE_URL).forEach { url ->
        stmt.execute(
          "INSERT INTO external_documents (url, url_status, url_checked_on) " +
            "VALUES ('$url', 'found', DATE '2026-09-14') ON CONFLICT (url) DO NOTHING",
        )
      }
    }
  }

  @Test
  fun `the legal row this suite varies is actually legal`() {
    // Without this, every case below could be passing because the BASE row is
    // refused -- by a different constraint, for a reason none of them is about.
    assertEquals(null, violatedConstraint(DEADLINE_COLUMNS), "the fixture row must be accepted")
    assertEquals(1, countRows())
  }

  @Test
  fun `value-iff-kind is refused in both directions`() {
    // A 'wording' row that grew a date somebody guessed: the invented fact this
    // table exists to prevent (D4).
    assertEquals(
      "fafsa_filing_deadlines_date_iff_kind_check",
      violatedConstraint(
        DEADLINE_COLUMNS +
          mapOf(
            "deadline_kind" to "'wording'",
            "deadline_as_published" to "'as soon as possible after the form opens'",
          ),
      ),
      "a wording row carrying a date must be refused",
    )

    // And the other direction: a 'date' row with nothing in it, which would
    // reach the public page as a blank cell under a state's name.
    assertEquals(
      "fafsa_filing_deadlines_date_iff_kind_check",
      violatedConstraint(DEADLINE_COLUMNS + mapOf("deadline_date" to "NULL")),
      "a date row carrying no date must be refused",
    )

    // A 'none' row with a date is the same defect once more, and 'none' is the
    // kind a page prints as a verified absence.
    assertEquals(
      "fafsa_filing_deadlines_date_iff_kind_check",
      violatedConstraint(
        DEADLINE_COLUMNS +
          mapOf(
            "deadline_kind" to "'none'",
            "deadline_as_published" to "'no state filing deadline is published'",
          ),
      ),
      "a none row carrying a date must be refused",
    )

    assertEquals(0, countRows(), "no refused row may be left behind")
  }

  @Test
  fun `a date outside its own cycle is refused`() {
    // LAST CYCLE'S 2 MARCH, TYPED UNDER THIS CYCLE'S KEY -- the 2026-27 date
    // under the 2027-28 award year. This is the exact input the check exists
    // for, and it is deliberately the NEAREST wrong date rather than a distant
    // one: a window that only refused 2025 would pass this row, and a family
    // would act on a deadline that closed a year ago. The window for
    // award_year 2027 opens on 2026-07-01.
    assertEquals(
      "fafsa_filing_deadlines_date_in_cycle_check",
      violatedConstraint(DEADLINE_COLUMNS + mapOf("deadline_date" to "DATE '2026-03-02'")),
      "last cycle's 2 March under this cycle's key must be refused",
    )

    // And the far end: the 2027-28 FAFSA closes on 30 June 2028 and its
    // corrections window in September 2028, so 2029 is nobody's date here.
    assertEquals(
      "fafsa_filing_deadlines_date_in_cycle_check",
      violatedConstraint(DEADLINE_COLUMNS + mapOf("deadline_date" to "DATE '2029-01-02'")),
      "a date after this cycle's window must be refused",
    )

    assertEquals(0, countRows(), "no refused row may be left behind")

    // THE LEGAL EDGE, so the two refusals above are not passing because the
    // window is empty: 1 October 2026 is the day the 2027-28 form opened, one
    // quarter INSIDE the lower bound, and it inserts.
    assertEquals(
      null,
      violatedConstraint(DEADLINE_COLUMNS + mapOf("deadline_date" to "DATE '2026-10-01'")),
      "the day this cycle's form opened is inside its own window",
    )
    assertEquals(1, countRows())
  }

  @Test
  fun `a form that opens cannot open never`() {
    // 'none' is a statement about a deadline a publisher does not set. Said of an
    // OPENING date it renders as "the FAFSA does not open".
    assertEquals(
      "fafsa_filing_deadlines_opening_is_dated_check",
      violatedConstraint(
        DEADLINE_COLUMNS +
          mapOf(
            "event" to "'form_opens'",
            "deadline_kind" to "'none'",
            "deadline_date" to "NULL",
            "deadline_as_published" to "'no opening date is published'",
          ),
      ),
      "a form_opens row of kind none must be refused",
    )
    assertEquals(0, countRows())
  }

  @Test
  fun `an archive may not be its own source`() {
    // An archive of a page that IS that page is the same link printed twice, and
    // a page told to offer both links would show one url as two.
    assertEquals(
      "fafsa_filing_deadlines_archive_is_not_the_source_check",
      violatedConstraint(DEADLINE_COLUMNS + mapOf("archive_document_id" to SOURCE_ID)),
      "an archive equal to its source must be refused",
    )

    // A DIFFERENT archive document is the legal shape, so the case above is
    // about the equality and not about archives.
    assertEquals(null, violatedConstraint(DEADLINE_COLUMNS + mapOf("archive_document_id" to ARCHIVE_ID)))
    assertEquals(1, countRows())
  }

  @Test
  fun `a row that cites nothing cannot be written`() {
    // NOT NULL, not a CHECK -- so PostgreSQL reports NO constraint name here and
    // the refusal is located by SQLSTATE 23502 plus the COLUMN instead. This is
    // the case that made the refusal worth carrying as four fields rather than
    // one name: asserted on the name alone, it could only have asserted null.
    // A row that cites nothing is the uncitable row this corpus exists to
    // prevent.
    val refusal = refusalFor(DEADLINE_COLUMNS + mapOf("source_document_id" to "NULL"))
    assertEquals("23502", refusal?.sqlState, "a missing citation is a NOT NULL violation; got [$refusal]")
    assertTrue(
      refusal?.message?.contains("source_document_id") == true,
      "the refusal must name the column that was empty; got [$refusal]",
    )
    assertEquals(0, countRows())
  }

  @Test
  fun `every remaining CHECK refuses its own bad row, by name`() {
    val cases =
      listOf(
        // A fifth event. The enum and this CHECK are the one vocabulary
        // (CLAUDE.md), and the day a SECOND table asks it becomes a domain.
        "fafsa_filing_deadlines_event_check" to mapOf("event" to "'verification_deadline'"),
        // A fourth kind. 'date', 'wording', 'none' and nothing else.
        "fafsa_filing_deadlines_kind_check" to
          mapOf("deadline_kind" to "'approximate'", "deadline_date" to "NULL"),
        // An empty string is a value-shaped absence: a page renders one as a
        // blank cell that looks like a fact.
        "fafsa_filing_deadlines_program_nonempty_check" to mapOf("program" to "''"),
        "fafsa_filing_deadlines_deadline_as_published_nonempty_check" to mapOf("deadline_as_published" to "''"),
        // Without a publisher the public table has no link text and cites a bare
        // url.
        "fafsa_filing_deadlines_publisher_nonempty_check" to mapOf("publisher" to "''"),
        // A row that expires the day it was read is believed for no days at all.
        "fafsa_filing_deadlines_expiry_after_checked_check" to mapOf("expires_on" to "DATE '2026-09-14'"),
        // A row cannot have been read from a page before the page could have
        // said it: the 2027-28 window opens on 2026-07-01, and this is the day
        // before it -- the nearest wrong value, not a distant one.
        "fafsa_filing_deadlines_checked_in_or_after_cycle_check" to mapOf("checked_on" to "DATE '2026-06-30'"),
        // The coarse DB-level backstop UsStateCodes.kt asks for: lower case and
        // three letters stop here, membership stops at the generator and the
        // seed test.
        "fafsa_filing_deadlines_state_format_check" to mapOf("state" to "'ca'"),
        "fafsa_filing_deadlines_state_format_check" to mapOf("state" to "'CAL'"),
      )

    // Every case is run and every mismatch reported, so one failing CHECK does
    // not hide the eight behind it -- the sibling corpus's rule.
    val mismatches =
      cases.mapNotNull { (expected, overrides) ->
        val refusal = refusalFor(DEADLINE_COLUMNS + overrides)
        if (refusal?.constraint == expected) null else "overrides [$overrides] expected [$expected], got [$refusal]"
      }
    assertEquals(emptyList<String>(), mismatches, "a CHECK did not refuse the row it exists to refuse")
    assertEquals(0, countRows(), "no refused row may be left behind")
  }

  /**
   * What PostgreSQL said about one row written from literal SQL: the constraint
   * it refused it with, the SQLSTATE, the DETAIL naming the failing row, and
   * the message.
   *
   * IT KEEPS ALL FOUR RATHER THAN REDUCING TO THE NAME. A helper that returned
   * only the constraint name reported a mismatch as "expected X, got Y" and
   * threw away the only lines that say WHY -- the SQLSTATE that distinguishes a
   * CHECK from a unique-index collision, and PostgreSQL's own "Failing row
   * contains (...)". Those are the two things a reader of a red build needs
   * first.
   */
  private data class Refusal(
    val constraint: String?,
    val sqlState: String?,
    val detail: String?,
    val message: String?,
  ) {
    /** What a mismatch prints: the name if there is one, and everything else after it. */
    override fun toString(): String = "constraint [$constraint] sqlState [$sqlState] detail [$detail] message [$message]"
  }

  /** The refusal for one literal-SQL row, or null when the database ACCEPTED it. */
  private fun refusalFor(columns: Map<String, String>): Refusal? =
    try {
      connection.createStatement().use { stmt ->
        stmt.execute(
          "INSERT INTO fafsa_filing_deadlines (${columns.keys.joinToString(", ")}) " +
            "VALUES (${columns.values.joinToString(", ")})",
        )
      }
      null
    } catch (e: SQLException) {
      val server = (e as? PSQLException)?.serverErrorMessage
      Refusal(
        constraint = server?.constraint,
        sqlState = e.sqlState,
        detail = server?.detail,
        message = e.message,
      )
    }

  /**
   * The constraint name a row was refused with, or null when it was accepted --
   * the assertion every case here makes, with [refusalFor]'s other three fields
   * available to whoever reads the failure.
   */
  private fun violatedConstraint(columns: Map<String, String>): String? = refusalFor(columns)?.constraint

  private fun countRows(): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT COUNT(*) FROM fafsa_filing_deadlines").use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }
}
