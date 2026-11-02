package ed.unicoach.db

import ed.unicoach.db.dao.DeletedAccountRowFixture
import ed.unicoach.db.dao.LlmCallRowFixture
import ed.unicoach.db.dao.RawSql
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.db.models.PartialDateColumns
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The deleted tables and the live-or-deleted key pairs, held against the real
 * schema (RFC 264, slice deletion/02/the-deleted-tables, brief 0002 D1/D9).
 *
 * Nothing in production writes a deleted row or a sibling key yet, so every
 * shape here is written by hand, the way `deletion/03`'s walk will write it.
 * One table per account root carries each pair claim: `students` (onto
 * `users`), `money_profiles` (onto `students`), `supporter_llm_costs` (onto
 * `supporters`); the two pairs that were nullable before this slice
 * (`email_sends.user_id`, `convos.supporter_id`) carry the at-most-one claim.
 *
 * Every test runs in one transaction that is rolled back, so no row keyed only
 * on a deleted account outlives it into another test class on this database.
 * An expected refusal is taken inside a savepoint, so the transaction goes on.
 */
class DeletedKeyPairTest {
  companion object {
    private lateinit var connection: Connection

    private const val CHECK_VIOLATION = "23514"
    private const val FOREIGN_KEY_VIOLATION = "23503"
    private const val UNIQUE_VIOLATION = "23505"
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

  // ---------------------------------------------------------------------------
  // Seeding
  // ---------------------------------------------------------------------------

  private fun user(): UUID {
    val id = UUID.randomUUID()
    RawSql.execute(
      connection,
      "INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, 'Pair User', 'ahash')",
      id,
      "pair-$id@test.com",
    )
    return id
  }

  private fun student(userId: UUID): UUID {
    val id = UUID.randomUUID()
    StudentRowFixture.insert(connection, id, userId)
    return id
  }

  private fun supporter(userId: UUID): UUID =
    RawSql.single(connection, "INSERT INTO supporters (user_id) VALUES (?) RETURNING id", userId) { it.getObject(1, UUID::class.java) }

  private fun deletedUser(userId: UUID): UUID = DeletedAccountRowFixture.eraseAccount(connection, userId)

  private fun deletedStudent(
    studentId: UUID,
    deletedUserId: UUID,
  ): UUID = DeletedAccountRowFixture.eraseStudent(connection, studentId, deletedUserId)

  private fun deletedSupporter(
    supporterId: UUID,
    deletedUserId: UUID,
  ): UUID = DeletedAccountRowFixture.eraseSupporter(connection, supporterId, deletedUserId)

  /** An erased student: the account and the student both have their deleted row. */
  private fun erasedStudent(): UUID {
    val userId = user()
    return deletedStudent(student(userId), deletedUser(userId))
  }

  /** An erased supporter, the same way. */
  private fun erasedSupporter(): UUID {
    val userId = user()
    return deletedSupporter(supporter(userId), deletedUser(userId))
  }

  private fun insertStudent(
    userId: UUID?,
    deletedUserId: UUID?,
  ) = RawSql.execute(
    connection,
    "INSERT INTO students (user_id, deleted_user_id, expected_high_school_graduation_on, " +
      "expected_high_school_graduation_precision, date_of_birth) VALUES (?, ?, ?, ?, ?)",
    userId,
    deletedUserId,
    PartialDateColumns.periodStart(StudentRowFixture.DEFAULT_GRADUATION),
    PartialDateColumns.precision(StudentRowFixture.DEFAULT_GRADUATION).value,
    StudentRowFixture.DEFAULT_DATE_OF_BIRTH,
  )

  private fun insertMoneyProfile(
    studentId: UUID?,
    deletedStudentId: UUID?,
  ) = RawSql.execute(connection, "INSERT INTO money_profiles (student_id, deleted_student_id) VALUES (?, ?)", studentId, deletedStudentId)

  private fun insertSupporterLlmCost(
    supporterId: UUID?,
    deletedSupporterId: UUID?,
  ) = RawSql.execute(
    connection,
    "INSERT INTO supporter_llm_costs (llm_request_id, supporter_id, deleted_supporter_id) VALUES (?, ?, ?)",
    LlmCallRowFixture.insert(connection, costNanodollars = 1),
    supporterId,
    deletedSupporterId,
  )

  private fun insertEmailSend(
    userId: UUID?,
    deletedUserId: UUID?,
  ) = RawSql.execute(
    connection,
    "INSERT INTO email_sends (recipient_email, sender_email, subject, body, status, provider, user_id, deleted_user_id) " +
      "VALUES ('to@test.com', 'from@test.com', 's', 'b', 'SENT', 'ses', ?, ?)",
    userId,
    deletedUserId,
  )

  private fun insertConvo(
    party: String,
    studentId: UUID,
    supporterId: UUID?,
    deletedSupporterId: UUID?,
  ) = RawSql.execute(
    connection,
    "INSERT INTO convos (student_id, name, party, supporter_id, deleted_supporter_id) VALUES (?, 'c', ?, ?, ?)",
    studentId,
    party,
    supporterId,
    deletedSupporterId,
  )

  /**
   * Runs [write] inside a savepoint and answers the refusal, so the enclosing
   * transaction survives it. Fails the test if [write] succeeds.
   */
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
    sqlState: String,
    constraint: String,
    failure: PSQLException,
  ) {
    assertEquals(sqlState, failure.sqlState, "[$failure]")
    assertEquals(constraint, failure.serverErrorMessage?.constraint, "[$failure]")
  }

  // ---------------------------------------------------------------------------
  // 4-6: exactly one of a pair
  // ---------------------------------------------------------------------------

  @Test
  fun `a row keyed on both the live and the deleted account is refused`() {
    val userId = user()
    val deleted = deletedUser(user())
    assertRefusedBy(CHECK_VIOLATION, "students_user_id_pair_check", refusal { insertStudent(userId, deleted) })

    val studentId = student(user())
    val deletedStudentId = erasedStudent()
    assertRefusedBy(
      CHECK_VIOLATION,
      "money_profiles_student_id_pair_check",
      refusal { insertMoneyProfile(studentId, deletedStudentId) },
    )

    val supporterId = supporter(user())
    val deletedSupporterId = erasedSupporter()
    assertRefusedBy(
      CHECK_VIOLATION,
      "supporter_llm_costs_supporter_id_pair_check",
      refusal { insertSupporterLlmCost(supporterId, deletedSupporterId) },
    )
  }

  @Test
  fun `a row keyed on neither is refused`() {
    assertRefusedBy(CHECK_VIOLATION, "students_user_id_pair_check", refusal { insertStudent(null, null) })
    assertRefusedBy(CHECK_VIOLATION, "money_profiles_student_id_pair_check", refusal { insertMoneyProfile(null, null) })
    assertRefusedBy(
      CHECK_VIOLATION,
      "supporter_llm_costs_supporter_id_pair_check",
      refusal { insertSupporterLlmCost(null, null) },
    )
  }

  @Test
  fun `a row keyed only on the deleted account is accepted`() {
    val deletedUserId = deletedUser(user())
    insertStudent(null, deletedUserId)
    assertEquals(
      1,
      RawSql.count(
        connection,
        "SELECT COUNT(*) FROM students WHERE user_id IS NULL AND deleted_user_id = ?",
        deletedUserId,
      ),
    )

    val deletedStudentId = erasedStudent()
    insertMoneyProfile(null, deletedStudentId)
    assertEquals(
      1,
      RawSql.count(
        connection,
        "SELECT COUNT(*) FROM money_profiles WHERE student_id IS NULL AND deleted_student_id = ?",
        deletedStudentId,
      ),
    )

    val deletedSupporterId = erasedSupporter()
    insertSupporterLlmCost(null, deletedSupporterId)
    assertEquals(
      1,
      RawSql.count(
        connection,
        "SELECT COUNT(*) FROM supporter_llm_costs WHERE supporter_id IS NULL AND deleted_supporter_id = ?",
        deletedSupporterId,
      ),
    )
  }

  // ---------------------------------------------------------------------------
  // 7: the two pairs that were nullable before
  // ---------------------------------------------------------------------------

  @Test
  fun `a nullable key may be absent but never doubled`() {
    // email_sends: a send no account is known to own stays legal; both keys never.
    insertEmailSend(null, null)
    val userId = user()
    val deletedUserId = deletedUser(user())
    assertRefusedBy(CHECK_VIOLATION, "email_sends_user_id_pair_check", refusal { insertEmailSend(userId, deletedUserId) })

    // convos: a student's own conversation has no supporter on either key.
    val studentId = student(user())
    insertConvo("student", studentId, supporterId = null, deletedSupporterId = null)

    // An erased parent's conversation is still a supporter conversation.
    val deletedSupporterId = erasedSupporter()
    insertConvo("supporter", studentId, supporterId = null, deletedSupporterId = deletedSupporterId)
    assertEquals(
      "supporter",
      RawSql.single(connection, "SELECT party FROM convos WHERE deleted_supporter_id = ?", deletedSupporterId) { it.getString(1) },
    )

    // And a supporter conversation with no author on either key is not one.
    assertRefusedBy(
      CHECK_VIOLATION,
      "convos_party_author_check",
      refusal { insertConvo("supporter", studentId, supporterId = null, deletedSupporterId = null) },
    )
  }

  // ---------------------------------------------------------------------------
  // 8-9: the deleted rows themselves
  // ---------------------------------------------------------------------------

  @Test
  fun `a deleted row names a real account`() {
    assertRefusedBy(
      FOREIGN_KEY_VIOLATION,
      "deleted_users_id_fkey",
      refusal { deletedUser(UUID.randomUUID()) },
    )

    val userId = user()
    val deletedUserId = deletedUser(userId)
    deletedStudent(student(userId), deletedUserId)
    // A second student (another account's, since students.user_id is unique)
    // claimed by the same deleted account: one student per account, live or deleted.
    val otherStudent = student(user())
    assertRefusedBy(
      UNIQUE_VIOLATION,
      "deleted_students_user_id_key",
      refusal { deletedStudent(otherStudent, deletedUserId) },
    )
  }

  @Test
  fun `a deleted row is never changed or removed`() {
    val userId = user()
    val rows =
      mapOf(
        "deleted_users" to deletedUser(userId),
        "deleted_students" to deletedStudent(student(userId), userId),
        "deleted_supporters" to deletedSupporter(supporter(userId), userId),
      )

    for ((table, id) in rows) {
      val update = refusal { RawSql.execute(connection, "UPDATE $table SET erased_at = NOW() WHERE id = ?", id) }
      assertEquals(RAISED, update.sqlState, "[$update]")
      assertTrue(update.message!!.contains("append-only"), "$table UPDATE must be refused by prevent_log_update: [$update]")

      val delete = refusal { RawSql.execute(connection, "DELETE FROM $table WHERE id = ?", id) }
      assertEquals(RAISED, delete.sqlState, "[$delete]")
      assertTrue(delete.message!!.contains("cannot be deleted"), "$table DELETE must be refused by prevent_log_delete: [$delete]")

      assertEquals(
        1,
        RawSql.count(connection, "SELECT COUNT(*) FROM $table WHERE id = ?", id),
        "the $table row is still there",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // 10: the history copy
  // ---------------------------------------------------------------------------

  @Test
  fun `the history copy carries the deleted key`() {
    val userId = user()
    val studentId = student(userId)
    deletedUser(userId)

    DeletedAccountRowFixture.moveStudentToDeletedKey(connection, studentId, userId)

    val (copiedLive, copiedDeleted) =
      RawSql.single(connection, "SELECT user_id, deleted_user_id FROM students_versions WHERE id = ? AND version = 2", studentId) {
        it.getObject(1, UUID::class.java) to it.getObject(2, UUID::class.java)
      }
    assertNull(copiedLive, "the version 2 history row records the live key gone")
    assertEquals(userId, assertNotNull(copiedDeleted), "and the deleted key the row moved to")
    assertEquals(
      userId,
      RawSql.single(connection, "SELECT user_id FROM students_versions WHERE id = ? AND version = 1", studentId) {
        it.getObject(1, UUID::class.java)
      },
      "the version 1 row still says who held it before",
    )
  }
}
