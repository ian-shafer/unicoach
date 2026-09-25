package ed.unicoach.family

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.models.NewSupporterStudentLink
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.UserId
import ed.unicoach.util.TokenGenerator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The supporter side of an account (RFC 241): the idempotent mint, the home
 * read, and the one thing the delegation change could break — that accepting an
 * invite still produces exactly one `supporters` row.
 *
 * The [SupporterInviteServiceTest] shape: the shared [CoachingTestDb], a
 * truncation of the four family tables before each test, and raw `users`
 * fixtures.
 */
class SupporterServiceTest {
  private val database = CoachingTestDb.database
  private val connection = CoachingTestDb.connection
  private val sqlSession = CoachingTestDb.sqlSession
  private val service = SupporterService(database)

  @BeforeEach
  fun reset() {
    CoachingTestDb.truncate("supporter_invites", "supporter_student_links", "supporters", "students", "users")
  }

  private fun createUser(name: String = "Test User"): UserId {
    val userId = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, ?, 'hash')").use { stmt ->
      stmt.setObject(1, userId)
      stmt.setString(2, "user-$userId@example.com")
      stmt.setString(3, name)
      stmt.executeUpdate()
    }
    return UserId(userId)
  }

  private fun createStudent(userId: UserId): StudentId {
    val studentId = UUID.randomUUID()
    StudentRowFixture.insert(connection, studentId, userId.value)
    return StudentId(studentId)
  }

  private fun supporterRowCount(): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT count(*) FROM supporters").use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  /**
   * The two answers `POST /api/v1/supporters` turns into `201` and `200`, and
   * the row count that says the second call wrote nothing.
   */
  @Test
  fun `ensure creates once and finds the same row on a second call`(): Unit =
    runBlocking {
      val user = createUser("Parent Jane")

      val first = service.ensure(user).getOrThrow()
      val created = assertIs<EnsureSupporterOutcome.Created>(first, "the first call mints the row")
      assertEquals(emptyList(), created.home.students, "a new supporter has linked nobody")

      val second = service.ensure(user).getOrThrow()
      val found = assertIs<EnsureSupporterOutcome.AlreadyPresent>(second, "the second call must not claim a create")

      assertEquals(created.home.supporter.id, found.home.supporter.id, "both calls answer with the same row")
      assertEquals(1, supporterRowCount(), "an idempotent mint writes one row, not two")
    }

  /**
   * The recovery's REFUSAL arm. `supporters_user_id_unique_idx` is TOTAL, so a
   * closed row holds its `user_id` forever: the insert is refused and the
   * re-read finds no live row. That is a domain ANSWER and not a fault, so it
   * arrives as [EnsureSupporterOutcome.Closed] — the door turns it into `409
   * supporter_closed` — rather than as the index's "already exists" exception,
   * which would have reached the client as an untyped 400 about a row that does
   * not exist. There is still no re-opening path, and a home the caller does
   * not hold is still not one.
   */
  @Test
  fun `ensure refuses a user whose supporter row was closed`(): Unit =
    runBlocking {
      val user = createUser("Closed Parent")
      val supporter =
        assertIs<EnsureSupporterOutcome.Present>(service.ensure(user).getOrThrow())
          .home.supporter
      SupportersDao.delete(sqlSession, supporter.id, supporter.version).getOrThrow()

      assertEquals(
        EnsureSupporterOutcome.Closed,
        service.ensure(user).getOrThrow(),
        "a closed supporter row is refused as an outcome, not as a fault",
      )
      assertNull(service.homeFor(user).getOrThrow(), "and the caller still holds no live row")
    }

  @Test
  fun `homeFor is null for a user who is not a supporter`(): Unit =
    runBlocking {
      val user = createUser("Student Alex")
      createStudent(user)

      assertNull(
        service.homeFor(user).getOrThrow(),
        "a user with no supporters row is an ordinary absence, which the door answers 404",
      )
    }

  /**
   * The home a linked parent lands on: their row, and a roster carrying the
   * student's name and the relationship word.
   */
  @Test
  fun `homeFor returns the roster of a linked supporter`(): Unit =
    runBlocking {
      val parent = createUser("Parent Jane")
      val studentUser = createUser("Ava Okafor")
      val studentId = createStudent(studentUser)
      val supporter =
        assertIs<EnsureSupporterOutcome.Present>(service.ensure(parent).getOrThrow())
          .home.supporter
      val link =
        SupporterStudentLinksDao
          .create(sqlSession, NewSupporterStudentLink(supporter.id, studentId, SupporterRelationship.PARENT))
          .getOrThrow()

      val home = assertNotNull(service.homeFor(parent).getOrThrow())

      assertEquals(supporter.id, home.supporter.id)
      val entry = home.students.single()
      assertEquals(link.id, entry.linkId)
      assertEquals(studentId, entry.studentId)
      assertEquals("Ava Okafor", entry.studentName.value)
      assertEquals(SupporterRelationship.PARENT, entry.relationship)
    }

  /**
   * The delegation change's own risk, asserted end to end: accepting an invite
   * goes through [SupporterService.getOrCreate] now, and if that ever
   * became a SECOND writer the accept would mint a second row — which the TOTAL
   * `supporters_user_id_unique_idx` would refuse, failing the accept.
   */
  @Test
  fun `accepting an invite still produces exactly one supporters row`(): Unit =
    runBlocking {
      val inviteService =
        SupporterInviteService(
          database,
          SupporterInviteConfig(
            inviteUrlBase = "https://app.unicoach.test/invite",
            tokenTtl = Duration.ofDays(14),
          ),
          TokenGenerator(),
          service,
        )
      val studentUser = createUser("Student Alex")
      val studentId = createStudent(studentUser)
      val parent = createUser("Parent Jane")

      val invite = inviteService.create(studentUser, SupporterRelationship.PARENT).getOrThrow()
      assertIs<AcceptInviteOutcome.Success>(inviteService.accept(invite.rawToken, parent).getOrThrow())

      assertEquals(1, supporterRowCount(), "the accept mints exactly one supporters row")
      val supporter = assertNotNull(SupportersDao.findByUserId(sqlSession, parent).getOrThrow())
      // And the row the accept made is the row the home read finds: one writer,
      // one row, both doors.
      val home = assertNotNull(service.homeFor(parent).getOrThrow())
      assertEquals(supporter.id, home.supporter.id)
      assertEquals(studentId, home.students.single().studentId)
      assertEquals(
        "Student Alex",
        home.students
          .single()
          .studentName.value,
      )
    }
}
