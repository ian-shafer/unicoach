package ed.unicoach.email

import ed.unicoach.common.json.asJson
import ed.unicoach.common.json.deserialize
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EmailJobPayloadTest {
  @Test
  fun `EmailJobPayload round-trips through JsonObject`() {
    val context = JsonObject(mapOf("verifyToken" to JsonPrimitive("abc-123")))
    val payload =
      EmailJobPayload(
        to = "user@example.com",
        template = EmailTemplate.EMAIL_VERIFICATION,
        context = context,
      )

    val decoded = payload.asJson().deserialize<EmailJobPayload>()

    assertEquals(payload.to, decoded.to)
    assertEquals(payload.template, decoded.template)
    assertEquals(payload.context, decoded.context)
    assertEquals(payload, decoded)
  }
}
