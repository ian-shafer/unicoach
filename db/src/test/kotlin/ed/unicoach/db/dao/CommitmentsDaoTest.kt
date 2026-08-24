package ed.unicoach.db.dao

import ed.unicoach.db.models.CommitmentDisclosure
import ed.unicoach.db.models.CommitmentLens
import ed.unicoach.db.models.CommitmentStatus
import ed.unicoach.db.models.CommitmentTriggerKind
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.NewCommitment
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

class CommitmentsDaoTest {
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
      // system_prompts is deliberately NOT truncated: it is the migration-seeded,
      // immutable catalog (RFC 33/0007) that every other module's tests on this
      // shared database read. bin/test re-migrates before every run, so it is
      // already complete; wiping it and hand-restoring a stale list left the seeds
      // partial for whoever ran next (RFC 129).
      stmt.execute(
        "TRUNCATE TABLE commitment_support, commitments, synthesis_runs, observations, claim_support, claims, extraction_runs, " +
          "convos, convo_requests, llm_requests, llm_responses, llm_responses_raw, students, users CASCADE",
      )
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
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'cm-$userId@test.com', 'Cm User', 'ahash')")
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

  private fun newCommitment(
    studentId: StudentId,
    statement: String = "help them narrow the college list",
    lens: CommitmentLens = CommitmentLens.GAP,
    disclosure: CommitmentDisclosure = CommitmentDisclosure.EXPLICIT,
  ): NewCommitment = NewCommitment(studentId, lens, disclosure, statement)

  @Test
  fun `create defaults status open and trigger_kind next_session`() {
    val student = createStudent()
    val commitment = CommitmentsDao.create(session, newCommitment(student)).getOrThrow()

    assertEquals(CommitmentStatus.OPEN, commitment.status)
    assertEquals(CommitmentTriggerKind.NEXT_SESSION, commitment.triggerKind)
    assertEquals(CommitmentLens.GAP, commitment.lens)
    assertEquals(CommitmentDisclosure.EXPLICIT, commitment.disclosure)
    assertNull(commitment.triggerAt)
    assertNull(commitment.fulfilledAt)
    assertNull(commitment.disclosedInConvoId)
    assertNull(commitment.droppedAt)
    assertNull(commitment.dropReason)
    assertEquals(commitment.createdAt, commitment.updatedAt)
  }

  @Test
  fun `create persists an optional triggerAt for a timing commitment`() {
    val student = createStudent()
    val at = java.time.Instant.parse("2027-11-01T00:00:00Z")
    val commitment =
      CommitmentsDao
        .create(session, NewCommitment(student, CommitmentLens.TIMING, CommitmentDisclosure.EXPLICIT, "ED deadline nears", at))
        .getOrThrow()
    assertEquals(at, commitment.triggerAt)
    assertEquals(CommitmentLens.TIMING, commitment.lens)
  }

  @Test
  fun `listOpenByStudent excludes fulfilled and dropped, ordered created_at id`() {
    val student = createStudent()
    val convo = createConvo(student)
    val open = CommitmentsDao.create(session, newCommitment(student, "open one")).getOrThrow()
    val toFulfill = CommitmentsDao.create(session, newCommitment(student, "fulfill me")).getOrThrow()
    val toDrop = CommitmentsDao.create(session, newCommitment(student, "drop me")).getOrThrow()
    val internalOpen =
      CommitmentsDao
        .create(session, newCommitment(student, "internal open", disclosure = CommitmentDisclosure.INTERNAL))
        .getOrThrow()

    CommitmentsDao.markFulfilled(session, toFulfill.id, convo).getOrThrow()
    CommitmentsDao.drop(session, toDrop.id, "stale_basis").getOrThrow()

    val openIds =
      CommitmentsDao
        .listOpenByStudent(session, student)
        .getOrThrow()
        .map { it.id }
    // open + internal-open (all disclosures), excluding fulfilled/dropped.
    assertEquals(listOf(open.id, internalOpen.id), openIds)
  }

  @Test
  fun `listOpenExplicitByStudent excludes internal, fulfilled, and dropped`() {
    val student = createStudent()
    val convo = createConvo(student)
    val explicitOpen = CommitmentsDao.create(session, newCommitment(student, "explicit open")).getOrThrow()
    CommitmentsDao.create(session, newCommitment(student, "internal open", disclosure = CommitmentDisclosure.INTERNAL)).getOrThrow()
    val explicitFulfilled = CommitmentsDao.create(session, newCommitment(student, "explicit fulfilled")).getOrThrow()
    CommitmentsDao.markFulfilled(session, explicitFulfilled.id, convo).getOrThrow()

    val ids =
      CommitmentsDao
        .listOpenExplicitByStudent(session, student)
        .getOrThrow()
        .map { it.id }
    assertEquals(listOf(explicitOpen.id), ids)
  }

  @Test
  fun `markFulfilled sets status, fulfilled_at, disclosed_in_convo_id, and bumps updated_at`() {
    val student = createStudent()
    val convo = createConvo(student)
    val commitment = CommitmentsDao.create(session, newCommitment(student)).getOrThrow()

    val fulfilled = CommitmentsDao.markFulfilled(session, commitment.id, convo).getOrThrow()
    assertEquals(CommitmentStatus.FULFILLED, fulfilled.status)
    assertNotNull(fulfilled.fulfilledAt)
    assertEquals(convo, fulfilled.disclosedInConvoId)
    assertNull(fulfilled.droppedAt)
    assertTrue(fulfilled.updatedAt.isAfter(commitment.updatedAt) || fulfilled.updatedAt == commitment.updatedAt)
  }

  @Test
  fun `drop sets status, dropped_at, and drop_reason`() {
    val student = createStudent()
    val commitment = CommitmentsDao.create(session, newCommitment(student)).getOrThrow()

    val dropped = CommitmentsDao.drop(session, commitment.id, "stale_basis").getOrThrow()
    assertEquals(CommitmentStatus.DROPPED, dropped.status)
    assertNotNull(dropped.droppedAt)
    assertEquals("stale_basis", dropped.dropReason)
    assertNull(dropped.fulfilledAt)
  }

  @Test
  fun `mutating an immutable column (id) fails`() {
    val student = createStudent()
    val commitment = CommitmentsDao.create(session, newCommitment(student)).getOrThrow()
    val ex =
      runCatching {
        connection.createStatement().use {
          it.execute("UPDATE commitments SET id = '${UUID.randomUUID()}' WHERE id = '${commitment.id.value}'")
        }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got $ex")
  }

  @Test
  fun `physical DELETE on commitments is blocked`() {
    val student = createStudent()
    val commitment = CommitmentsDao.create(session, newCommitment(student)).getOrThrow()
    val ex =
      runCatching {
        connection.createStatement().use { it.execute("DELETE FROM commitments WHERE id = '${commitment.id.value}'") }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got $ex")
  }

  @Test
  fun `an FK violation on unknown student_id surfaces as failure`() {
    val result = CommitmentsDao.create(session, newCommitment(StudentId(UUID.randomUUID())))
    assertTrue(result.exceptionOrNull() is NotFoundException, "got ${result.exceptionOrNull()}")
  }

  @Test
  fun `each enum column rejects an out-of-set value`() {
    val student = createStudent()
    val valid =
      mapOf(
        "lens" to "gap",
        "disclosure" to "explicit",
        "status" to "open",
      )
    for (badColumn in valid.keys) {
      val values = valid.mapValues { (col, v) -> if (col == badColumn) "BOGUS" else v }
      val ex =
        runCatching {
          connection
            .prepareStatement(
              "INSERT INTO commitments (student_id, lens, disclosure, status, statement) VALUES (?, ?, ?, ?, 'x')",
            ).use { stmt ->
              stmt.setObject(1, student.value)
              stmt.setString(2, values.getValue("lens"))
              stmt.setString(3, values.getValue("disclosure"))
              stmt.setString(4, values.getValue("status"))
              stmt.executeUpdate()
            }
        }.exceptionOrNull()
      assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "column=$badColumn got $ex")
    }
  }

  @Test
  fun `fulfilled status with null fulfilled_at or null disclosed_in_convo_id violates consistency CHECK`() {
    val student = createStudent()
    val commitment = CommitmentsDao.create(session, newCommitment(student)).getOrThrow()
    val ex =
      runCatching {
        connection.createStatement().use {
          it.execute("UPDATE commitments SET status = 'fulfilled', fulfilled_at = NULL WHERE id = '${commitment.id.value}'")
        }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `dropped status with null dropped_at violates consistency CHECK`() {
    val student = createStudent()
    val commitment = CommitmentsDao.create(session, newCommitment(student)).getOrThrow()
    val ex =
      runCatching {
        connection.createStatement().use {
          it.execute("UPDATE commitments SET status = 'dropped', dropped_at = NULL WHERE id = '${commitment.id.value}'")
        }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `blank statement and an oversized statement are rejected`() {
    val student = createStudent()
    val blank =
      runCatching {
        connection
          .prepareStatement(
            "INSERT INTO commitments (student_id, lens, disclosure, statement) VALUES (?, 'gap', 'explicit', '   ')",
          ).use {
            it.setObject(1, student.value)
            it.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(blank is java.sql.SQLException && blank.sqlState == "23514", "got $blank")

    val oversized =
      runCatching {
        connection
          .prepareStatement(
            "INSERT INTO commitments (student_id, lens, disclosure, statement) VALUES (?, 'gap', 'explicit', ?)",
          ).use {
            it.setObject(1, student.value)
            it.setString(2, "x".repeat(2049))
            it.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(oversized is java.sql.SQLException && oversized.sqlState == "23514", "got $oversized")
  }

  @Test
  fun `findById returns the commitment`() {
    val student = createStudent()
    val commitment = CommitmentsDao.create(session, newCommitment(student)).getOrThrow()
    assertEquals(commitment.id, CommitmentsDao.findById(session, commitment.id).getOrThrow().id)
  }
}
