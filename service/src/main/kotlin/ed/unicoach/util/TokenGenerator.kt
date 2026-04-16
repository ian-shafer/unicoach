package ed.unicoach.util

import java.security.SecureRandom
import java.util.Base64

class TokenGenerator(
  private val secureRandom: SecureRandom = SecureRandom(),
) {
  fun generateToken(): String {
    val bytes = ByteArray(32) // 256 bits
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }
}
