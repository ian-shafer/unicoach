package ed.unicoach.auth

import ed.unicoach.common.models.ValidationError
import ed.unicoach.email.RenderValidationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VerificationEmailRendererTest {
  private val renderer = VerificationEmailRenderer(verifyUrlBase = "https://uni.coach/verify-email")

  @Test
  fun `renders fixed subject, body, and verify link`() {
    val context = JsonObject(mapOf("verifyToken" to JsonPrimitive("abc")))

    val rendered = renderer.render(context).getOrThrow()

    assertEquals("Verify your email address", rendered.subject.value)
    assertTrue(
      rendered.body.value.contains("https://uni.coach/verify-email?token=abc"),
      "Body must carry the verify link, got ${rendered.body.value}",
    )
    assertTrue(
      rendered.body.value.contains("Welcome to Unicoach"),
      "Body must carry the welcome copy, got ${rendered.body.value}",
    )
  }

  @Test
  fun `missing verifyToken returns failure preserving the deserialization cause`() {
    val result = renderer.render(JsonObject(emptyMap()))
    assertTrue(result.isFailure, "An empty context must fail to render")
    // The root deserialization throwable is preserved unaltered (not flattened to a
    // string), so the handler can wrap it in EmailRenderException with its cause.
    val cause = result.exceptionOrNull()
    assertTrue(cause != null, "The failure must carry the underlying deserialization throwable")
  }

  @Test
  fun `subject or body validation failure surfaces as a typed RenderValidationException`() {
    // A verifyUrlBase long enough to blow the EmailBody length bound drives the
    // body ValidationResult.Invalid branch, which must surface as a typed
    // RenderValidationException carrying the ValidationError ADT — not an opaque
    // IllegalStateException string.
    val overlongBase = "https://uni.coach/verify-email?pad=" + "x".repeat(200_000)
    val longBaseRenderer = VerificationEmailRenderer(verifyUrlBase = overlongBase)
    val context = JsonObject(mapOf("verifyToken" to JsonPrimitive("abc")))

    val result = longBaseRenderer.render(context)

    assertTrue(result.isFailure, "An overlong body must fail to render")
    val cause =
      assertIs<RenderValidationException>(
        result.exceptionOrNull(),
        "The failure must be a typed RenderValidationException carrying the ValidationError",
      )
    assertTrue(
      cause.validationError is ValidationError.TooLong,
      "The ValidationError ADT (TooLong) must be preserved, got ${cause.validationError}",
    )
  }
}
