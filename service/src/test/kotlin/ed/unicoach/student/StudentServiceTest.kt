package ed.unicoach.student

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.LoginMethod
import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StudentServiceTest {
  companion object {
    private lateinit var connection: Connection
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf", "service.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE students, sessions, users CASCADE")
    }
  }

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val service by lazy { StudentService(database) }

  private fun partialDate(iso: String): PartialDate = (PartialDate.parse(iso) as ValidationResult.Valid).value

  private fun createUser(emailSuffix: String = UUID.randomUUID().toString()): User {
    val email = (EmailAddress.create("svc-$emailSuffix@example.com") as ValidationResult.Valid).value
    val name = (PersonName.create("Svc User") as ValidationResult.Valid).value
    val pwd = (PasswordHash.create("ahash") as ValidationResult.Valid).value
    return UsersDao
      .create(
        sqlSession,
        NewUser(email = email, name = name, displayName = null, passwordHash = pwd),
      ).getOrThrow()
  }

  // --- createStudent ---

  @Test
  fun `createStudent returns Success for each precision`() =
    runTest {
      val u1 = createUser()
      val r1 = service.createStudent(u1.id, "2028").getOrThrow()
      assertTrue(r1 is CreateStudentResult.Success)

      val u2 = createUser()
      val r2 = service.createStudent(u2.id, "2028-06").getOrThrow()
      assertTrue(r2 is CreateStudentResult.Success)

      val u3 = createUser()
      val r3 = service.createStudent(u3.id, "2028-06-15").getOrThrow()
      assertTrue(r3 is CreateStudentResult.Success)
      assertEquals("2028-06-15", r3.student.expectedHighSchoolGraduationDate.toIso())
    }

  @Test
  fun `createStudent returns ValidationFailure for malformed date`() =
    runTest {
      val u = createUser()
      val result = service.createStudent(u.id, "2028-13-40").getOrThrow()
      assertTrue(result is CreateStudentResult.ValidationFailure)
      assertTrue(result.fieldErrors.any { it.field == "expectedHighSchoolGraduationDate" })
    }

  @Test
  fun `createStudent returns AlreadyExists when one is present`() =
    runTest {
      val u = createUser()
      service.createStudent(u.id, "2028").getOrThrow()
      val second = service.createStudent(u.id, "2029").getOrThrow()
      assertTrue(second is CreateStudentResult.AlreadyExists)
    }

  // --- getStudentForUser ---

  @Test
  fun `getStudentForUser returns the student and null when none exists`() =
    runTest {
      val u = createUser()
      assertNull(service.getStudentForUser(u.id).getOrThrow())

      service.createStudent(u.id, "2028").getOrThrow()
      val found = service.getStudentForUser(u.id).getOrThrow()
      assertNotNull(found)
      assertEquals("2028", found.expectedHighSchoolGraduationDate.toIso())
    }

  // --- updateStudent ---

  @Test
  fun `updateStudent returns Success`() =
    runTest {
      val u = createUser()
      val created = (service.createStudent(u.id, "2028").getOrThrow() as CreateStudentResult.Success).student

      val result = service.updateStudent(u.id, created.version, "2029-09").getOrThrow()
      assertTrue(result is UpdateStudentResult.Success)
      assertEquals("2029-09", result.student.expectedHighSchoolGraduationDate.toIso())
    }

  @Test
  fun `updateStudent returns VersionConflict on stale version`() =
    runTest {
      val u = createUser()
      val created = (service.createStudent(u.id, "2028").getOrThrow() as CreateStudentResult.Success).student
      service.updateStudent(u.id, created.version, "2029").getOrThrow()

      val stale = service.updateStudent(u.id, created.version, "2030").getOrThrow()
      assertTrue(stale is UpdateStudentResult.VersionConflict)
    }

  @Test
  fun `updateStudent returns NotFound when user has no student`() =
    runTest {
      val u = createUser()
      val result = service.updateStudent(u.id, 1, "2029").getOrThrow()
      assertTrue(result is UpdateStudentResult.NotFound)
    }

  @Test
  fun `updateStudent returns ValidationFailure on bad date`() =
    runTest {
      val u = createUser()
      val created = (service.createStudent(u.id, "2028").getOrThrow() as CreateStudentResult.Success).student
      val result = service.updateStudent(u.id, created.version, "garbage").getOrThrow()
      assertTrue(result is UpdateStudentResult.ValidationFailure)
    }

  // --- deleteStudentAndAccount ---

  private fun userDeletedAt(userId: UserId): java.sql.Timestamp? {
    connection.prepareStatement("SELECT deleted_at FROM users WHERE id = ?").use { stmt ->
      stmt.setObject(1, userId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getTimestamp("deleted_at")
      }
    }
  }

  private fun studentDeletedAt(userId: UserId): java.sql.Timestamp? {
    connection.prepareStatement("SELECT deleted_at FROM students WHERE user_id = ?").use { stmt ->
      stmt.setObject(1, userId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getTimestamp("deleted_at")
      }
    }
  }

  private fun versionRowCount(
    table: String,
    id: UUID,
  ): Int {
    connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE id = ? AND deleted_at IS NOT NULL").use { stmt ->
      stmt.setObject(1, id)
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  @Test
  fun `deleteStudentAndAccount soft-deletes student and user atomically with identical deleted_at`() =
    runTest {
      val u = createUser()
      val created = (service.createStudent(u.id, "2028").getOrThrow() as CreateStudentResult.Success).student

      val tokenHash = TokenHash(byteArrayOf(1, 2, 3, 4))
      SessionsDao
        .create(
          sqlSession,
          NewSession(
            userId = u.id,
            tokenHash = tokenHash,
            userAgent = null,
            initialIp = null,
            metadata = null,
            expiration = Duration.ofDays(7),
            loginMethod = LoginMethod.PASSWORD,
          ),
        ).getOrThrow()

      val result = service.deleteStudentAndAccount(u.id, tokenHash).getOrThrow()
      assertTrue(result is DeleteStudentResult.Success)

      val userDel = userDeletedAt(u.id)
      val studentDel = studentDeletedAt(u.id)
      assertNotNull(userDel)
      assertNotNull(studentDel)
      assertEquals(userDel, studentDel, "User and student must share an identical deleted_at")

      // getCurrentUser via AuthService would return null; verify the session is revoked.
      val sessionLookup = SessionsDao.findByTokenHash(sqlSession, tokenHash)
      assertTrue(sessionLookup.isFailure, "Session should be revoked after delete")

      // Both version tables logged the deletion.
      assertTrue(versionRowCount("students_versions", created.id.value) >= 1)
      assertTrue(versionRowCount("users_versions", u.id.value) >= 1)
    }

  @Test
  fun `deleteStudentAndAccount is all-or-nothing and rolls back on a later failure`() =
    runTest {
      val u = createUser()
      service.createStudent(u.id, "2028").getOrThrow()

      // No session is created for this token, so the trailing revokeByTokenHash
      // raises NotFound and aborts the transaction AFTER the student and user
      // soft-deletes have been staged. The whole unit must roll back.
      val tokenHash = TokenHash(byteArrayOf(9, 9, 9))

      val result = service.deleteStudentAndAccount(u.id, tokenHash)
      assertTrue(result.isFailure, "Delete should fail when the session revoke cannot proceed")

      // Rollback: neither the student nor the user row may be soft-deleted.
      assertNull(studentDeletedAt(u.id), "Student must remain active after rollback")
      assertNull(userDeletedAt(u.id), "User must remain active after rollback")
    }

  @Test
  fun `deleteStudentAndAccount returns NotFound when user has no student`() =
    runTest {
      val u = createUser()
      val tokenHash = TokenHash(byteArrayOf(7, 7, 7))
      val result = service.deleteStudentAndAccount(u.id, tokenHash).getOrThrow()
      assertTrue(result is DeleteStudentResult.NotFound)
    }
}
