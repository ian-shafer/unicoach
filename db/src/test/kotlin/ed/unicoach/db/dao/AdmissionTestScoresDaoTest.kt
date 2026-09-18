package ed.unicoach.db.dao

import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AdmissionTestScoreComponent
import ed.unicoach.db.models.NewAdmissionTestScore
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.StudentId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.LocalDate
import java.time.Month
import java.time.Year
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two score tables, asserted against the database that actually has them
 * (RFC 207, `create-admission-test-scores.sql`).
 *
 * Four claims:
 *
 * 1. **The published scale is the database's guarantee.** An SAT total is
 *    400-1600 in steps of ten, an SAT section 200-800 in steps of ten, an ACT
 *    score 1-36 whole points -- and a component belongs to its own test. These
 *    are driven through RAW SQL because the CHECK is the authority the Kotlin
 *    [ed.unicoach.db.models.PublishedScoreScale] mirrors.
 * 2. **A sitting is RFC 209's shape.** `is_period_start` refuses a date that
 *    is not the start of the precision it claims, so a month we never recorded
 *    cannot sit in the column.
 * 3. **The natural key is (student, component, sitting).** A second write of
 *    the same component and sitting updates the row -- and a month-precision
 *    re-statement with the same period start is one correction, not two rows.
 * 4. **The decline table stores only the refusal**, one live row per student,
 *    idempotently, and withdraws in place.
 */
class AdmissionTestScoresDaoTest {
  companion object {
    private lateinit var connection: Connection

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
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    connection.createStatement().use {
      it.execute("TRUNCATE TABLE students, users, admission_test_scores, admission_test_score_declines CASCADE")
    }
  }

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'score-$userId@test.com', 'Score User', 'ahash')")
      StudentRowFixture.insert(connection, studentId, userId)
    }
    return StudentId(studentId)
  }

  /** One raw insert of a score row, for the cases [NewAdmissionTestScore] cannot express (a malformed one). */
  private fun insertRawScore(
    studentId: StudentId,
    test: String,
    component: String,
    score: Int,
    takenOn: LocalDate,
    precision: String,
  ) {
    connection
      .prepareStatement(
        """
        INSERT INTO admission_test_scores (student_id, test, component, score, taken_on, taken_precision)
        VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.setString(2, test)
        stmt.setString(3, component)
        stmt.setInt(4, score)
        stmt.setObject(5, takenOn)
        stmt.setString(6, precision)
        stmt.executeUpdate()
      }
  }

  private fun rejectionStateOf(block: () -> Unit): String = assertFailsWith<SQLException> { block() }.sqlState

  private fun march2026(): PartialDate = PartialDate.YearAndMonth(Year.of(2026), Month.MARCH)

  // ---------------------------------------------------------------------------
  // 1. The published scale and the component/test pairing
  // ---------------------------------------------------------------------------

  @Test
  fun `a score on its published scale writes and reads back whole`() {
    val student = createStudent()
    val written =
      AdmissionTestScoresDao
        .upsertForStudent(
          session,
          NewAdmissionTestScore(student, AdmissionTest.SAT, AdmissionTestScoreComponent.SAT_TOTAL, 1340, march2026()),
        ).getOrThrow()

    assertEquals(1340, written.score)
    assertEquals(AdmissionTestScoreComponent.SAT_TOTAL, written.component)
    assertEquals(march2026(), written.sitting, "the sitting is read back at its stored precision, never shortened")

    val live = AdmissionTestScoresDao.listLiveByStudent(session, student).getOrThrow()
    assertEquals(1, live.size)
    assertEquals(written.id, live.single().id)
  }

  @Test
  fun `the schema refuses a number off the published scale`() {
    val student = createStudent()
    assertEquals(
      "23514",
      rejectionStateOf {
        insertRawScore(student, "sat", "sat_total", 1337, LocalDate.of(2026, 3, 1), "month")
      },
      "1337 is not an SAT total: the SAT is published in steps of ten",
    )
    assertEquals(
      "23514",
      rejectionStateOf {
        insertRawScore(student, "sat", "sat_math", 900, LocalDate.of(2026, 3, 1), "month")
      },
      "a section score is 200-800, never 900",
    )
    assertEquals(
      "23514",
      rejectionStateOf {
        insertRawScore(student, "act", "act_composite", 37, LocalDate.of(2026, 3, 1), "month")
      },
      "an ACT score is 1-36",
    )
  }

  @Test
  fun `the schema refuses a component on the wrong test`() {
    val student = createStudent()
    assertEquals(
      "23514",
      rejectionStateOf {
        insertRawScore(student, "act", "sat_total", 1300, LocalDate.of(2026, 3, 1), "month")
      },
    )
  }

  // ---------------------------------------------------------------------------
  // 2. The sitting is RFC 209's shape
  // ---------------------------------------------------------------------------

  @Test
  fun `the schema refuses a sitting that is not the start of its period`() {
    val student = createStudent()
    assertEquals(
      "23514",
      rejectionStateOf {
        insertRawScore(student, "sat", "sat_total", 1300, LocalDate.of(2026, 3, 5), "year")
      },
      "2026-03-05 stored at year precision would leak a month we never recorded",
    )
  }

  // ---------------------------------------------------------------------------
  // 3. The natural key
  // ---------------------------------------------------------------------------

  @Test
  fun `a second write of the same component and sitting updates rather than inserts`() {
    val student = createStudent()
    val first =
      AdmissionTestScoresDao
        .upsertForStudent(
          session,
          NewAdmissionTestScore(student, AdmissionTest.SAT, AdmissionTestScoreComponent.SAT_TOTAL, 1300, march2026()),
        ).getOrThrow()
    val second =
      AdmissionTestScoresDao
        .upsertForStudent(
          session,
          NewAdmissionTestScore(student, AdmissionTest.SAT, AdmissionTestScoreComponent.SAT_TOTAL, 1340, march2026()),
        ).getOrThrow()

    assertEquals(first.id, second.id, "a correction is one row, not two")
    assertEquals(2, second.version, "the correction bumps the version")
    assertEquals(1340, second.score)
    assertEquals(1, AdmissionTestScoresDao.listLiveByStudent(session, student).getOrThrow().size)
  }

  @Test
  fun `a month-precision re-statement with the same period start is one correction`() {
    val student = createStudent()
    AdmissionTestScoresDao
      .upsertForStudent(
        session,
        NewAdmissionTestScore(student, AdmissionTest.SAT, AdmissionTestScoreComponent.SAT_TOTAL, 1300, march2026()),
      ).getOrThrow()
    // "March 1, 2026" and "March 2026" store the SAME period start, 2026-03-01,
    // so the day precision is a correction of the same sitting, not a second row.
    val precise =
      PartialDate.FullDate(LocalDate.of(2026, 3, 1))
    val corrected =
      AdmissionTestScoresDao
        .upsertForStudent(session, NewAdmissionTestScore(student, AdmissionTest.SAT, AdmissionTestScoreComponent.SAT_TOTAL, 1340, precise))
        .getOrThrow()

    assertEquals(precise, corrected.sitting, "the day precision replaces the month precision on the same row")
    assertEquals(1, AdmissionTestScoresDao.listLiveByStudent(session, student).getOrThrow().size)
  }

  @Test
  fun `two components on the same sitting are two rows`() {
    val student = createStudent()
    AdmissionTestScoresDao
      .upsertForStudent(
        session,
        NewAdmissionTestScore(student, AdmissionTest.SAT, AdmissionTestScoreComponent.SAT_TOTAL, 1340, march2026()),
      ).getOrThrow()
    AdmissionTestScoresDao
      .upsertForStudent(
        session,
        NewAdmissionTestScore(student, AdmissionTest.SAT, AdmissionTestScoreComponent.SAT_MATH, 700, march2026()),
      ).getOrThrow()
    assertEquals(2, AdmissionTestScoresDao.listLiveByStudent(session, student).getOrThrow().size)
  }

  @Test
  fun `remove soft-deletes the named sitting and only it`() {
    val student = createStudent()
    AdmissionTestScoresDao
      .upsertForStudent(
        session,
        NewAdmissionTestScore(student, AdmissionTest.SAT, AdmissionTestScoreComponent.SAT_TOTAL, 1340, march2026()),
      ).getOrThrow()
    val math =
      AdmissionTestScoresDao
        .upsertForStudent(
          session,
          NewAdmissionTestScore(student, AdmissionTest.SAT, AdmissionTestScoreComponent.SAT_MATH, 700, march2026()),
        ).getOrThrow()

    val removed = AdmissionTestScoresDao.removeForStudent(session, student, AdmissionTestScoreComponent.SAT_MATH, march2026()).getOrThrow()
    assertNotNull(removed.deletedAt, "the removed row is soft-deleted, not erased")
    assertEquals(math.id, removed.id)

    val live = AdmissionTestScoresDao.listLiveByStudent(session, student).getOrThrow()
    assertEquals(listOf(AdmissionTestScoreComponent.SAT_TOTAL), live.map { it.component })
  }

  // ---------------------------------------------------------------------------
  // 4. The decline table
  // ---------------------------------------------------------------------------

  @Test
  fun `a decline inserts once, idempotently, and withdraws in place`() {
    val student = createStudent()
    val first = AdmissionTestScoreDeclinesDao.insertDecline(session, student).getOrThrow()
    val second = AdmissionTestScoreDeclinesDao.insertDecline(session, student).getOrThrow()
    assertEquals(first.id, second.id, "declining twice is one refusal")
    assertNull(second.withdrawnAt)

    val withdrawn = AdmissionTestScoreDeclinesDao.withdrawLive(session, student).getOrThrow()
    assertNotNull(withdrawn.withdrawnAt)
    assertEquals(first.id, withdrawn.id)

    val missing = AdmissionTestScoreDeclinesDao.findLiveByStudent(session, student)
    assertTrue(missing.isFailure, "after withdrawal there is no live refusal")
    assertTrue(missing.exceptionOrNull() is NotFoundException)
  }
}
