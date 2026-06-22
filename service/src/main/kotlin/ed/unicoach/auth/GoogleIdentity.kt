package ed.unicoach.auth

/**
 * The claims read from a verified Google ID token. [name] is optional — the
 * `name` claim may be absent, in which case callers derive a name elsewhere.
 */
data class GoogleIdentity(
  val subject: String,
  val email: String,
  val emailVerified: Boolean,
  val name: String?,
)
