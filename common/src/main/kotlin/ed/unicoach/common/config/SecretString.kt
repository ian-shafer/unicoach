package ed.unicoach.common.config

/**
 * Wraps sensitive configuration values ensuring default methods do not expose raw secrets.
 */
class SecretString(
  val value: String,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as SecretString
    return value == other.value
  }

  override fun hashCode(): Int = value.hashCode()

  override fun toString(): String = "********"
}
