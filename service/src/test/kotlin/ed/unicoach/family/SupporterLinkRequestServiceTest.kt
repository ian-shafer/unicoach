package ed.unicoach.family

import ed.unicoach.auth.AppleIdTokenVerifier
import ed.unicoach.auth.AuthService
import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.auth.EmailVerificationService
import ed.unicoach.auth.FailingJobsDao
import ed.unicoach.auth.GoogleIdTokenVerifier
import ed.unicoach.auth.SendEmailJobQueries
import ed.unicoach.auth.SsoLoginResult
import ed.unicoach.auth.StubIdTokenVerifier
import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.report.ReportTestDb
import ed.unicoach.coaching.report.RevokeCostReportOutcome
import ed.unicoach.common.config.AppConfig
import ed.unicoach.common.json.asJson
import ed.unicoach.common.json.deserialize
import ed.unicoach.db.dao.CostReportSharesDao
import ed.unicoach.db.dao.ShareEventsDao
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SupporterLinkRequestsDao
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.AuthProvider
import ed.unicoach.db.models.NewCostReportShare
import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.NewSupporterStudentLink
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.SupporterLinkRequestId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import ed.unicoach.email.EmailTemplate
import ed.unicoach.queue.QueueService
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.TokenGenerator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A parent's request to connect from a report link (RFC 254), against the real
 * database: what a claim writes, when the student is told, and what approve and
 * decline do.
 */
class SupporterLinkRequestServiceTest {
  private val database = CoachingTestDb.database
  private val connection = CoachingTestDb.connection
  private val session = CoachingTestDb.sqlSession
  private val supporterService = SupporterService(database, LinkEndedNotice(QueueService(database)))
  private val service = SupporterLinkRequestService(database, supporterService, LinkRequestedNotice(QueueService(database)))
  private val authService =
    AuthService(
      database,
      Argon2Hasher(),
      TokenGenerator(),
      EmailVerificationService(
        database,
        QueueService(database),
        TokenGenerator(),
        EmailVerificationConfig
          .from(AppConfig.load("common.conf", "db.conf", "service.conf", "email.conf").getOrThrow())
          .getOrThrow(),
      ),
      GoogleIdTokenVerifier(StubIdTokenVerifier()),
      AppleIdTokenVerifier(StubIdTokenVerifier()),
      service::releaseOnVerified,
    )

  @BeforeEach
  fun reset() {
    CoachingTestDb.truncate(
      "jobs",
      "share_events",
      "supporter_link_requests",
      "cost_report_shares",
      "supporter_student_links",
      "supporters",
      "students",
      "users",
    )
  }

  // --- fixtures ---------------------------------------------------------------

  private fun user(
    name: String,
    verified: Boolean = true,
    passwordHash: String? = "hash",
  ): UserId {
    val id = UUID.randomUUID()
    connection
      .prepareStatement("INSERT INTO users (id, email, name, password_hash, email_verified_at) VALUES (?, ?, ?, ?, ?)")
      .use { stmt ->
        stmt.setObject(1, id)
        stmt.setString(2, "user-$id@example.com")
        stmt.setString(3, name)
        stmt.setString(4, passwordHash)
        stmt.setObject(5, if (verified) java.sql.Timestamp.from(Instant.now()) else null)
        stmt.executeUpdate()
      }
    return UserId(id)
  }

  private fun emailOf(userId: UserId): String =
    UsersDao
      .findById(session, userId)
      .getOrThrow()
      .email.value

  private fun studentOf(userId: UserId): StudentId {
    val id = UUID.randomUUID()
    StudentRowFixture.insert(connection, id, userId.value)
    return StudentId(id)
  }

  /** A student with a live report link, and its raw token — the mint shape, 43 base64url characters. */
  private class Shared(
    val user: UserId,
    val student: StudentId,
    val token: String,
  )

  private fun sharedStudent(
    name: String = "Ava Okafor",
    verified: Boolean = true,
  ): Shared {
    val userId = user(name, verified)
    val student = studentOf(userId)
    val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    val id = CostReportSharesDao.nextId(session).getOrThrow()
    CostReportSharesDao.create(session, NewCostReportShare(id, student, TokenHash.fromRawToken(raw))).getOrThrow()
    return Shared(userId, student, raw)
  }

  private fun claim(
    userId: UserId,
    token: String,
  ): ClaimOutcome = runBlocking { service.claimFromReport(userId, token).getOrThrow() }

  private fun supporterIdOf(userId: UserId): SupporterId? = SupportersDao.findByUserId(session, userId).getOrThrow()?.id

  private fun linkCount(): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT count(*) FROM supporter_student_links").use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun requestsOf(student: StudentId) = runBlocking { supporterService.supportersOf(student).getOrThrow().requests }

  private fun linkRequestedEmails(): List<Pair<String, LinkRequestedContext>> =
    SendEmailJobQueries.payloads(connection).filter { it.template == EmailTemplate.LINK_REQUESTED }.map {
      it.to to it.context.deserialize<LinkRequestedContext>()
    }

  private fun markVerified(userId: UserId) {
    connection
      .prepareStatement("UPDATE users SET email_verified_at = NOW(), version = version + 1 WHERE id = ?")
      .use { stmt ->
        stmt.setObject(1, userId.value)
        stmt.executeUpdate()
      }
  }

  private fun release(userId: UserId) = runBlocking { database.withConnection { service.releaseOnVerified(it, userId) } }

  private fun onlyRequestOf(student: StudentId): SupporterLinkRequestId = requestsOf(student).single().requestId

  private fun requestCount(): Int = countOf("SELECT count(*) FROM supporter_link_requests")

  private fun pendingRequestCount(): Int = countOf("SELECT count(*) FROM supporter_link_requests WHERE resolution IS NULL")

  private fun countOf(sql: String): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery(sql).use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  // --- claiming ---------------------------------------------------------------

  /**
   * THE case the request exists for: the link is not proof of who sent it, so a
   * stranger holding a forwarded link gets an account of their own and a
   * request — and no link until the student approves.
   */
  @Test
  fun `a stranger holding a forwarded link gets an account and a request, and no link`() {
    val ava = sharedStudent()
    val stranger = user("Sam Stranger")

    assertEquals(ClaimOutcome.Requested(awaitingVerification = false), claim(stranger, ava.token))

    assertNotNull(supporterIdOf(stranger), "the claimant has a supporters row, so they land in the parent home")
    assertEquals(0, linkCount(), "nothing is linked until the student approves")
    assertEquals(listOf("Sam Stranger"), requestsOf(ava.student).map { it.name.value })
    assertEquals(emailOf(stranger), requestsOf(ava.student).single().email.value, "the student sees who asked, by address")
    assertEquals(
      listOf(emailOf(ava.user) to LinkRequestedContext("Sam Stranger", emailOf(stranger))),
      linkRequestedEmails(),
      "one email, to the student's own address, naming the claimant",
    )
  }

  @Test
  fun `the student claiming their own report makes nothing`() {
    val ava = sharedStudent()

    assertEquals(ClaimOutcome.NotClaimed(ClaimRefusal.OWN_REPORT), claim(ava.user, ava.token))
    assertNull(supporterIdOf(ava.user), "no supporters row is minted for the student's own report")
    assertEquals(emptyList(), requestsOf(ava.student))
  }

  @Test
  fun `an already-connected parent is told so and nothing is written`() {
    val ava = sharedStudent()
    val jane = user("Jane Okafor")
    val supporter = SupportersDao.create(session, NewSupporter(jane)).getOrThrow()
    SupporterStudentLinksDao.create(session, NewSupporterStudentLink(supporter.id, ava.student, SupporterRelationship.PARENT)).getOrThrow()

    assertEquals(ClaimOutcome.AlreadyConnected, claim(jane, ava.token))
    assertEquals(emptyList(), requestsOf(ava.student))
  }

  @Test
  fun `a second claim while one is pending writes nothing and sends nothing`() {
    val ava = sharedStudent()
    val jane = user("Jane Okafor")
    claim(jane, ava.token)

    assertEquals(ClaimOutcome.AlreadyRequested(awaitingVerification = false), claim(jane, ava.token))
    assertEquals(1, requestsOf(ava.student).size)
    assertEquals(1, linkRequestedEmails().size, "the student is told once")
  }

  @Test
  fun `a decline is final for the pair, and the claimant keeps their account`() {
    val ava = sharedStudent()
    val jane = user("Jane Okafor")
    claim(jane, ava.token)
    assertEquals(DeclineRequestOutcome.Declined, runBlocking { service.decline(ava.student, onlyRequestOf(ava.student)).getOrThrow() })

    assertEquals(ClaimOutcome.NotClaimed(ClaimRefusal.DECLINED_BEFORE), claim(jane, ava.token))
    assertNotNull(supporterIdOf(jane))
    assertEquals(emptyList(), requestsOf(ava.student))
    assertEquals(0, linkCount())
  }

  @Test
  fun `a revoked, malformed or closed student's link makes no request and mints nothing`() {
    val revoked = sharedStudent()
    CostReportSharesDao.revokeLive(session, revoked.student).getOrThrow()
    val closed = sharedStudent()
    StudentsDao.delete(session, closed.student, StudentsDao.findById(session, closed.student).getOrThrow().version).getOrThrow()
    val jane = user("Jane Okafor")

    assertEquals(ClaimOutcome.NotClaimed(ClaimRefusal.NO_LIVE_SHARE), claim(jane, revoked.token))
    assertEquals(ClaimOutcome.NotClaimed(ClaimRefusal.ACCOUNT_CLOSED), claim(jane, closed.token))
    assertEquals(ClaimOutcome.NotClaimed(ClaimRefusal.MALFORMED_TOKEN), claim(jane, "not-a-token"))
    assertNull(supporterIdOf(jane))
  }

  /**
   * A closed supporter row is a known state, read before the mint rather than
   * found by the mint failing twice: one transaction, no request, no new row.
   */
  @Test
  fun `a claimant whose supporter row is closed is refused and nothing is written`() {
    val ava = sharedStudent()
    val jane = user("Jane Okafor")
    val supporter = SupportersDao.create(session, NewSupporter(jane)).getOrThrow()
    SupportersDao.delete(session, supporter.id, supporter.version).getOrThrow()

    assertEquals(ClaimOutcome.NotClaimed(ClaimRefusal.SUPPORTER_CLOSED), claim(jane, ava.token))
    assertNull(supporterIdOf(jane), "no live row was minted over the closed one")
    assertEquals(0, requestCount(), "no request was written")
  }

  /**
   * The email's remedy for a forwarded link is "decline, and ask your coach to
   * turn the link off". Turning it off withdraws what it carried: a stranger
   * who claimed unverified and verifies AFTER the revoke reaches nobody.
   */
  @Test
  fun `turning the report link off cancels the requests made from it, so a later verify tells nobody`() {
    val ava = sharedStudent()
    val stranger = user("Sam Stranger", verified = false)
    assertEquals(ClaimOutcome.Requested(awaitingVerification = true), claim(stranger, ava.token))

    assertIs<RevokeCostReportOutcome.Revoked>(runBlocking { ReportTestDb.serviceWith().revoke(ava.student).getOrThrow() })
    markVerified(stranger)
    release(stranger)

    assertEquals(emptyList(), requestsOf(ava.student), "the request is not listed")
    assertEquals(emptyList(), linkRequestedEmails(), "and the student is not emailed")
    assertEquals(0, pendingRequestCount(), "it was cancelled, not left pending")
  }

  // --- who is told, and when ---------------------------------------------------

  /**
   * Anyone can register as somebody else's address, so a request from an
   * unverified claimant is invisible and silent until the claimant verifies —
   * and the release tells the student exactly once. The verify door itself is
   * driven end to end in `ReportClaimRoutingTest`.
   */
  @Test
  fun `an unverified claimant's request is invisible and silent until they verify, then told once`() {
    val ava = sharedStudent()
    val jane = user("Jane Okafor", verified = false)

    assertEquals(ClaimOutcome.Requested(awaitingVerification = true), claim(jane, ava.token))
    assertEquals(emptyList(), requestsOf(ava.student), "not listed")
    assertEquals(emptyList(), linkRequestedEmails(), "and nothing sent")

    markVerified(jane)
    release(jane)
    assertEquals(listOf("Jane Okafor"), requestsOf(ava.student).map { it.name.value }, "listed once verified")
    assertEquals(1, linkRequestedEmails().size, "and the student is told")

    release(jane)
    assertEquals(1, linkRequestedEmails().size, "a second release sends nothing")
  }

  /**
   * A sign-in with Google or Apple verifies an address too (RFC 113), and it is
   * a verification like the mailed link: the waiting request is released in
   * the same transaction, once. The claimant here has no password — an account
   * an earlier SSO sign-in made, moved since to an address not yet confirmed —
   * because a password account with an unconfirmed address is refused the
   * email-matched link outright.
   */
  @Test
  fun `a sign-in that verifies the claimant's address tells the student once`() {
    val ava = sharedStudent()
    val jane = user("Jane Okafor", verified = false, passwordHash = null)
    claim(jane, ava.token)
    assertEquals(emptyList(), linkRequestedEmails(), "nothing sent before the address is proved")

    val idToken = "stub:sub=jane-apple;email=${emailOf(jane)};email_verified=true;name=Jane Okafor"

    fun signIn() =
      runBlocking {
        authService.loginWithSso(AuthProvider.APPLE, idToken, null, null, Duration.ofDays(1), null, null).getOrThrow()
      }

    assertIs<SsoLoginResult.Success>(signIn())
    assertEquals(listOf("Jane Okafor"), requestsOf(ava.student).map { it.name.value }, "listed once verified")
    assertEquals(1, linkRequestedEmails().size, "and the student is told")

    assertIs<SsoLoginResult.Success>(signIn())
    assertEquals(1, linkRequestedEmails().size, "a second sign-in sends nothing")
  }

  @Test
  fun `an opted-out student gets the request in the list and no email, decided once`() {
    val ava = sharedStudent()
    ShareEventsDao.recordOptOut(session, ava.student).getOrThrow()
    val jane = user("Jane Okafor")

    claim(jane, ava.token)

    assertEquals(1, requestsOf(ava.student).size, "the request is listed")
    assertEquals(emptyList(), linkRequestedEmails(), "no email")
    val janeSupporter = assertNotNull(supporterIdOf(jane))
    assertEquals(
      emptyList(),
      SupporterLinkRequestsDao.listUnnotifiedForSupporter(session, janeSupporter).getOrThrow(),
      "notified_at is set",
    )
  }

  @Test
  fun `an unverified student is not emailed`() {
    val ava = sharedStudent(verified = false)

    claim(user("Jane Okafor"), ava.token)

    assertEquals(emptyList(), linkRequestedEmails())
  }

  /**
   * A student account closes by its `students` row OR its `users` row (RFC
   * 252). A request released after the profile closed, while the user row is
   * still live and verified, reaches nobody.
   */
  @Test
  fun `a student whose profile is closed is not emailed when a request is released`() {
    val ava = sharedStudent()
    val jane = user("Jane Okafor", verified = false)
    claim(jane, ava.token)
    StudentsDao.delete(session, ava.student, StudentsDao.findById(session, ava.student).getOrThrow().version).getOrThrow()

    markVerified(jane)
    release(jane)

    assertEquals(emptyList(), linkRequestedEmails(), "the closed student is not emailed, though their users row is live")
  }

  /**
   * The enqueue is REQUIRED (ASYNC_WORK.md rule 2), so its failure rolls the
   * claim back — and the error names the request's student and claimant, the
   * ids an operator needs to say which claim or verification failed.
   */
  @Test
  fun `a failed notice enqueue rolls the claim back and names whose notice failed`() {
    val ava = sharedStudent()
    val jane = user("Jane Okafor")
    val failing =
      SupporterLinkRequestService(database, supporterService, LinkRequestedNotice(QueueService(database, FailingJobsDao())))

    val failure = assertNotNull(runBlocking { failing.claimFromReport(jane, ava.token) }.exceptionOrNull())

    assertTrue(failure.message.orEmpty().contains("studentId=[${ava.student.value}]"), "names the student: [${failure.message}]")
    assertTrue(
      generateSequence(failure) { it.cause }.any { it.message == "enqueue failed" },
      "and keeps the queue's own error in its cause chain",
    )
    assertEquals(0, requestCount(), "the request rolled back with the enqueue")
  }

  // --- approve and decline --------------------------------------------------------

  @Test
  fun `approve links the pair with the catch-all relationship and resolves the request onto it`() {
    val ava = sharedStudent()
    val jane = user("Jane Okafor")
    claim(jane, ava.token)
    val requestId = onlyRequestOf(ava.student)

    val approved = assertIs<ApproveRequestOutcome.Approved>(runBlocking { service.approve(ava.student, requestId).getOrThrow() })

    val link = assertNotNull(SupporterStudentLinksDao.findActive(session, assertNotNull(supporterIdOf(jane)), ava.student).getOrThrow())
    assertEquals(link.id, approved.supporter.linkId)
    assertEquals("Jane Okafor", approved.supporter.name.value)
    assertEquals(SupporterRelationship.SUPPORTER, link.relationship)
    assertEquals(emptyList(), requestsOf(ava.student), "the request is resolved")
    assertEquals(
      ApproveRequestOutcome.NotFound(RequestNotFoundReason.NOT_VISIBLE),
      runBlocking { service.approve(ava.student, requestId).getOrThrow() },
      "a resolved request is not found",
    )
  }

  @Test
  fun `approve stands on a link the pair already has`() {
    val ava = sharedStudent()
    val jane = user("Jane Okafor")
    claim(jane, ava.token)
    val supporter = assertNotNull(supporterIdOf(jane))
    val existing =
      SupporterStudentLinksDao.create(session, NewSupporterStudentLink(supporter, ava.student, SupporterRelationship.PARENT)).getOrThrow()

    val approved =
      assertIs<ApproveRequestOutcome.Approved>(runBlocking { service.approve(ava.student, onlyRequestOf(ava.student)).getOrThrow() })

    assertEquals(existing.id, approved.supporter.linkId)
    assertEquals(1, linkCount())
  }

  @Test
  fun `another student, an unknown id and a closed claimant are all not found`() {
    val ava = sharedStudent()
    val noor = sharedStudent("Noor Haddad")
    val jane = user("Jane Okafor")
    claim(jane, ava.token)
    val requestId = onlyRequestOf(ava.student)

    assertEquals(
      ApproveRequestOutcome.NotFound(RequestNotFoundReason.NOT_VISIBLE),
      runBlocking {
        service.approve(noor.student, requestId).getOrThrow()
      },
    )
    assertEquals(DeclineRequestOutcome.NotFound, runBlocking { service.decline(noor.student, requestId).getOrThrow() })
    assertEquals(
      ApproveRequestOutcome.NotFound(RequestNotFoundReason.NOT_VISIBLE),
      runBlocking { service.approve(ava.student, SupporterLinkRequestId(UUID.randomUUID())).getOrThrow() },
    )

    UsersDao.delete(session, jane, UsersDao.findById(session, jane).getOrThrow().version).getOrThrow()
    assertEquals(
      ApproveRequestOutcome.NotFound(RequestNotFoundReason.NOT_VISIBLE),
      runBlocking {
        service.approve(ava.student, requestId).getOrThrow()
      },
    )
    assertEquals(0, linkCount())
  }

  /**
   * The locked read does not look at the student's own row, so a closed student
   * reaches the link insert, whose closed-endpoint trigger refuses — and that
   * refusal is the same not-found as every other request the student cannot
   * act on, never a fault.
   */
  @Test
  fun `approve onto a closed student is not found and links nobody`() {
    val ava = sharedStudent()
    val jane = user("Jane Okafor")
    claim(jane, ava.token)
    val requestId = onlyRequestOf(ava.student)
    StudentsDao.delete(session, ava.student, StudentsDao.findById(session, ava.student).getOrThrow().version).getOrThrow()

    assertEquals(
      ApproveRequestOutcome.NotFound(RequestNotFoundReason.STUDENT_CLOSED),
      runBlocking { service.approve(ava.student, requestId).getOrThrow() },
      "the same 404 on the wire, and the log says which endpoint refused",
    )
    assertEquals(0, linkCount())
  }

  @Test
  fun `a request from a claimant whose supporter row is closed is not listed and not found`() {
    val ava = sharedStudent()
    val jane = user("Jane Okafor")
    claim(jane, ava.token)
    val requestId = onlyRequestOf(ava.student)
    val supporter = assertNotNull(SupportersDao.findByUserId(session, jane).getOrThrow())
    SupportersDao.delete(session, supporter.id, supporter.version).getOrThrow()

    assertEquals(emptyList(), requestsOf(ava.student), "not listed")
    assertEquals(
      ApproveRequestOutcome.NotFound(RequestNotFoundReason.NOT_VISIBLE),
      runBlocking {
        service.approve(ava.student, requestId).getOrThrow()
      },
    )
    assertEquals(DeclineRequestOutcome.NotFound, runBlocking { service.decline(ava.student, requestId).getOrThrow() })
  }

  // --- what each side reads -----------------------------------------------------

  @Test
  fun `the parent's home counts the requests still waiting`() {
    val ava = sharedStudent()
    val noor = sharedStudent("Noor Haddad")
    val jane = user("Jane Okafor")
    claim(jane, ava.token)
    claim(jane, noor.token)
    runBlocking { service.decline(noor.student, onlyRequestOf(noor.student)).getOrThrow() }

    assertEquals(1, runBlocking { assertNotNull(supporterService.homeFor(jane).getOrThrow()).pendingRequests })
  }

  @Test
  fun `the report offers each reader what fits who they are, and nothing about the student`() {
    val ava = sharedStudent()
    val jane = user("Jane Okafor")
    val newcomer = user("New Reader")
    val declined = user("Declined Reader")
    claim(jane, ava.token)
    claim(declined, ava.token)
    runBlocking {
      service.decline(ava.student, requestsOf(ava.student).single { it.name.value == "Declined Reader" }.requestId).getOrThrow()
    }

    fun view(viewer: UserId?) = runBlocking { database.withConnection { service.claimView(it, viewer, ava.student) } }

    assertEquals(ClaimView.SIGN_UP, view(null))
    assertEquals(ClaimView.NONE, view(ava.user), "the student who shared it")
    assertEquals(ClaimView.ASK_TO_CONNECT, view(newcomer))
    assertEquals(ClaimView.PENDING, view(jane))
    assertEquals(ClaimView.NONE, view(declined), "declined before")

    val closed = user("Closed Reader")
    val closedRow = SupportersDao.create(session, NewSupporter(closed)).getOrThrow()
    SupportersDao.delete(session, closedRow.id, closedRow.version).getOrThrow()
    assertEquals(ClaimView.NONE, view(closed), "a closed supporter row, which a claim refuses (SUPPORTER_CLOSED)")

    runBlocking { service.approve(ava.student, onlyRequestOf(ava.student)).getOrThrow() }
    assertEquals(ClaimView.NONE, view(jane), "connected")
  }

  @Test
  fun `the email says who asked and what to do, in the RFC's words`() {
    val email =
      LinkRequestedEmailRenderer()
        .render(LinkRequestedContext("Sam Stranger", "sam@example.com").asJson())
        .getOrThrow()

    assertEquals(LINK_REQUESTED_SUBJECT, email.subject.value)
    val body = email.body.value
    assertTrue(body.startsWith("Sam Stranger (sam@example.com) opened the Family Cost Report link you shared"), body)
    assertTrue(body.contains("Settings → Family & Supporters"), body)
    assertTrue(body.contains("If you don't know this person, decline."), body)
  }
}
