package ed.unicoach.db.models

/** Federated identity provider, persisted as `user_auth_identities.provider`. */
enum class AuthProvider(
  val wire: String,
  /** The session login method a sign-in through this provider records. */
  val loginMethod: LoginMethod,
) {
  GOOGLE("google", LoginMethod.GOOGLE),
  APPLE("apple", LoginMethod.APPLE),
  ;

  companion object {
    /** Resolves a persisted wire value back to its enum, or null when unknown. */
    fun fromWire(value: String): AuthProvider? = entries.firstOrNull { it.wire == value }
  }
}
