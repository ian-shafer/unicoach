package ed.unicoach.account

import ed.unicoach.auth.FailingJobsDao
import ed.unicoach.auth.SendEmailJobQueries
import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.common.json.deserialize
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.dao.CostReportSharesDao
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.db.dao.SupporterInvitesDao
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.dao.UserAuthIdentitiesDao
import ed.unicoach.db.models.AuthProvider
import ed.unicoach.db.models.InviterRole
import ed.unicoach.db.models.LoginMethod
import ed.unicoach.db.models.NewAuthIdentity
import ed.unicoach.db.models.NewCostReportShare
import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.NewSupporterInvite
import ed.unicoach.db.models.NewSupporterStudentLink
import ed.unicoach.db.models.ProviderSubject
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.SupporterInvite
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.SupporterStudentLink
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import ed.unicoach.email.EmailTemplate
import ed.unicoach.family.LinkEndedContext
import ed.unicoach.family.LinkEndedEmailRenderer
import ed.unicoach.family.LinkEndedNotice
import ed.unicoach.family.SupporterService
import ed.unicoach.queue.QueueService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The account close (RFC 252), tested by what it WRITES. Every statement of the
 * close names the closer's own keys, and only two tables hold a key from both
 * parties (links and invites), so "the other party survives" is proven on
 * exactly those plus the other party's own account rows — never by a per-table
 * fixture or a schema walk, which nothing would keep current (Ian, at the
 * approval gate).
 */
class AccountClosureServiceTest {
  private val database = CoachingTestDb.database
  private val connection = CoachingTestDb.connection
  private val sqlSession = CoachingTestDb.sqlSession
  private val notice = LinkEndedNotice(QueueService(database))
  private val service = AccountClosureService(database, notice)

  @BeforeEach
  fun reset() {
    CoachingTestDb.truncate(
      "jobs",
      "cost_report_shares",
      "supporter_invites",
      "supporter_student_links",
      "supporters",
      "students",
      "sessions",
      "user_auth_identities",
      "users",
    )
  }

  // --- fixtures ---------------------------------------------------------------

  private class Account(
    val userId: UserId,
    val email: String,
    val sessionHash: TokenHash,
  )

  private fun account(
    name: String,
    verified: Boolean = true,
  ): Account {
    val userId = UUID.randomUUID()
    val email = "user-$userId@example.com"
    connection
      .prepareStatement(
        "INSERT INTO users (id, email, name, password_hash, email_verified_at) VALUES (?, ?, ?, 'hash', ?)",
      ).use { stmt ->
        stmt.setObject(1, userId)
        stmt.setString(2, email)
        stmt.setString(3, name)
        stmt.setObject(4, if (verified) java.sql.Timestamp.from(Instant.now()) else null)
        stmt.executeUpdate()
      }
    val hash = TokenHash(UUID.randomUUID().toString().toByteArray())
    SessionsDao
      .create(
        sqlSession,
        NewSession(UserId(userId), hash, "agent", null, null, Duration.ofDays(7), LoginMethod.PASSWORD),
      ).getOrThrow()
    return Account(UserId(userId), email, hash)
  }

  private fun studentOf(account: Account): StudentId {
    val id = UUID.randomUUID()
    StudentRowFixture.insert(connection, id, account.userId.value)
    return StudentId(id)
  }

  private fun supporterOf(account: Account): SupporterId = SupportersDao.create(sqlSession, NewSupporter(account.userId)).getOrThrow().id

  private fun link(
    supporter: SupporterId,
    student: StudentId,
  ): SupporterStudentLink =
    SupporterStudentLinksDao
      .create(sqlSession, NewSupporterStudentLink(supporter, student, SupporterRelationship.PARENT))
      .getOrThrow()

  private fun invite(
    inviter: Account,
    student: StudentId? = null,
    supporter: SupporterId? = null,
  ): SupporterInvite =
    SupporterInvitesDao
      .create(
        sqlSession,
        NewSupporterInvite(
          tokenHash = TokenHash.fromRawToken("invite-${UUID.randomUUID()}"),
          inviterUserId = inviter.userId,
          inviterRole = if (student != null) InviterRole.STUDENT else InviterRole.SUPPORTER,
          studentId = student,
          supporterId = supporter,
          relationship = SupporterRelationship.PARENT,
          expiresAt = Instant.now().plus(14, ChronoUnit.DAYS),
        ),
      ).getOrThrow()

  private fun share(student: StudentId): TokenHash {
    val hash = TokenHash.fromRawToken("share-${UUID.randomUUID()}")
    val id = CostReportSharesDao.nextId(sqlSession).getOrThrow()
    CostReportSharesDao.create(sqlSession, NewCostReportShare(id, student, hash)).getOrThrow()
    return hash
  }

  private fun identity(account: Account) {
    UserAuthIdentitiesDao
      .create(
        sqlSession,
        NewAuthIdentity(
          userId = account.userId,
          provider = AuthProvider.APPLE,
          subject = (ProviderSubject.create("apple-${UUID.randomUUID()}") as ValidationResult.Valid).value,
          email = (EmailAddress.create(account.email) as ValidationResult.Valid).value,
          emailVerified = true,
        ),
      ).getOrThrow()
  }

  private fun close(account: Account): CloseAccountOutcome = runBlocking { service.close(account.userId).getOrThrow() }

  // --- reads --------------------------------------------------------------------

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

  private fun linkIsLive(link: SupporterStudentLink) = isLive("supporter_student_links", link.id.value)

  private fun linkHistoryCount(link: SupporterStudentLink): Int =
    connection.prepareStatement("SELECT count(*) FROM supporter_student_links_versions WHERE id = ?").use { stmt ->
      stmt.setObject(1, link.id.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun inviteIsLive(invite: SupporterInvite) =
    SupporterInvitesDao.findLiveByTokenHash(sqlSession, invite.tokenHash).getOrThrow() != null

  private fun sessionIsLive(account: Account) = SessionsDao.findByTokenHash(sqlSession, account.sessionHash).isSuccess

  /** Every queued link-ended email, as (recipient, context). */
  private fun linkEndedEmails(): List<Pair<String, LinkEndedContext>> =
    SendEmailJobQueries.payloads(connection).map { payload ->
      assertEquals(EmailTemplate.LINK_ENDED, payload.template)
      payload.to to payload.context.deserialize<LinkEndedContext>()
    }

  // --- the partition --------------------------------------------------------------

  @Test
  fun `closing a student ends every connection and leaves each parent's own account alone`() {
    val ava = account("Ava Okafor")
    val avaStudent = studentOf(ava)
    val jane = account("Jane Okafor")
    val janeSupporter = supporterOf(jane)
    val noor = account("Noor Haddad")
    val noorStudent = studentOf(noor)
    val avaJane = link(janeSupporter, avaStudent)
    val noorJane = link(janeSupporter, noorStudent)
    val avasInvite = invite(ava, student = avaStudent)
    val janesInvite = invite(jane, supporter = janeSupporter)
    val avasShare = share(avaStudent)
    identity(ava)

    assertEquals(CloseAccountOutcome.Closed, close(ava))

    // What the close writes, under the closer's keys.
    assertFalse(linkIsLive(avaJane), "the closer's link is severed")
    assertEquals(2, linkHistoryCount(avaJane), "and the sever is a history row")
    assertFalse(inviteIsLive(avasInvite), "the closer's unused invite is revoked")
    assertNull(CostReportSharesDao.findLiveByTokenHash(sqlSession, avasShare).getOrThrow(), "the cost report link is revoked")
    assertFalse(sessionIsLive(ava), "every session of the closer is revoked")
    assertFalse(isLive("students", avaStudent.value))
    assertFalse(isLive("users", ava.userId.value))
    assertTrue(UserAuthIdentitiesDao.listByUser(sqlSession, ava.userId).getOrThrow().isEmpty(), "the closer's sign-in identities are gone")

    // The other party: their account, their own row, their session, their own
    // invite, and their link to a SECOND student.
    assertTrue(isLive("users", jane.userId.value))
    assertTrue(isLive("supporters", janeSupporter.value))
    assertTrue(sessionIsLive(jane))
    assertTrue(inviteIsLive(janesInvite), "the parent's own invite is theirs, not the closer's")
    assertTrue(linkIsLive(noorJane), "the parent's link to another student is not severed")

    assertEquals(listOf(jane.email to LinkEndedContext("Jane Okafor", "Ava Okafor")), linkEndedEmails())
  }

  @Test
  fun `closing a parent ends every connection and leaves each student's own account alone`() {
    val jane = account("Jane Okafor")
    val janeSupporter = supporterOf(jane)
    val ava = account("Ava Okafor")
    val avaStudent = studentOf(ava)
    val noor = account("Noor Haddad")
    val noorStudent = studentOf(noor)
    val sam = account("Sam Okafor")
    val samSupporter = supporterOf(sam)
    val avaJane = link(janeSupporter, avaStudent)
    val noorJane = link(janeSupporter, noorStudent)
    val avaSam = link(samSupporter, avaStudent)
    val janesInvite = invite(jane, supporter = janeSupporter)
    val avasInvite = invite(ava, student = avaStudent)
    val avasShare = share(avaStudent)

    assertEquals(CloseAccountOutcome.Closed, close(jane))

    assertFalse(linkIsLive(avaJane))
    assertFalse(linkIsLive(noorJane))
    assertFalse(inviteIsLive(janesInvite))
    assertFalse(sessionIsLive(jane))
    assertFalse(isLive("supporters", janeSupporter.value))
    assertFalse(isLive("users", jane.userId.value))

    for (student in listOf(ava to avaStudent, noor to noorStudent)) {
      assertTrue(isLive("users", student.first.userId.value))
      assertTrue(isLive("students", student.second.value))
      assertTrue(sessionIsLive(student.first))
    }
    assertTrue(inviteIsLive(avasInvite), "the student's own invite stays live")
    assertNotNull(CostReportSharesDao.findLiveByTokenHash(sqlSession, avasShare).getOrThrow(), "the student's own share stays live")
    assertTrue(linkIsLive(avaSam), "the student's link to ANOTHER parent is not severed")

    assertEquals(
      listOf(
        ava.email to LinkEndedContext("Ava Okafor", "Jane Okafor"),
        noor.email to LinkEndedContext("Noor Haddad", "Jane Okafor"),
      ).sortedBy { it.first },
      linkEndedEmails().sortedBy { it.first },
    )
  }

  @Test
  fun `a dual-role account closes both of its rows and tells both other parties`() {
    val dual = account("Dana Reyes")
    val dualStudent = studentOf(dual)
    val dualSupporter = supporterOf(dual)
    val parent = account("Pat Reyes")
    val child = account("Chris Reyes")
    val asStudent = link(supporterOf(parent), dualStudent)
    val asSupporter = link(dualSupporter, studentOf(child))

    assertEquals(CloseAccountOutcome.Closed, close(dual))

    assertFalse(linkIsLive(asStudent))
    assertFalse(linkIsLive(asSupporter))
    assertFalse(isLive("students", dualStudent.value))
    assertFalse(isLive("supporters", dualSupporter.value))
    assertFalse(isLive("users", dual.userId.value))
    assertEquals(
      listOf(
        parent.email to LinkEndedContext("Pat Reyes", "Dana Reyes"),
        child.email to LinkEndedContext("Chris Reyes", "Dana Reyes"),
      ).sortedBy { it.first },
      linkEndedEmails().sortedBy { it.first },
    )
  }

  @Test
  fun `a role-less account closes, and a second close finds nothing`() {
    val nobody = account("No Role Yet", verified = false)

    assertEquals(CloseAccountOutcome.Closed, close(nobody))
    assertFalse(isLive("users", nobody.userId.value))
    assertFalse(sessionIsLive(nobody))

    assertEquals(CloseAccountOutcome.NotFound, close(nobody))
  }

  @Test
  fun `an unverified other party gets no email and the close still succeeds`() {
    val ava = account("Ava Okafor")
    val avaStudent = studentOf(ava)
    val jane = account("Jane Okafor", verified = false)
    val avaJane = link(supporterOf(jane), avaStudent)

    assertEquals(CloseAccountOutcome.Closed, close(ava))

    assertFalse(linkIsLive(avaJane))
    assertEquals(0, SendEmailJobQueries.count(connection), "an unverified address may be a stranger's")
  }

  /** ASYNC_WORK.md rule 2: the email is required, so a failed enqueue fails the whole close. */
  @Test
  fun `a failed enqueue rolls the whole close back`() {
    val ava = account("Ava Okafor")
    val avaStudent = studentOf(ava)
    val jane = account("Jane Okafor")
    val avaJane = link(supporterOf(jane), avaStudent)
    val avasInvite = invite(ava, student = avaStudent)
    val avasShare = share(avaStudent)
    val failing = AccountClosureService(database, LinkEndedNotice(QueueService(database, FailingJobsDao())))

    assertTrue(runBlocking { failing.close(ava.userId) }.isFailure)

    assertTrue(linkIsLive(avaJane))
    assertTrue(inviteIsLive(avasInvite))
    assertNotNull(CostReportSharesDao.findLiveByTokenHash(sqlSession, avasShare).getOrThrow())
    assertTrue(sessionIsLive(ava))
    assertTrue(isLive("students", avaStudent.value))
    assertTrue(isLive("users", ava.userId.value))
    assertEquals(0, SendEmailJobQueries.count(connection))
  }

  /**
   * One text for every cause (RFC 252 §3): the email for a close and the email
   * for a disconnect are byte-identical given the same two names, so receiving
   * it cannot say which one happened.
   */
  @Test
  fun `the email for a close and for a disconnect is the same email`() {
    val renderer = LinkEndedEmailRenderer()
    val supporterService = SupporterService(database, notice)

    val ava = account("Ava Okafor")
    val jane = account("Jane Okafor")
    val disconnected = link(supporterOf(jane), studentOf(ava))
    runBlocking { supporterService.disconnectAsStudent(disconnected.studentId, disconnected.id).getOrThrow() }
    val byDisconnect = SendEmailJobQueries.payloads(connection).single()

    reset()

    val ava2 = account("Ava Okafor")
    val jane2 = account("Jane Okafor")
    link(supporterOf(jane2), studentOf(ava2))
    close(ava2)
    val byClose = SendEmailJobQueries.payloads(connection).single()

    val rendered = listOf(byDisconnect, byClose).map { renderer.render(it.context).getOrThrow() }
    assertEquals(rendered[0].subject.value, rendered[1].subject.value)
    assertEquals(rendered[0].body.value, rendered[1].body.value)
    assertTrue(rendered[0].body.value.contains("You and Ava Okafor are no longer connected on unicoach."))
  }
}
