package ed.unicoach.db.dao

import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.NewObservation
import ed.unicoach.db.models.ObservationId
import ed.unicoach.db.models.StudentId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservationsDaoTest {
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
    connection.autoCommit = true
    connection.createStatement().use { stmt ->
      stmt.execute(
        "TRUNCATE TABLE observations, claim_support, claims, extraction_runs, " +
          "convos, convo_requests, convo_responses, convo_responses_raw, system_prompts, students, users CASCADE",
      )
      // Restore the migration-seeded prompts so a later cross-module suite (e.g.
      // rest-server) can still resolve them on the shared test DB.
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('coach', 'v1', 'You are Uni, a warm coach.')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('extraction', 'v1', 'distill the transcript')")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'obs-$userId@test.com', 'Obs User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun createConvo(studentId: StudentId): ConvoId {
    val convoId = UUID.randomUUID()
    connection
      .prepareStatement("INSERT INTO convos (id, student_id, name) VALUES (?, ?, 'Convo')")
      .use { stmt ->
        stmt.setObject(1, convoId)
        stmt.setObject(2, studentId.value)
        stmt.executeUpdate()
      }
    return ConvoId(convoId)
  }

  private var promptCounter = 0

  private fun createSystemPrompt(): UUID {
    val id = UUID.randomUUID()
    connection
      .prepareStatement("INSERT INTO system_prompts (id, name, version, body) VALUES (?, 'coach', ?, 'be a coach')")
      .use { stmt ->
        stmt.setObject(1, id)
        stmt.setString(2, "p${promptCounter++}")
        stmt.executeUpdate()
      }
    return id
  }

  private fun appendRequest(convoId: ConvoId): ConvoRequestId {
    val promptId = createSystemPrompt()
    connection
      .prepareStatement(
        """
        INSERT INTO convo_requests (convo_id, provider, model_requested, system_prompt_id, content)
        VALUES (?, 'anthropic', 'claude-opus-4-8', ?, '[]'::jsonb) RETURNING id
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, convoId.value)
        stmt.setObject(2, promptId)
        stmt.executeQuery().use { rs ->
          rs.next()
          return ConvoRequestId(rs.getLong("id"))
        }
      }
  }

  private fun newObservation(
    studentId: StudentId,
    convoId: ConvoId,
    requestId: ConvoRequestId,
    quote: String = "I want to study engineering",
    utteredAt: Instant = Instant.now(),
  ): NewObservation = NewObservation(studentId, convoId, requestId, utteredAt, quote)

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  fun `append returns a row with DB id and uttered_at`() {
    val student = createStudent()
    val convo = createConvo(student)
    val req = appendRequest(convo)
    val uttered = Instant.parse("2026-01-02T03:04:05Z")

    val obs = ObservationsDao.append(session, newObservation(student, convo, req, "hello", uttered)).getOrThrow()

    assertTrue(obs.id.value > 0)
    assertEquals(student, obs.studentId)
    assertEquals(convo, obs.convoId)
    assertEquals(req, obs.sourceRequestId)
    assertEquals("hello", obs.quote)
    assertEquals(uttered, obs.utteredAt)
  }

  @Test
  fun `listByConvoRange returns only rows in the open-closed window ordered by created_at then id`() {
    val student = createStudent()
    val convo = createConvo(student)
    val r1 = appendRequest(convo)
    val r2 = appendRequest(convo)
    val r3 = appendRequest(convo)

    ObservationsDao.append(session, newObservation(student, convo, r1, "first")).getOrThrow()
    val o2 = ObservationsDao.append(session, newObservation(student, convo, r2, "second")).getOrThrow()
    val o3 = ObservationsDao.append(session, newObservation(student, convo, r3, "third")).getOrThrow()

    // window (r1, r3] excludes r1's observation, includes r2 and r3.
    val window = ObservationsDao.listByConvoRange(session, convo, r1, r3).getOrThrow()
    assertEquals(listOf(o2.id, o3.id), window.map { it.id })
  }

  @Test
  fun `listByStudent returns all of a student's observations`() {
    val student = createStudent()
    val convo = createConvo(student)
    val r1 = appendRequest(convo)
    val r2 = appendRequest(convo)
    ObservationsDao.append(session, newObservation(student, convo, r1, "a")).getOrThrow()
    ObservationsDao.append(session, newObservation(student, convo, r2, "b")).getOrThrow()

    assertEquals(2, ObservationsDao.listByStudent(session, student).getOrThrow().size)
  }

  @Test
  fun `findById returns the row for a known id and NotFound for an unknown id`() {
    val student = createStudent()
    val convo = createConvo(student)
    val req = appendRequest(convo)
    val obs = ObservationsDao.append(session, newObservation(student, convo, req, "found me")).getOrThrow()

    assertEquals(obs.id, ObservationsDao.findById(session, obs.id).getOrThrow().id)
    assertEquals("found me", ObservationsDao.findById(session, obs.id).getOrThrow().quote)

    val miss = ObservationsDao.findById(session, ObservationId(999_999L))
    assertTrue(miss.exceptionOrNull() is NotFoundException, "got ${miss.exceptionOrNull()}")
  }

  @Test
  fun `list pages and orders by id`() {
    val student = createStudent()
    val convo = createConvo(student)
    val r1 = appendRequest(convo)
    val r2 = appendRequest(convo)
    val r3 = appendRequest(convo)
    val o1 = ObservationsDao.append(session, newObservation(student, convo, r1, "a")).getOrThrow()
    val o2 = ObservationsDao.append(session, newObservation(student, convo, r2, "b")).getOrThrow()
    val o3 = ObservationsDao.append(session, newObservation(student, convo, r3, "c")).getOrThrow()

    assertEquals(listOf(o1.id, o2.id), ObservationsDao.list(session, 2, 0).getOrThrow().map { it.id })
    assertEquals(listOf(o3.id), ObservationsDao.list(session, 2, 2).getOrThrow().map { it.id })
  }

  @Test
  fun `bounded listByStudent returns only that student's rows, bounded and ordered`() {
    val student = createStudent()
    val other = createStudent()
    val convo = createConvo(student)
    val otherConvo = createConvo(other)
    val r1 = appendRequest(convo)
    val r2 = appendRequest(convo)
    val r3 = appendRequest(convo)
    val rOther = appendRequest(otherConvo)

    val o1 = ObservationsDao.append(session, newObservation(student, convo, r1, "a")).getOrThrow()
    val o2 = ObservationsDao.append(session, newObservation(student, convo, r2, "b")).getOrThrow()
    val o3 = ObservationsDao.append(session, newObservation(student, convo, r3, "c")).getOrThrow()
    ObservationsDao.append(session, newObservation(other, otherConvo, rOther, "x")).getOrThrow()

    val mine = ObservationsDao.listByStudent(session, student, 50, 0).getOrThrow()
    assertEquals(listOf(o1.id, o2.id, o3.id), mine.map { it.id })
    // Bounded by limit/offset.
    assertEquals(listOf(o1.id, o2.id), ObservationsDao.listByStudent(session, student, 2, 0).getOrThrow().map { it.id })
    assertEquals(listOf(o3.id), ObservationsDao.listByStudent(session, student, 2, 2).getOrThrow().map { it.id })
    // The existing unbounded overload still returns all rows.
    assertEquals(3, ObservationsDao.listByStudent(session, student).getOrThrow().size)
  }

  @Test
  fun `FK violation on unknown source_request_id surfaces as a failure`() {
    val student = createStudent()
    val convo = createConvo(student)
    val result = ObservationsDao.append(session, newObservation(student, convo, ConvoRequestId(999_999L)))
    assertTrue(result.isFailure, "expected FK failure, got $result")
    assertTrue(result.exceptionOrNull() is NotFoundException, "got ${result.exceptionOrNull()}")
  }

  @Test
  fun `UPDATE on observations raises P0001`() {
    val student = createStudent()
    val convo = createConvo(student)
    val req = appendRequest(convo)
    val obs = ObservationsDao.append(session, newObservation(student, convo, req)).getOrThrow()

    val ex =
      runCatching {
        connection.createStatement().use { it.execute("UPDATE observations SET quote = 'x' WHERE id = ${obs.id.value}") }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && (ex.sqlState == "P0001"), "got $ex")
  }

  @Test
  fun `DELETE on observations raises P0001`() {
    val student = createStudent()
    val convo = createConvo(student)
    val req = appendRequest(convo)
    val obs = ObservationsDao.append(session, newObservation(student, convo, req)).getOrThrow()

    val ex =
      runCatching {
        connection.createStatement().use { it.execute("DELETE FROM observations WHERE id = ${obs.id.value}") }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && (ex.sqlState == "P0001"), "got $ex")
  }

  @Test
  fun `blank quote is rejected by the not-empty CHECK`() {
    val student = createStudent()
    val convo = createConvo(student)
    val req = appendRequest(convo)
    val result = ObservationsDao.append(session, newObservation(student, convo, req, "   "))
    assertTrue(result.exceptionOrNull() is ConstraintViolationException, "got ${result.exceptionOrNull()}")
  }
}
