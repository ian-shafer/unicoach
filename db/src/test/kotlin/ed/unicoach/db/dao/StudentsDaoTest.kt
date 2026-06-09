package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.models.NewStudent
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.UserId
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
import kotlin.test.assertTrue

class StudentsDaoTest {
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
      if (::connection.isInitialized && !connection.isClosed) {
        connection.close()
      }
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE students, users CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun createUser(): UserId {
    val rawId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash) VALUES ('$rawId', 'stud-$rawId@test.com', 'Stud User', 'ahash')",
      )
    }
    return UserId(rawId)
  }

  private fun partialDate(iso: String): PartialDate = (PartialDate.parse(iso) as ValidationResult.Valid).value

  private fun countVersions(id: StudentId): Int {
    connection.prepareStatement("SELECT COUNT(*) FROM students_versions WHERE id = ?").use { stmt ->
      stmt.setObject(1, id.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  @Test
  fun `create persists with version 1 and round-trips all three precisions`() {
    val u1 = createUser()
    val yearOnly = StudentsDao.create(session, NewStudent(u1, partialDate("2028"))).getOrThrow()
    assertEquals(1, yearOnly.versionId.value)
    assertTrue(yearOnly.expectedHighSchoolGraduationDate is PartialDate.YearOnly)

    val u2 = createUser()
    val yearMonth = StudentsDao.create(session, NewStudent(u2, partialDate("2028-06"))).getOrThrow()
    assertTrue(yearMonth.expectedHighSchoolGraduationDate is PartialDate.YearAndMonth)

    val u3 = createUser()
    val full = StudentsDao.create(session, NewStudent(u3, partialDate("2028-06-15"))).getOrThrow()
    assertTrue(full.expectedHighSchoolGraduationDate is PartialDate.FullDate)
    assertEquals("2028-06-15", full.expectedHighSchoolGraduationDate.toIso())
  }

  @Test
  fun `create rejects a second active student for the same user`() {
    val u = createUser()
    assertTrue(StudentsDao.create(session, NewStudent(u, partialDate("2028"))).isSuccess)

    val second = StudentsDao.create(session, NewStudent(u, partialDate("2029")))
    assertTrue(
      second.isFailure && second.exceptionOrNull() is StudentAlreadyExistsException,
      "Expected StudentAlreadyExistsException, got $second",
    )
  }

  @Test
  fun `create rejects a second student even after the first is soft-deleted`() {
    val u = createUser()
    val first = StudentsDao.create(session, NewStudent(u, partialDate("2028"))).getOrThrow()
    StudentsDao.delete(session, first.id, first.versionId).getOrThrow()

    val second = StudentsDao.create(session, NewStudent(u, partialDate("2029")))
    assertTrue(
      second.isFailure && second.exceptionOrNull() is StudentAlreadyExistsException,
      "Expected StudentAlreadyExistsException even across soft-delete, got $second",
    )
  }

  @Test
  fun `create rejects a user_id not present in users`() {
    val orphan = UserId(UUID.randomUUID())
    val result = StudentsDao.create(session, NewStudent(orphan, partialDate("2028")))
    assertTrue(
      result.isFailure && result.exceptionOrNull() is NotFoundException,
      "Expected NotFoundException for orphan FK, got $result",
    )
  }

  @Test
  fun `constraint backstops reject month out of range orphan day and impossible date`() {
    val u = createUser()

    fun insertRaw(
      year: Int,
      month: Int?,
      day: Int?,
    ): java.sql.SQLException? =
      try {
        connection
          .prepareStatement(
            """
            INSERT INTO students (user_id, expected_high_school_graduation_year,
              expected_high_school_graduation_month, expected_high_school_graduation_day)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, u.value)
            stmt.setInt(2, year)
            if (month != null) stmt.setInt(3, month) else stmt.setNull(3, java.sql.Types.SMALLINT)
            if (day != null) stmt.setInt(4, day) else stmt.setNull(4, java.sql.Types.SMALLINT)
            stmt.executeUpdate()
          }
        null
      } catch (e: java.sql.SQLException) {
        connection.rollback()
        e
      }

    connection.autoCommit = false
    try {
      assertNotNull(insertRaw(2028, 13, null), "month 13 should be rejected")
      assertNotNull(insertRaw(2028, null, 15), "orphan day should be rejected")
      assertNotNull(insertRaw(2028, 2, 31), "Feb 31 should be rejected")
    } finally {
      connection.rollback()
      connection.autoCommit = true
    }
  }

  @Test
  fun `findById and findByUserId exclude soft-deleted rows unless includeDeleted`() {
    val u = createUser()
    val created = StudentsDao.create(session, NewStudent(u, partialDate("2028"))).getOrThrow()

    assertTrue(StudentsDao.findById(session, created.id).isSuccess)
    assertTrue(StudentsDao.findByUserId(session, u).isSuccess)

    StudentsDao.delete(session, created.id, created.versionId).getOrThrow()

    assertTrue(StudentsDao.findById(session, created.id).exceptionOrNull() is NotFoundException)
    assertTrue(StudentsDao.findByUserId(session, u).exceptionOrNull() is NotFoundException)

    assertTrue(StudentsDao.findById(session, created.id, includeDeleted = true).isSuccess)
    assertTrue(StudentsDao.findByUserId(session, u, includeDeleted = true).isSuccess)
  }

  @Test
  fun `update with correct version succeeds bumps timestamps and logs a version`() {
    val u = createUser()
    val created = StudentsDao.create(session, NewStudent(u, partialDate("2028"))).getOrThrow()
    assertEquals(1, countVersions(created.id))

    val updated =
      StudentsDao
        .update(
          session,
          created.copy(expectedHighSchoolGraduationDate = partialDate("2029-09")),
        ).getOrThrow()

    assertEquals(2, updated.versionId.value)
    assertEquals("2029-09", updated.expectedHighSchoolGraduationDate.toIso())
    assertTrue(updated.updatedAt >= created.updatedAt)
    assertTrue(updated.rowUpdatedAt >= created.rowUpdatedAt)
    assertEquals(2, countVersions(created.id))
  }

  @Test
  fun `update with stale version raises ConcurrentModification`() {
    val u = createUser()
    val created = StudentsDao.create(session, NewStudent(u, partialDate("2028"))).getOrThrow()

    StudentsDao.update(session, created.copy(expectedHighSchoolGraduationDate = partialDate("2029"))).getOrThrow()

    val staleResult =
      StudentsDao.update(session, created.copy(expectedHighSchoolGraduationDate = partialDate("2030")))
    assertTrue(
      staleResult.isFailure && staleResult.exceptionOrNull() is ConcurrentModificationException,
      "Expected ConcurrentModificationException, got $staleResult",
    )
  }

  @Test
  fun `immutable-field updates are rejected`() {
    val u = createUser()
    val created = StudentsDao.create(session, NewStudent(u, partialDate("2028"))).getOrThrow()

    fun expectImmutableFailure(
      column: String,
      value: String,
    ) {
      connection.autoCommit = false
      try {
        connection
          .prepareStatement(
            "UPDATE students SET version = version + 1, $column = $value WHERE id = ?",
          ).use { stmt ->
            stmt.setObject(1, created.id.value)
            var threw = false
            try {
              stmt.executeUpdate()
            } catch (e: java.sql.SQLException) {
              threw = true
            }
            assertTrue(threw, "Updating immutable column $column should be rejected")
          }
      } finally {
        connection.rollback()
        connection.autoCommit = true
      }
    }

    expectImmutableFailure("id", "'${UUID.randomUUID()}'")
    expectImmutableFailure("created_at", "NOW() + INTERVAL '1 day'")
    expectImmutableFailure("row_created_at", "NOW() + INTERVAL '1 day'")
  }

  @Test
  fun `physical delete is rejected by the trigger`() {
    val u = createUser()
    val created = StudentsDao.create(session, NewStudent(u, partialDate("2028"))).getOrThrow()

    connection.autoCommit = false
    try {
      var threw = false
      try {
        connection.prepareStatement("DELETE FROM students WHERE id = ?").use { stmt ->
          stmt.setObject(1, created.id.value)
          stmt.executeUpdate()
        }
      } catch (e: java.sql.SQLException) {
        threw = true
      }
      assertTrue(threw, "Physical DELETE should be blocked by the trigger")
    } finally {
      connection.rollback()
      connection.autoCommit = true
    }
  }

  @Test
  fun `delete soft-deletes bumps version and logs a version`() {
    val u = createUser()
    val created = StudentsDao.create(session, NewStudent(u, partialDate("2028"))).getOrThrow()

    val deleted = StudentsDao.delete(session, created.id, created.versionId).getOrThrow()
    assertNotNull(deleted.deletedAt)
    assertEquals(2, deleted.versionId.value)
    assertEquals(2, countVersions(created.id))

    val refetch = StudentsDao.findById(session, created.id)
    assertTrue(refetch.exceptionOrNull() is NotFoundException)
  }

  @Test
  fun `timestamp bypass bumps row_updated_at but not updated_at`() {
    val u = createUser()
    val created = StudentsDao.create(session, NewStudent(u, partialDate("2028"))).getOrThrow()

    connection.autoCommit = false
    try {
      connection.createStatement().use { it.execute("SET LOCAL unicoach.bypass_logical_timestamp = 'true'") }
      val bypassSession =
        object : SqlSession {
          override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
        }
      val updated =
        StudentsDao
          .update(
            bypassSession,
            created.copy(expectedHighSchoolGraduationDate = partialDate("2030")),
          ).getOrThrow()

      assertEquals(created.updatedAt, updated.updatedAt, "updated_at should be unchanged under bypass")
      assertTrue(updated.rowUpdatedAt >= created.rowUpdatedAt, "row_updated_at should still advance")
      connection.commit()
    } finally {
      connection.autoCommit = true
    }
  }

  @Test
  fun `findByIdForUpdate returns row and locks it`() {
    val u = createUser()
    val created = StudentsDao.create(session, NewStudent(u, partialDate("2028"))).getOrThrow()
    val result = StudentsDao.findByIdForUpdate(session, created.id)
    assertTrue(result.isSuccess)
    assertEquals(created.id, result.getOrThrow().id)

    val missing = StudentsDao.findByIdForUpdate(session, StudentId(UUID.randomUUID()))
    assertTrue(missing.exceptionOrNull() is NotFoundException)
  }
}
