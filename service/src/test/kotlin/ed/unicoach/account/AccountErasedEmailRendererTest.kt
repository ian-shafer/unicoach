package ed.unicoach.account

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The deletion confirmation's copy (RFC 269 §3), rendered from an empty context. */
class AccountErasedEmailRendererTest {
  private val renderer = AccountErasedEmailRenderer()

  @Test
  fun `renders the subject and body from an empty context`() {
    val rendered = renderer.render(JsonObject(emptyMap())).getOrThrow()

    assertEquals("Your unicoach account has been deleted", rendered.subject.value)
    assertEquals(
      "Your unicoach account has been deleted, as you asked, and everything in it has been erased.\n\n" +
        "What we keep: a record that your account existed and when it was deleted; your subscription record " +
        "with Apple, if you have one; and our usage records — how much you used the coach — with your words " +
        "removed.\n\n" +
        "Our AI provider keeps conversation text for up to 30 days with no account attached, then deletes it.\n\n" +
        "If you subscribed through Apple, the subscription continues until you cancel it in your Apple ID settings.\n\n" +
        "— unicoach",
      rendered.body.value,
    )
  }

  @Test
  fun `carries no link, since there is no account left to link to`() {
    val rendered = renderer.render(JsonObject(emptyMap())).getOrThrow()

    assertFalse(rendered.body.value.contains("http"), "the deletion email must carry no link")
  }

  @Test
  fun `refuses a context that carries anything`() {
    val result = renderer.render(JsonObject(mapOf("name" to JsonPrimitive("Ava"))))

    assertTrue(result.isFailure, "no field of a context could be rendered, so one is a producer's mistake")
  }
}
