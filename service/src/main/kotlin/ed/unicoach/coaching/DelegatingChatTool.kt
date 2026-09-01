package ed.unicoach.coaching

import ed.unicoach.chat.ChatTool
import kotlinx.serialization.json.JsonObject

/**
 * A [ChatTool] that is nothing but a VERBATIM delegate to a chat-free tool in
 * `:college` (RFC 67 keeps that module free of any `:chat` dependency, so
 * something on this side has to do the registration).
 *
 * Written out by hand three times — `search_colleges`, `find_college`,
 * `similar_colleges` — the adapter was the same six lines each time, and RFC
 * 154 parked the abstraction at n=2 as speculative. At n=3 it is not: the
 * bridge is ONE fact ("name, definition and execute are the wrapped tool's
 * own, unreshaped"), and stating it once is what makes a fourth college tool a
 * one-line subclass rather than a fourth chance to reshape a payload on the
 * way through.
 *
 * [definition] and [execute] are taken from the wrapped tool as they are. A
 * subclass that wanted to reshape either one would not be this class; it would
 * implement [ChatTool] directly, and the difference would be visible.
 */
abstract class DelegatingChatTool(
  override val name: String,
  override val definition: JsonObject,
  private val delegate: suspend (JsonObject) -> JsonObject,
) : ChatTool {
  final override suspend fun execute(input: JsonObject): JsonObject = delegate(input)
}
