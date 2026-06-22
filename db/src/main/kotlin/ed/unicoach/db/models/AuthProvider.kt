package ed.unicoach.db.models

/** Federated identity provider, persisted as `user_auth_identities.provider`. */
enum class AuthProvider(
  val wire: String,
) {
  GOOGLE("google"),
  ;

  companion object {
    /** Resolves a persisted wire value back to its enum, or null when unknown. */
    fun fromWire(value: String): AuthProvider? = entries.firstOrNull { it.wire == value }
  }
}
