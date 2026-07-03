package ed.unicoach.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ToolRegistryTest {
  private fun tool(toolName: String): ChatTool =
    object : ChatTool {
      override val name = toolName
      override val definition = buildJsonObject { put("name", toolName) }

      override suspend fun execute(input: JsonObject): JsonObject = buildJsonObject { put("ok", true) }
    }

  @Test
  fun `get returns the registered tool and null for an unknown name`() {
    val alpha = tool("alpha")
    val registry = ToolRegistry(listOf(alpha, tool("beta")))

    assertSame(alpha, registry.get("alpha"))
    assertNull(registry.get("gamma"))
  }

  @Test
  fun `definitions preserve registration order`() {
    val registry = ToolRegistry(listOf(tool("alpha"), tool("beta")))

    assertEquals(
      listOf("alpha", "beta"),
      registry.definitions().map { it.getValue("name").toString().trim('"') },
    )
  }

  @Test
  fun `constructing with a duplicate name throws`() {
    assertFailsWith<IllegalArgumentException> {
      ToolRegistry(listOf(tool("alpha"), tool("alpha")))
    }
  }
}
