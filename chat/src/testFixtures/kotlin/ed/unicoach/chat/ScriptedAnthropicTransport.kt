package ed.unicoach.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

// A recorded transport sequence: the events one stream() call emits, optionally
// terminated by a thrown exception. HTTP/IO failures originate as a thrown
// exception from the real transport flow (AnthropicHttpException / IOException),
// so the fake models that by throwing after emitting its events.
data class Replay(
  val events: List<AnthropicTransportEvent>,
  val throwing: Throwable? = null,
)

/**
 * A scripted, multi-response fake at the [AnthropicStreamTransport] seam. Each
 * `stream()` call dequeues the next [Replay] in order, so one collection of a
 * coaching tool-dispatch loop (call 1 -> tool_use, call 2 -> final text) is
 * served from one recorded script. Every received request body is captured into
 * [bodies], letting a test assert the wire body the real provider built (e.g. the
 * TOOL_RESULT continuation on the second call).
 *
 * The script is strict: a `stream()` call past the end of [replays] throws, so an
 * unexpected extra provider call fails the test loudly rather than silently
 * returning a canned reply. A cold flow that is collected N times therefore needs
 * an N-element script.
 */
class ScriptedAnthropicTransport(
  private val replays: List<Replay>,
) : AnthropicStreamTransport {
  private val captured = mutableListOf<JsonObject>()

  /** Request bodies received, one per `stream()` call, in call order. */
  val bodies: List<JsonObject> get() = captured

  /** The most recent request body, or null before any call. */
  val body: JsonObject? get() = captured.lastOrNull()

  /** Number of `stream()` calls so far. */
  val calls: Int get() = captured.size

  override fun stream(body: JsonObject): Flow<AnthropicTransportEvent> {
    val index = captured.size
    check(index < replays.size) {
      "ScriptedAnthropicTransport exhausted: unexpected stream() call #${index + 1}, script has ${replays.size}"
    }
    captured.add(body)
    val replay = replays[index]
    return flow {
      for (event in replay.events) emit(event)
      replay.throwing?.let { throw it }
    }
  }

  companion object {
    /** A single-response script from a bare event list. */
    fun of(events: List<AnthropicTransportEvent>): ScriptedAnthropicTransport = ScriptedAnthropicTransport(listOf(Replay(events)))

    /** A single-response script that emits nothing then throws [error]. */
    fun throwing(error: Throwable): ScriptedAnthropicTransport = ScriptedAnthropicTransport(listOf(Replay(emptyList(), error)))
  }
}
