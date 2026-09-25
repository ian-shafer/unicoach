package ed.unicoach.family

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.db.dao.SupporterInvitesDao
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.models.InviterRole
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import ed.unicoach.util.TokenGenerator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SupporterInviteServiceTest {
  private val database = CoachingTestDb.database
  private val connection = CoachingTestDb.connection
  private val sqlSession = CoachingTestDb.sqlSession
  private val tokenGenerator = TokenGenerator()
  private val config =
    SupporterInviteConfig(
      inviteUrlBase = "https://app.unicoach.test/invite",
      tokenTtl = Duration.ofDays(14),
    )
  private val supporterService = SupporterService(database)
  private val service = SupporterInviteService(database, config, tokenGenerator, supporterService)

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

  private fun createStudent(userId: UserId = createUser("Student Alex")): StudentId {
    val studentId = UUID.randomUUID()
    ed.unicoach.db.dao.StudentRowFixture
      .insert(connection, studentId, userId.value)
    return StudentId(studentId)
  }

  @Test
  fun `student invites supporter and supporter accepts end to end`(): Unit =
    runBlocking {
      val studentUser = createUser("Student Alex")
      val studentId = createStudent(studentUser)

      val supporterUser = createUser("Parent Jane")

      val inviteResult = service.create(studentUser, SupporterRelationship.PARENT).getOrThrow()
      assertEquals(InviterRole.STUDENT, inviteResult.inviterRole)
      assertEquals(SupporterRelationship.PARENT, inviteResult.relationship)
      assertTrue(inviteResult.inviteUrl.startsWith("https://app.unicoach.test/invite?token="))

      val preview = service.preview(inviteResult.rawToken).getOrThrow()
      assertIs<InvitePreviewOutcome.Valid>(preview)
      assertEquals("Student Alex", preview.inviterName)
      assertEquals(InviterRole.STUDENT, preview.inviterRole)
      assertEquals(SupporterRelationship.PARENT, preview.relationship)

      val acceptResult = service.accept(inviteResult.rawToken, supporterUser).getOrThrow()
      assertIs<AcceptInviteOutcome.Success>(acceptResult)

      // The adult had no `supporters` row before this accept (brief 0014 D21):
      // acceptance is what mints it, so its absence here is a real regression.
      val supporter = SupportersDao.findByUserId(sqlSession, supporterUser).getOrThrow()
      assertNotNull(supporter)

      val activeLink = SupporterStudentLinksDao.findActive(sqlSession, supporter.id, studentId).getOrThrow()
      assertNotNull(activeLink)
      assertEquals(SupporterRelationship.PARENT, activeLink.relationship)

      // The invite is single-use: a replayed token is refused, NOT answered
      // with the link the first accept made.
      val secondAccept = service.accept(inviteResult.rawToken, supporterUser).getOrThrow()
      assertIs<AcceptInviteOutcome.InvalidToken>(secondAccept)
    }

  /**
   * The link row inherits the INVITE's own word (RFC 240 decision 4). The
   * end-to-end test above mints `parent`, which is also what a hard-coded link
   * relationship would record, so only a non-`parent` invite can tell the two
   * apart — and `supporter` is now the word an unstated relationship mints.
   */
  @Test
  fun `an accepted invite records its own relationship on the link row`(): Unit =
    runBlocking {
      val studentUser = createUser("Student Alex")
      val studentId = createStudent(studentUser)
      val supporterUser = createUser("Aunt Jane")

      val invite = service.create(studentUser, SupporterRelationship.SUPPORTER).getOrThrow()
      val accepted = service.accept(invite.rawToken, supporterUser).getOrThrow()

      assertIs<AcceptInviteOutcome.Success>(accepted)
      assertEquals(SupporterRelationship.SUPPORTER, accepted.link.relationship)
      val supporter = SupportersDao.findByUserId(sqlSession, supporterUser).getOrThrow()
      assertNotNull(supporter)
      val activeLink = SupporterStudentLinksDao.findActive(sqlSession, supporter.id, studentId).getOrThrow()
      assertNotNull(activeLink)
      assertEquals(SupporterRelationship.SUPPORTER, activeLink.relationship)
    }

  @Test
  fun `supporter invites student and student accepts`(): Unit =
    runBlocking {
      val supporterUser = createUser("Parent Jane")
      val studentUser = createUser("Student Alex")
      val studentId = createStudent(studentUser)

      val inviteResult = service.create(supporterUser, SupporterRelationship.GUARDIAN).getOrThrow()
      assertEquals(InviterRole.SUPPORTER, inviteResult.inviterRole)
      assertEquals(SupporterRelationship.GUARDIAN, inviteResult.relationship)

      // The inviting adult is not a `supporters` row until the MINT makes one
      // (brief 0014 D21) — the link below is built on this id.
      val supporter = SupportersDao.findByUserId(sqlSession, supporterUser).getOrThrow()
      assertNotNull(supporter)

      val acceptResult = service.accept(inviteResult.rawToken, studentUser).getOrThrow()
      assertIs<AcceptInviteOutcome.Success>(acceptResult)

      val activeLink = SupporterStudentLinksDao.findActive(sqlSession, supporter.id, studentId).getOrThrow()
      assertNotNull(activeLink)
      assertEquals(SupporterRelationship.GUARDIAN, activeLink.relationship)
    }

  @Test
  fun `user without student profile cannot accept supporter invite`(): Unit =
    runBlocking {
      val supporterUser = createUser("Parent Jane")
      val otherUser = createUser("Non-student User")

      val invite = service.create(supporterUser, SupporterRelationship.PARENT).getOrThrow()
      val accept = service.accept(invite.rawToken, otherUser).getOrThrow()
      assertIs<AcceptInviteOutcome.StudentProfileRequired>(accept)
    }

  @Test
  fun `self link is prohibited`(): Unit =
    runBlocking {
      val studentUser = createUser("Student Alex")
      createStudent(studentUser)

      val invite = service.create(studentUser, SupporterRelationship.PARENT).getOrThrow()
      val accept = service.accept(invite.rawToken, studentUser).getOrThrow()
      assertIs<AcceptInviteOutcome.SelfLinkProhibited>(accept)
    }

  @Test
  fun `revoked invite cannot be accepted`(): Unit =
    runBlocking {
      val studentUser = createUser("Student Alex")
      createStudent(studentUser)
      val supporterUser = createUser("Parent Jane")

      val invite = service.create(studentUser, SupporterRelationship.PARENT).getOrThrow()
      val revoked = service.revoke(invite.id, studentUser).getOrThrow()
      assertNotNull(revoked, "the inviter's own live invite is revocable")
      assertNotNull(revoked.revokedAt, "revocation stamps the row it returns")

      val preview = service.preview(invite.rawToken).getOrThrow()
      assertIs<InvitePreviewOutcome.Invalid>(preview)

      val accept = service.accept(invite.rawToken, supporterUser).getOrThrow()
      assertIs<AcceptInviteOutcome.InvalidToken>(accept)
    }

  /**
   * D7 and correction X7 make hash-at-rest the security claim of this table, and
   * every other assertion in this suite round-trips through
   * [TokenHash.fromRawToken] — which would pass just as well if the DAO stored
   * the raw bytes. So this one reads the ROW back, twice over: the persisted
   * bytes ARE the SHA-256 of the raw token, and no column of the row carries the
   * raw token's text anywhere.
   */
  @Test
  fun `the minted token is stored only as its SHA-256 hash`(): Unit =
    runBlocking {
      val studentUser = createUser("Student Alex")
      createStudent(studentUser)

      val minted = service.create(studentUser, SupporterRelationship.PARENT).getOrThrow()

      val stored = SupporterInvitesDao.findByTokenHash(sqlSession, TokenHash.fromRawToken(minted.rawToken)).getOrThrow()
      assertNotNull(stored, "the invite is findable by the hash of its raw token")
      assertContentEquals(
        MessageDigest.getInstance("SHA-256").digest(minted.rawToken.toByteArray()),
        stored.tokenHash.value,
        "token_hash must be SHA-256 of the raw token",
      )

      // The WHOLE row as text, not just the column we suspect: a raw token
      // copied into any other column is the same leak.
      assertFalse(
        rowTextContains(minted.rawToken),
        "no column of supporter_invites may carry the raw invite token",
      )
    }

  @Test
  fun `an expired invite cannot be previewed or accepted`(): Unit =
    runBlocking {
      val studentUser = createUser("Student Alex")
      createStudent(studentUser)
      val supporterUser = createUser("Parent Jane")
      // A NEGATIVE TTL rather than a sleep: the invite is born expired, so the
      // test asserts the expiry predicate rather than the clock.
      val expiring =
        SupporterInviteService(
          database,
          config.copy(tokenTtl = Duration.ofSeconds(-1)),
          tokenGenerator,
          supporterService,
        )

      val invite = expiring.create(studentUser, SupporterRelationship.PARENT).getOrThrow()

      assertIs<InvitePreviewOutcome.Invalid>(expiring.preview(invite.rawToken).getOrThrow())
      assertIs<AcceptInviteOutcome.InvalidToken>(expiring.accept(invite.rawToken, supporterUser).getOrThrow())
    }

  /**
   * The `activeLink ?:` branch of `SupporterInviteService.accept`: a second invite accepted by an
   * already-linked supporter stands on the LIVE link rather than minting a
   * duplicate. Without this the branch is dead code as far as the suite can tell.
   */
  @Test
  fun `a second invite accepted by an already linked supporter reuses the live link`(): Unit =
    runBlocking {
      val studentUser = createUser("Student Alex")
      val studentId = createStudent(studentUser)
      val supporterUser = createUser("Parent Jane")

      val first = service.create(studentUser, SupporterRelationship.PARENT).getOrThrow()
      val firstAccept = service.accept(first.rawToken, supporterUser).getOrThrow()
      assertIs<AcceptInviteOutcome.Success>(firstAccept)

      val second = service.create(studentUser, SupporterRelationship.PARENT).getOrThrow()
      val secondAccept = service.accept(second.rawToken, supporterUser).getOrThrow()
      assertIs<AcceptInviteOutcome.Success>(secondAccept)

      assertEquals(firstAccept.link.id, secondAccept.link.id, "the live link stands; no duplicate is created")
      assertEquals(studentId, secondAccept.link.studentId)
      assertEquals(1, linkCount(), "exactly one link row exists")
    }

  /**
   * The central correctness claim of acceptance: consume, supporter creation and
   * link insert are ONE transaction. The fault is injected at the last step —
   * the student endpoint is closed, so `supporter_student_links_student_live`
   * refuses the insert AFTER the CAS consume has already run. A burnt token with
   * no link is exactly what a non-atomic implementation leaves behind.
   *
   * The refusal is an OUTCOME, not a `Result.failure`: a closed endpoint is a
   * state the product reaches, and a 500 would land on a recipient who has just
   * registered. It answers in the SAME words a dead token gets, and the
   * rollback is what makes that answer honest — the invite is still live.
   */
  @Test
  fun `a closed endpoint refuses the accept, rolls the consume back and leaves the token live`(): Unit =
    runBlocking {
      val studentUser = createUser("Student Alex")
      val studentId = createStudent(studentUser)
      val supporterUser = createUser("Parent Jane")

      val minted = service.create(studentUser, SupporterRelationship.PARENT).getOrThrow()

      // A SOFT delete: the invite's FK cascade never fires, so the token stays
      // findable and the refusal happens at the link insert, not before it.
      closeStudent(studentId)

      assertIs<AcceptInviteOutcome.InvalidToken>(
        service.accept(minted.rawToken, supporterUser).getOrThrow(),
        "a closed endpoint is refused, not thrown",
      )
      assertEquals(0, linkCount(), "no link row survives the fault")
      assertNotNull(
        SupporterInvitesDao.findLiveByTokenHash(sqlSession, TokenHash.fromRawToken(minted.rawToken)).getOrThrow(),
        "the consume rolled back with the failed insert: the invite is still live",
      )
    }

  /**
   * `supporter_invites.inviter_user_id` CASCADEs on a HARD delete only, and
   * closing an account is a SOFT delete — so a LIVE invite can name a user the
   * ACTIVE read cannot see. That used to throw the DAO's `NotFoundException`
   * out of `SupporterInviteService.preview`, which `GET /invite` answered with a 500 on a page
   * whose whole contract is one uniform refusal.
   */
  @Test
  fun `an invite whose inviter is soft deleted previews as a refusal, not a fault`(): Unit =
    runBlocking {
      val studentUser = createUser("Student Alex")
      createStudent(studentUser)

      val invite = service.create(studentUser, SupporterRelationship.PARENT).getOrThrow()
      closeUser(studentUser)

      val preview = service.preview(invite.rawToken)
      assertTrue(preview.isSuccess, "a closed inviter is an outcome, not a failure: $preview")
      assertIs<InvitePreviewOutcome.Invalid>(preview.getOrThrow())
    }

  /**
   * The ACCEPT-side half of the closed-inviter case. The soft delete fires no
   * cascade onto `supporter_invites`, so the token stays LIVE and the invite
   * row alone cannot tell the accept that its inviter is gone. Without the
   * check, `POST /invite` — and the `/sign-in` and `/register` doors, which
   * never preview at all — burn the token and link the recipient to an account
   * nobody can reach.
   */
  @Test
  fun `an invite whose inviter is soft deleted is refused on accept and leaves the token live`(): Unit =
    runBlocking {
      val studentUser = createUser("Student Alex")
      createStudent(studentUser)
      val supporterUser = createUser("Parent Jane")

      val invite = service.create(studentUser, SupporterRelationship.PARENT).getOrThrow()
      closeUser(studentUser)

      val accept = service.accept(invite.rawToken, supporterUser).getOrThrow()
      val refusal = assertIs<AcceptInviteOutcome.InvalidToken>(accept, "a closed inviter is refused, not linked")
      assertEquals(InviteMissReason.INVITER_UNREADABLE, refusal.reason)
      assertEquals(0, linkCount(), "a closed inviter links nobody")
      assertNotNull(
        SupporterInvitesDao.findLiveByTokenHash(sqlSession, TokenHash.fromRawToken(invite.rawToken)).getOrThrow(),
        "the refusal lands before the consume: the token is not burnt",
      )
    }

  private fun closeUser(userId: UserId) {
    connection.prepareStatement("UPDATE users SET deleted_at = NOW(), version = version + 1 WHERE id = ?").use { stmt ->
      stmt.setObject(1, userId.value)
      stmt.executeUpdate()
    }
  }

  /** Every column of every `supporter_invites` row, as text. */
  private fun rowTextContains(needle: String): Boolean =
    connection.prepareStatement("SELECT count(*) FROM supporter_invites WHERE supporter_invites::text LIKE ?").use { stmt ->
      stmt.setString(1, "%$needle%")
      stmt.executeQuery().use { rows ->
        rows.next()
        rows.getInt(1) > 0
      }
    }

  private fun linkCount(): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT count(*) FROM supporter_student_links WHERE deleted_at IS NULL").use { rows ->
        rows.next()
        rows.getInt(1)
      }
    }

  /**
   * Soft-closes a student. `version = version + 1` because `students` carries
   * `enforce_versioning`, which refuses an update that does not advance it.
   */
  private fun closeStudent(studentId: StudentId) {
    connection.prepareStatement("UPDATE students SET deleted_at = NOW(), version = version + 1 WHERE id = ?").use { stmt ->
      stmt.setObject(1, studentId.value)
      stmt.executeUpdate()
    }
  }
}
