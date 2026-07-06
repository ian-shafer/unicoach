package ed.unicoach.coaching

import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.ContentBlocks
import ed.unicoach.common.util.truncateForLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The verbatim Anthropic `tool_choice` object that forces the single tool
 * [toolName] — `{"type":"tool","name":toolName}` (RFC 104). Shared by the four
 * structured-output coaching calls, each of which pairs it with its own
 * one-element `tools` list so the model must emit exactly one `tool_use` block
 * whose `input` is the structured payload.
 */
fun forcedToolChoice(toolName: String): JsonObject =
  buildJsonObject {
    put("type", "tool")
    put("name", toolName)
  }

/**
 * The result of reading a forced tool's payload out of a billed [ChatResponse]
 * (RFC 104), the single dispatch shape shared by all four structured-output
 * coaching call sites (extraction, synthesis, fit-lens query, fit-lens reason).
 *
 * - [Present] carries the forced tool's `input` object, handed to the call's
 *   per-field `parseX`.
 * - [Absent] means the response carried no `tool_use` block named for the forced
 *   tool — the structured object the model was forced to produce never arrived
 *   (a refusal, clarifying prose, or a `max_tokens` truncation before the block
 *   closed). It carries the diagnostic context needed to tell those apart: the
 *   provider [stopReason] (verbatim) and a bounded [excerpt] of the rendered
 *   assistant text (empty when the model emitted no text). Each call site folds
 *   both into its own "no tool use" failure variant so they survive on the
 *   persisted `failure_reason`, not just a transient log line.
 */
sealed interface ForcedToolInput {
  data class Present(
    val input: JsonObject,
  ) : ForcedToolInput

  data class Absent(
    val stopReason: String,
    val excerpt: String,
  ) : ForcedToolInput
}

/**
 * Reads the forced tool [expectedName]'s `input` object out of [response], the
 * single enforcement point of the forced-tool contract shared across the four
 * structured-output calls. Matching on the block `name` (via
 * [ContentBlocks.toolUseInput]) means a differently-named `tool_use` block is
 * treated as absent, not mistaken for the payload. On absence, captures the
 * `stopReason` and a truncated excerpt of the rendered assistant text so the
 * decline case (refusal / prose / `max_tokens` truncation) keeps its diagnostic
 * context — `renderText` renders `tool_use` blocks as empty, so this excerpt is
 * exactly the non-tool text the model produced instead.
 */
fun readForcedTool(
  response: ChatResponse,
  expectedName: String,
): ForcedToolInput =
  when (val input = ContentBlocks.toolUseInput(response.content, expectedName)) {
    null -> {
      ForcedToolInput.Absent(
        stopReason = response.stopReason,
        excerpt = truncateForLog(ContentBlocks.renderText(response.content)),
      )
    }

    else -> {
      ForcedToolInput.Present(input)
    }
  }

/**
 * A minimal JSON-Schema DSL for the four coaching tool `input_schema`s. The
 * schema is tier-A **guidance** (RFC 104): it steers the model — enumerating
 * each enum's values — but is not a hard validator (`strict: true` is a deferred
 * tier-B upgrade), so `required` is omitted and the code-side per-field
 * extraction stays the enforcement point.
 */
object ToolSchema {
  /** A verbatim Anthropic tool spec `{name, description, input_schema}`. */
  fun tool(
    name: String,
    description: String,
    inputSchema: JsonObject,
  ): JsonObject =
    buildJsonObject {
      put("name", name)
      put("description", description)
      put("input_schema", inputSchema)
    }

  /** `{"type":"object","properties":{…}}` — the schema root every tool uses. */
  fun objectSchema(vararg properties: Pair<String, JsonObject>): JsonObject =
    buildJsonObject {
      put("type", "object")
      put("properties", buildJsonObject { properties.forEach { (k, v) -> put(k, v) } })
    }

  fun string(): JsonObject = typed("string")

  fun integer(): JsonObject = typed("integer")

  fun number(): JsonObject = typed("number")

  fun enum(vararg values: String): JsonObject =
    buildJsonObject {
      put("type", "string")
      put("enum", JsonArray(values.map { JsonPrimitive(it) }))
    }

  fun arrayOf(items: JsonObject): JsonObject =
    buildJsonObject {
      put("type", "array")
      put("items", items)
    }

  private fun typed(type: String): JsonObject = buildJsonObject { put("type", type) }
}
