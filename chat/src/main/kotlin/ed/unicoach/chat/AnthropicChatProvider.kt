package ed.unicoach.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

// The first transmitting ChatProvider: streams the Anthropic Messages API
// through the AnthropicStreamTransport seam and maps SSE events onto the
// ChatEvent taxonomy. Mirrors SesEmailProvider — constructor-injected seam plus
// an AutoCloseable backing resource (the HttpClient) it owns.
//
// Each collection performs exactly one transport call (cold flow). Everything
// the transport emits or throws — except CancellationException, rethrown for
// cooperative cancellation — is converted to ChatEvents ending in exactly one
// terminal; no exception escapes the flow. The flow completes at the first
// terminal: transport events after message_stop are not collected.
class AnthropicChatProvider(
  private val transport: AnthropicStreamTransport,
  // Backing HttpClient; closed by close().
  private val resource: AutoCloseable,
) : ChatProvider,
  AutoCloseable {
  override val id: String = PROVIDER_ID

  private val logger = LoggerFactory.getLogger(AnthropicChatProvider::class.java)

  override fun stream(request: ChatRequest): Flow<ChatEvent> =
    flow {
      val accumulator = MessageAccumulator()
      var requestId: String? = null
      try {
        transport.stream(requestBody(request)).collect { transportEvent ->
          when (transportEvent) {
            is AnthropicTransportEvent.Opened -> {
              requestId = transportEvent.requestId
            }

            is AnthropicTransportEvent.Frame -> {
              when (val outcome = handleFrame(transportEvent, accumulator, requestId)) {
                is FrameOutcome.Emit -> {
                  emit(outcome.event)
                }

                is FrameOutcome.Terminal -> {
                  emit(outcome.event)
                  // Stop collecting upstream: this cancels the in-flight transport
                  // call so frames after the terminal are never collected.
                  throw StopCollection
                }

                FrameOutcome.Drop -> {
                  Unit
                }
              }
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: StopCollection) {
        return@flow
      } catch (e: AnthropicHttpException) {
        emit(httpFailure(e))
        return@flow
      } catch (e: Terminator) {
        emit(e.terminal)
        return@flow
      } catch (e: Throwable) {
        emit(transportFailure(e, requestId ?: accumulator.messageId()))
        return@flow
      }
      // The transport completed without a terminal frame — a dropped stream.
      emit(truncatedFailure(accumulator))
    }

  override fun close() {
    resource.close()
  }

  // --- Request mapping -------------------------------------------------------

  private fun requestBody(request: ChatRequest): JsonObject =
    buildJsonObject {
      // params first (vendor passthrough), then typed fields written over it so
      // the model/ceiling the caller pinned is what is transmitted and params
      // cannot disable streaming.
      request.params?.forEach { (key, value) -> put(key, value) }
      put("model", request.model)
      put("max_tokens", request.maxTokens)
      put("stream", true)
      request.system?.let { put("system", it) }
      put(
        "messages",
        buildJsonArray {
          for (message in request.messages) {
            add(
              buildJsonObject {
                put("role", if (message.role == ChatRole.USER) "user" else "assistant")
                put("content", message.text)
              },
            )
          }
        },
      )
    }

  // --- Frame handling --------------------------------------------------------

  private sealed interface FrameOutcome {
    data class Emit(
      val event: ChatEvent,
    ) : FrameOutcome

    data class Terminal(
      val event: ChatEvent.Terminal,
    ) : FrameOutcome

    object Drop : FrameOutcome
  }

  // A protocol/accumulation failure raised from inside frame handling, carrying
  // its already-built terminal. Caught in stream() and emitted as the terminal.
  private class Terminator(
    val terminal: ChatEvent.Terminal,
  ) : Exception()

  // Thrown after the terminal event is emitted to break upstream collection and
  // cancel the in-flight transport call. Not a CancellationException: it is the
  // adapter's own normal completion, caught and swallowed in stream().
  private object StopCollection : Exception() {
    private fun readResolve(): Any = StopCollection
  }

  private fun handleFrame(
    frame: AnthropicTransportEvent.Frame,
    accumulator: MessageAccumulator,
    openedRequestId: String?,
  ): FrameOutcome {
    val data = parseJson(frame.data)
    return when (frame.event) {
      "message_start" -> {
        val message =
          data.objOrNull("message")
            ?: throw Terminator(protocolViolation(frame.event, "missing [message]"))
        accumulator.start(message)
        FrameOutcome.Emit(
          ChatEvent.MessageStart(
            providerRequestId = message.string("id"),
            model = message.string("model"),
            usage = usage(message.objOrNull("usage")),
          ),
        )
      }

      "content_block_start" -> {
        val index = data.intField(frame.event, "index")
        val contentBlock =
          data.objOrNull("content_block")
            ?: throw Terminator(protocolViolation(frame.event, "missing [content_block]"))
        accumulator.openBlock(index, contentBlock)
        FrameOutcome.Emit(
          ChatEvent.ContentBlockStart(
            index = index,
            blockType = contentBlock.string("type") ?: "",
            block = emptyOpenBlock(contentBlock),
          ),
        )
      }

      "content_block_delta" -> {
        val index = data.intField(frame.event, "index")
        val deltaObj =
          data.objOrNull("delta")
            ?: throw Terminator(protocolViolation(frame.event, "missing [delta]"))
        accumulator.applyDelta(index, deltaObj)
        FrameOutcome.Emit(ChatEvent.ContentBlockDelta(index, contentDelta(deltaObj)))
      }

      "content_block_stop" -> {
        val index = data.intField(frame.event, "index")
        accumulator.closeBlock(index)
        FrameOutcome.Emit(ChatEvent.ContentBlockStop(index))
      }

      "message_delta" -> {
        val deltaObj = data.objOrNull("delta") ?: JsonObject(emptyMap())
        val usageObj = data.objOrNull("usage")
        accumulator.applyMessageDelta(deltaObj, usageObj)
        FrameOutcome.Emit(ChatEvent.MessageDelta(deltaObj.string("stop_reason"), usage(usageObj)))
      }

      "message_stop" -> {
        accumulator.terminated = true
        FrameOutcome.Terminal(accumulator.completed())
      }

      "ping" -> {
        FrameOutcome.Drop
      }

      "error" -> {
        FrameOutcome.Terminal(streamErrorFailure(data, accumulator.messageId() ?: openedRequestId))
      }

      else -> {
        logger.warn("[{}] unmodeled SSE event [{}] surfaced as Raw", PROVIDER_ID, frame.event)
        FrameOutcome.Emit(ChatEvent.Raw(frame.event, data))
      }
    }
  }

  // The block object carried by ContentBlockStart.block: null iff every field
  // other than `type` is an empty string / empty object / empty array;
  // otherwise the verbatim content_block.
  private fun emptyOpenBlock(contentBlock: JsonObject): JsonElement? {
    val carriesData =
      contentBlock.any { (key, value) ->
        key != "type" && !value.isEmptyValue()
      }
    return if (carriesData) contentBlock else null
  }

  private fun JsonElement.isEmptyValue(): Boolean =
    when (this) {
      is JsonNull -> true
      is JsonPrimitive -> !isString || contentOrNull == ""
      is JsonObject -> isEmpty()
      is JsonArray -> isEmpty()
    }

  private fun contentDelta(delta: JsonObject): ContentDelta =
    when (delta.string("type")) {
      "text_delta" -> {
        ContentDelta.Text(delta.string("text") ?: "")
      }

      "thinking_delta" -> {
        ContentDelta.Thinking(delta.string("thinking") ?: "")
      }

      "input_json_delta" -> {
        ContentDelta.ToolInput(delta.string("partial_json") ?: "")
      }

      "signature_delta" -> {
        ContentDelta.Opaque(delta)
      }

      else -> {
        logger.warn("[{}] unrecognized content delta type [{}] surfaced as Opaque", PROVIDER_ID, delta.string("type"))
        ContentDelta.Opaque(delta)
      }
    }

  private fun usage(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null
    return TokenUsage(
      inputTokens = usage.intOrNull("input_tokens"),
      outputTokens = usage.intOrNull("output_tokens"),
      cacheReadTokens = usage.intOrNull("cache_read_input_tokens"),
      cacheWriteTokens = usage.intOrNull("cache_creation_input_tokens"),
    )
  }

  // --- Error classification --------------------------------------------------

  private fun httpFailure(e: AnthropicHttpException): ChatEvent.Terminal {
    val rawPayload = e.body.takeIf { it.isNotEmpty() }?.let { parseJson(it) }
    val errorObj = (rawPayload as? JsonObject)?.objOrNull("error")
    val errorType = errorObj?.string("type")
    val providerMessage = errorObj?.string("message").orEmpty()
    val reason = "http [${e.status}] [${errorType ?: ""}] $providerMessage".trim()
    val permanent =
      when {
        errorType != null && isPermanentType(errorType) -> true

        errorType != null && isTransientType(errorType) -> false

        // No recognized type: fall back to the HTTP status bucket.
        e.status == 408 || e.status == 429 || e.status >= 500 -> false

        e.status in 400..499 -> true

        else -> false
      }
    return terminal(permanent, reason, e.requestId, rawPayload)
  }

  private fun streamErrorFailure(
    data: JsonElement,
    providerRequestId: String?,
  ): ChatEvent.Terminal {
    val errorObj = (data as? JsonObject)?.objOrNull("error")
    val errorType = errorObj?.string("type")
    val providerMessage = errorObj?.string("message").orEmpty()
    val reason = "stream error [${errorType ?: ""}] $providerMessage".trim()
    // No HTTP status mid-stream: an unrecognized type is the transient floor.
    val permanent = errorType != null && isPermanentType(errorType)
    return terminal(permanent, reason, providerRequestId, data)
  }

  private fun transportFailure(
    e: Throwable,
    providerRequestId: String?,
  ): ChatEvent.TransientFailure =
    ChatEvent.TransientFailure(
      reason = "transport [${e::class.simpleName}] ${e.message.orEmpty()}".trim(),
      providerRequestId = providerRequestId,
      rawPayload = null,
    )

  private fun truncatedFailure(accumulator: MessageAccumulator): ChatEvent.TransientFailure =
    ChatEvent.TransientFailure(
      reason = "truncated stream after [${accumulator.frameCount}] events",
      providerRequestId = accumulator.messageId(),
      rawPayload = null,
    )

  private fun terminal(
    permanent: Boolean,
    reason: String,
    providerRequestId: String?,
    rawPayload: JsonElement?,
  ): ChatEvent.Terminal =
    if (permanent) {
      ChatEvent.Rejected(reason, providerRequestId, rawPayload)
    } else {
      ChatEvent.TransientFailure(reason, providerRequestId, rawPayload)
    }

  private fun isPermanentType(type: String): Boolean = type in PERMANENT_ERROR_TYPES

  private fun isTransientType(type: String): Boolean = type in TRANSIENT_ERROR_TYPES

  // --- JSON helpers ----------------------------------------------------------

  private fun parseJson(text: String): JsonElement = runCatching { JSON.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }

  private fun JsonElement.objOrNull(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

  private fun JsonElement.intField(
    event: String,
    key: String,
  ): Int =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull
      ?: throw Terminator(protocolViolation(event, "missing [$key]"))

  private fun protocolViolation(
    event: String,
    detail: String,
  ): ChatEvent.TransientFailure =
    ChatEvent.TransientFailure(
      reason = "protocol violation [$event] $detail",
      providerRequestId = null,
      rawPayload = null,
    )

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)
      ?.takeIf {
        it.isString || it !is JsonNull
      }?.contentOrNull

  private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

  // --- Accumulation ----------------------------------------------------------

  // Builds the accumulated Message as a mutable JSON tree so Completed.rawPayload
  // is identical in shape to the non-streaming response body. A frame the
  // accumulator cannot apply throws Terminator(protocol violation).
  private inner class MessageAccumulator {
    private var message: MutableMap<String, JsonElement>? = null
    private val content = mutableListOf<MutableMap<String, JsonElement>>()

    // partial_json buffers per open content index, flushed at content_block_stop.
    private val toolInputBuffers = mutableMapOf<Int, StringBuilder>()
    var terminated: Boolean = false
    var frameCount: Int = 0
      private set

    fun messageId(): String? = (message?.get("id") as? JsonPrimitive)?.contentOrNull

    fun start(messageObj: JsonObject) {
      frameCount++
      message = messageObj.toMutableMap()
      content.clear()
      (messageObj["content"] as? JsonArray)?.forEach { element ->
        (element as? JsonObject)?.let { content.add(it.toMutableMap()) }
      }
    }

    fun openBlock(
      index: Int,
      contentBlock: JsonObject,
    ) {
      frameCount++
      requireStarted("content_block_start")
      // content[index] = the verbatim content_block; pad if needed.
      while (content.size <= index) content.add(mutableMapOf())
      content[index] = contentBlock.toMutableMap()
    }

    fun applyDelta(
      index: Int,
      delta: JsonObject,
    ) {
      frameCount++
      val block = blockAt(index, "content_block_delta")
      when (delta.string("type")) {
        "text_delta" -> {
          appendString(block, "text", delta.string("text"))
        }

        "thinking_delta" -> {
          appendString(block, "thinking", delta.string("thinking"))
        }

        "signature_delta" -> {
          appendString(block, "signature", delta.string("signature"))
        }

        "input_json_delta" -> {
          toolInputBuffers.getOrPut(index) { StringBuilder() }.append(delta.string("partial_json") ?: "")
        }

        // Other unrecognized delta types are surfaced as Opaque on the stream
        // but not folded — no folding rule can exist for an unknown shape.
        else -> {
          Unit
        }
      }
    }

    fun closeBlock(index: Int) {
      frameCount++
      val block = blockAt(index, "content_block_stop")
      toolInputBuffers.remove(index)?.let { buffer ->
        val parsed =
          runCatching {
            if (buffer.isEmpty()) JsonObject(emptyMap()) else JSON.parseToJsonElement(buffer.toString())
          }.getOrElse { JsonObject(emptyMap()) }
        block["input"] = parsed
      }
    }

    fun applyMessageDelta(
      delta: JsonObject,
      usage: JsonObject?,
    ) {
      frameCount++
      val msg = message ?: throw Terminator(protocolViolation("message_delta", "no message_start"))
      delta.forEach { (key, value) -> msg[key] = value }
      if (usage != null) {
        val merged = ((msg["usage"] as? JsonObject)?.toMutableMap() ?: mutableMapOf())
        usage.forEach { (key, value) -> merged[key] = value }
        msg["usage"] = JsonObject(merged)
      }
    }

    fun completed(): ChatEvent.Completed {
      frameCount++
      val raw = finalMessage()
      return ChatEvent.Completed(response = project(raw), rawPayload = raw)
    }

    private fun finalMessage(): JsonObject {
      val msg = message ?: throw Terminator(protocolViolation("message_stop", "no message_start"))
      msg["content"] = JsonArray(content.map { JsonObject(it) })
      return JsonObject(msg)
    }

    private fun project(raw: JsonObject): ChatResponse =
      ChatResponse(
        content = raw["content"] ?: JsonArray(emptyList()),
        modelResolved = raw.string("model") ?: "",
        stopReason = raw.string("stop_reason") ?: "",
        usage = usage(raw.objOrNull("usage")) ?: TokenUsage(null, null, null, null),
        providerRequestId = raw.string("id"),
      )

    private fun appendString(
      block: MutableMap<String, JsonElement>,
      key: String,
      addition: String?,
    ) {
      val existing = (block[key] as? JsonPrimitive)?.contentOrNull ?: ""
      block[key] = JsonPrimitive(existing + (addition ?: ""))
    }

    private fun blockAt(
      index: Int,
      event: String,
    ): MutableMap<String, JsonElement> {
      requireStarted(event)
      if (index < 0 || index >= content.size) {
        throw Terminator(protocolViolation(event, "no open block at index [$index]"))
      }
      return content[index]
    }

    private fun requireStarted(event: String) {
      if (message == null) throw Terminator(protocolViolation(event, "no message_start"))
    }
  }

  companion object {
    const val PROVIDER_ID = "anthropic"

    private val JSON = Json { ignoreUnknownKeys = true }

    private val PERMANENT_ERROR_TYPES =
      setOf(
        "invalid_request_error",
        "authentication_error",
        "permission_error",
        "not_found_error",
        "request_too_large",
      )

    private val TRANSIENT_ERROR_TYPES =
      setOf(
        "rate_limit_error",
        "api_error",
        "overloaded_error",
      )
  }
}
