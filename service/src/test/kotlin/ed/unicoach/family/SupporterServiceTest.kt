package ed.unicoach.family

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.db.dao.CostReportSharesDao
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.models.NewCostReportShare
import ed.unicoach.db.models.NewSupporterStudentLink
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.Supporter
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.SupporterStudentLink
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UnlinkOutcome
import ed.unicoach.db.models.UserId
import ed.unicoach.util.TokenGenerator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    CoachingTestDb.truncate(
      "cost_report_shares",
      "convos",
      "supporter_invites",
      "supporter_student_links",
      "supporters",
      "students",
      "users",
    )
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
      val entry = home.students.single().student
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
      val entry = home.students.single().student
      assertEquals(studentId, entry.studentId)
      assertEquals("Student Alex", entry.studentName.value)
    }

  // --- Both ends of a link (RFC 249) ----------------------------------------

  private val inviteService =
    SupporterInviteService(
      database,
      SupporterInviteConfig(inviteUrlBase = "https://app.unicoach.test/invite", tokenTtl = Duration.ofDays(14)),
      TokenGenerator(),
      service,
    )

  private fun supporterOf(userId: UserId): Supporter =
    runBlocking { assertIs<EnsureSupporterOutcome.Present>(service.ensure(userId).getOrThrow()).home.supporter }

  private fun link(
    supporter: Supporter,
    studentId: StudentId,
  ): SupporterStudentLink =
    SupporterStudentLinksDao
      .create(sqlSession, NewSupporterStudentLink(supporter.id, studentId, SupporterRelationship.PARENT))
      .getOrThrow()

  private fun insertLiveShare(studentId: StudentId) {
    val id = CostReportSharesDao.nextId(sqlSession).getOrThrow()
    CostReportSharesDao
      .create(sqlSession, NewCostReportShare(id, studentId, TokenHash.fromRawToken("raw-$id")))
      .getOrThrow()
  }

  private fun liveLinkCount(studentId: StudentId): Int = SupporterStudentLinksDao.listForStudent(sqlSession, studentId).getOrThrow().size

  /** Whether every listed table's row for [id] is still live — the "nothing else changed" claim. */
  private fun isLive(
    table: String,
    id: UUID,
  ): Boolean =
    connection.prepareStatement("SELECT deleted_at IS NULL FROM $table WHERE id = ?").use { stmt ->
      stmt.setObject(1, id)
      stmt.executeQuery().use { rs ->
        assertTrue(rs.next(), "the [$table] row [$id] must still be there")
        rs.getBoolean(1)
      }
    }

  @Test
  fun `supportersOf names the adults in the student's voice and says whether the cost report link is live`(): Unit =
    runBlocking {
      val studentId = createStudent(createUser("Ava Okafor"))
      val jane = link(supporterOf(createUser("Jane Okafor")), studentId)

      val before = service.supportersOf(studentId).getOrThrow()
      assertEquals(listOf(jane.id to "Jane Okafor"), before.supporters.map { it.linkId to it.name.value })
      assertEquals(SupporterCeiling.toStudent, before.ceiling, "the student reads the ceiling in their own voice")
      assertFalse(before.costReportLinkLive, "no share minted, so the cost-report sentence must not appear")

      insertLiveShare(studentId)

      assertTrue(service.supportersOf(studentId).getOrThrow().costReportLinkLive)
    }

  @Test
  fun `a student disconnects their own adult, and nobody else's`(): Unit =
    runBlocking {
      val studentId = createStudent(createUser("Ava Okafor"))
      val otherStudent = createStudent(createUser("Noor Haddad"))
      val jane = supporterOf(createUser("Jane Okafor"))
      val mine = link(jane, studentId)
      val theirs = link(jane, otherStudent)

      assertEquals(UnlinkOutcome.NotFound, service.disconnectAsStudent(studentId, theirs.id).getOrThrow())
      assertEquals(1, liveLinkCount(otherStudent), "another student's link is not this student's to end")

      assertEquals(mine.id, assertIs<UnlinkOutcome.Unlinked>(service.disconnectAsStudent(studentId, mine.id).getOrThrow()).link.id)
      assertEquals(0, liveLinkCount(studentId))
      assertEquals(
        UnlinkOutcome.NotFound,
        service.disconnectAsStudent(studentId, mine.id).getOrThrow(),
        "a second disconnect has nothing left to end",
      )
    }

  @Test
  fun `a supporter disconnects themselves, and a caller with no supporter row holds nothing`(): Unit =
    runBlocking {
      val studentId = createStudent(createUser("Ava Okafor"))
      val janeUser = createUser("Jane Okafor")
      val mine = link(supporterOf(janeUser), studentId)
      val samsLink = link(supporterOf(createUser("Sam Okafor")), studentId)

      assertEquals(UnlinkOutcome.NotFound, service.disconnectAsSupporter(janeUser, samsLink.id).getOrThrow())
      assertEquals(
        UnlinkOutcome.NotFound,
        service.disconnectAsSupporter(createUser("Not A Supporter"), mine.id).getOrThrow(),
        "no supporters row, so no link to end",
      )
      assertEquals(2, liveLinkCount(studentId), "neither refusal severed anything")

      assertEquals(mine.id, assertIs<UnlinkOutcome.Unlinked>(service.disconnectAsSupporter(janeUser, mine.id).getOrThrow()).link.id)
      assertEquals(listOf(samsLink.id), SupporterStudentLinksDao.listForStudent(sqlSession, studentId).getOrThrow().map { it.id })
    }

  /**
   * A disconnect ends ONE thing. Both accounts, the student's conversations, the
   * supporter's own row, the Family Cost Report share (X12) and an unused invite
   * link the student minted earlier (D2: sharing the link was the consent) all
   * survive it.
   */
  @Test
  fun `a disconnect leaves both accounts, the conversations, the cost report share and unused invites alone`(): Unit =
    runBlocking {
      val studentUser = createUser("Ava Okafor")
      val studentId = createStudent(studentUser)
      val janeUser = createUser("Jane Okafor")
      val jane = supporterOf(janeUser)
      val link = link(jane, studentId)
      val convoId = UUID.randomUUID()
      connection.prepareStatement("INSERT INTO convos (id, student_id, name) VALUES (?, ?, 'Convo')").use { stmt ->
        stmt.setObject(1, convoId)
        stmt.setObject(2, studentId.value)
        stmt.executeUpdate()
      }
      insertLiveShare(studentId)
      val unusedInvite = inviteService.create(studentUser, SupporterRelationship.PARENT).getOrThrow()

      assertEquals(link.id, assertIs<UnlinkOutcome.Unlinked>(service.disconnectAsStudent(studentId, link.id).getOrThrow()).link.id)

      assertTrue(isLive("users", studentUser.value), "the student's account")
      assertTrue(isLive("students", studentId.value), "the student's profile")
      assertTrue(isLive("users", janeUser.value), "the supporter's account")
      assertTrue(isLive("supporters", jane.id.value), "the supporter's own row")
      assertTrue(isLive("convos", convoId), "the student's conversation")
      assertNotNull(CostReportSharesDao.findLiveByStudent(sqlSession, studentId).getOrThrow(), "the cost report share")
      assertIs<AcceptInviteOutcome.Success>(
        inviteService.accept(unusedInvite.rawToken, createUser("Sam Okafor")).getOrThrow(),
        "an unused invite link the student minted earlier still works",
      )
    }

  /**
   * Re-inviting after a disconnect, through the service that makes links in
   * production: accept is `findActive ?: create`, and the severed row is
   * invisible to `findActive`, so the parent comes back on ONE new live link.
   */
  @Test
  fun `a re-invite after a disconnect produces exactly one live link`(): Unit =
    runBlocking {
      val studentUser = createUser("Ava Okafor")
      val studentId = createStudent(studentUser)
      val janeUser = createUser("Jane Okafor")
      val first = inviteService.create(studentUser, SupporterRelationship.PARENT).getOrThrow()
      val firstLink = assertIs<AcceptInviteOutcome.Success>(inviteService.accept(first.rawToken, janeUser).getOrThrow()).link

      assertEquals(
        firstLink.id,
        assertIs<UnlinkOutcome.Unlinked>(service.disconnectAsStudent(studentId, firstLink.id).getOrThrow()).link.id,
      )

      val second = inviteService.create(studentUser, SupporterRelationship.PARENT).getOrThrow()
      val secondLink = assertIs<AcceptInviteOutcome.Success>(inviteService.accept(second.rawToken, janeUser).getOrThrow()).link

      assertTrue(secondLink.id != firstLink.id, "the re-link is a NEW row, never the severed one revived")
      assertEquals(listOf(secondLink.id), SupporterStudentLinksDao.listForStudent(sqlSession, studentId).getOrThrow().map { it.id })
    }

  @Test
  fun `the home carries each student's other supporters and never the caller`(): Unit =
    runBlocking {
      val janeUser = createUser("Jane Okafor")
      val jane = supporterOf(janeUser)
      val ava = createStudent(createUser("Ava Okafor"))
      val noor = createStudent(createUser("Noor Haddad"))
      link(jane, ava)
      link(jane, noor)
      link(supporterOf(createUser("Sam Okafor")), ava)
      link(supporterOf(createUser("Grace Okafor")), ava)

      val home = assertNotNull(service.homeFor(janeUser).getOrThrow())

      assertEquals(
        listOf(
          "Ava Okafor" to listOf("Sam Okafor", "Grace Okafor"),
          "Noor Haddad" to emptyList(),
        ),
        home.students.map { entry -> entry.student.studentName.value to entry.otherSupporterNames.map { it.value } },
      )
    }
}
