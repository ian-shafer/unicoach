package ed.unicoach.email

import ed.unicoach.common.config.AppConfig
import ed.unicoach.common.json.asJson
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.queue.JobResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EmailSendHandlerTest {
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
      if (::database.isInitialized) database.close()
    }
  }

  @BeforeEach
  fun resetDatabase() {
    DriverManager.getConnection(jdbcUrl, dbUser, dbPassword ?: "").use { c ->
      c.createStatement().use { stmt -> stmt.execute("TRUNCATE TABLE email_sends") }
    }
  }

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

  // A fixed-copy renderer standing in for VerificationEmailRenderer; the handler
  // only depends on the EmailTemplateRenderer port, not the service/auth impl.
  private class StubVerificationRenderer(
    private val urlBase: String = "https://uni.coach/verify-email",
  ) : EmailTemplateRenderer {
    override val template = EmailTemplate.EMAIL_VERIFICATION

    override fun render(context: JsonObject): Result<RenderedEmail> {
      val token =
        (context["verifyToken"] as? JsonPrimitive)?.content
          ?: return Result.failure(IllegalArgumentException("missing verifyToken"))
      val subject = (EmailSubject.create("Verify your email address") as ed.unicoach.common.models.ValidationResult.Valid).value
      val body =
        (
          EmailBody.create("Confirm your email by visiting:\n\n$urlBase?token=$token")
            as ed.unicoach.common.models.ValidationResult.Valid
        ).value
      return Result.success(RenderedEmail(subject, body))
    }
  }

  private fun config(defaultFrom: String = "noreply@uni.coach") =
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

  private fun handler(
    provider: EmailProvider,
    renderers: List<EmailTemplateRenderer> = listOf(StubVerificationRenderer()),
    db: Database = database,
  ): EmailSendHandler = EmailSendHandler(EmailService(db, provider, config()), renderers)

  private fun verificationPayload(
    to: String = "user@example.com",
    token: String = "tok-abc",
  ): JsonObject =
    EmailJobPayload(
      to = to,
      template = EmailTemplate.EMAIL_VERIFICATION,
      context = JsonObject(mapOf("verifyToken" to JsonPrimitive(token))),
    ).asJson()

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
            "recipient_email" to rs.getString("recipient_email"),
            "body" to rs.getString("body"),
          )
        }
      }
    }
  }

  @Test
  fun `valid job sends, records SENT, returns Success`() =
    runTest {
      val provider = FakeProvider(ProviderResult.Sent("pm-1"))
      val result = handler(provider).execute(verificationPayload(token = "link-token"))

      assertEquals(JobResult.Success, result)
      assertEquals(1, countRows())
      val row = firstRow()
      assertEquals("SENT", row["status"])
      assertTrue(
        row["body"]?.contains("https://uni.coach/verify-email?token=link-token") == true,
        "Body must carry the rendered verify link, got ${row["body"]}",
      )
    }

  @Test
  fun `permanent rejection records REJECTED and returns PermanentFailure naming recipient and template`() =
    runTest {
      val provider = FakeProvider(ProviderResult.Rejected("bad recipient"))
      val result = handler(provider).execute(verificationPayload(to = "user@example.com"))

      val failure = assertIs<JobResult.PermanentFailure>(result, "Expected PermanentFailure, got $result")
      assertEquals(1, countRows())
      assertEquals("REJECTED", firstRow()["status"])
      // A dead-lettered provider rejection must be triageable from job_attempts.error_message
      // alone: the message names the recipient and template, not just the provider's reason.
      val message = failure.message
      assertTrue(message.contains("user@example.com"), "Message must carry the recipient, got: $message")
      assertTrue(message.contains("EMAIL_VERIFICATION"), "Message must carry the template, got: $message")
      assertTrue(message.contains("bad recipient"), "Message must preserve the provider reason, got: $message")
    }

  @Test
  fun `transient failure returns RetriableFailure and writes no row`() =
    runTest {
      val provider = FakeProvider(ProviderResult.TransientFailure("timeout"))
      val result = handler(provider).execute(verificationPayload())

      assertTrue(result is JobResult.RetriableFailure, "Expected RetriableFailure, got $result")
      assertEquals(0, countRows())
    }

  @Test
  fun `malformed payload returns PermanentFailure`() =
    runTest {
      val provider = FakeProvider(ProviderResult.Sent("pm-1"))
      // A JsonObject missing the required `template`/`to` fields.
      val malformed = JsonObject(mapOf("nonsense" to JsonPrimitive("x")))
      val result = handler(provider).execute(malformed)

      assertTrue(result is JobResult.PermanentFailure, "Expected PermanentFailure, got $result")
      assertNull(provider.captured, "No send may be attempted for a malformed payload")
      assertEquals(0, countRows())
    }

  @Test
  fun `unresolvable template returns PermanentFailure carrying to and template context`() =
    runTest {
      val provider = FakeProvider(ProviderResult.Sent("pm-1"))
      val result =
        handler(provider, renderers = emptyList()).execute(verificationPayload(to = "user@example.com"))

      val failure = assertIs<JobResult.PermanentFailure>(result, "Expected PermanentFailure, got $result")
      val message = failure.message
      assertTrue(message.contains("EMAIL_VERIFICATION"), "Message must carry the template, got: $message")
      assertTrue(message.contains("user@example.com"), "Message must carry the recipient, got: $message")
      assertNull(provider.captured, "No send may be attempted with no renderer")
      assertEquals(0, countRows())
    }

  @Test
  fun `invalid recipient returns PermanentFailure preserving the ValidationError ADT`() =
    runTest {
      val provider = FakeProvider(ProviderResult.Sent("pm-1"))
      val result = handler(provider).execute(verificationPayload(to = "not-an-email"))

      val failure = assertIs<JobResult.PermanentFailure>(result, "Expected PermanentFailure, got $result")
      val message = failure.message
      // The typed InvalidRecipientException carries the recipient, template, and the
      // ValidationError ADT (InvalidFormat) — none is flattened away at the failure site.
      assertTrue(message.contains("not-an-email"), "Message must carry the recipient, got: $message")
      assertTrue(message.contains("EMAIL_VERIFICATION"), "Message must carry the template, got: $message")
      assertTrue(message.contains("InvalidFormat"), "Message must preserve the ValidationError ADT, got: $message")
      assertNull(provider.captured, "No send may be attempted for an invalid recipient")
      assertEquals(0, countRows())
    }

  @Test
  fun `render failure returns PermanentFailure carrying template context and root cause`() =
    runTest {
      // A renderer that always fails with a typed RenderValidationException; the
      // handler must wrap it in EmailRenderException preserving the [template]
      // context and the root cause, then fold to PermanentFailure.
      val failingRenderer =
        object : EmailTemplateRenderer {
          override val template = EmailTemplate.EMAIL_VERIFICATION

          override fun render(context: JsonObject): Result<RenderedEmail> =
            Result.failure(
              RenderValidationException("verification subject", ed.unicoach.common.models.ValidationError.Blank),
            )
        }
      val provider = FakeProvider(ProviderResult.Sent("pm-1"))
      val result =
        handler(provider, renderers = listOf(failingRenderer)).execute(verificationPayload(to = "user@example.com"))

      val failure = assertIs<JobResult.PermanentFailure>(result, "Expected PermanentFailure, got $result")
      val message = failure.message
      assertTrue(message.contains("EMAIL_VERIFICATION"), "Message must carry the template, got: $message")
      assertTrue(message.contains("user@example.com"), "Message must carry the recipient, got: $message")
      assertNull(provider.captured, "No send may be attempted when render fails")
      assertEquals(0, countRows())
    }

  @Test
  fun `render failure preserves the raw offending context in the failure message`() =
    runTest {
      // A renderer whose deserialization of the context fails, returning only the
      // raw throwable. The handler must fold to a PermanentFailure whose message
      // carries the raw offending context JSON so an operator sees what was
      // malformed without querying the jobs table.
      val failingRenderer =
        object : EmailTemplateRenderer {
          override val template = EmailTemplate.EMAIL_VERIFICATION

          override fun render(context: JsonObject): Result<RenderedEmail> =
            Result.failure(IllegalArgumentException("cannot deserialize context"))
        }
      val provider = FakeProvider(ProviderResult.Sent("pm-1"))
      // A context with an unexpected shape — the sentinel field must survive to the log.
      val payload =
        EmailJobPayload(
          to = "user@example.com",
          template = EmailTemplate.EMAIL_VERIFICATION,
          context = JsonObject(mapOf("unexpectedField" to JsonPrimitive("sentinel-value"))),
        ).asJson()
      val result = handler(provider, renderers = listOf(failingRenderer)).execute(payload)

      val failure = assertIs<JobResult.PermanentFailure>(result, "Expected PermanentFailure, got $result")
      val message = failure.message
      assertTrue(
        message.contains("unexpectedField") && message.contains("sentinel-value"),
        "Message must carry the raw offending context, got: $message",
      )
      assertNull(provider.captured, "No send may be attempted when render fails")
      assertEquals(0, countRows())
    }

  @Test
  fun `DB write failing after a successful send yields RetriableFailure`() =
    runTest {
      // A closed Database rejects the email_sends insert. EmailService.send returns
      // a Result.failure carrying the raw, unmarked DB exception; the handler
      // rethrows it and the worker's default maps it to RetriableFailure. Assert
      // the handler produces that mapping here by driving through the worker's
      // classification contract: an unmarked throwable surfaces as a thrown
      // exception from execute(), which the worker turns into RetriableFailure.
      val poisoned = Database(dbConfig).also { it.close() }
      val provider = FakeProvider(ProviderResult.Sent("pm-1"))

      val thrown =
        runCatching { handler(provider, db = poisoned).execute(verificationPayload()) }.exceptionOrNull()

      assertTrue(
        thrown != null,
        "The handler must rethrow the unmarked DB failure so the worker's catch maps it to RetriableFailure",
      )
      assertTrue(
        thrown !is EmailRejectedException && thrown !is EmailDeliveryException && thrown !is EmailConfigException,
        "The rethrown cause must be the raw, unmarked DB exception, got $thrown",
      )
    }
}
