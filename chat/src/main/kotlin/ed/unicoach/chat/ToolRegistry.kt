package ed.unicoach.chat

import kotlinx.serialization.json.JsonObject

/**
 * The set of tools advertised on every turn, indexed by name. Constructed once
 * at the composition root from a static list; there is no runtime discovery and
 * no per-turn subsetting — every turn advertises the full registry. A duplicate
 * tool name fails construction fast (an ambiguous dispatch key is a wiring bug,
 * not a runtime condition to recover from).
 */
class ToolRegistry(
  tools: List<ChatTool>,
) {
  // Registration order is preserved so definitions() is stable on the wire.
  private val byName: Map<String, ChatTool> =
    LinkedHashMap<String, ChatTool>().apply {
      for (tool in tools) {
        val existing = put(tool.name, tool)
        require(existing == null) { "duplicate tool name [${tool.name}] in ToolRegistry" }
      }
    }

  /** The tool specs for [ChatRequest.tools], in registration order. */
  fun definitions(): List<JsonObject> = byName.values.map { it.definition }

  /** The tool registered under [name]; null when the model named an unknown tool. */
  fun get(name: String): ChatTool? = byName[name]
}
