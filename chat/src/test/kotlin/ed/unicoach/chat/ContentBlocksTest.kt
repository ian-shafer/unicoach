package ed.unicoach.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ContentBlocksTest {
  @Test
  fun `toolUseInput returns the input object of a tool_use block`() {
    val content =
      buildJsonArray {
        add(
          buildJsonObject {
            put("type", "tool_use")
            put("id", "toolu_1")
            put("name", "record_extraction")
            put(
              "input",
              buildJsonObject {
                put("observations", JsonArray(emptyList()))
                put("claims", JsonArray(emptyList()))
              },
            )
          },
        )
      }

    val input = ContentBlocks.toolUseInput(content, "record_extraction")
    assertNotNull(input)
    assertEquals(JsonArray(emptyList()), input["observations"])
    assertEquals(JsonArray(emptyList()), input["claims"])
  }

  @Test
  fun `toolUseInput returns null for a text-only block array`() {
    val content =
      buildJsonArray {
        add(
          buildJsonObject {
            put("type", "text")
            put("text", "no tool call here")
          },
        )
      }
    assertNull(ContentBlocks.toolUseInput(content, "record_extraction"))
  }

  @Test
  fun `toolUseInput returns null for a non-array element`() {
    assertNull(ContentBlocks.toolUseInput(JsonPrimitive("not an array"), "record_extraction"))
    assertNull(ContentBlocks.toolUseInput(JsonObject(emptyMap()), "record_extraction"))
  }

  @Test
  fun `toolUseInput returns null for a tool_use block whose name mismatches the expected tool`() {
    val content =
      buildJsonArray {
        add(
          buildJsonObject {
            put("type", "tool_use")
            put("id", "toolu_1")
            put("name", "some_other_tool")
            put("input", buildJsonObject { put("k", "v") })
          },
        )
      }
    assertNull(ContentBlocks.toolUseInput(content, "record_extraction"))
  }

  @Test
  fun `toolUseInput returns an empty object for a tool_use block whose input is absent`() {
    val content =
      buildJsonArray {
        add(
          buildJsonObject {
            put("type", "tool_use")
            put("id", "toolu_1")
            put("name", "record_extraction")
          },
        )
      }
    assertEquals(JsonObject(emptyMap()), ContentBlocks.toolUseInput(content, "record_extraction"))
  }

  @Test
  fun `toolUseInput returns an empty object for a tool_use block whose input is non-object`() {
    val content =
      buildJsonArray {
        add(
          buildJsonObject {
            put("type", "tool_use")
            put("id", "toolu_1")
            put("name", "record_extraction")
            put("input", JsonPrimitive("oops"))
          },
        )
      }
    assertEquals(JsonObject(emptyMap()), ContentBlocks.toolUseInput(content, "record_extraction"))
  }

  @Test
  fun `toolUseInput takes the first tool_use block matching the expected name`() {
    val content =
      buildJsonArray {
        add(
          buildJsonObject {
            put("type", "tool_use")
            put("id", "toolu_1")
            put("name", "record")
            put(
              "input",
              buildJsonObject { put("which", "first") },
            )
          },
        )
        add(
          buildJsonObject {
            put("type", "tool_use")
            put("id", "toolu_2")
            put("name", "record")
            put(
              "input",
              buildJsonObject { put("which", "second") },
            )
          },
        )
      }
    val input = ContentBlocks.toolUseInput(content, "record")
    assertEquals("first", (input?.get("which") as? JsonPrimitive)?.content)
  }

  @Test
  fun `toolUseInput skips a differently-named tool_use block and reads the expected one`() {
    val content =
      buildJsonArray {
        add(
          buildJsonObject {
            put("type", "tool_use")
            put("id", "toolu_1")
            put("name", "other")
            put("input", buildJsonObject { put("which", "other") })
          },
        )
        add(
          buildJsonObject {
            put("type", "tool_use")
            put("id", "toolu_2")
            put("name", "record")
            put("input", buildJsonObject { put("which", "record") })
          },
        )
      }
    val input = ContentBlocks.toolUseInput(content, "record")
    assertEquals("record", (input?.get("which") as? JsonPrimitive)?.content)
  }

  @Test
  fun `toolUseInput skips leading text blocks and reads the tool_use block`() {
    val content =
      buildJsonArray {
        add(
          buildJsonObject {
            put("type", "text")
            put("text", "preamble")
          },
        )
        add(
          buildJsonObject {
            put("type", "tool_use")
            put("id", "toolu_1")
            put("name", "record")
            put(
              "input",
              buildJsonObject { put("ok", true) },
            )
          },
        )
      }
    val input = ContentBlocks.toolUseInput(content, "record")
    assertEquals(true, (input?.get("ok") as? JsonPrimitive)?.content?.toBoolean())
  }
}
