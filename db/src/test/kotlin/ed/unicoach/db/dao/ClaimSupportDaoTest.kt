package ed.unicoach.db.dao

import ed.unicoach.db.models.ClaimId
import ed.unicoach.db.models.ClaimKind
import ed.unicoach.db.models.ClaimOrigin
import ed.unicoach.db.models.ClaimSubject
import ed.unicoach.db.models.ClaimTopic
import ed.unicoach.db.models.ClaimVisibility
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.NewClaim
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

class ClaimSupportDaoTest {
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
      // Restore the migration-seeded prompts for cross-module suites on the shared DB.
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('coach', 'v1', 'You are Uni, a warm coach.')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('extraction', 'v1', 'distill the transcript')")
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
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'cs-$userId@test.com', 'CS User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

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

  private fun newClaim(studentId: StudentId): NewClaim =
    NewClaim(
      studentId,
      ClaimOrigin.STUDENT_STATED,
      ClaimKind.GOAL,
      ClaimSubject.STUDENT,
      ClaimTopic.ACADEMICS,
      ClaimVisibility.STUDENT_VISIBLE,
      "wants CS",
    )

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
    val claim = ClaimsDao.create(session, newClaim(student)).getOrThrow().id
    val obs = observation(student, convo, "quote")

    val first = ClaimSupportDao.link(session, claim, obs).getOrThrow()
    val second = ClaimSupportDao.link(session, claim, obs).getOrThrow()

    assertEquals(first.claimId, second.claimId)
    assertEquals(first.observationId, second.observationId)
    assertEquals(first.createdAt, second.createdAt)
    // exactly one row exists
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT COUNT(*) FROM claim_support WHERE claim_id = '${claim.value}'").use { rs ->
        rs.next()
        assertEquals(1, rs.getInt(1))
      }
    }
  }

  @Test
  fun `listObservationsForClaim returns the linked set`() {
    val student = createStudent()
    val convo = createConvo(student)
    val claim = ClaimsDao.create(session, newClaim(student)).getOrThrow().id
    val o1 = observation(student, convo, "a")
    val o2 = observation(student, convo, "b")
    ClaimSupportDao.link(session, claim, o1).getOrThrow()
    ClaimSupportDao.link(session, claim, o2).getOrThrow()

    val linked =
      ClaimSupportDao
        .listObservationsForClaim(session, claim)
        .getOrThrow()
        .map { it.id }
        .toSet()
    assertEquals(setOf(o1, o2), linked)
  }

  @Test
  fun `reverse index lookup works`() {
    val student = createStudent()
    val convo = createConvo(student)
    val c1 = ClaimsDao.create(session, newClaim(student)).getOrThrow().id
    val c2 = ClaimsDao.create(session, newClaim(student)).getOrThrow().id
    val obs = observation(student, convo, "shared")
    ClaimSupportDao.link(session, c1, obs).getOrThrow()
    ClaimSupportDao.link(session, c2, obs).getOrThrow()

    connection.prepareStatement("SELECT claim_id FROM claim_support WHERE observation_id = ?").use { stmt ->
      stmt.setLong(1, obs.value)
      stmt.executeQuery().use { rs ->
        val claims = mutableSetOf<UUID>()
        while (rs.next()) claims.add(UUID.fromString(rs.getString("claim_id")))
        assertEquals(setOf(c1.value, c2.value), claims)
      }
    }
  }

  @Test
  fun `UPDATE on claim_support raises P0001`() {
    val student = createStudent()
    val convo = createConvo(student)
    val claim = ClaimsDao.create(session, newClaim(student)).getOrThrow().id
    val obs = observation(student, convo, "quote")
    ClaimSupportDao.link(session, claim, obs).getOrThrow()

    val ex =
      runCatching {
        connection.createStatement().use {
          it.execute("UPDATE claim_support SET created_at = NOW() WHERE claim_id = '${claim.value}' AND observation_id = ${obs.value}")
        }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got $ex")
  }

  @Test
  fun `DELETE on claim_support raises P0001`() {
    val student = createStudent()
    val convo = createConvo(student)
    val claim = ClaimsDao.create(session, newClaim(student)).getOrThrow().id
    val obs = observation(student, convo, "quote")
    ClaimSupportDao.link(session, claim, obs).getOrThrow()

    val ex =
      runCatching {
        connection.createStatement().use {
          it.execute("DELETE FROM claim_support WHERE claim_id = '${claim.value}' AND observation_id = ${obs.value}")
        }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got $ex")
  }

  @Test
  fun `link with unknown claim surfaces NotFound`() {
    val student = createStudent()
    val convo = createConvo(student)
    val obs = observation(student, convo, "x")
    val result = ClaimSupportDao.link(session, ClaimId(UUID.randomUUID()), obs)
    assertTrue(result.exceptionOrNull() is NotFoundException, "got ${result.exceptionOrNull()}")
  }
}
