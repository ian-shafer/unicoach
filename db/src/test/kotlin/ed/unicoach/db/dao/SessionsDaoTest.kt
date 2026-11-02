package ed.unicoach.db.dao

import com.google.protobuf.ByteString
import ed.unicoach.db.models.LoginMethod
import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.SessionId
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import ed.unicoach.db.session.ChangeEmailDraft
import ed.unicoach.db.session.FormDrafts
import ed.unicoach.db.session.SessionMetadata
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionsDaoTest {
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
      stmt.execute("TRUNCATE TABLE sessions CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun createUser(): UserId {
    val rawId = UUID.randomUUID()
    RawSql.execute(
      connection,
      "INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, 'Sess User', 'ahash')",
      rawId,
      "sess-$rawId@test.com",
    )
    return UserId(rawId)
  }

  private fun createSession(
    userId: UserId?,
    hash: ByteArray,
  ) = SessionsDao
    .create(
      session,
      NewSession(
        userId = userId,
        tokenHash = TokenHash(hash),
        userAgent = "agent",
        initialIp = null,
        metadata = SessionMetadata.getDefaultInstance(),
        expiration = Duration.ofDays(7),
        loginMethod = if (userId != null) LoginMethod.PASSWORD else null,
      ),
    ).getOrThrow()

  @Test
  fun `findById returns the session by id and NotFound for unknown id`() {
    val user = createUser()
    val created = createSession(user, byteArrayOf(20, 21, 22))

    val found = SessionsDao.findById(session, created.id)
    assertTrue(found.isSuccess)
    assertEquals(created.id, found.getOrThrow().id)

    val missing = SessionsDao.findById(session, SessionId(UUID.randomUUID()))
    assertTrue(missing.isFailure && missing.exceptionOrNull() is NotFoundException)
  }

  @Test
  fun `listByUser filters to the owner and list pages newest-first`() {
    val owner = createUser()
    val other = createUser()

    val s1 = createSession(owner, byteArrayOf(30, 1))
    Thread.sleep(5)
    val s2 = createSession(owner, byteArrayOf(30, 2))
    Thread.sleep(5)
    createSession(other, byteArrayOf(30, 3))

    val byOwner = SessionsDao.listByUser(session, owner, limit = 50, offset = 0).getOrThrow()
    assertEquals(setOf(s1.id, s2.id), byOwner.map { it.id }.toSet())
    // created_at DESC: s2 (newer) before s1.
    assertEquals(s2.id, byOwner.first().id)

    val all = SessionsDao.list(session, limit = 50, offset = 0).getOrThrow()
    assertTrue(all.size >= 3)
    // Paging advances the cursor.
    val page0 = SessionsDao.list(session, limit = 1, offset = 0).getOrThrow()
    val page1 = SessionsDao.list(session, limit = 1, offset = 1).getOrThrow()
    assertTrue(page0.first().id != page1.first().id)
  }

  @Test
  fun `destroy physically removes the row`() {
    val user = createUser()
    val created = createSession(user, byteArrayOf(40, 41))

    val deleteResult = SessionsDao.destroy(session, created.id)
    assertTrue(deleteResult.isSuccess)

    val refetch = SessionsDao.findById(session, created.id)
    assertTrue(refetch.isFailure && refetch.exceptionOrNull() is NotFoundException)

    val deleteMissing = SessionsDao.destroy(session, SessionId(UUID.randomUUID()))
    assertTrue(deleteMissing.isFailure && deleteMissing.exceptionOrNull() is NotFoundException)
  }

  @Test
  fun `verifies mapping, insertion, and retrieval of anonymous sessions`() {
    val hash = byteArrayOf(1, 2, 3)
    val newSession =
      NewSession(
        userId = null,
        tokenHash =
          ed.unicoach.db.models
            .TokenHash(hash),
        userAgent = "test-agent",
        initialIp = "127.0.0.1",
        metadata = SessionMetadata.getDefaultInstance(),
        expiration = Duration.ofDays(7),
      )

    val createResult = SessionsDao.create(session, newSession)
    assertTrue(createResult.isSuccess)

    val retrievedResult = SessionsDao.findByTokenHash(session, TokenHash(hash))
    assertTrue(retrievedResult.isSuccess)
    assertTrue(retrievedResult.getOrNull()!!.userId == null)
  }

  @Test
  fun `expiry extension actively shifts expiry forwards securely`() {
    val hash = byteArrayOf(4, 5, 6)
    val newSession =
      NewSession(
        userId = null,
        tokenHash =
          ed.unicoach.db.models
            .TokenHash(hash),
        userAgent = "agent",
        initialIp = null,
        metadata = SessionMetadata.getDefaultInstance(),
        expiration = Duration.ofDays(7),
      )

    val createResult = SessionsDao.create(session, newSession)
    assertTrue(createResult.isSuccess)

    val created = createResult.getOrNull()!!
    val extendResult = SessionsDao.extendExpiry(session, created.id, created.version)
    assertTrue(extendResult.isSuccess)

    val extended = extendResult.getOrNull()!!
    assertTrue(extended.expiresAt.isAfter(created.expiresAt))
  }

  @Test
  fun `revokeByTokenHash revokes active session`() {
    val hash = byteArrayOf(7, 8, 9)
    val tokenHash = TokenHash(hash)
    val newSession =
      NewSession(
        userId = null,
        tokenHash = tokenHash,
        userAgent = "agent",
        initialIp = null,
        metadata = SessionMetadata.getDefaultInstance(),
        expiration = Duration.ofDays(7),
      )

    val createResult = SessionsDao.create(session, newSession)
    assertTrue(createResult.isSuccess)

    val revokeResult = SessionsDao.revokeByTokenHash(session, tokenHash)
    assertTrue(revokeResult.isSuccess)

    val original = createResult.getOrNull()!!
    val revoked = revokeResult.getOrNull()!!
    assertTrue(revoked.version == original.version + 1)

    val findResult = SessionsDao.findByTokenHash(session, tokenHash)
    assertTrue(findResult.isFailure && findResult.exceptionOrNull() is NotFoundException)
  }

  @Test
  fun `revokeAllForUser revokes every session of one user and no other`() {
    val user = createUser()
    val other = createUser()
    createSession(user, byteArrayOf(40, 41))
    createSession(user, byteArrayOf(42, 43))
    createSession(other, byteArrayOf(44, 45))

    assertEquals(2, SessionsDao.revokeAllForUser(session, user).getOrThrow())

    assertTrue(SessionsDao.findByTokenHash(session, TokenHash(byteArrayOf(40, 41))).exceptionOrNull() is NotFoundException)
    assertTrue(SessionsDao.findByTokenHash(session, TokenHash(byteArrayOf(42, 43))).exceptionOrNull() is NotFoundException)
    assertTrue(SessionsDao.findByTokenHash(session, TokenHash(byteArrayOf(44, 45))).isSuccess, "another user's session is untouched")
    assertEquals(0, SessionsDao.revokeAllForUser(session, user).getOrThrow(), "nothing live is left to revoke")
  }

  @Test
  fun `revokeByTokenHash returns NotFound for nonexistent token`() {
    val hash = byteArrayOf(10, 11, 12)
    val tokenHash = TokenHash(hash)

    val revokeResult = SessionsDao.revokeByTokenHash(session, tokenHash)
    assertTrue(revokeResult.isFailure && revokeResult.exceptionOrNull() is NotFoundException)
  }

  @Test
  fun `revokeByTokenHash returns NotFound for already-revoked session`() {
    val hash = byteArrayOf(13, 14, 15)
    val tokenHash = TokenHash(hash)
    val newSession =
      NewSession(
        userId = null,
        tokenHash = tokenHash,
        userAgent = "agent",
        initialIp = null,
        metadata = SessionMetadata.getDefaultInstance(),
        expiration = Duration.ofDays(7),
      )

    SessionsDao.create(session, newSession)
    SessionsDao.revokeByTokenHash(session, tokenHash)

    val secondRevokeResult = SessionsDao.revokeByTokenHash(session, tokenHash)
    assertTrue(secondRevokeResult.isFailure && secondRevokeResult.exceptionOrNull() is NotFoundException)
  }

  @Test
  fun `create round-trips login_method for password and google`() {
    val user = createUser()

    val passwordSession =
      SessionsDao
        .create(
          session,
          NewSession(
            userId = user,
            tokenHash = TokenHash(byteArrayOf(50, 1)),
            userAgent = null,
            initialIp = null,
            metadata = SessionMetadata.getDefaultInstance(),
            expiration = Duration.ofDays(7),
            loginMethod = LoginMethod.PASSWORD,
          ),
        ).getOrThrow()
    assertEquals(LoginMethod.PASSWORD, passwordSession.loginMethod)

    val googleSession =
      SessionsDao
        .create(
          session,
          NewSession(
            userId = user,
            tokenHash = TokenHash(byteArrayOf(50, 2)),
            userAgent = null,
            initialIp = null,
            metadata = SessionMetadata.getDefaultInstance(),
            expiration = Duration.ofDays(7),
            loginMethod = LoginMethod.GOOGLE,
          ),
        ).getOrThrow()
    assertEquals(LoginMethod.GOOGLE, googleSession.loginMethod)
  }

  @Test
  fun `create with a user but no login_method is rejected by the presence check`() {
    val user = createUser()
    val result =
      SessionsDao.create(
        session,
        NewSession(
          userId = user,
          tokenHash = TokenHash(byteArrayOf(51, 1)),
          userAgent = null,
          initialIp = null,
          metadata = SessionMetadata.getDefaultInstance(),
          expiration = Duration.ofDays(7),
          loginMethod = null,
        ),
      )
    assertTrue(result.isFailure, "Authenticated session without a login_method must be rejected")
  }

  @Test
  fun `create with no user but a login_method is rejected by the presence check`() {
    val result =
      SessionsDao.create(
        session,
        NewSession(
          userId = null,
          tokenHash = TokenHash(byteArrayOf(52, 1)),
          userAgent = null,
          initialIp = null,
          metadata = SessionMetadata.getDefaultInstance(),
          expiration = Duration.ofDays(7),
          loginMethod = LoginMethod.PASSWORD,
        ),
      )
    assertTrue(result.isFailure, "Anonymous session carrying a login_method must be rejected")
  }

  @Test
  fun `create anonymous session with null login_method succeeds`() {
    val created = createSession(null, byteArrayOf(53, 1))
    assertTrue(created.userId == null)
    assertEquals(null, created.loginMethod)
  }

  @Test
  fun `remintToken sets login_method on the formerly anonymous row`() {
    val anon = createSession(null, byteArrayOf(54, 1))
    val user = createUser()

    val reminted =
      SessionsDao
        .remintToken(
          session = session,
          id = anon.id,
          currentVersion = anon.version,
          newUserId = user,
          newTokenHash = byteArrayOf(54, 2),
          newExpirationSeconds = Duration.ofDays(7).seconds,
          newLoginMethod = LoginMethod.GOOGLE,
          newMetadata = SessionMetadata.getDefaultInstance(),
        ).getOrThrow()

    assertEquals(user, reminted.userId)
    assertEquals(LoginMethod.GOOGLE, reminted.loginMethod)
  }

  // --- metadata (RFC 265) ----------------------------------------------------

  private fun metadataWith(
    token: ByteArray,
    newEmail: String,
  ): SessionMetadata =
    SessionMetadata
      .newBuilder()
      .setCsrfToken(ByteString.copyFrom(token))
      .setDrafts(FormDrafts.newBuilder().setChangeEmail(ChangeEmailDraft.newBuilder().setNewEmail(newEmail)))
      .build()

  /** One column of the session row, read back as SQL sees it. */
  private fun sqlString(
    sql: String,
    id: SessionId,
  ): String? = RawSql.single(connection, sql, id.value) { it.getString(1) }

  /** Writes [rawJson] straight into the column, as a row no codec wrote. */
  private fun storeRawMetadata(
    id: SessionId,
    rawJson: String?,
  ) {
    RawSql.execute(connection, "UPDATE sessions SET metadata = ?::jsonb, version = version + 1 WHERE id = ?", rawJson, id.value)
  }

  @Test
  fun `metadata round-trips and is stored with the canonical proto3 JSON spellings`() {
    val created = createSession(createUser(), byteArrayOf(60, 1))
    val token = ByteArray(32) { it.toByte() }
    val metadata = metadataWith(token, "new@example.com")

    val written = SessionsDao.writeMetadata(session, created.id, created.version, metadata).getOrThrow()

    assertEquals(metadata, written.metadata)
    assertEquals(metadata, SessionsDao.findById(session, created.id).getOrThrow().metadata)
    assertEquals(
      java.util.Base64
        .getEncoder()
        .encodeToString(token),
      sqlString("SELECT metadata ->> 'csrfToken' FROM sessions WHERE id = ?", created.id),
    )
    assertEquals(
      "new@example.com",
      sqlString("SELECT metadata -> 'drafts' -> 'changeEmail' ->> 'newEmail' FROM sessions WHERE id = ?", created.id),
    )
  }

  /**
   * "Nothing set" has one spelling in the column: SQL `NULL`, the one the read
   * side maps back to the default instance — never `{}` on some rows and `NULL`
   * on others. Every write path binds through the one codec, so each is checked.
   */
  @Test
  fun `the default instance is stored as SQL NULL on every write path`() {
    val metadataText = "SELECT metadata::text FROM sessions WHERE id = ?"
    val user = createUser()

    val created = createSession(user, byteArrayOf(64, 1))
    assertNull(sqlString(metadataText, created.id), "create stored the default instance as a document")

    val holding = SessionsDao.writeMetadata(session, created.id, created.version, metadataWith(ByteArray(32), "a@example.com")).getOrThrow()
    val cleared = SessionsDao.writeMetadata(session, created.id, holding.version, SessionMetadata.getDefaultInstance()).getOrThrow()
    assertNull(sqlString(metadataText, created.id), "writeMetadata stored the default instance as a document")
    assertEquals(SessionMetadata.getDefaultInstance(), cleared.metadata)

    val held = SessionsDao.writeMetadata(session, created.id, cleared.version, metadataWith(ByteArray(32), "b@example.com")).getOrThrow()
    SessionsDao
      .remintToken(
        session,
        created.id,
        held.version,
        user,
        UUID.randomUUID().toString().toByteArray(),
        3600,
        LoginMethod.PASSWORD,
        SessionMetadata.getDefaultInstance(),
      ).getOrThrow()
    assertNull(sqlString(metadataText, created.id), "remintToken stored the default instance as a document")
  }

  @Test
  fun `a NULL metadata column reads as the default instance`() {
    val created = createSession(createUser(), byteArrayOf(61, 1))
    storeRawMetadata(created.id, null)

    assertNull(sqlString("SELECT metadata::text FROM sessions WHERE id = ?", created.id))
    assertEquals(SessionMetadata.getDefaultInstance(), SessionsDao.findById(session, created.id).getOrThrow().metadata)
  }

  @Test
  fun `stored metadata the strict parser refuses is a corrupt row`() {
    for (raw in listOf("""{"csrfToken":"AAAA","unknownField":1}""", """{"csrfToken":7}""", """[1,2]""")) {
      val created = createSession(createUser(), UUID.randomUUID().toString().toByteArray())
      storeRawMetadata(created.id, raw)

      val read = SessionsDao.findById(session, created.id)

      val failure = assertIs<CorruptPersistedValueException>(read.exceptionOrNull(), "[$raw] must be refused")
      assertEquals("sessions.metadata id=[${created.id.value}]", failure.location)
    }
  }

  /**
   * The cell is `pii.jsonb` and the parser's own message can quote it — here
   * it names the unknown key, which is the stored text. The parser failure is
   * still chained, by type and stack, so the log says what refused the row;
   * nothing anywhere in the chain repeats what the row holds.
   */
  @Test
  fun `a corrupt metadata row chains the parser failure and quotes none of the stored value`() {
    val stored = "familyTypedThis${UUID.randomUUID().toString().replace("-", "")}"
    val created = createSession(createUser(), byteArrayOf(65, 1))
    storeRawMetadata(created.id, """{"$stored":1}""")

    val failure = assertIs<CorruptPersistedValueException>(SessionsDao.findById(session, created.id).exceptionOrNull())

    val cause = assertIs<WithheldMessageException>(failure.cause, "the parser failure is not chained")
    assertEquals(com.google.protobuf.InvalidProtocolBufferException::class.java.name, cause.originalType)
    val chain = generateSequence<Throwable>(failure) { it.cause }.toList()
    for (link in chain) {
      assertTrue(!link.message.orEmpty().contains(stored), "[${link.javaClass.name}] quotes the stored value: ${link.message}")
    }
  }

  @Test
  fun `metadata past the cap is refused as too large and the row is unchanged`() {
    val created = createSession(createUser(), byteArrayOf(62, 1))
    val kept = SessionsDao.writeMetadata(session, created.id, created.version, metadataWith(ByteArray(32), "kept@example.com")).getOrThrow()
    val huge = SessionMetadataFixtures.textPastTheCap()

    val refused = SessionsDao.writeMetadata(session, created.id, kept.version, metadataWith(ByteArray(32), huge))

    val failure = assertIs<SessionMetadataTooLargeException>(refused.exceptionOrNull())
    assertEquals(created.id, failure.sessionId, "the refusal names the session it refused")
    assertTrue(failure.printedBytes > huge.length, "the refusal carries the size of what it refused: ${failure.printedBytes}")
    val after = SessionsDao.findById(session, created.id).getOrThrow()
    assertEquals(kept.metadata, after.metadata, "never silently truncated: the previous value stands")
    assertEquals(kept.version, after.version)
  }

  @Test
  fun `writeMetadata never moves expires_at`() {
    val created = createSession(createUser(), byteArrayOf(63, 1))

    val written = SessionsDao.writeMetadata(session, created.id, created.version, metadataWith(ByteArray(32), "a@example.com")).getOrThrow()

    assertEquals(created.expiresAt, written.expiresAt)
    assertEquals(created.version + 1, written.version)
  }

  @Test
  fun `writeMetadata at a stale version is a conflict and writes nothing`() {
    val created = createSession(createUser(), byteArrayOf(64, 1))
    val extended = SessionsDao.extendExpiry(session, created.id, created.version).getOrThrow()

    val stale = SessionsDao.writeMetadata(session, created.id, created.version, metadataWith(ByteArray(32), "a@example.com"))

    assertIs<ConcurrentModificationException>(stale.exceptionOrNull())
    assertEquals(extended, SessionsDao.findById(session, created.id).getOrThrow())
  }

  @Test
  fun `writeMetadata on a revoked session is not found and writes nothing`() {
    val tokenHash = TokenHash(byteArrayOf(65, 1))
    val created = createSession(createUser(), tokenHash.value)
    val revoked = SessionsDao.revokeByTokenHash(session, tokenHash).getOrThrow()

    val gone = SessionsDao.writeMetadata(session, created.id, revoked.version, metadataWith(ByteArray(32), "a@example.com"))

    assertIs<NotFoundException>(gone.exceptionOrNull())
    assertEquals(revoked, SessionsDao.findById(session, created.id).getOrThrow())
  }

  @Test
  fun `remintToken replaces the metadata the row held for its previous holder`() {
    val first = createUser()
    val created = createSession(first, byteArrayOf(66, 1))
    val held =
      SessionsDao
        .writeMetadata(
          session,
          created.id,
          created.version,
          metadataWith(
            ByteArray(32) {
              1
            },
            "first@example.com",
          ),
        ).getOrThrow()
    val fresh = SessionMetadata.newBuilder().setCsrfToken(ByteString.copyFrom(ByteArray(32) { 2 })).build()

    val reminted =
      SessionsDao
        .remintToken(
          session = session,
          id = held.id,
          currentVersion = held.version,
          newUserId = createUser(),
          newTokenHash = byteArrayOf(66, 2),
          newExpirationSeconds = Duration.ofDays(7).seconds,
          newLoginMethod = LoginMethod.PASSWORD,
          newMetadata = fresh,
        ).getOrThrow()

    assertEquals(fresh, reminted.metadata)
  }
}
