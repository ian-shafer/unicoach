package ed.unicoach.coaching

import ed.unicoach.chat.ContentBlocks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Single owner of the content-block representation shared by persistence,
 * provider replay, and the REST projection. A user turn is stored as a one-text
 * block array; rendering flattens a block array back to plain text.
 */
object ConvoContent {
  private val logger = LoggerFactory.getLogger(ConvoContent::class.java)

  /** `[{"type": "text", "text": text}]` — the stored shape of every user turn. */
  fun userContent(text: String): JsonElement =
    buildJsonArray {
      add(
        buildJsonObject {
          put("type", "text")
          put("text", text)
        },
      )
    }

  /**
   * Concatenated `text` of every block whose `type == "text"`; `""` for anything
   * that is not a block array (faithful, lossy-free for the shapes this service
   * persists and the provider returns). Delegates to [ContentBlocks.renderText]
   * in the `chat` module, the owner of the block-array wire shape.
   */
  fun renderText(content: JsonElement): String = ContentBlocks.renderText(content)

  /**
   * One requested tool call parsed from an assistant response's `tool_use`
   * block: the block's `id` (echoed back as the answering `tool_result`'s
   * `tool_use_id`), the tool `name` (the registry dispatch key), and the
   * verbatim `input` object.
   */
  data class ToolUse(
    val id: String,
    val name: String,
    val input: JsonObject,
  )

  /**
   * Every `tool_use` block in [content], in order. Anthropic may emit several in
   * one message (parallel tool use) — all must be answered — so this returns a
   * list. A malformed block (missing id/name) is skipped; a `tool_use`
   * stop_reason with no usable block leaves the list empty and the loop treats
   * the response as final.
   */
  fun toolUses(content: JsonElement): List<ToolUse> {
    if (content !is JsonArray) return emptyList()
    return content.mapNotNull { block ->
      val obj = block as? JsonObject ?: return@mapNotNull null
      if (obj["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return@mapNotNull null
      // A tool_use block missing id/name cannot be dispatched or answered.
      // Dropping it silently would leave the model's tool_use unanswered on the
      // next call with no visibility — log the raw block so the gap is traceable.
      val id =
        obj["id"]?.jsonPrimitive?.contentOrNull ?: run {
          logger.warn("dropping malformed tool_use block, missing [id]: [{}]", obj)
          return@mapNotNull null
        }
      val name =
        obj["name"]?.jsonPrimitive?.contentOrNull ?: run {
          logger.warn("dropping malformed tool_use block, missing [name]: [{}]", obj)
          return@mapNotNull null
        }
      val input = obj["input"] as? JsonObject ?: JsonObject(emptyMap())
      ToolUse(id = id, name = name, input = input)
    }
  }

  /**
   * A single `tool_result` content block answering the `tool_use` identified by
   * [toolUseId]. [result] is serialized to a compact JSON string on `content`
   * (the shape the model reads back). [isError] marks a transport failure — an
   * unknown/throwing tool — so the model can recover; a tool's own domain
   * `{ "error": ... }` object is a normal result with `is_error` unset.
   */
  fun toolResultBlock(
    toolUseId: String,
    result: JsonElement,
    isError: Boolean,
  ): JsonObject =
    buildJsonObject {
      put("type", "tool_result")
      put("tool_use_id", toolUseId)
      put("content", result.toString())
      if (isError) put("is_error", true)
    }

  /** Wraps [blocks] as a content-block array — the stored/sent shape of a tool_result user message. */
  fun blockArray(blocks: List<JsonObject>): JsonElement =
    buildJsonArray {
      blocks.forEach { add(it) }
    }
}
