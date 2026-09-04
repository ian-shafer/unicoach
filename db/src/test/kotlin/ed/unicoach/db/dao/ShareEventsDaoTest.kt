package ed.unicoach.db.dao

import ed.unicoach.db.models.CostReportShareId
import ed.unicoach.db.models.NewCostReportShare
import ed.unicoach.db.models.ShareEventKind
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.TokenHash
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real-DB coverage for the append-only `share_events` log (RFC 160), modelled
 * on [SynthesisRunsDaoTest]: one connection opened in @BeforeAll, a truncate
 * before each test, raw statements for what the ADT makes unrepresentable.
 */
class ShareEventsDaoTest {
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

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE share_events, cost_report_shares, students, users CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'se-$userId@test.com', 'Se User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun createShare(student: StudentId): CostReportShareId {
    val id = CostReportSharesDao.nextId(session).getOrThrow()
    CostReportSharesDao
      .create(session, NewCostReportShare(id, student, TokenHash.fromRawToken("raw-$id")))
      .getOrThrow()
    return id
  }

  @Test
  fun `record round-trips every share-bearing kind with its share id`() {
    val student = createStudent()
    val share = createShare(student)

    // Derived from the allowlist every kind declares at its definition site,
    // not a complement-of-one: a new share-bearing kind joins the round-trip
    // (or fails the pairing CHECK loudly) the day it lands.
    val shareBearingKinds = ShareEventKind.entries.filter { it.namesShareRow }
    for (kind in shareBearingKinds) {
      val event = ShareEventsDao.record(session, student, share, kind).getOrThrow()
      assertEquals(student, event.studentId)
      assertEquals(share, event.shareId)
      assertEquals(kind, event.kind)
    }
    assertEquals(shareBearingKinds.size, ShareEventsDao.listByStudent(session, student).getOrThrow().size)
  }

  @Test
  fun `recordOptOut persists an opted_out event with no share`() {
    val student = createStudent()

    val event = ShareEventsDao.recordOptOut(session, student).getOrThrow()

    assertEquals(ShareEventKind.OPTED_OUT, event.kind)
    assertNull(event.shareId, "the opt-out is about the student, not any share row")
  }

  @Test
  fun `record refuses OPTED_OUT at the call site, naming recordOptOut`() {
    val student = createStudent()
    val share = createShare(student)

    val ex =
      runCatching { ShareEventsDao.record(session, student, share, ShareEventKind.OPTED_OUT) }.exceptionOrNull()

    assertTrue(ex is IllegalArgumentException, "got [$ex]")
    assertTrue(ex.message.orEmpty().contains("recordOptOut"), "got [${ex.message}]")
  }

  @Test
  fun `the pairing CHECK refuses a shareless minted event`() {
    // Raw SQL: the split write API makes this shape unrepresentable in Kotlin,
    // so the DB CHECK is provoked directly.
    val student = createStudent()
    val ex =
      runCatching {
        connection
          .prepareStatement("INSERT INTO share_events (student_id, share_id, kind) VALUES (?, NULL, 'minted')")
          .use { stmt ->
            stmt.setObject(1, student.value)
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got [$ex]")
  }

  @Test
  fun `the pairing CHECK refuses a share-bearing opted_out event`() {
    val student = createStudent()
    val share = createShare(student)
    val ex =
      runCatching {
        connection
          .prepareStatement("INSERT INTO share_events (student_id, share_id, kind) VALUES (?, ?, 'opted_out')")
          .use { stmt ->
            stmt.setObject(1, student.value)
            stmt.setObject(2, share.value)
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got [$ex]")
  }

  @Test
  fun `a kind outside the closed set is rejected by the CHECK`() {
    val student = createStudent()
    val share = createShare(student)
    val ex =
      runCatching {
        connection
          .prepareStatement("INSERT INTO share_events (student_id, share_id, kind) VALUES (?, ?, 'bogus')")
          .use { stmt ->
            stmt.setObject(1, student.value)
            stmt.setObject(2, share.value)
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got [$ex]")
  }

  @Test
  fun `hasOptOut is false before and true forever after the opted_out event`() {
    val student = createStudent()
    val other = createStudent()

    assertFalse(ShareEventsDao.hasOptOut(session, student).getOrThrow())

    // Share-surface events are NOT an opt-out.
    ShareEventsDao.record(session, student, createShare(student), ShareEventKind.MINTED).getOrThrow()
    assertFalse(ShareEventsDao.hasOptOut(session, student).getOrThrow())

    ShareEventsDao.recordOptOut(session, student).getOrThrow()
    assertTrue(ShareEventsDao.hasOptOut(session, student).getOrThrow())
    assertFalse(ShareEventsDao.hasOptOut(session, other).getOrThrow(), "one student's never is not another's")
  }

  @Test
  fun `listByStudent returns only that student's events in insertion order`() {
    val student = createStudent()
    val other = createStudent()
    val share = createShare(student)
    ShareEventsDao.record(session, student, share, ShareEventKind.MINTED).getOrThrow()
    ShareEventsDao.record(session, student, share, ShareEventKind.REPEAT).getOrThrow()
    ShareEventsDao.record(session, other, createShare(other), ShareEventKind.MINTED).getOrThrow()

    val events = ShareEventsDao.listByStudent(session, student).getOrThrow()

    assertEquals(listOf(ShareEventKind.MINTED, ShareEventKind.REPEAT), events.map { it.kind })
    assertTrue(events.all { it.studentId == student })
  }

  @Test
  fun `UPDATE on share_events raises P0001`() {
    val student = createStudent()
    val event = ShareEventsDao.record(session, student, createShare(student), ShareEventKind.MINTED).getOrThrow()
    val ex =
      runCatching {
        connection.createStatement().use { it.execute("UPDATE share_events SET kind = 'repeat' WHERE id = ${event.id.value}") }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got [$ex]")
  }

  @Test
  fun `DELETE on share_events raises P0001`() {
    val student = createStudent()
    val event = ShareEventsDao.record(session, student, createShare(student), ShareEventKind.MINTED).getOrThrow()
    val ex =
      runCatching {
        connection.createStatement().use { it.execute("DELETE FROM share_events WHERE id = ${event.id.value}") }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got [$ex]")
  }
}
