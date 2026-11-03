package ed.unicoach.email

import ed.unicoach.common.json.asJson
import ed.unicoach.common.json.deserialize
import ed.unicoach.db.models.UserId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmailJobPayloadTest {
  private val context = JsonObject(mapOf("verifyToken" to JsonPrimitive("abc-123")))

  private fun payload(account: EmailAccountKey?) =
    EmailJobPayload(to = "user@example.com", template = EmailTemplate.EMAIL_VERIFICATION, context = context, account = account)

  private fun json(text: String): JsonObject = Json.decodeFromString<JsonObject>(text)

  @Test
  fun `EmailJobPayload round-trips through JsonObject for every account`() {
    val id = UserId(UUID.randomUUID())
    for (account in listOf(null, EmailAccountKey.Live(id), EmailAccountKey.Deleted(id))) {
      assertEquals(payload(account), payload(account).asJson().deserialize<EmailJobPayload>())
    }
  }

  /**
   * RFC 267: SEND_EMAIL jobs already queued carry `{to, template, context,
   * userId}`. The account became one value in Kotlin; the wire did not change,
   * so those jobs still decode, and a new one is written the same way.
   */
  @Test
  fun `a job queued before RFC 267 decodes, and a live or no-account payload is written as it was`() {
    val id = UUID.randomUUID()
    val queued =
      json(
        """{"to":"user@example.com","template":"EMAIL_VERIFICATION","context":{"verifyToken":"abc-123"},"userId":"$id"}""",
      )
    val noAccount =
      json(
        """{"to":"user@example.com","template":"EMAIL_VERIFICATION","context":{"verifyToken":"abc-123"},"userId":null}""",
      )

    assertEquals(payload(EmailAccountKey.Live(UserId(id))), queued.deserialize<EmailJobPayload>())
    assertEquals(payload(null), noAccount.deserialize<EmailJobPayload>())
    assertEquals(queued, payload(EmailAccountKey.Live(UserId(id))).asJson())
    assertEquals(noAccount, payload(null).asJson())
  }

  @Test
  fun `an erased account is written as deletedUserId`() {
    val id = UUID.randomUUID()

    assertEquals(
      json(
        """{"to":"user@example.com","template":"EMAIL_VERIFICATION","context":{"verifyToken":"abc-123"},""" +
          """"userId":null,"deletedUserId":"$id"}""",
      ),
      payload(EmailAccountKey.Deleted(UserId(id))).asJson(),
    )
  }

  @Test
  fun `a wire payload naming two accounts, or an id that is not a UUID, does not decode`() {
    val live = UUID.randomUUID()
    val erased = UUID.randomUUID()
    val base = """"to":"user@example.com","template":"EMAIL_VERIFICATION","context":{}"""

    val both =
      assertFailsWith<InvalidAccountKeyException.NamesBothAccounts> {
        json("""{$base,"userId":"$live","deletedUserId":"$erased"}""").deserialize<EmailJobPayload>()
      }
    assertEquals(listOf(live.toString(), erased.toString()), listOf(both.live, both.deleted))
    assertEquals("user@example.com" to EmailTemplate.EMAIL_VERIFICATION, both.to to both.template)

    val notAUuid =
      assertFailsWith<InvalidAccountKeyException.NotAUuid> {
        json("""{$base,"userId":"not-a-uuid"}""").deserialize<EmailJobPayload>()
      }
    assertEquals("not-a-uuid", notAUuid.raw)
  }
}
