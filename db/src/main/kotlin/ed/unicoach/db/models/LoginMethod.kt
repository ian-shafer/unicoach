package ed.unicoach.db.models

/** How a session authenticated, persisted as `sessions.login_method`. */
enum class LoginMethod(
  val wire: String,
) {
  PASSWORD("password"),
  GOOGLE("google"),
  ;

  companion object {
    /** Resolves a persisted wire value back to its enum, or null when unknown. */
    fun fromWire(value: String): LoginMethod? = entries.firstOrNull { it.wire == value }
  }
}
