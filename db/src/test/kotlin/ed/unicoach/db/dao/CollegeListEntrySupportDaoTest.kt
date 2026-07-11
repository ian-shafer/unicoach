package ed.unicoach.db.dao

import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeListEntry
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

class CollegeListEntrySupportDaoTest {
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
        "TRUNCATE TABLE observations, college_list_entry_support, college_list_entries, " +
          "convos, convo_requests, llm_requests, llm_responses, llm_responses_raw, system_prompts, students, users, colleges CASCADE",
      )
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('coach', 'v1', 'You are Uni, a warm coach.')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('extraction', 'v1', 'distill the transcript')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('synthesis', 'v1', 'reflect over the model')")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private var unitIdCounter = 910000

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'cls-$userId@test.com', 'CLS User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun createCollege(): CollegeId =
    CollegesDao
      .upsert(
        session,
        NewCollege(
          unitId = unitIdCounter++,
          opeid = null,
          name = "Test College",
          city = "Townsville",
          state = "CA",
          region = 8,
          locale = 13,
          latitude = 34.0,
          longitude = -118.0,
          control = 1,
          undergradEnrollment = 5000,
          admissionRate = 0.5,
          satAvg = 1200,
          costAttendance = 40000,
          netPrice = 20000,
          tuitionInState = 12000,
          tuitionOutState = 30000,
          graduationRate = 0.7,
          medianEarnings = 55000,
          pctPell = 0.4,
          website = null,
        ),
      ).getOrThrow()
      .id

  private fun createEntry(
    studentId: StudentId,
    collegeId: CollegeId,
  ): CollegeListEntryId =
    CollegeListEntriesDao
      .create(session, NewCollegeListEntry(studentId, collegeId, CollegeListEntryStatus.CONSIDERING, null))
      .getOrThrow()
      .id

  private fun createConvo(studentId: StudentId): ConvoId {
    val convoId = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO convos (id, student_id, name) VALUES (?, ?, 'Convo')").use { stmt ->
      stmt.setObject(1, convoId)
      stmt.setObject(2, studentId.value)
      stmt.executeUpdate()
    }
    return ConvoId(convoId)
  }

  private var promptCounter = 0

  private fun appendRequest(convoId: ConvoId): ConvoRequestId {
    val promptId = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO system_prompts (id, name, version, body) VALUES (?, 'coach', ?, 'be a coach')").use { stmt ->
      stmt.setObject(1, promptId)
      stmt.setString(2, "p${promptCounter++}")
      stmt.executeUpdate()
    }
    val llmRequestId =
      connection
        .prepareStatement(
          "INSERT INTO llm_requests (provider, model_requested, content, max_tokens) VALUES ('anthropic', 'claude-opus-4-8', '[]'::jsonb, 1024) RETURNING id",
        ).use { stmt ->
          stmt.executeQuery().use { rs ->
            rs.next()
            rs.getLong("id")
          }
        }
    connection
      .prepareStatement(
        """
        INSERT INTO convo_requests (convo_id, system_prompt_id, llm_request_id, turn_id)
        VALUES (?, ?, ?, nextval('convo_turn_id_seq')) RETURNING id
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, convoId.value)
        stmt.setObject(2, promptId)
        stmt.setLong(3, llmRequestId)
        stmt.executeQuery().use { rs ->
          rs.next()
          return ConvoRequestId(rs.getLong("id"))
        }
      }
  }

  private fun observation(
    studentId: StudentId,
    convoId: ConvoId,
    quote: String,
  ): ObservationId {
    val req = appendRequest(convoId)
    return ObservationsDao
      .append(session, NewObservation(studentId, convoId, req, Instant.now(), quote))
      .getOrThrow()
      .id
  }

  @Test
  fun `link is idempotent`() {
    val student = createStudent()
    val convo = createConvo(student)
    val entry = createEntry(student, createCollege())
    val obs = observation(student, convo, "quote")

    val first = CollegeListEntrySupportDao.link(session, entry, obs).getOrThrow()
    val second = CollegeListEntrySupportDao.link(session, entry, obs).getOrThrow()

    assertEquals(first.entryId, second.entryId)
    assertEquals(first.observationId, second.observationId)
    assertEquals(first.createdAt, second.createdAt)
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT COUNT(*) FROM college_list_entry_support WHERE entry_id = '${entry.value}'").use { rs ->
        rs.next()
        assertEquals(1, rs.getInt(1))
      }
    }
  }

  @Test
  fun `link with unknown entry_id or observation_id raises NotFoundException`() {
    val student = createStudent()
    val convo = createConvo(student)
    val obs = observation(student, convo, "x")

    val unknownEntry = CollegeListEntrySupportDao.link(session, CollegeListEntryId(UUID.randomUUID()), obs)
    assertTrue(unknownEntry.exceptionOrNull() is NotFoundException, "got ${unknownEntry.exceptionOrNull()}")

    val entry = createEntry(student, createCollege())
    val unknownObs = CollegeListEntrySupportDao.link(session, entry, ObservationId(999999999L))
    assertTrue(unknownObs.exceptionOrNull() is NotFoundException, "got ${unknownObs.exceptionOrNull()}")
  }

  @Test
  fun `listObservationsForEntry and listEntriesForObservation are exact inverses`() {
    val student = createStudent()
    val convo = createConvo(student)
    val entry1 = createEntry(student, createCollege())
    val entry2 = createEntry(student, createCollege())
    val obs = observation(student, convo, "shared")
    val otherObs = observation(student, convo, "other")

    CollegeListEntrySupportDao.link(session, entry1, obs).getOrThrow()
    CollegeListEntrySupportDao.link(session, entry2, obs).getOrThrow()

    val entriesForObs =
      CollegeListEntrySupportDao
        .listEntriesForObservation(session, obs)
        .getOrThrow()
        .map { it.id }
        .toSet()
    assertEquals(setOf(entry1, entry2), entriesForObs)

    assertTrue(obs in CollegeListEntrySupportDao.listObservationsForEntry(session, entry1).getOrThrow().map { it.id })
    assertTrue(obs in CollegeListEntrySupportDao.listObservationsForEntry(session, entry2).getOrThrow().map { it.id })

    assertTrue(CollegeListEntrySupportDao.listEntriesForObservation(session, otherObs).getOrThrow().isEmpty())
  }

  @Test
  fun `UPDATE on college_list_entry_support raises P0001`() {
    val student = createStudent()
    val convo = createConvo(student)
    val entry = createEntry(student, createCollege())
    val obs = observation(student, convo, "quote")
    CollegeListEntrySupportDao.link(session, entry, obs).getOrThrow()

    val ex =
      runCatching {
        connection.createStatement().use {
          it.execute(
            "UPDATE college_list_entry_support SET created_at = NOW() " +
              "WHERE entry_id = '${entry.value}' AND observation_id = ${obs.value}",
          )
        }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got $ex")
  }

  @Test
  fun `DELETE on college_list_entry_support raises P0001`() {
    val student = createStudent()
    val convo = createConvo(student)
    val entry = createEntry(student, createCollege())
    val obs = observation(student, convo, "quote")
    CollegeListEntrySupportDao.link(session, entry, obs).getOrThrow()

    val ex =
      runCatching {
        connection.createStatement().use {
          it.execute("DELETE FROM college_list_entry_support WHERE entry_id = '${entry.value}' AND observation_id = ${obs.value}")
        }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got $ex")
  }
}
