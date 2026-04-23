package ed.unicoach.db.models

/**
 * Wraps a raw token hash array to enforce domain signature bounds.
 *
 * Note on `ByteArray` equality: JVM arrays resolve equality by reference
 * rather than by structural content. When using this class in data classes,
 * you must explicitly override `equals` and `hashCode` and use `contentEquals`
 * to ensure tokens match correctly.
 */
class TokenHash(
  val value: ByteArray,
) {
  init {
    require(value.isNotEmpty()) { "TokenHash cannot be empty." }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as TokenHash

    return value.contentEquals(other.value)
  }

  override fun hashCode(): Int = value.contentHashCode()

  companion object {
    fun fromRawToken(token: String): TokenHash {
      val hash =
        java.security.MessageDigest
          .getInstance("SHA-256")
          .digest(token.toByteArray(Charsets.UTF_8))
      return TokenHash(hash)
    }
  }
}
