package ed.unicoach.auth

/**
 * The claims read from a verified federated ID token (Google or Apple).
 * [name] is optional — the `name` claim may be absent (Apple's identity token
 * never carries one), in which case callers derive a name elsewhere.
 */
data class FederatedIdentity(
  val subject: String,
  val email: String,
  val emailVerified: Boolean,
  val name: String?,
)
