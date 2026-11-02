package ed.unicoach.db

import ed.unicoach.db.dao.RawSql
import ed.unicoach.db.dao.StudentRowFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `soft_delete_entity`, the function that erases the rows of one table that
 * match one key, held against the real schema (RFC 266 §2, slice
 * deletion/03/the-walk, brief 0002 D3).
 *
 * The column set comes from the catalog, never a list, so each case here is a
 * different SHAPE of table: one with pii.* columns and `deleted_at` (claims),
 * one that also carries `enforce_versioning` and a history trigger (students),
 * a history table erased by the entity id it copies (students_versions), and
 * one with nothing to erase (subscriptions). Each also proves the second call
 * writes nothing.
 *
 * Every test runs in one transaction that is rolled back.
 */
class SoftDeleteEntityTest {
  companion object {
    private lateinit var connection: Connection

    /** 22023, invalid_parameter_value: what both of soft_delete_entity's refusals raise. */
    private const val INVALID_PARAMETER = "22023"

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

  private fun user(): UUID {
    val id = UUID.randomUUID()
    RawSql.execute(
      connection,
      "INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, 'Erase Me', 'ahash')",
      id,
      "soft-delete-$id@test.com",
    )
    return id
  }

  private fun student(): UUID {
    val id = UUID.randomUUID()
    StudentRowFixture.insert(connection, id, user())
    return id
  }

  private fun softDelete(
    table: String,
    keyColumn: String,
    key: Any,
  ): Long =
    RawSql.single(connection, "SELECT soft_delete_entity(?::regclass, ?, ?)", table, keyColumn, key.toString()) {
      it.getLong(1)
    }

  private fun count(
    sql: String,
    vararg args: Any?,
  ): Long = RawSql.single(connection, sql, *args) { it.getLong(1) }

  @Test
  fun `a claim loses its statement and gains deleted_at, and nothing else moves`() {
    val studentId = student()
    val claimId =
      RawSql.single(
        connection,
        "INSERT INTO claims (student_id, origin, kind, subject, topic, visibility, statement) " +
          "VALUES (?, 'student_stated', 'goal', 'student', 'academics', 'student_visible', 'I want to study the sea') " +
          "RETURNING id",
        studentId,
      ) { it.getObject(1, UUID::class.java) }
    val createdBefore =
      RawSql.single(connection, "SELECT created_at FROM claims WHERE id = ?", claimId) {
        it.getTimestamp(1).toInstant()
      }

    assertEquals(1L, softDelete("claims", "id", claimId))

    RawSql.single(connection, "SELECT statement, deleted_at, student_id, created_at FROM claims WHERE id = ?", claimId) { rs ->
      assertNull(rs.getString("statement"), "the pii.* statement must be NULL")
      assertNotNull(rs.getTimestamp("deleted_at"), "deleted_at must be stamped")
      assertEquals(studentId, rs.getObject("student_id", UUID::class.java), "the key is the walk's to move, not this function's")
      assertEquals(createdBefore, rs.getTimestamp("created_at").toInstant(), "created_at must be untouched")
    }

    assertEquals(0L, softDelete("claims", "id", claimId), "an erased claim must be skipped on a second call")
  }

  @Test
  fun `a student row bumps its version and writes one history row`() {
    val studentId = student()
    val versionBefore = count("SELECT version FROM students WHERE id = ?", studentId)
    val historyBefore = count("SELECT count(*) FROM students_versions WHERE id = ?", studentId)

    assertEquals(1L, softDelete("students", "id", studentId))

    RawSql.single(
      connection,
      "SELECT version, date_of_birth, expected_high_school_graduation_on, deleted_at FROM students WHERE id = ?",
      studentId,
    ) { rs ->
      assertEquals(versionBefore + 1, rs.getLong("version"), "enforce_versioning requires the bump")
      assertNull(rs.getDate("date_of_birth"))
      assertNull(rs.getDate("expected_high_school_graduation_on"))
      assertNotNull(rs.getTimestamp("deleted_at"))
    }
    assertEquals(historyBefore + 1, count("SELECT count(*) FROM students_versions WHERE id = ?", studentId))

    assertEquals(0L, softDelete("students", "id", studentId), "an erased student must be skipped on a second call")
    assertEquals(versionBefore + 1, count("SELECT version FROM students WHERE id = ?", studentId), "no second bump")
    assertEquals(historyBefore + 1, count("SELECT count(*) FROM students_versions WHERE id = ?", studentId), "no second history row")
  }

  @Test
  fun `a history table erased by the entity id erases every version`() {
    val studentId = student()
    StudentRowFixture.updateGraduation(connection, studentId, StudentRowFixture.graduationYear(2029))
    val versions = count("SELECT count(*) FROM students_versions WHERE id = ?", studentId)
    assertTrue(versions >= 2, "the fixture must hold more than one version; it holds $versions")

    assertEquals(versions, softDelete("students_versions", "id", studentId))

    assertEquals(
      0L,
      count(
        "SELECT count(*) FROM students_versions WHERE id = ? " +
          "AND (date_of_birth IS NOT NULL OR expected_high_school_graduation_on IS NOT NULL OR deleted_at IS NULL)",
        studentId,
      ),
      "every version must be erased",
    )
    assertEquals(0L, softDelete("students_versions", "id", studentId), "an erased history must be skipped on a second call")
    assertEquals(versions, count("SELECT count(*) FROM students_versions WHERE id = ?", studentId), "a history table writes no history")
  }

  private fun refusal(call: () -> Unit): PSQLException {
    val savepoint = connection.setSavepoint()
    try {
      call()
    } catch (e: SQLException) {
      connection.rollback(savepoint)
      return e as? PSQLException ?: fail("expected a PSQLException, got [$e]")
    }
    connection.releaseSavepoint(savepoint)
    fail("soft_delete_entity accepted the target; it must be refused")
  }

  @Test
  fun `a target outside public or a column that is not a key is refused`() {
    val studentId = student()
    val claimId =
      RawSql.single(
        connection,
        "INSERT INTO claims (student_id, origin, kind, subject, topic, visibility, statement) " +
          "VALUES (?, 'student_stated', 'goal', 'student', 'academics', 'student_visible', 'I want to study the sea') " +
          "RETURNING id",
        studentId,
      ) { it.getObject(1, UUID::class.java) }

    val outside = refusal { softDelete("pg_catalog.pg_class", "oid", "1259") }
    assertEquals(INVALID_PARAMETER, outside.sqlState)

    val notKey = refusal { softDelete("claims", "statement", "I want to study the sea") }
    assertEquals(INVALID_PARAMETER, notKey.sqlState)
    assertEquals(
      1L,
      count("SELECT count(*) FROM claims WHERE id = ? AND statement IS NOT NULL AND deleted_at IS NULL", claimId),
      "a refused call writes nothing",
    )

    assertEquals(1L, softDelete("claims", "student_id", studentId), "a single-column foreign key is a key")
  }

  @Test
  fun `a subscription has nothing to erase and is never changed`() {
    val userId = user()
    val subscriptionId =
      RawSql.single(
        connection,
        "INSERT INTO subscriptions (user_id, original_transaction_id, product_id, status, period_start, period_end) " +
          "VALUES (?, ?, 'p', 'active', NOW(), NOW() + INTERVAL '30 days') RETURNING id",
        userId,
        "soft-delete-${UUID.randomUUID()}",
      ) { it.getObject(1, UUID::class.java) }
    val updatedBefore: Instant =
      RawSql.single(connection, "SELECT row_updated_at FROM subscriptions WHERE id = ?", subscriptionId) {
        it.getTimestamp(1).toInstant()
      }
    val historyBefore = count("SELECT count(*) FROM subscriptions_versions WHERE id = ?", subscriptionId)

    assertEquals(0L, softDelete("subscriptions", "id", subscriptionId))
    assertEquals(0L, softDelete("subscriptions", "id", subscriptionId))

    assertEquals(1L, count("SELECT version FROM subscriptions WHERE id = ?", subscriptionId), "the subscription outlives the person (D6)")
    assertEquals(
      updatedBefore,
      RawSql.single(connection, "SELECT row_updated_at FROM subscriptions WHERE id = ?", subscriptionId) { it.getTimestamp(1).toInstant() },
    )
    assertEquals(historyBefore, count("SELECT count(*) FROM subscriptions_versions WHERE id = ?", subscriptionId))
  }
}
