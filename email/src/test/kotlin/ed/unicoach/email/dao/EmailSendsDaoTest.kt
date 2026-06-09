package ed.unicoach.email.dao

import ed.unicoach.common.config.AppConfig
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.DaoException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.email.EmailBody
import ed.unicoach.email.EmailSendStatus
import ed.unicoach.email.EmailSubject
import ed.unicoach.error.PermanentError
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmailSendsDaoTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config = AppConfig.load("common.conf", "db.conf", "email.conf").getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
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
      stmt.execute("TRUNCATE TABLE email_sends")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun address(value: String): EmailAddress = (EmailAddress.create(value) as ValidationResult.Valid).value

  private fun subject(value: String): EmailSubject = (EmailSubject.create(value) as ValidationResult.Valid).value

  private fun body(value: String): EmailBody = (EmailBody.create(value) as ValidationResult.Valid).value

  private fun sentSend() =
    NewEmailSend(
      recipient = address("to@example.com"),
      sender = address("noreply@unicoach.app"),
      subject = subject("Welcome"),
      body = body("Hello there"),
      status = EmailSendStatus.SENT,
      provider = "log",
      providerMessageId = "msg-123",
      errorMessage = null,
    )

  private fun rejectedSend() =
    NewEmailSend(
      recipient = address("to@example.com"),
      sender = address("noreply@unicoach.app"),
      subject = subject("Welcome"),
      body = body("Hello there"),
      status = EmailSendStatus.REJECTED,
      provider = "log",
      providerMessageId = null,
      errorMessage = "mailbox full",
    )

  @Test
  fun `insert SENT row is readable with all fields intact`() {
    val result = EmailSendsDao.insert(session, sentSend())
    val sent = result.getOrThrow()
    assertNotNull(sent.id)
    assertEquals("msg-123", sent.providerMessageId)

    connection.prepareStatement("SELECT * FROM email_sends WHERE id = ?").use { stmt ->
      stmt.setObject(1, sent.id)
      stmt.executeQuery().use { rs ->
        assertTrue(rs.next())
        assertEquals("to@example.com", rs.getString("recipient_email"))
        assertEquals("noreply@unicoach.app", rs.getString("sender_email"))
        assertEquals("Welcome", rs.getString("subject"))
        assertEquals("Hello there", rs.getString("body"))
        assertEquals("SENT", rs.getString("status"))
        assertEquals("log", rs.getString("provider"))
        assertEquals("msg-123", rs.getString("provider_message_id"))
        assertNull(rs.getString("error_message"))
      }
    }
  }

  @Test
  fun `insert REJECTED row is readable with error message set and message id null`() {
    val sent = EmailSendsDao.insert(session, rejectedSend()).getOrThrow()

    connection.prepareStatement("SELECT * FROM email_sends WHERE id = ?").use { stmt ->
      stmt.setObject(1, sent.id)
      stmt.executeQuery().use { rs ->
        assertTrue(rs.next())
        assertEquals("REJECTED", rs.getString("status"))
        assertEquals("mailbox full", rs.getString("error_message"))
        assertNull(rs.getString("provider_message_id"))
      }
    }
  }

  @Test
  fun `CHECK constraint rejects an out-of-domain status`() {
    assertFailsWith<SQLException> {
      connection.createStatement().use { stmt ->
        stmt.execute(
          """
          INSERT INTO email_sends (recipient_email, sender_email, subject, body, status, provider)
          VALUES ('to@example.com', 'noreply@unicoach.app', 's', 'b', 'PENDING', 'log')
          """.trimIndent(),
        )
      }
    }
  }

  @Test
  fun `UPDATE on a row is blocked by the log guard`() {
    val sent = EmailSendsDao.insert(session, sentSend()).getOrThrow()
    assertFailsWith<SQLException> {
      connection.prepareStatement("UPDATE email_sends SET subject = 'changed' WHERE id = ?").use { stmt ->
        stmt.setObject(1, sent.id)
        stmt.executeUpdate()
      }
    }
  }

  @Test
  fun `DELETE on a row is blocked by the log guard`() {
    val sent = EmailSendsDao.insert(session, sentSend()).getOrThrow()
    assertFailsWith<SQLException> {
      connection.prepareStatement("DELETE FROM email_sends WHERE id = ?").use { stmt ->
        stmt.setObject(1, sent.id)
        stmt.executeUpdate()
      }
    }
  }

  @Test
  fun `omitting a NOT NULL column is rejected`() {
    assertFailsWith<SQLException> {
      connection.createStatement().use { stmt ->
        stmt.execute(
          """
          INSERT INTO email_sends (recipient_email, sender_email, subject, status, provider)
          VALUES ('to@example.com', 'noreply@unicoach.app', 's', 'SENT', 'log')
          """.trimIndent(),
        )
      }
    }
  }

  @Test
  fun `a constraint violation insert returns failure whose cause is a permanent DaoException`() {
    val sql =
      """
      INSERT INTO email_sends (recipient_email, sender_email, subject, body, status, provider)
      VALUES (?, ?, ?, ?, ?, ?)
      RETURNING *
      """.trimIndent()
    val result =
      try {
        session.prepareStatement(sql).use { stmt ->
          stmt.setString(1, "to@example.com")
          stmt.setString(2, "noreply@unicoach.app")
          stmt.setString(3, "s")
          stmt.setString(4, "b")
          stmt.setString(5, "PENDING") // out-of-domain status -> CHECK violation
          stmt.setString(6, "log")
          stmt.executeQuery().use { Result.success(Unit) }
        }
      } catch (e: Exception) {
        Result.failure(
          ed.unicoach.db.dao
            .mapDatabaseError(e),
        )
      }

    assertTrue(result.isFailure)
    val cause = result.exceptionOrNull()
    assertTrue(cause is DaoException)
    assertTrue(cause is PermanentError)
  }
}
