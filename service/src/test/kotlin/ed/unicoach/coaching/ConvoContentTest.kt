package ed.unicoach.coaching

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ConvoContentTest {
  @Test
  fun `userContent shape is a single text block`() {
    val content = ConvoContent.userContent("hello world")
    val array = content as JsonArray
    assertEquals(1, array.size)
    val block = array[0] as JsonObject
    assertEquals("text", block["type"]!!.jsonPrimitive.content)
    assertEquals("hello world", block["text"]!!.jsonPrimitive.content)
  }

  @Test
  fun `renderText concatenates text blocks and ignores non-text blocks`() {
    val content =
      Json.parseToJsonElement(
        """
        [
          {"type":"text","text":"Hello "},
          {"type":"thinking","thinking":"hmm"},
          {"type":"tool_use","id":"t1","name":"x"},
          {"type":"text","text":"world"}
        ]
        """.trimIndent(),
      )
    assertEquals("Hello world", ConvoContent.renderText(content))
  }

  @Test
  fun `renderText yields empty string for non-array content`() {
    val obj = Json.parseToJsonElement("""{"type":"text","text":"hi"}""")
    assertEquals("", ConvoContent.renderText(obj))
  }

  @Test
  fun `round-trip renderText of userContent returns the original text`() {
    val original = "Some\nmulti-line\ttext with spaces"
    assertEquals(original, ConvoContent.renderText(ConvoContent.userContent(original)))
  }
}
