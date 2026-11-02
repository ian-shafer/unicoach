package ed.unicoach.db

import ed.unicoach.db.dao.DeletedAccountRowFixture
import ed.unicoach.db.dao.RawSql
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.db.dao.SubjectTaxonomyFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The guard arm (RFC 266 §5, slice deletion/03/the-walk, decisions D-B/D-C),
 * held against the real schema.
 *
 * One test per guard function that gained the arm, each making the same UPDATE
 * the erasure walk makes on a row of that guard's table, three times: with no
 * declared erasure (refused), with the declaration naming a LIVE account
 * (refused: a stray marker opens nothing), and with the declaration naming a
 * CLOSED account (admitted). Each refusal is checked to come from the guard
 * under test, by the PL/pgSQL frame the server reports, so a refusal from some
 * other trigger or CHECK cannot pass for it.
 *
 * And the converse: a guard WITHOUT the arm still refuses inside a declared
 * erasure (D-B: only the guards the walk trips admit anything).
 *
 * Every test runs in one transaction that is rolled back; each refusal is
 * taken inside a savepoint so the transaction goes on.
 */
class ErasureGuardArmTest {
  companion object {
    private lateinit var connection: Connection

    private const val RAISED = "P0001"

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @BeforeEach
  fun begin() {
    connection.autoCommit = false
  }

  @AfterEach
  fun rollback() {
    connection.rollback()
    connection.autoCommit = true
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String) = connection.prepareStatement(sql)
    }

  // ---------------------------------------------------------------------------
  // Seeding. Each account's deleted rows are written up front, while it is
  // still live, so the key moves below satisfy their sibling foreign keys in
  // all three cases and the guard is the only thing that can refuse them.
  // ---------------------------------------------------------------------------

  private fun user(): UUID {
    val id = UUID.randomUUID()
    RawSql.execute(
      connection,
      "INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, 'Guard Arm', 'ahash')",
      id,
      "guard-arm-$id@test.com",
    )
    DeletedAccountRowFixture.eraseAccount(connection, id)
    return id
  }

  private fun student(userId: UUID): UUID {
    val id = UUID.randomUUID()
    StudentRowFixture.insert(connection, id, userId)
    DeletedAccountRowFixture.eraseStudent(connection, id, userId)
    return id
  }

  private fun supporter(userId: UUID): UUID {
    val id =
      RawSql.single(connection, "INSERT INTO supporters (user_id) VALUES (?) RETURNING id", userId) {
        it.getObject(1, UUID::class.java)
      }
    DeletedAccountRowFixture.eraseSupporter(connection, id, userId)
    return id
  }

  private fun insertReturningId(
    sql: String,
    vararg args: Any?,
  ): UUID = RawSql.single(connection, sql, *args) { it.getObject(1, UUID::class.java) }

  private fun close(userId: UUID) =
    RawSql.execute(connection, "UPDATE users SET deleted_at = NOW(), version = version + 1 WHERE id = ?", userId)

  /** Declares (or, with null, clears) the erasure, as the walk does. */
  private fun declare(account: UUID?) =
    RawSql.single(connection, "SELECT set_config('unicoach.erasure_account', ?, true)", account?.toString() ?: "") {}

  private fun refusal(write: () -> Unit): PSQLException {
    val savepoint = connection.setSavepoint()
    try {
      write()
    } catch (e: SQLException) {
      connection.rollback(savepoint)
      return e as? PSQLException ?: fail("expected a PSQLException, got [$e]")
    }
    connection.releaseSavepoint(savepoint)
    fail("the write was accepted; it must be refused")
  }

  private fun assertRefusedBy(
    guard: String,
    failure: PSQLException,
  ) {
    assertEquals(RAISED, failure.sqlState, "[$failure]")
    val frame = failure.serverErrorMessage?.where.orEmpty()
    assertTrue("function $guard()" in frame, "the refusal must come from $guard(); it came from [$frame]: [$failure]")
  }

  /**
   * Test 12's three cases for one guard: [write] is the walk's UPDATE on a row
   * of [account]'s, and [guard] the function that refuses it.
   */
  private fun assertArmAdmitsOnlyAClosedAccount(
    guard: String,
    account: UUID,
    write: () -> Unit,
  ) {
    declare(null)
    assertRefusedBy(guard, refusal(write))

    declare(account)
    assertRefusedBy(guard, refusal(write))

    close(account)
    declare(account)
    write()
    declare(null)
  }

  // ---------------------------------------------------------------------------
  // 12: one test per guard function in §5's table
  // ---------------------------------------------------------------------------

  @Test
  fun `prevent_log_update admits the walk's key move on an append-only table`() {
    val account = user()
    val sendId =
      insertReturningId(
        "INSERT INTO email_sends (recipient_email, sender_email, subject, body, status, provider, user_id) " +
          "VALUES ('to@test.com', 'from@test.com', 's', 'b', 'SENT', 'ses', ?) RETURNING id",
        account,
      )
    assertArmAdmitsOnlyAClosedAccount("prevent_log_update", account) {
      RawSql.execute(connection, "UPDATE email_sends SET deleted_user_id = user_id, user_id = NULL WHERE id = ?", sendId)
    }
  }

  @Test
  fun `prevent_convos_party_updates admits the walk's student key move`() {
    val account = user()
    val convoId =
      insertReturningId(
        "INSERT INTO convos (student_id, name, party) VALUES (?, 'c', 'student') RETURNING id",
        student(account),
      )
    assertArmAdmitsOnlyAClosedAccount("prevent_convos_party_updates", account) {
      RawSql.execute(connection, "UPDATE convos SET deleted_student_id = student_id, student_id = NULL WHERE id = ?", convoId)
    }
  }

  @Test
  fun `prevent_supporter_immutable_updates admits the walk's account key move`() {
    val account = user()
    val supporterId = supporter(account)
    assertArmAdmitsOnlyAClosedAccount("prevent_supporter_immutable_updates", account) {
      RawSql.execute(
        connection,
        "UPDATE supporters SET deleted_user_id = user_id, user_id = NULL, version = version + 1 WHERE id = ?",
        supporterId,
      )
    }
  }

  @Test
  fun `prevent_supporter_student_link_immutable_updates admits the walk's supporter key move`() {
    val account = user()
    val linkId =
      insertReturningId(
        "INSERT INTO supporter_student_links (supporter_id, student_id, relationship) VALUES (?, ?, 'parent') RETURNING id",
        supporter(account),
        student(user()),
      )
    assertArmAdmitsOnlyAClosedAccount("prevent_supporter_student_link_immutable_updates", account) {
      RawSql.execute(
        connection,
        "UPDATE supporter_student_links SET deleted_supporter_id = supporter_id, supporter_id = NULL, " +
          "version = version + 1 WHERE id = ?",
        linkId,
      )
    }
  }

  @Test
  fun `prevent_supporter_link_request_column_updates admits the walk's supporter key move`() {
    val account = user()
    val studentId = student(user())
    val shareId =
      insertReturningId(
        "INSERT INTO cost_report_shares (student_id, token_hash) VALUES (?, ?) RETURNING id",
        studentId,
        Random.nextBytes(32),
      )
    val requestId =
      insertReturningId(
        "INSERT INTO supporter_link_requests (supporter_id, student_id, cost_report_share_id) VALUES (?, ?, ?) RETURNING id",
        supporter(account),
        studentId,
        shareId,
      )
    assertArmAdmitsOnlyAClosedAccount("prevent_supporter_link_request_column_updates", account) {
      RawSql.execute(
        connection,
        "UPDATE supporter_link_requests SET deleted_supporter_id = supporter_id, supporter_id = NULL WHERE id = ?",
        requestId,
      )
    }
  }

  @Test
  fun `prevent_sharing_permission_column_updates admits the walk's student key move`() {
    val account = user()
    val permissionId =
      insertReturningId(
        "INSERT INTO sharing_permissions (student_id, permission, status) VALUES (?, 'intended_subjects', 'granted') RETURNING id",
        student(account),
      )
    assertArmAdmitsOnlyAClosedAccount("prevent_sharing_permission_column_updates", account) {
      RawSql.execute(
        connection,
        "UPDATE sharing_permissions SET deleted_student_id = student_id, student_id = NULL, version = version + 1 WHERE id = ?",
        permissionId,
      )
    }
  }

  @Test
  fun `prevent_intended_subject_immutable_updates admits the walk's student key move`() {
    val account = user()
    val slug = "guard-arm-${UUID.randomUUID()}"
    SubjectTaxonomyFixture.insertSubject(session, slug, "Guard Arm Studies", "26")
    val subjectId =
      insertReturningId(
        "INSERT INTO intended_subjects (student_id, subject_slug) VALUES (?, ?) RETURNING id",
        student(account),
        slug,
      )
    assertArmAdmitsOnlyAClosedAccount("prevent_intended_subject_immutable_updates", account) {
      RawSql.execute(
        connection,
        "UPDATE intended_subjects SET deleted_student_id = student_id, student_id = NULL, version = version + 1 WHERE id = ?",
        subjectId,
      )
    }
  }

  @Test
  fun `prevent_intended_subject_status_immutable_updates admits the walk's student key move`() {
    val account = user()
    val statusId =
      insertReturningId(
        "INSERT INTO intended_subject_status (student_id, status) VALUES (?, 'declined') RETURNING id",
        student(account),
      )
    assertArmAdmitsOnlyAClosedAccount("prevent_intended_subject_status_immutable_updates", account) {
      RawSql.execute(
        connection,
        "UPDATE intended_subject_status SET deleted_student_id = student_id, student_id = NULL, version = version + 1 WHERE id = ?",
        statusId,
      )
    }
  }

  // ---------------------------------------------------------------------------
  // 13: a guard without the arm still refuses inside the erasure
  // ---------------------------------------------------------------------------

  @Test
  fun `a guard without the arm still refuses inside the erasure`() {
    val account = user()
    val claimId =
      insertReturningId(
        "INSERT INTO claims (student_id, origin, kind, subject, topic, visibility, statement) " +
          "VALUES (?, 'student_stated', 'goal', 'student', 'academics', 'student_visible', 's') RETURNING id",
        student(account),
      )
    close(account)
    declare(account)

    assertRefusedBy(
      "prevent_immutable_updates",
      refusal { RawSql.execute(connection, "UPDATE claims SET id = ? WHERE id = ?", UUID.randomUUID(), claimId) },
    )
    assertRefusedBy(
      "prevent_physical_delete",
      refusal { RawSql.execute(connection, "DELETE FROM claims WHERE id = ?", claimId) },
    )
  }
}
