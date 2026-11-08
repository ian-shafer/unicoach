package ed.unicoach.db.dao

import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.ExchangeSelection
import ed.unicoach.db.models.NewCollegeTuitionExchange
import ed.unicoach.db.models.SeparateApplication
import ed.unicoach.db.models.TuitionExchange
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `college_tuition_exchanges` and its member-state table against the real database (RFC 277). */
class CollegeTuitionExchangesDaoTest {
  companion object {
    private lateinit var connection: Connection

    private const val SOURCE_URL = "https://example.edu/exchange"
    private const val OTHER_SOURCE_URL = "https://example.edu/exchange-again"

    private val COLUMNS =
      mapOf(
        "college_id" to "?",
        "exchange" to "'wue'",
        "max_percent_of_resident_tuition" to "150",
        "selection" to "'selective'",
        "separate_application" to "'not_required'",
        "minimum_unweighted_gpa" to "NULL",
        "percent_of_eligible_offered" to "NULL",
        "apply_by" to "NULL",
        "source_document_id" to "(SELECT id FROM external_documents WHERE url = '$SOURCE_URL')",
        "checked_on" to "DATE '2026-11-08'",
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
        ed.unicoach.db.DatabaseConfig
          .from(config)
          .getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) {
        connection.createStatement().use { stmt -> stmt.execute("TRUNCATE TABLE colleges CASCADE") }
        connection.close()
      }
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private var college = CollegeId(UUID.randomUUID())
  private var otherCollege = CollegeId(UUID.randomUUID())

  @BeforeEach
  fun setup() {
    connection.autoCommit = true
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE colleges CASCADE")
      stmt.execute(
        "INSERT INTO external_documents (url, url_status, url_checked_on) VALUES " +
          "('$SOURCE_URL', 'found', DATE '2026-11-08') ON CONFLICT (url) DO NOTHING",
      )
    }
    CodebookReferenceFixture.seed(session)
    college = seedCollege(990101, "Example State University")
    otherCollege = seedCollege(990102, "Second State University")
  }

  private fun seedCollege(
    unitId: Int,
    name: String,
  ): CollegeId =
    CollegesDao
      .upsert(session, newCollegeFixture(unitId, name))
      .getOrThrow()
      .id

  private fun row(
    collegeId: CollegeId = college,
    selection: ExchangeSelection = ExchangeSelection.SELECTIVE,
    sourceUrl: String = SOURCE_URL,
    checkedOn: LocalDate = LocalDate.of(2026, 11, 8),
  ) = NewCollegeTuitionExchange(
    collegeId = collegeId,
    exchange = TuitionExchange.WUE,
    maxPercentOfResidentTuition = 150,
    selection = selection,
    separateApplication = SeparateApplication.NOT_REQUIRED,
    minimumUnweightedGpa = BigDecimal("3.25"),
    percentOfEligibleOffered = 30,
    applyBy = checkedOn.plusMonths(3),
    sourceUrl = sourceUrl,
    checkedOn = checkedOn,
    expiresOn = checkedOn.plusMonths(9),
  )

  private fun countRows(): Int = RawSql.count(connection, "SELECT COUNT(*) FROM college_tuition_exchanges")

  /** The constraint a literal INSERT of [columns] violated, or null when it landed. */
  private fun violatedConstraint(
    table: String,
    columns: Map<String, String>,
    bindCollege: Boolean,
  ): String? =
    try {
      connection
        .prepareStatement(
          "INSERT INTO $table (${columns.keys.joinToString()}) VALUES (${columns.values.joinToString()})",
        ).use { stmt ->
          if (bindCollege) stmt.setObject(1, college.value)
          stmt.executeUpdate()
        }
      null
    } catch (e: PSQLException) {
      e.serverErrorMessage?.constraint
    }

  @Test
  fun `a row round-trips with its citation and its exchange's member states`() {
    CollegeTuitionExchangesDao.upsert(session, row()).getOrThrow()

    val stored = CollegeTuitionExchangesDao.listByColleges(session, listOf(college)).getOrThrow().single()

    assertEquals(college, stored.collegeId)
    assertEquals(TuitionExchange.WUE, stored.exchange)
    assertEquals(150, stored.maxPercentOfResidentTuition)
    assertEquals(ExchangeSelection.SELECTIVE, stored.selection)
    assertEquals(SeparateApplication.NOT_REQUIRED, stored.separateApplication)
    assertEquals(0, BigDecimal("3.25").compareTo(stored.minimumUnweightedGpa))
    assertEquals(30, stored.percentOfEligibleOffered)
    assertEquals(row().applyBy, stored.applyBy)
    assertEquals(SOURCE_URL, stored.sourceUrl)
    assertEquals(row().checkedOn, stored.checkedOn)
    assertEquals(row().expiresOn, stored.expiresOn)
    assertEquals(
      setOf("AK", "AZ", "CA", "CO", "HI", "ID", "MT", "NV", "NM", "ND", "OR", "SD", "UT", "WA", "WY", "AS", "MP", "GU"),
      stored.memberStates,
      "the migration's own seed of who may use the exchange",
    )
  }

  @Test
  fun `a list asks for the colleges named and no others`() {
    CollegeTuitionExchangesDao.upsert(session, row()).getOrThrow()
    CollegeTuitionExchangesDao.upsert(session, row(collegeId = otherCollege)).getOrThrow()

    assertEquals(
      listOf(otherCollege),
      CollegeTuitionExchangesDao.listByColleges(session, listOf(otherCollege)).getOrThrow().map { it.collegeId },
    )
    assertEquals(emptyList(), CollegeTuitionExchangesDao.listByColleges(session, emptyList()).getOrThrow())
  }

  @Test
  fun `an unchanged re-write reports unchanged, and a corrected one replaces the row`() {
    val first = CollegeTuitionExchangesDao.upsert(session, row()).getOrThrow()
    assertEquals(UpsertOutcome.INSERTED, first.outcome)

    val again = CollegeTuitionExchangesDao.upsert(session, row()).getOrThrow()
    assertEquals(UpsertOutcome.UNCHANGED, again.outcome)
    assertEquals(first.id, again.id)

    val corrected = CollegeTuitionExchangesDao.upsert(session, row(selection = ExchangeSelection.UNSTATED)).getOrThrow()
    assertEquals(UpsertOutcome.CHANGED, corrected.outcome)
    assertEquals(first.id, corrected.id)
    assertEquals(1, countRows())
    assertEquals(
      ExchangeSelection.UNSTATED,
      CollegeTuitionExchangesDao
        .listByColleges(session, listOf(college))
        .getOrThrow()
        .single()
        .selection,
    )
  }

  @Test
  fun `a prune drops what is not kept and refuses to empty the table`() {
    val kept = CollegeTuitionExchangesDao.upsert(session, row()).getOrThrow()
    CollegeTuitionExchangesDao.upsert(session, row(collegeId = otherCollege, sourceUrl = OTHER_SOURCE_URL)).getOrThrow()

    assertEquals(1, CollegeTuitionExchangesDao.deleteExcept(session, listOf(kept.id)).getOrThrow())
    assertEquals(1, countRows())

    val refused = assertFailsWith<IllegalArgumentException> { CollegeTuitionExchangesDao.deleteExcept(session, emptyList()) }
    assertTrue(refused.message.orEmpty().contains("college_tuition_exchanges"), "got [${refused.message}]")
    assertEquals(1, countRows())
  }

  @Test
  fun `every CHECK on the school table refuses its own bad row, by name`() {
    val cases =
      listOf(
        "college_tuition_exchanges_exchange_check" to mapOf("exchange" to "'msep'"),
        "college_tuition_exchanges_max_percent_check" to mapOf("max_percent_of_resident_tuition" to "99"),
        "college_tuition_exchanges_selection_check" to mapOf("selection" to "'maybe'"),
        "college_tuition_exchanges_separate_application_check" to mapOf("separate_application" to "'maybe'"),
        "college_tuition_exchanges_gpa_check" to mapOf("minimum_unweighted_gpa" to "4.01"),
        "college_tuition_exchanges_gpa_check" to mapOf("minimum_unweighted_gpa" to "-0.01"),
        "college_tuition_exchanges_offered_check" to mapOf("percent_of_eligible_offered" to "0"),
        "college_tuition_exchanges_offered_check" to mapOf("percent_of_eligible_offered" to "101"),
        // The boundary: a row that expires the day it was read is never live.
        "college_tuition_exchanges_expiry_check" to mapOf("expires_on" to "DATE '2026-11-08'"),
      )

    val mismatches =
      cases.mapNotNull { (expected, overrides) ->
        val actual = violatedConstraint("college_tuition_exchanges", COLUMNS + overrides, bindCollege = true)
        if (actual == expected) null else "overrides $overrides expected [$expected], got [$actual]"
      }

    assertEquals(emptyList<String>(), mismatches, "a CHECK did not refuse the row it exists to refuse")
    assertEquals(0, countRows(), "no refused row may be left behind")
  }

  @Test
  fun `one school holds one row per exchange`() {
    assertEquals(null, violatedConstraint("college_tuition_exchanges", COLUMNS, bindCollege = true))
    assertEquals(
      "college_tuition_exchanges_one_per_exchange",
      violatedConstraint("college_tuition_exchanges", COLUMNS, bindCollege = true),
    )
  }

  @Test
  fun `the member-state table refuses an exchange we do not model and a malformed state`() {
    assertEquals(
      "tuition_exchange_states_exchange_check",
      violatedConstraint("tuition_exchange_states", mapOf("exchange" to "'msep'", "state_code" to "'WA'"), bindCollege = false),
    )
    assertEquals(
      "tuition_exchange_states_state_code_check",
      violatedConstraint("tuition_exchange_states", mapOf("exchange" to "'wue'", "state_code" to "'wa'"), bindCollege = false),
    )
  }
}
