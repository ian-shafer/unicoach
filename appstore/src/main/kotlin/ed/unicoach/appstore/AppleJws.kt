package ed.unicoach.appstore

import com.auth0.jwt.JWT
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * Base64url-decodes the payload segment of a JWS and parses it as a
 * [JsonObject]. DECODES WITHOUT VERIFYING the signature: every caller must hold
 * a trust reason — the bytes were fetched from Apple over TLS, or the value is
 * used only as an untrusted lookup key. The Notifications-V2 webhook RFC adds
 * the x5c-chain verifying sibling; nothing may route an inbound Apple-signed
 * payload through this decode-only path.
 */
class AppleJws {
  /**
   * Failure on: malformed JWS structure or base64url ([com.auth0.jwt.exceptions.JWTDecodeException]
   * from [JWT.decode]), non-JSON(-object) payload.
   */
  fun payload(jws: String): Result<JsonObject> =
    runCatching {
      // JWT.decode owns the structural decode — segment count and base64url —
      // leaving only the JsonObject shape, which java-jwt's Claim map cannot
      // express, as this module's own concern. Its payload accessor hands back
      // the still-encoded segment, hence the decode here.
      val decoded = Base64.getUrlDecoder().decode(JWT.decode(jws).payload)
      Json.parseToJsonElement(decoded.toString(Charsets.UTF_8)) as? JsonObject
        ?: throw IllegalArgumentException("JWS payload is JSON but not an object")
    }
}
