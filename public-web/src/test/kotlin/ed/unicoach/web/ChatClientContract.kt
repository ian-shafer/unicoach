package ed.unicoach.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `chat-client-contract.json`, as the Kotlin suites read it: the page hooks,
 * the styled client-built classes and the stream events `chat.ts` depends on.
 *
 * The SAME file `chat_test.ts` imports and asserts the client's constants and
 * decoder against. Kotlin asserts what the server SERVES against it, so the two
 * languages, which share no source, are joined through one fixture instead of
 * through two lists that only agree by hand.
 */
object ChatClientContract {
  private val root: JsonObject =
    Json
      .parseToJsonElement(
        checkNotNull(ChatClientContract::class.java.getResource("/chat-client-contract.json")) {
          "chat-client-contract.json is not on the test classpath"
        }.readText(),
      ).jsonObject

  private val page: JsonObject = root.getValue("page").jsonObject

  val composerSelector: String = page.string("composerSelector")
  val transcriptSelector: String = page.string("transcriptSelector")
  val emptyStateSelector: String = page.string("emptyStateSelector")
  val retriableFailureAttribute: String = page.string("retriableFailureAttribute")
  val exchangeClass: String = page.string("exchangeClass")
  val studentMessageClasses: String = page.string("studentMessageClasses")
  val coachMessageClasses: String = page.string("coachMessageClasses")

  /** The header that marks a body as one of the server's own notices — the only body `chat.ts` inserts. */
  val noticeHeader: String = root.getValue("notice").jsonObject.string("header")

  private val csrf: JsonObject = root.getValue("csrf").jsonObject

  /** The composer's hidden field `chat.ts` reads the session's CSRF token from (RFC 265). */
  val csrfField: String = csrf.string("field")

  /** The request header `chat.ts` sends that token in. */
  val csrfHeader: String = csrf.string("header")

  /** The classes `chat.ts` builds that the stylesheet must style. */
  val styledClientClasses: List<String> =
    root
      .getValue("styledClientClasses")
      .jsonObject.values
      .map { it.jsonPrimitive.content }

  /** Each event `chat.ts` decodes, by name: its data's field names and their JSON kinds. */
  val events: Map<String, Map<String, String>> =
    root.getValue("events").jsonArray.associate { sample ->
      val event = sample.jsonObject
      event.string("event") to fieldKinds(event.getValue("data").jsonObject)
    }

  /** [data]'s field names, each with its kind — `string` or `boolean`, the two `decodeEvent` checks for. */
  fun fieldKinds(data: JsonObject): Map<String, String> =
    data.mapValues { (_, value) ->
      val primitive = value as? JsonPrimitive
      when {
        primitive == null -> "non-primitive"
        primitive.isString -> "string"
        primitive.booleanOrNull != null -> "boolean"
        else -> "other"
      }
    }

  private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
