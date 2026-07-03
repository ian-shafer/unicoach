package ed.unicoach.db.dao

import ed.unicoach.db.models.ClaimId
import ed.unicoach.db.models.ClaimKind
import ed.unicoach.db.models.ClaimOrigin
import ed.unicoach.db.models.ClaimSubject
import ed.unicoach.db.models.ClaimTopic
import ed.unicoach.db.models.ClaimVisibility
import ed.unicoach.db.models.CommitmentDisclosure
import ed.unicoach.db.models.CommitmentId
import ed.unicoach.db.models.CommitmentLens
import ed.unicoach.db.models.NewClaim
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
import kotlin.test.assertTrue

class CommitmentSupportDaoTest {
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
        "TRUNCATE TABLE commitment_support, commitments, synthesis_runs, observations, claim_support, claims, extraction_runs, " +
          "convos, convo_requests, convo_responses, convo_responses_raw, system_prompts, students, users CASCADE",
      )
      // Restore all migration-seeded prompts for cross-module suites on the shared DB.
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('coach', 'v1', 'You are Uni, a warm coach.')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('extraction', 'v1', 'distill the transcript')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('synthesis', 'v1', 'reflect over the model')")
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
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'cs-$userId@test.com', 'Cs User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun createClaim(
    studentId: StudentId,
    statement: String = "wants CS",
  ): ClaimId =
    ClaimsDao
      .create(
        session,
        NewClaim(
          studentId,
          ClaimOrigin.STUDENT_STATED,
          ClaimKind.GOAL,
          ClaimSubject.STUDENT,
          ClaimTopic.ACADEMICS,
          ClaimVisibility.STUDENT_VISIBLE,
          statement,
        ),
      ).getOrThrow()
      .id

  private fun createCommitment(
    studentId: StudentId,
    statement: String = "narrow the list",
  ): CommitmentId =
    CommitmentsDao
      .create(session, NewCommitment(studentId, CommitmentLens.GAP, CommitmentDisclosure.EXPLICIT, statement))
      .getOrThrow()
      .id

  @Test
  fun `link is idempotent - a repeat is a no-op success, not a duplicate-key error`() {
    val student = createStudent()
    val commitment = createCommitment(student)
    val claim = createClaim(student)

    val first = CommitmentSupportDao.link(session, commitment, claim).getOrThrow()
    val second = CommitmentSupportDao.link(session, commitment, claim).getOrThrow()

    assertEquals(first.commitmentId, second.commitmentId)
    assertEquals(first.claimId, second.claimId)
    assertEquals(first.createdAt, second.createdAt)

    // Exactly one row persisted.
    connection.prepareStatement("SELECT COUNT(*) FROM commitment_support WHERE commitment_id = ? AND claim_id = ?").use { stmt ->
      stmt.setObject(1, commitment.value)
      stmt.setObject(2, claim.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        assertEquals(1, rs.getInt(1))
      }
    }
  }

  @Test
  fun `listClaimsForCommitment and listCommitmentsForClaim are exact inverses`() {
    val student = createStudent()
    val commitmentA = createCommitment(student, "A")
    val commitmentB = createCommitment(student, "B")
    val claim1 = createClaim(student, "one")
    val claim2 = createClaim(student, "two")

    CommitmentSupportDao.link(session, commitmentA, claim1).getOrThrow()
    CommitmentSupportDao.link(session, commitmentA, claim2).getOrThrow()
    CommitmentSupportDao.link(session, commitmentB, claim1).getOrThrow()

    assertEquals(
      setOf(claim1, claim2),
      CommitmentSupportDao
        .listClaimsForCommitment(session, commitmentA)
        .getOrThrow()
        .map { it.id }
        .toSet(),
    )
    assertEquals(
      setOf(commitmentA, commitmentB),
      CommitmentSupportDao
        .listCommitmentsForClaim(session, claim1)
        .getOrThrow()
        .map { it.id }
        .toSet(),
    )
    assertEquals(
      setOf(commitmentA),
      CommitmentSupportDao
        .listCommitmentsForClaim(session, claim2)
        .getOrThrow()
        .map { it.id }
        .toSet(),
    )
  }

  @Test
  fun `an unknown commitment_id or claim_id surfaces as NotFound`() {
    val student = createStudent()
    val commitment = createCommitment(student)
    val claim = createClaim(student)

    val badCommitment = CommitmentSupportDao.link(session, CommitmentId(UUID.randomUUID()), claim)
    assertTrue(badCommitment.exceptionOrNull() is NotFoundException, "got ${badCommitment.exceptionOrNull()}")

    val badClaim = CommitmentSupportDao.link(session, commitment, ClaimId(UUID.randomUUID()))
    assertTrue(badClaim.exceptionOrNull() is NotFoundException, "got ${badClaim.exceptionOrNull()}")
  }

  @Test
  fun `UPDATE on commitment_support raises P0001`() {
    val student = createStudent()
    val commitment = createCommitment(student)
    val claim = createClaim(student)
    CommitmentSupportDao.link(session, commitment, claim).getOrThrow()

    val ex =
      runCatching {
        connection.createStatement().use {
          it.execute("UPDATE commitment_support SET created_at = NOW() WHERE commitment_id = '${commitment.value}'")
        }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got $ex")
  }

  @Test
  fun `DELETE on commitment_support raises P0001`() {
    val student = createStudent()
    val commitment = createCommitment(student)
    val claim = createClaim(student)
    CommitmentSupportDao.link(session, commitment, claim).getOrThrow()

    val ex =
      runCatching {
        connection.createStatement().use {
          it.execute("DELETE FROM commitment_support WHERE commitment_id = '${commitment.value}'")
        }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got $ex")
  }
}
