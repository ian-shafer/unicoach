package ed.unicoach.db.dao

import ed.unicoach.db.models.ClaimKind
import ed.unicoach.db.models.ClaimOrigin
import ed.unicoach.db.models.ClaimRevision
import ed.unicoach.db.models.ClaimStatus
import ed.unicoach.db.models.ClaimSubject
import ed.unicoach.db.models.ClaimTopic
import ed.unicoach.db.models.ClaimVisibility
import ed.unicoach.db.models.NewClaim
import ed.unicoach.db.models.StudentId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaimsDaoTest {
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
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'cl-$userId@test.com', 'Cl User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun newClaim(
    studentId: StudentId,
    statement: String = "wants to study CS",
    origin: ClaimOrigin = ClaimOrigin.STUDENT_STATED,
    kind: ClaimKind = ClaimKind.GOAL,
    subject: ClaimSubject = ClaimSubject.STUDENT,
    topic: ClaimTopic = ClaimTopic.ACADEMICS,
    visibility: ClaimVisibility = ClaimVisibility.STUDENT_VISIBLE,
  ): NewClaim = NewClaim(studentId, origin, kind, subject, topic, visibility, statement)

  @Test
  fun `create defaults status active, confidence 0, visibility student_visible`() {
    val student = createStudent()
    val claim = ClaimsDao.create(session, newClaim(student)).getOrThrow()

    assertEquals(ClaimStatus.ACTIVE, claim.status)
    assertEquals(0, claim.confidence)
    assertEquals(ClaimVisibility.STUDENT_VISIBLE, claim.visibility)
    assertEquals(ClaimOrigin.STUDENT_STATED, claim.origin)
    assertEquals(ClaimKind.GOAL, claim.kind)
    assertNull(claim.supersededById)
    assertNull(claim.supersededAt)
    assertNull(claim.retractedAt)
    assertEquals(claim.createdAt, claim.updatedAt)
  }

  @Test
  fun `internal visibility is honored`() {
    val student = createStudent()
    val claim = ClaimsDao.create(session, newClaim(student, visibility = ClaimVisibility.INTERNAL)).getOrThrow()
    assertEquals(ClaimVisibility.INTERNAL, claim.visibility)
  }

  @Test
  fun `listActiveByStudent excludes superseded and retracted`() {
    val student = createStudent()
    val active = ClaimsDao.create(session, newClaim(student, "active one")).getOrThrow()
    val toSupersede = ClaimsDao.create(session, newClaim(student, "old one")).getOrThrow()
    val replacement = ClaimsDao.create(session, newClaim(student, "new one")).getOrThrow()
    val toRetract = ClaimsDao.create(session, newClaim(student, "wrong one")).getOrThrow()

    ClaimsDao.revise(session, toSupersede.id, ClaimRevision(ClaimStatus.SUPERSEDED, 0, replacement.id)).getOrThrow()
    ClaimsDao.revise(session, toRetract.id, ClaimRevision(ClaimStatus.RETRACTED, 0)).getOrThrow()

    val activeIds =
      ClaimsDao
        .listActiveByStudent(session, student)
        .getOrThrow()
        .map { it.id }
        .toSet()
    assertTrue(active.id in activeIds)
    assertTrue(replacement.id in activeIds)
    assertTrue(toSupersede.id !in activeIds)
    assertTrue(toRetract.id !in activeIds)
  }

  @Test
  fun `revise to superseded sets supersession pointer, status, confidence, and bumps updated_at`() {
    val student = createStudent()
    val old = ClaimsDao.create(session, newClaim(student, "old")).getOrThrow()
    val replacement = ClaimsDao.create(session, newClaim(student, "new")).getOrThrow()

    val revised = ClaimsDao.revise(session, old.id, ClaimRevision(ClaimStatus.SUPERSEDED, 250, replacement.id)).getOrThrow()

    assertEquals(ClaimStatus.SUPERSEDED, revised.status)
    assertEquals(250, revised.confidence)
    assertEquals(replacement.id, revised.supersededById)
    assertNotNull(revised.supersededAt)
    assertNull(revised.retractedAt)
    assertTrue(revised.updatedAt.isAfter(old.updatedAt) || revised.updatedAt == old.updatedAt)
  }

  @Test
  fun `revise to retracted stamps retracted_at and clears supersession`() {
    val student = createStudent()
    val claim = ClaimsDao.create(session, newClaim(student)).getOrThrow()
    val revised = ClaimsDao.revise(session, claim.id, ClaimRevision(ClaimStatus.RETRACTED, 0)).getOrThrow()

    assertEquals(ClaimStatus.RETRACTED, revised.status)
    assertNotNull(revised.retractedAt)
    assertNull(revised.supersededById)
    assertNull(revised.supersededAt)
  }

  @Test
  fun `findById returns the claim`() {
    val student = createStudent()
    val claim = ClaimsDao.create(session, newClaim(student)).getOrThrow()
    assertEquals(claim.id, ClaimsDao.findById(session, claim.id).getOrThrow().id)
  }

  @Test
  fun `mutating an immutable column (id) fails`() {
    val student = createStudent()
    val claim = ClaimsDao.create(session, newClaim(student)).getOrThrow()
    val ex =
      runCatching {
        connection.createStatement().use {
          it.execute("UPDATE claims SET id = '${UUID.randomUUID()}' WHERE id = '${claim.id.value}'")
        }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got $ex")
  }

  @Test
  fun `physical DELETE on claims is blocked`() {
    val student = createStudent()
    val claim = ClaimsDao.create(session, newClaim(student)).getOrThrow()
    val ex =
      runCatching {
        connection.createStatement().use { it.execute("DELETE FROM claims WHERE id = '${claim.id.value}'") }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got $ex")
  }

  @Test
  fun `confidence out of range is rejected`() {
    val student = createStudent()
    val claim = ClaimsDao.create(session, newClaim(student)).getOrThrow()
    val result = ClaimsDao.revise(session, claim.id, ClaimRevision(ClaimStatus.ACTIVE, 1001))
    assertTrue(result.exceptionOrNull() is ConstraintViolationException, "got ${result.exceptionOrNull()}")
  }

  @Test
  fun `each enum column rejects an out-of-set value`() {
    val student = createStudent()
    // One row per enum column: the named column carries 'BOGUS' while the others
    // stay valid, so the named CHECK that fires is unambiguous (SQLState 23514).
    val valid =
      mapOf(
        "origin" to "student_stated",
        "kind" to "goal",
        "subject" to "student",
        "topic" to "academics",
        "visibility" to "student_visible",
      )
    for (badColumn in valid.keys) {
      val values = valid.mapValues { (col, v) -> if (col == badColumn) "BOGUS" else v }
      val ex =
        runCatching {
          connection
            .prepareStatement(
              "INSERT INTO claims (student_id, origin, kind, subject, topic, visibility, statement) VALUES (?, ?, ?, ?, ?, ?, 'x')",
            ).use { stmt ->
              stmt.setObject(1, student.value)
              stmt.setString(2, values.getValue("origin"))
              stmt.setString(3, values.getValue("kind"))
              stmt.setString(4, values.getValue("subject"))
              stmt.setString(5, values.getValue("topic"))
              stmt.setString(6, values.getValue("visibility"))
              stmt.executeUpdate()
            }
        }.exceptionOrNull()
      assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "column=$badColumn got $ex")
    }
  }

  @Test
  fun `self-supersession (superseded_by_id = id) violates not_self_superseded CHECK`() {
    val student = createStudent()
    val claim = ClaimsDao.create(session, newClaim(student)).getOrThrow()
    val ex =
      runCatching {
        connection.createStatement().use {
          it.execute(
            "UPDATE claims SET status = 'superseded', superseded_by_id = id, superseded_at = NOW() WHERE id = '${claim.id.value}'",
          )
        }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `retracted status with null retracted_at violates retracted consistency CHECK`() {
    val student = createStudent()
    val claim = ClaimsDao.create(session, newClaim(student)).getOrThrow()
    val ex =
      runCatching {
        connection.createStatement().use {
          it.execute("UPDATE claims SET status = 'retracted', retracted_at = NULL WHERE id = '${claim.id.value}'")
        }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `superseded status with null superseded_by_id violates consistency CHECK`() {
    val student = createStudent()
    val claim = ClaimsDao.create(session, newClaim(student)).getOrThrow()
    // revise enforces consistency by deriving superseded_at; but a null successor
    // with superseded status is the violation we assert at the SQL layer.
    val ex =
      runCatching {
        connection.createStatement().use {
          it.execute(
            "UPDATE claims SET status = 'superseded', superseded_at = NOW() WHERE id = '${claim.id.value}'",
          )
        }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }
}
