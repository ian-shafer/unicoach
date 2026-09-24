package ed.unicoach.db.models

/**
 * Wraps a raw token hash array to enforce domain signature bounds.
 *
 * Note on `ByteArray` equality: JVM arrays resolve equality by reference
 * rather than by structural content. When using this class in data classes,
 * you must explicitly override `equals` and `hashCode` and use `contentEquals`
 * to ensure tokens match correctly.
 *
 * The bytes are COPIED in and COPIED out. A `ByteArray` field published by
 * reference is one mutable buffer shared with every holder of the model that
 * carries it — and this class's `hashCode` is content-based, so an in-place
 * edit by any of them silently re-keys an object already in a map. This is a
 * credential digest; it is not shared.
 */
class TokenHash(
  bytes: ByteArray,
) {
  init {
    require(bytes.isNotEmpty()) { "TokenHash cannot be empty." }
  }

  private val stored = bytes.copyOf()

  val value: ByteArray get() = stored.copyOf()

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as TokenHash

    return stored.contentEquals(other.stored)
  }

  override fun hashCode(): Int = stored.contentHashCode()

  /** Redacted: this is the digest of a live credential and never belongs in a log line. */
  override fun toString(): String = "TokenHash(redacted)"

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
