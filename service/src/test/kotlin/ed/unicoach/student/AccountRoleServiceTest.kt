package ed.unicoach.student

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.db.dao.RawSql
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.UserId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * RFC 268's one read of "has this account onboarded", against the real
 * database: neither row, either one, and both — where the student wins.
 */
class AccountRoleServiceTest {
  private val connection = CoachingTestDb.connection
  private val sqlSession = CoachingTestDb.sqlSession
  private val service = AccountRoleService(CoachingTestDb.database)

  @BeforeEach
  fun reset() {
    CoachingTestDb.truncate("supporter_student_links", "supporters", "students", "users")
  }

  private fun createUser(): UserId {
    val userId = UUID.randomUUID()
    RawSql.execute(
      connection,
      "INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, 'Role Test', 'hash')",
      userId,
      "role-$userId@example.com",
    )
    return UserId(userId)
  }

  private fun createStudent(userId: UserId): StudentId {
    val studentId = UUID.randomUUID()
    StudentRowFixture.insert(connection, studentId, userId.value)
    return StudentId(studentId)
  }

  private fun createSupporter(userId: UserId) = SupportersDao.create(sqlSession, NewSupporter(userId)).getOrThrow()

  @Test
  fun `an account with neither row has no role`() {
    runBlocking {
      assertEquals(AccountRole.None, service.roleOf(createUser()).getOrThrow())
    }
  }

  @Test
  fun `an account with a student row is a student`() {
    runBlocking {
      val user = createUser()
      val studentId = createStudent(user)

      val role = assertIs<AccountRole.Student>(service.roleOf(user).getOrThrow())
      assertEquals(studentId, role.student.id)
    }
  }

  @Test
  fun `an account with a supporter row is a supporter`() {
    runBlocking {
      val user = createUser()
      val supporter = createSupporter(user)

      val role = assertIs<AccountRole.Supporter>(service.roleOf(user).getOrThrow())
      assertEquals(supporter.id, role.supporter.id)
    }
  }

  @Test
  fun `an account with both rows is a student`() {
    runBlocking {
      val user = createUser()
      val studentId = createStudent(user)
      createSupporter(user)

      val role = assertIs<AccountRole.Student>(service.roleOf(user).getOrThrow(), "the student row wins, as on iOS")
      assertEquals(studentId, role.student.id)
    }
  }

  @Test
  fun `a closed supporter row is no role`() {
    runBlocking {
      val user = createUser()
      val supporter = createSupporter(user)
      SupportersDao.delete(sqlSession, supporter.id, supporter.version).getOrThrow()

      assertEquals(AccountRole.None, service.roleOf(user).getOrThrow())
    }
  }
}
