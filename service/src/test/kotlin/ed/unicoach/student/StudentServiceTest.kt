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
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StudentServiceTest {
  companion object {
    /**
     * The pinned "today" the age-floor tests are judged against, and a 13+ date
     * of birth every other test can pass without thinking about the floor. Both
     * are LITERALS, never `now()` arithmetic: a fixture that drifts with the
     * wall clock makes a boundary test lie about which side of the floor it is on.
     */
    private val TODAY: LocalDate = LocalDate.of(2026, 3, 15)
    private const val ADULT_DOB = "2009-07-10"

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

  /**
   * A service whose "today" is pinned to [TODAY]. Every age-floor assertion
   * below is written against this clock, so none of them changes its answer
   * tomorrow.
   */
  private val pinnedService by lazy {
    StudentService(database, Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC))
  }

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
      val r1 = service.createStudent(u1.id, "2028", ADULT_DOB).getOrThrow()
      assertTrue(r1 is CreateStudentResult.Success)

      val u2 = createUser()
      val r2 = service.createStudent(u2.id, "2028-06", ADULT_DOB).getOrThrow()
      assertTrue(r2 is CreateStudentResult.Success)

      val u3 = createUser()
      val r3 = service.createStudent(u3.id, "2028-06-15", ADULT_DOB).getOrThrow()
      assertTrue(r3 is CreateStudentResult.Success)
      assertEquals("2028-06-15", r3.student.expectedHighSchoolGraduationDate.toIso())
    }

  @Test
  fun `createStudent returns ValidationFailure for malformed date`() =
    runTest {
      val u = createUser()
      val result = service.createStudent(u.id, "2028-13-40", ADULT_DOB).getOrThrow()
      assertTrue(result is CreateStudentResult.ValidationFailure)
      assertTrue(result.fieldErrors.any { it.field == "expectedHighSchoolGraduationDate" })
    }

  @Test
  fun `createStudent returns AlreadyExists when one is present`() =
    runTest {
      val u = createUser()
      service.createStudent(u.id, "2028", ADULT_DOB).getOrThrow()
      val second = service.createStudent(u.id, "2029", ADULT_DOB).getOrThrow()
      assertTrue(second is CreateStudentResult.AlreadyExists)
    }

  // --- createStudent: the 13+ age floor (RFC 222) ---

  private fun studentRowCount(): Int {
    connection.prepareStatement("SELECT COUNT(*) FROM students").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  @Test
  fun `createStudent accepts a student whose thirteenth birthday is today`() =
    runTest {
      val u = createUser()
      // 2013-03-15 turns thirteen on 2026-03-15, the pinned today. The floor is
      // `<=`, so this is the accepted edge, pinned explicitly.
      val result = pinnedService.createStudent(u.id, "2028", "2013-03-15").getOrThrow()
      assertTrue(result is CreateStudentResult.Success, "Expected Success on the thirteenth birthday, got $result")
      assertEquals(LocalDate.of(2013, 3, 15), result.student.dateOfBirth)
    }

  @Test
  fun `createStudent refuses a student whose thirteenth birthday is tomorrow and writes no row`() =
    runTest {
      val u = createUser()
      val result = pinnedService.createStudent(u.id, "2028", "2013-03-16").getOrThrow()
      assertTrue(result is CreateStudentResult.UnderMinimumAge, "Expected UnderMinimumAge, got $result")
      assertEquals(0, studentRowCount(), "A refused creation must write no students row")
    }

  @Test
  fun `createStudent accepts a student one day past the thirteenth birthday`() =
    runTest {
      val u = createUser()
      val result = pinnedService.createStudent(u.id, "2028", "2013-03-14").getOrThrow()
      assertTrue(result is CreateStudentResult.Success, "Expected Success one day past thirteen, got $result")
    }

  /**
   * A service whose "today" is pinned to [today]. Literal dates only, never
   * wall-clock arithmetic: a boundary this narrow is only a boundary if the
   * date it is judged against cannot move.
   */
  private fun serviceAt(today: LocalDate): StudentService =
    StudentService(database, Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC))

  @Test
  fun `createStudent judges a 29 February birthdate at the two dates minusYears clamping decides`() =
    runTest {
      // A 2012-02-29 student turns thirteen in 2025, which has no 29 February,
      // so the floor is whatever `LocalDate.minusYears` clamps to. On
      // 2025-03-01 the floor is 2012-03-01 and the leap day is before it:
      // accepted.
      val accepted = serviceAt(LocalDate.of(2025, 3, 1)).createStudent(createUser().id, "2028", "2012-02-29").getOrThrow()
      assertTrue(accepted is CreateStudentResult.Success, "Expected Success on 2025-03-01, got $accepted")
      assertEquals(LocalDate.of(2012, 2, 29), accepted.student.dateOfBirth)

      // One day earlier the floor is 2012-02-28, which the leap day is AFTER:
      // refused. These two dates are the only pair that tells the clamping
      // apart from a date comparison that ignores it.
      val refused = serviceAt(LocalDate.of(2025, 2, 28)).createStudent(createUser().id, "2028", "2012-02-29").getOrThrow()
      assertTrue(refused is CreateStudentResult.UnderMinimumAge, "Expected UnderMinimumAge on 2025-02-28, got $refused")
    }

  @Test
  fun `createStudent returns ValidationFailure naming dateOfBirth for a malformed empty future or implausible date`() =
    runTest {
      for (bad in listOf("10-07-2009", "2009-7-10", "not-a-date", "", "2026-03-16", "1880-01-01")) {
        val u = createUser()
        val result = pinnedService.createStudent(u.id, "2028", bad).getOrThrow()
        assertTrue(result is CreateStudentResult.ValidationFailure, "Expected ValidationFailure for [$bad], got $result")
        assertTrue(
          result.fieldErrors.any { it.field == "dateOfBirth" },
          "Expected a dateOfBirth field error for [$bad], got ${result.fieldErrors}",
        )
      }
      assertEquals(0, studentRowCount(), "No refused creation may write a students row")
    }

  @Test
  fun `createStudent refuses an expanded-year date of birth the published pattern rejects`() =
    runTest {
      // `LocalDate.parse` uses SignStyle.EXCEEDS_PAD, so a signed expanded year
      // parses: "+02009-07-10" would otherwise be accepted as a 13+ date the
      // openapi pattern ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ forbids. The shape check
      // runs before the parse, so both forms are malformed input.
      for (bad in listOf("+02009-07-10", "+02026-07-10")) {
        val u = createUser()
        val result = pinnedService.createStudent(u.id, "2028", bad).getOrThrow()
        assertTrue(result is CreateStudentResult.ValidationFailure, "Expected ValidationFailure for [$bad], got $result")
        assertTrue(
          result.fieldErrors.any { it.field == "dateOfBirth" },
          "Expected a dateOfBirth field error for [$bad], got ${result.fieldErrors}",
        )
      }
      assertEquals(0, studentRowCount(), "A refused creation must write no students row")
    }

  @Test
  fun `createStudent reports both bad fields from one request`() =
    runTest {
      val u = createUser()
      // Both answers are judged before either is reported (precedent:
      // RegistrationValidator), so one round trip names both fields.
      val result = pinnedService.createStudent(u.id, "2028-13-40", "10-07-2009").getOrThrow()
      assertTrue(result is CreateStudentResult.ValidationFailure, "Expected ValidationFailure, got $result")
      val fields = result.fieldErrors.map { it.field }
      assertTrue(fields.contains("expectedHighSchoolGraduationDate"), "Expected the graduation field, got $fields")
      assertTrue(fields.contains("dateOfBirth"), "Expected the dateOfBirth field, got $fields")
    }

  @Test
  fun `createStudent returns ValidationFailure naming dateOfBirth when the field is absent`() =
    runTest {
      val u = createUser()
      // The old-installed-client case: the request carried no field at all.
      val result = pinnedService.createStudent(u.id, "2028", null).getOrThrow()
      assertTrue(result is CreateStudentResult.ValidationFailure, "Expected ValidationFailure, got $result")
      assertTrue(result.fieldErrors.any { it.field == "dateOfBirth" })
    }

  // --- getStudentForUser ---

  @Test
  fun `getStudentForUser returns the student and null when none exists`() =
    runTest {
      val u = createUser()
      assertNull(service.getStudentForUser(u.id).getOrThrow())

      service.createStudent(u.id, "2028", ADULT_DOB).getOrThrow()
      val found = service.getStudentForUser(u.id).getOrThrow()
      assertNotNull(found)
      assertEquals("2028", found.expectedHighSchoolGraduationDate.toIso())
    }

  // --- updateStudent ---

  @Test
  fun `updateStudent returns Success`() =
    runTest {
      val u = createUser()
      val created = (service.createStudent(u.id, "2028", ADULT_DOB).getOrThrow() as CreateStudentResult.Success).student

      val result = service.updateStudent(u.id, created.version, "2029-09").getOrThrow()
      assertTrue(result is UpdateStudentResult.Success)
      assertEquals("2029-09", result.student.expectedHighSchoolGraduationDate.toIso())
    }

  @Test
  fun `updateStudent returns VersionConflict on stale version`() =
    runTest {
      val u = createUser()
      val created = (service.createStudent(u.id, "2028", ADULT_DOB).getOrThrow() as CreateStudentResult.Success).student
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
      val created = (service.createStudent(u.id, "2028", ADULT_DOB).getOrThrow() as CreateStudentResult.Success).student
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
      val created = (service.createStudent(u.id, "2028", ADULT_DOB).getOrThrow() as CreateStudentResult.Success).student

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
      service.createStudent(u.id, "2028", ADULT_DOB).getOrThrow()

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
