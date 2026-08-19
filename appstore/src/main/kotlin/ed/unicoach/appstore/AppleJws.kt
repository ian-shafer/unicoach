package ed.unicoach.appstore

import com.auth0.jwt.JWT
import ed.unicoach.common.util.DataSize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * Base64url-decodes [payloadSegment] — a JWS's payload as java-jwt hands it
 * back, still encoded — and parses it as a [JsonObject]. Throws
 * [IllegalArgumentException] on undecodable base64url or a payload that is not
 * a JSON object, [kotlinx.serialization.SerializationException] on one that is
 * not JSON at all.
 *
 * The one implementation behind both [AppleJws] (decode-only) and
 * [AppleJwsVerifier] (post-signature): the payload's shape is pure syntax and
 * carries no trust distinction — only the caller's reason for trusting the
 * bytes differs, never this step.
 */
internal fun decodedPayloadObject(payloadSegment: String): JsonObject {
  val bytes = Base64.getUrlDecoder().decode(payloadSegment)
  return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
    ?: throw IllegalArgumentException("JWS payload is JSON but not an object")
}

/**
 * Base64url-decodes the payload segment of a JWS and parses it as a
 * [JsonObject]. DECODES WITHOUT VERIFYING the signature: every caller must hold
 * a trust reason — the bytes were fetched from Apple over TLS, or the value is
 * used only as an untrusted lookup key. [AppleJwsVerifier] is the x5c-chain
 * verifying sibling: every inbound Apple-signed payload goes through it, and
 * nothing may route one around it to this decode-only path. The verifier itself
 * delegates its payload step here once the signature holds — the signature
 * being that caller's trust reason — so this class stays the module's single
 * JWS-payload decode.
 */
class AppleJws {
  /**
   * Failure on: malformed JWS structure or base64url ([com.auth0.jwt.exceptions.JWTDecodeException]
   * from [JWT.decode]), non-JSON(-object) payload.
   */
  fun payload(jws: String): Result<JsonObject> =
    // JWT.decode owns the structural decode — segment count and base64url —
    // but its payload accessor hands back the still-encoded segment, leaving
    // the decode and the JsonObject shape, which java-jwt's Claim map cannot
    // express, to this module's own primitive.
    runCatching { decodedPayloadObject(JWT.decode(jws).payload) }
}

/**
 * Why a JWS-bearing string is refused outright, or null to go on and parse it:
 * the cheap check every caller makes before spending a decode or a chain
 * validation on hostile input. A JWS is base64url — ASCII throughout — so a
 * `length` comparison against [max] is exact, which is why the message says
 * characters. [field] names the caller's own wire field, since the refusal
 * reaches whoever posted it.
 */
fun jwsBoundsFailure(
  value: String,
  max: DataSize,
  field: String,
): String? =
  when {
    value.isBlank() -> "$field must not be blank"
    value.length > max.bytes -> "$field must be at most ${max.bytes} characters"
    else -> null
  }
