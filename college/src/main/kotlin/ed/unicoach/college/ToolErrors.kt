package ed.unicoach.college

import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError
import ed.unicoach.error.errorCategory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// The ONE home for the model-facing error envelope this module's chat tools
// emit (RFC 154, tier-1 review). `CollegeSearchTool` and `FindCollegeTool` must
// fail identically — the same keys, the same words — because a coach reading a
// refusal cannot tell which tool produced it, and because the RFC's failure map
// says "the same error object `search_colleges` already emits". That agreement
// used to be a COMMENT beside a byte-for-byte copy, with nothing enforcing it;
// it is a shared function now, so the two tools cannot drift apart silently.
//
// Deliberately `internal` and deliberately module-local: `:service`'s
// `StudentScopedChatTool` carries its own equivalents for its own tools, and
// merging the two is a cross-module decision this file does not make.

/**
 * A malformed-INPUT error: a precise validation sentence under a flat `error`
 * key. Distinct from [searchFailureObject], which reports a failed SEARCH — the
 * caller wrote something wrong here, so there is nothing to retry.
 */
internal fun errorObject(reason: String): JsonObject = buildJsonObject { put("error", reason) }

/**
 * The same flat input error, worded from a THROWN rejection. It degrades the
 * way [searchFailureObject] already does — the exception's simple name before
 * the generic sentence — so a rejection with a null message still names
 * something a reader can act on instead of collapsing to "invalid input".
 */
internal fun inputErrorObject(error: Throwable): JsonObject = errorObject(error.message ?: error::class.simpleName ?: "invalid input")

/**
 * A structured error for a search-time DAO failure. Unlike a malformed-input
 * error (a precise validation string), a DAO failure carries a retryability
 * category: [TransientError] (a DB blip — the same query may succeed on retry)
 * vs [PermanentError] (a programming/SQL fault — retrying will not help). The
 * `transient` flag and the wrapper's cause message let the consumer branch on
 * the category instead of re-parsing a flattened string.
 */
internal fun searchFailureObject(error: Throwable): JsonObject =
  buildJsonObject {
    putJsonObject("error") {
      put("kind", "search_failed")
      put("category", error.errorCategory())
      put("transient", error is TransientError)
      put("detail", error.message ?: error::class.simpleName ?: "search failed")
      error.cause?.message?.let { put("cause", it) }
    }
  }

/**
 * The refusal sentence for fields [input] carries that are not in [known], or
 * `null` when every field is known. Refused BY NAME rather than ignored (RFC
 * 150 D53): a model that writes a field the tool does not take is told which
 * word it was, so it can correct itself instead of being quietly answered as if
 * it had not asked.
 */
internal fun unknownFieldsReason(
  input: JsonObject,
  known: Set<String>,
): String? {
  val unknown = input.keys - known
  return if (unknown.isEmpty()) null else "unknown field(s): [${unknown.sorted().joinToString(", ")}]"
}
