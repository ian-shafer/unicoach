package ed.unicoach.db.models

sealed interface AuthMethod {
  data class Password(
    val hash: PasswordHash,
  ) : AuthMethod

  data class SSO(
    val providerId: SsoProviderId,
  ) : AuthMethod

  data class Both(
    val hash: PasswordHash,
    val providerId: SsoProviderId,
  ) : AuthMethod
}
