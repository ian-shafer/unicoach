package ed.unicoach.auth

import com.google.protobuf.ByteString
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.RawSql
import ed.unicoach.db.dao.SessionMetadataFixtures
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.models.LoginMethod
import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.Session
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import ed.unicoach.db.session.ChangeEmailDraft
import ed.unicoach.db.session.FormDrafts
import ed.unicoach.db.session.SessionMetadata
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [SessionMetadataService] against the real database (RFC 265): the one-UPDATE
 * common case, the bounded retry through a REAL interleaved `extendExpiry`, and
 * the two outcomes a family can cause.
 *
 * "One UPDATE" is read off the row's version: the versioning trigger lets an
 * UPDATE move it by exactly one, so the version a write returns counts the
 * UPDATEs that reached the row.
 */
class SessionMetadataServiceTest {
  companion object {
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf", "service.conf")
          .getOrThrow()
      database = Database(DatabaseConfig.from(config).getOrThrow())
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
    }
  }

  private val service by lazy { SessionMetadataService(database) }

  @BeforeEach
  fun resetDatabase() {
    database.createRawConnection().use { connection ->
      connection.createStatement().use { it.execute("TRUNCATE TABLE sessions, users CASCADE") }
    }
  }

  private fun liveSession(): Session {
    val userId = UUID.randomUUID()
    database.createRawConnection().use { connection ->
      RawSql.execute(
        connection,
        "INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, 'Meta User', 'ahash')",
        userId,
        "meta-$userId@test.com",
      )
    }
    return runBlocking {
      database.withConnection { sql ->
        SessionsDao
          .create(
            sql,
            NewSession(
              userId = UserId(userId),
              tokenHash = TokenHash(UUID.randomUUID().toString().toByteArray()),
              userAgent = null,
              initialIp = null,
              metadata = SessionMetadata.newBuilder().setCsrfToken(ByteString.copyFrom(ByteArray(32) { 7 })).build(),
              expiration = Duration.ofDays(30),
              loginMethod = LoginMethod.PASSWORD,
            ),
          ).getOrThrow()
      }
    }
  }

  private fun withDraft(newEmail: String): (SessionMetadata) -> SessionMetadata =
    { stored ->
      stored
        .toBuilder()
        .setDrafts(FormDrafts.newBuilder().setChangeEmail(ChangeEmailDraft.newBuilder().setNewEmail(newEmail)))
        .build()
    }

  private fun reread(session: Session): Session =
    runBlocking {
      database.withConnection { SessionsDao.findById(it, session.id).getOrThrow() }
    }

  @Test
  fun `an uncontended update is one UPDATE and keeps what it did not change`() {
    runBlocking {
      val seen = liveSession()

      val outcome = service.update(seen, withDraft("draft@example.com")).getOrThrow()

      val written = assertIs<MetadataWrite.Written>(outcome).session
      assertEquals(seen.version + 1, written.version, "exactly one UPDATE reached the row")
      assertEquals("draft@example.com", written.metadata.drafts.changeEmail.newEmail)
      assertEquals(seen.metadata.csrfToken, written.metadata.csrfToken, "the token is untouched")
      assertEquals(seen.expiresAt, written.expiresAt, "saving a draft never keeps a session alive")
    }
  }

  @Test
  fun `a conflict from an interleaved expiry refresh is retried on the fresh row`() {
    runBlocking {
      val seen = liveSession()
      var calls = 0
      var extended: Session? = null
      val change: (SessionMetadata) -> SessionMetadata = { stored ->
        calls++
        // Between this request's read and its write, the iOS app's sliding
        // refresh lands on the same row, on its own connection.
        if (calls == 1) {
          extended = runBlocking { database.withConnection { SessionsDao.extendExpiry(it, seen.id, seen.version).getOrThrow() } }
        }
        withDraft("retried@example.com")(stored)
      }

      val outcome = service.update(seen, change).getOrThrow()

      val written = assertIs<MetadataWrite.Written>(outcome).session
      assertEquals(2, calls, "the change was applied again to the re-read row")
      assertEquals(extended!!.expiresAt, written.expiresAt, "the refresh is kept")
      assertEquals(seen.version + 2, written.version, "the refresh, then this write")
      assertEquals("retried@example.com", written.metadata.drafts.changeEmail.newEmail)
      assertEquals(written, reread(seen))
    }
  }

  @Test
  fun `a change past the cap is TooLarge and writes nothing`() {
    runBlocking {
      val seen = liveSession()
      val huge = SessionMetadataFixtures.textPastTheCap()

      val outcome = service.update(seen, withDraft(huge)).getOrThrow()

      assertEquals(MetadataWrite.TooLarge, outcome)
      assertEquals(seen, reread(seen))
    }
  }

  /**
   * A NUL is text a family can POST and `jsonb` cannot hold (SQLSTATE `22P05`):
   * a refusal they caused, answered as an outcome like an overflow, never a
   * failed [Result] that a page would render as a fault.
   */
  @Test
  fun `a change holding text the column cannot store is Unstorable and writes nothing`() {
    runBlocking {
      val seen = liveSession()

      val outcome = service.update(seen, withDraft("nul\u0000here@example.com")).getOrThrow()

      assertEquals(MetadataWrite.Unstorable, outcome)
      assertEquals(seen, reread(seen))
    }
  }

  @Test
  fun `a revoked session is SessionGone`() {
    runBlocking {
      val seen = liveSession()
      database.withConnection { sql ->
        sql.prepareStatement("UPDATE sessions SET is_revoked = true, version = version + 1 WHERE id = ?").use {
          it.setObject(1, seen.id.value)
          it.executeUpdate()
        }
      }

      assertEquals(MetadataWrite.SessionGone, service.update(seen, withDraft("gone@example.com")).getOrThrow())
    }
  }
}
