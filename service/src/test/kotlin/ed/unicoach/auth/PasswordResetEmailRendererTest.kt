package ed.unicoach.auth

import ed.unicoach.email.EmailTemplate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasswordResetEmailRendererTest {
  private val renderer = PasswordResetEmailRenderer(resetUrlBase = "https://uni.coach/reset-password")

  @Test
  fun `renders the subject, the words and the link`() {
    val rendered = renderer.render(JsonObject(mapOf("resetToken" to JsonPrimitive("abc")))).getOrThrow()

    assertEquals(EmailTemplate.PASSWORD_RESET, renderer.template)
    assertEquals("Reset your Unicoach password", rendered.subject.value)
    assertEquals(
      "Someone asked to reset the password for your Unicoach account. To choose a new password, visit:\n\n" +
        "https://uni.coach/reset-password?token=abc\n\n" +
        "This link works once and expires soon. If you did not ask for this, you can ignore this message " +
        "and your password will stay the same.",
      rendered.body.value,
    )
  }

  @Test
  fun `a context that cannot be deserialized fails with its cause`() {
    val result = renderer.render(JsonObject(emptyMap()))

    assertTrue(result.isFailure, "An empty context must fail to render")
    assertTrue(result.exceptionOrNull() != null, "The failure must carry the underlying deserialization throwable")
  }
}
