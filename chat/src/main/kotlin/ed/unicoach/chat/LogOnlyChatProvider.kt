package ed.unicoach.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

// No-network stub adapter so callers can build and test against the port. It
// echoes the last message back as a well-formed event sequence, logs one line,
// and transmits nothing. The real adapter (Anthropic, RFC 44) slots in behind
// ChatProvider.
//
// Every field of every event is deterministic given the request except
// providerRequestId, which is a fresh UUID per collection (one provider call
// = one id). Synthetic "token" counts are character counts; maxTokens is
// ignored (the stub never truncates the echo — synthetic counts are
// characters, not tokens, so a token ceiling has no meaningful enforcement
// here).
class LogOnlyChatProvider : ChatProvider {
  override val id: String = PROVIDER_ID

  private val logger = LoggerFactory.getLogger(LogOnlyChatProvider::class.java)

  override fun stream(request: ChatRequest): Flow<ChatEvent> =
    flow {
      val providerRequestId = UUID.randomUUID().toString()
      val reply = ECHO_PREFIX + (request.messages.lastOrNull()?.text ?: "")
      val inputChars = (request.system?.length ?: 0) + request.messages.sumOf { it.text.length }
      val fullUsage =
        TokenUsage(
          inputTokens = inputChars,
          outputTokens = reply.length,
          cacheReadTokens = null,
          cacheWriteTokens = null,
        )

      logger.info(
        "[{}] recorded chat request model=[{}] messages=[{}]",
        PROVIDER_ID,
        request.model,
        request.messages.size,
      )

      emit(
        ChatEvent.MessageStart(
          providerRequestId = providerRequestId,
          model = request.model,
          usage = TokenUsage(inputChars, null, null, null),
        ),
      )
      emit(ChatEvent.ContentBlockStart(index = 0, blockType = "text", block = null))
      for (chunk in reply.chunked(DELTA_CHUNK_SIZE)) {
        emit(ChatEvent.ContentBlockDelta(index = 0, delta = ContentDelta.Text(chunk)))
      }
      emit(ChatEvent.ContentBlockStop(index = 0))
      emit(ChatEvent.MessageDelta(stopReason = STOP_REASON, usage = fullUsage))

      val content =
        buildJsonArray {
          add(
            buildJsonObject {
              put("type", "text")
              put("text", reply)
            },
          )
        }
      val response =
        ChatResponse(
          content = content,
          modelResolved = request.model,
          stopReason = STOP_REASON,
          usage = fullUsage,
          providerRequestId = providerRequestId,
        )
      emit(ChatEvent.Completed(response = response, rawPayload = rawPayload(providerRequestId, request.model, content, fullUsage)))
    }

  // The stub's own wire shape — not an imitation of any vendor's format.
  private fun rawPayload(
    providerRequestId: String,
    model: String,
    content: JsonElement,
    usage: TokenUsage,
  ): JsonElement =
    buildJsonObject {
      put("provider", PROVIDER_ID)
      put("id", providerRequestId)
      put("model", model)
      put("content", content)
      put("stop_reason", STOP_REASON)
      put(
        "usage",
        buildJsonObject {
          put("input_tokens", usage.inputTokens)
          put("output_tokens", usage.outputTokens)
        },
      )
    }

  companion object {
    const val PROVIDER_ID = "log"
    const val DELTA_CHUNK_SIZE = 16
    const val ECHO_PREFIX = "[log] echo: "
    private const val STOP_REASON = "end_turn"
  }
}
