package ed.unicoach.account

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The deletion confirmation's interim copy (RFC 267 §6), rendered from an empty context. */
class AccountErasedEmailRendererTest {
  private val renderer = AccountErasedEmailRenderer()

  @Test
  fun `renders the subject and body from an empty context`() {
    val rendered = renderer.render(JsonObject(emptyMap())).getOrThrow()

    assertEquals("Your unicoach account has been deleted", rendered.subject.value)
    assertEquals(
      "Your unicoach account has been deleted, as you asked, and the information in it has been erased.\n\n" +
        "We keep a record that the account existed and when it was deleted, your subscription record with Apple, " +
        "and our usage records with your words removed.\n\n" +
        "If you subscribed through Apple, the subscription continues until you cancel it in your Apple ID settings.\n\n" +
        "— unicoach",
      rendered.body.value,
    )
  }

  @Test
  fun `refuses a context that carries anything`() {
    val result = renderer.render(JsonObject(mapOf("name" to JsonPrimitive("Ava"))))

    assertTrue(result.isFailure, "no field of a context could be rendered, so one is a producer's mistake")
  }
}
