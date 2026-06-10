package ed.unicoach.email

import ed.unicoach.common.config.AppConfig
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EmailServiceTest {
  companion object {
    private lateinit var database: Database
    private lateinit var dbConfig: DatabaseConfig
    private lateinit var jdbcUrl: String
    private lateinit var dbUser: String
    private var dbPassword: String? = null

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config = AppConfig.load("common.conf", "db.conf", "email.conf").getOrThrow()
      dbConfig = DatabaseConfig.from(config).getOrThrow()
      jdbcUrl = dbConfig.jdbcUrl
      dbUser = dbConfig.user
      dbPassword = dbConfig.password
      database = Database(dbConfig)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) {
        database.close()
      }
    }
  }

  @BeforeEach
  fun resetDatabase() {
    DriverManager.getConnection(jdbcUrl, dbUser, dbPassword ?: "").use { c ->
      c.createStatement().use { stmt -> stmt.execute("TRUNCATE TABLE email_sends") }
    }
  }

  private fun address(value: String): EmailAddress = (EmailAddress.create(value) as ValidationResult.Valid).value

  private fun subject(value: String): EmailSubject = (EmailSubject.create(value) as ValidationResult.Valid).value

  private fun body(value: String): EmailBody = (EmailBody.create(value) as ValidationResult.Valid).value

  private class FakeProvider(
    private val outcome: ProviderResult,
    override val id: String = "fake",
  ) : EmailProvider {
    var captured: OutboundEmail? = null

    override suspend fun send(email: OutboundEmail): ProviderResult {
      captured = email
      return outcome
    }
  }

  private fun config(defaultFrom: String = "noreply@unicoach.app") =
    EmailConfig
      .from(
        com.typesafe.config.ConfigFactory
          .parseString(
            """
            email.defaultFrom = "$defaultFrom"
            email.provider = "log"
            email.ses.region = "us-east-1"
            """.trimIndent(),
          ),
      ).getOrThrow()

  private fun countRows(): Int {
    DriverManager.getConnection(jdbcUrl, dbUser, dbPassword ?: "").use { c ->
      c.prepareStatement("SELECT COUNT(*) FROM email_sends").use { stmt ->
        stmt.executeQuery().use { rs ->
          rs.next()
          return rs.getInt(1)
        }
      }
    }
  }

  private fun firstRow(): Map<String, String?> {
    DriverManager.getConnection(jdbcUrl, dbUser, dbPassword ?: "").use { c ->
      c.prepareStatement("SELECT * FROM email_sends").use { stmt ->
        stmt.executeQuery().use { rs ->
          assertTrue(rs.next())
          return mapOf(
            "status" to rs.getString("status"),
            "error_message" to rs.getString("error_message"),
            "provider_message_id" to rs.getString("provider_message_id"),
            "provider" to rs.getString("provider"),
          )
        }
      }
    }
  }

  @Test
  fun `provider Sent writes exactly one SENT row and returns success`() =
    runTest {
      val provider = FakeProvider(ProviderResult.Sent("pm-1"))
      val service = EmailService(database, provider, config())

      val result = service.send(address("to@example.com"), subject("Hi"), body("Body"))

      assertTrue(result.isSuccess)
      val sent = result.getOrThrow()
      assertNotNull(sent.id)
      assertEquals("pm-1", sent.providerMessageId)
      assertEquals(1, countRows())
      val row = firstRow()
      assertEquals("SENT", row["status"])
      // The provider column is sourced from provider.id, not a hardcoded literal.
      assertEquals(provider.id, row["provider"])
    }

  @Test
  fun `provider Rejected writes exactly one REJECTED row and returns a permanent failure`() =
    runTest {
      val provider = FakeProvider(ProviderResult.Rejected("bad recipient"))
      val service = EmailService(database, provider, config())

      val result = service.send(address("to@example.com"), subject("Hi"), body("Body"))

      assertTrue(result.isFailure)
      assertTrue(result.exceptionOrNull() is PermanentError)
      assertEquals(1, countRows())
      val row = firstRow()
      assertEquals("REJECTED", row["status"])
      assertEquals("bad recipient", row["error_message"])
      // The provider column is sourced from provider.id, not a hardcoded literal.
      assertEquals(provider.id, row["provider"])
    }

  @Test
  fun `provider TransientFailure writes no row and returns a transient failure`() =
    runTest {
      val provider = FakeProvider(ProviderResult.TransientFailure("timeout"))
      val service = EmailService(database, provider, config())

      val result = service.send(address("to@example.com"), subject("Hi"), body("Body"))

      assertTrue(result.isFailure)
      assertTrue(result.exceptionOrNull() is TransientError)
      assertEquals(0, countRows())
    }

  @Test
  fun `invalid defaultFrom fails permanently without invoking the provider or writing a row`() =
    runTest {
      val provider = FakeProvider(ProviderResult.Sent("pm-1"))
      val service = EmailService(database, provider, config(defaultFrom = "not-an-email"))

      val result = service.send(address("to@example.com"), subject("Hi"), body("Body"))

      assertTrue(result.isFailure)
      assertTrue(result.exceptionOrNull() is PermanentError)
      assertNull(provider.captured)
      assertEquals(0, countRows())
    }

  @Test
  fun `ledger insert failure after a Sent outcome propagates the DAO cause unaltered`() =
    runTest {
      val poisoned = Database(dbConfig).also { it.close() }
      val provider = FakeProvider(ProviderResult.Sent("pm-1"))
      val service = EmailService(poisoned, provider, config())

      val result = service.send(address("to@example.com"), subject("Hi"), body("Body"))

      assertTrue(result.isFailure)
      // The DAO/DB failure is propagated, not swallowed or remapped to an email exception.
      val cause = result.exceptionOrNull()
      assertTrue(cause !is EmailRejectedException && cause !is EmailDeliveryException && cause !is EmailConfigException)
    }

  @Test
  fun `fake provider captures the outbound email with the configured from and pass-through fields`() =
    runTest {
      val provider = FakeProvider(ProviderResult.Sent("pm-1"))
      val service = EmailService(database, provider, config())

      service.send(address("to@example.com"), subject("Subject line"), body("Body text"))

      val captured = provider.captured
      assertNotNull(captured)
      assertEquals("noreply@unicoach.app", captured.from.value)
      assertEquals("to@example.com", captured.to.value)
      assertEquals("Subject line", captured.subject.value)
      assertEquals("Body text", captured.body.value)
    }
}
