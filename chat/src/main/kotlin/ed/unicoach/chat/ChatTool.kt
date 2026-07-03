package ed.unicoach.chat

import kotlinx.serialization.json.JsonObject

/**
 * The registration contract for a tool the coach may call mid-conversation. A
 * tool is a verbatim Anthropic [definition] plus a total [execute]: it signals a
 * domain failure by returning an error-shaped object (which the model reads),
 * never by throwing — though the loop still guards against a throwing tool. The
 * element type is opaque [JsonObject] on both ends, matching the port's stance
 * that content blocks and vendor params are opaque JSON.
 */
interface ChatTool {
  /** Dispatch key; MUST equal `definition["name"]`. */
  val name: String

  /** Verbatim Anthropic tool spec (name/description/input_schema). */
  val definition: JsonObject

  /** Runs the tool. Total by contract: returns a result object, never throws for a domain failure. */
  suspend fun execute(input: JsonObject): JsonObject
}
