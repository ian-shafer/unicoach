package ed.unicoach.util

import java.security.SecureRandom
import java.util.Base64

class TokenGenerator(
  private val secureRandom: SecureRandom = SecureRandom(),
) {
  fun generateToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(generateBytes())

  /**
   * [TOKEN_BYTES] bytes from [secureRandom]: the raw form of [generateToken],
   * for a secret that is stored as bytes rather than sent as text, such as a
   * session's CSRF token (RFC 265).
   */
  fun generateBytes(): ByteArray = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)

  companion object {
    /**
     * The size of every token this generator mints: 256 bits of entropy. A
     * reader that accepts a stored token (`CsrfToken.of`) takes its expected
     * length from here, so a token of any other size is one no code minted.
     */
    const val TOKEN_BYTES = 32
  }
}
