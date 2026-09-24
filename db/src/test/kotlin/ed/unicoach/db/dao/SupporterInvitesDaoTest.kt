package ed.unicoach.db.dao

import ed.unicoach.db.models.InviterRole
import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.NewSupporterInvite
import ed.unicoach.db.models.NewSupporterStudentLink
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupporterInvitesDaoTest {
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
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE supporter_invites, supporter_student_links, supporters, students, users CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun createUser(): UserId {
    val userId = UUID.randomUUID()
    connection
      .prepareStatement("INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, 'Test User', 'hash')")
      .use { stmt ->
        stmt.setObject(1, userId)
        stmt.setString(2, "user-$userId@test.com")
        stmt.executeUpdate()
      }
    return UserId(userId)
  }

  private fun createStudent(userId: UserId = createUser()): StudentId {
    val studentId = UUID.randomUUID()
    StudentRowFixture.insert(connection, studentId, userId.value)
    return StudentId(studentId)
  }

  private fun createSupporter(userId: UserId = createUser()): SupporterId =
    SupportersDao.create(session, NewSupporter(userId)).getOrThrow().id

  private fun future(): Instant = Instant.now().plus(14, ChronoUnit.DAYS)

  private fun past(): Instant = Instant.now().minus(1, ChronoUnit.DAYS)

  /**
   * The `relationship` CHECK is a COPY of [SupporterRelationship]'s member
   * values, in this table and in `supporters-and-student-links.sql`. A member
   * added to the enum and not to this CHECK compiles, reviews clean, and then
   * refuses every insert of the new value at run time — so every member is
   * driven through the real constraint here, and the drift fails by name.
   */
  @Test
  fun `every SupporterRelationship value is accepted by the invite CHECK`() {
    val studentUser = createUser()
    val studentId = createStudent(studentUser)

    // EVERY member is driven, and every refusal is collected: `getOrThrow()` in
    // the loop halted the sweep on the first drifted member, so two drifted
    // members reported as one and cost two runs to find.
    val refused =
      SupporterRelationship.entries.mapNotNull { relationship ->
        val attempt =
          SupporterInvitesDao.create(
            session,
            NewSupporterInvite(
              tokenHash = TokenHash.fromRawToken("relationship-${relationship.value}-0123456789012345"),
              inviterUserId = studentUser,
              inviterRole = InviterRole.STUDENT,
              studentId = studentId,
              supporterId = null,
              relationship = relationship,
              expiresAt = future(),
            ),
          )
        when (val created = attempt.getOrNull()) {
          null -> {
            "${relationship.value} (${attempt.exceptionOrNull()?.message})"
          }

          else -> {
            if (created.relationship == relationship) {
              null
            } else {
              "${relationship.value} (stored as [${created.relationship.value}])"
            }
          }
        }
      }
    assertTrue(refused.isEmpty(), "supporter_invites.relationship refuses these SupporterRelationship members: $refused")
  }

  @Test
  fun `student can create supporter invite and it is retrievable by token hash`() {
    val studentUser = createUser()
    val studentId = createStudent(studentUser)
    val tokenHash = TokenHash.fromRawToken("test-raw-token-12345678901234567890123")

    val input =
      NewSupporterInvite(
        tokenHash = tokenHash,
        inviterUserId = studentUser,
        inviterRole = InviterRole.STUDENT,
        studentId = studentId,
        supporterId = null,
        relationship = SupporterRelationship.PARENT,
        expiresAt = future(),
      )

    val created = SupporterInvitesDao.create(session, input).getOrThrow()
    assertEquals(studentId, created.studentId)
    assertNull(created.supporterId)
    assertEquals(InviterRole.STUDENT, created.inviterRole)
    assertEquals(SupporterRelationship.PARENT, created.relationship)
    assertNull(created.consumedAt)
    assertNull(created.revokedAt)

    val found = SupporterInvitesDao.findLiveByTokenHash(session, tokenHash).getOrThrow()
    assertNotNull(found)
    assertEquals(created.id, found.id)
  }

  @Test
  fun `supporter can create student invite`() {
    val supporterUser = createUser()
    val supporterId = createSupporter(supporterUser)
    val tokenHash = TokenHash.fromRawToken("supporter-token-12345678901234567890123")

    val input =
      NewSupporterInvite(
        tokenHash = tokenHash,
        inviterUserId = supporterUser,
        inviterRole = InviterRole.SUPPORTER,
        studentId = null,
        supporterId = supporterId,
        relationship = SupporterRelationship.GUARDIAN,
        expiresAt = future(),
      )

    val created = SupporterInvitesDao.create(session, input).getOrThrow()
    assertNull(created.studentId)
    assertEquals(supporterId, created.supporterId)
    assertEquals(InviterRole.SUPPORTER, created.inviterRole)
    assertEquals(SupporterRelationship.GUARDIAN, created.relationship)
  }

  /**
   * Pinned to the CHECK by NAME, and each case given its OWN token hash: a bare
   * `isFailure` stayed green when the refusal came from the shared token-hash
   * unique index, from a type error, or from a CHECK rewritten to forbid
   * something else entirely.
   */
  @Test
  fun `invalid endpoints check constraint refuses mismatched role and id`() {
    val user = createUser()
    val studentId = createStudent(user)
    val supporterId = createSupporter(user)

    listOf(
      "both endpoints" to
        NewSupporterInvite(
          tokenHash = TokenHash.fromRawToken("bad-endpoints-both-012345678901234567"),
          inviterUserId = user,
          inviterRole = InviterRole.STUDENT,
          studentId = studentId,
          supporterId = supporterId,
          relationship = SupporterRelationship.PARENT,
          expiresAt = future(),
        ),
      "neither endpoint" to
        NewSupporterInvite(
          tokenHash = TokenHash.fromRawToken("bad-endpoints-none-012345678901234567"),
          inviterUserId = user,
          inviterRole = InviterRole.STUDENT,
          studentId = null,
          supporterId = null,
          relationship = SupporterRelationship.PARENT,
          expiresAt = future(),
        ),
    ).forEach { (case, input) ->
      val refusal = SupporterInvitesDao.create(session, input).exceptionOrNull()
      assertIs<ConstraintViolationException>(refusal, "[$case] must be refused by a constraint, not by chance")
      assertEquals("supporter_invites_inviter_endpoints_check", refusal.constraint, "[$case]")
    }
  }

  @Test
  fun `CAS consume burns token and second consume yields null`() {
    val user = createUser()
    val studentId = createStudent(user)
    val tokenHash = TokenHash.fromRawToken("consume-token-12345678901234567890123")

    SupporterInvitesDao
      .create(
        session,
        NewSupporterInvite(
          tokenHash = tokenHash,
          inviterUserId = user,
          inviterRole = InviterRole.STUDENT,
          studentId = studentId,
          supporterId = null,
          relationship = SupporterRelationship.PARENT,
          expiresAt = future(),
        ),
      ).getOrThrow()

    val consumed = SupporterInvitesDao.consume(session, tokenHash).getOrThrow()
    assertNotNull(consumed)
    assertNotNull(consumed.consumedAt)

    val secondConsume = SupporterInvitesDao.consume(session, tokenHash).getOrThrow()
    assertNull(secondConsume)

    val live = SupporterInvitesDao.findLiveByTokenHash(session, tokenHash).getOrThrow()
    assertNull(live)

    // Single-use burns the TOKEN, not the ROW: the invite stays readable so a
    // later accept can be classified as CONSUMED rather than UNKNOWN.
    val byHash = SupporterInvitesDao.findByTokenHash(session, tokenHash).getOrThrow()
    assertNotNull(byHash)
    assertNotNull(byHash.consumedAt)
  }

  @Test
  fun `expired invite cannot be consumed and is not live`() {
    val user = createUser()
    val studentId = createStudent(user)
    val tokenHash = TokenHash.fromRawToken("expired-token-12345678901234567890123")

    SupporterInvitesDao
      .create(
        session,
        NewSupporterInvite(
          tokenHash = tokenHash,
          inviterUserId = user,
          inviterRole = InviterRole.STUDENT,
          studentId = studentId,
          supporterId = null,
          relationship = SupporterRelationship.PARENT,
          expiresAt = past(),
        ),
      ).getOrThrow()

    val live = SupporterInvitesDao.findLiveByTokenHash(session, tokenHash).getOrThrow()
    assertNull(live)

    val consumed = SupporterInvitesDao.consume(session, tokenHash).getOrThrow()
    assertNull(consumed)
  }

  @Test
  fun `revoked invite cannot be consumed`() {
    val user = createUser()
    val studentId = createStudent(user)
    val tokenHash = TokenHash.fromRawToken("revoke-token-12345678901234567890123")

    val created =
      SupporterInvitesDao
        .create(
          session,
          NewSupporterInvite(
            tokenHash = tokenHash,
            inviterUserId = user,
            inviterRole = InviterRole.STUDENT,
            studentId = studentId,
            supporterId = null,
            relationship = SupporterRelationship.PARENT,
            expiresAt = future(),
          ),
        ).getOrThrow()

    val revoked = SupporterInvitesDao.revoke(session, created.id, user).getOrThrow()
    assertNotNull(revoked)
    assertNotNull(revoked.revokedAt)

    val live = SupporterInvitesDao.findLiveByTokenHash(session, tokenHash).getOrThrow()
    assertNull(live)

    val consumed = SupporterInvitesDao.consume(session, tokenHash).getOrThrow()
    assertNull(consumed)
  }

  /**
   * The UNIQUE half of `supporter_invites_token_hash_idx`, which is what makes
   * an invite single-use at the storage layer. Without this, dropping `UNIQUE`
   * from the index passes the whole suite.
   */
  @Test
  fun `the token-hash index refuses a second invite carrying the same hash`() {
    val user = createUser()
    val studentId = createStudent(user)
    val tokenHash = TokenHash.fromRawToken("duplicate-hash-token-1234567890123456")

    val input =
      NewSupporterInvite(
        tokenHash = tokenHash,
        inviterUserId = user,
        inviterRole = InviterRole.STUDENT,
        studentId = studentId,
        supporterId = null,
        relationship = SupporterRelationship.PARENT,
        expiresAt = future(),
      )

    SupporterInvitesDao.create(session, input).getOrThrow()

    val second = SupporterInvitesDao.create(session, input).exceptionOrNull()
    assertIs<ConstraintViolationException>(second, "a repeated token hash is refused by the index, not by chance")
    assertEquals("supporter_invites_token_hash_idx", second.constraint)
  }

  /**
   * The OTHER half of the self-link rule (RFC 232). `SupporterInviteService`
   * refuses a self-addressed invite before it ever writes, but that check can be
   * bypassed — the trigger cannot, and nothing else in this change fires it.
   */
  @Test
  fun `the not-self-link trigger refuses a link whose supporter and student are one user`() {
    val user = createUser()
    val studentId = createStudent(user)
    val supporterId = createSupporter(user)

    val created =
      SupporterStudentLinksDao.create(
        session,
        NewSupporterStudentLink(supporterId, studentId, SupporterRelationship.PARENT),
      )

    val refusal = created.exceptionOrNull()
    assertIs<SelfSupporterLinkException>(refusal, "the trigger must refuse a self-link")
    assertEquals("supporter_student_links_not_self_link", refusal.constraint)
  }
}
