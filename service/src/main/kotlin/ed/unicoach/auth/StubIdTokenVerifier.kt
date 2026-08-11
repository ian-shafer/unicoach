package ed.unicoach.auth

/**
 * Offline [IdTokenVerifier] for test/dev, selected by configuration and never
 * wired in production. It performs no network access and no cryptographic
 * verification. Shared by both providers — the fake-token format carries no
 * provider distinction.
 *
 * Documented fake-token format: the literal prefix `stub:` followed by
 * `field=value` pairs separated by `;`. Recognised fields:
 * - `sub` (required) — the subject
 * - `email` (required) — the email claim
 * - `email_verified` — `true`/`false`, defaults to `false` when absent
 * - `name` — the optional display name
 *
 * Example: `stub:sub=12345;email=a@b.com;email_verified=true;name=Ada`.
 *
 * Any token not matching this format (missing prefix, missing `sub`/`email`, or
 * the literal `stub:invalid`) surfaces as [IdTokenInvalidException]. The
 * literal `stub:unavailable` surfaces as [IdTokenUnavailableException] so the
 * transient path is exercisable offline.
 */
class StubIdTokenVerifier : IdTokenVerifier {
  override suspend fun verify(idToken: String): Result<FederatedIdentity> {
    if (idToken == UNAVAILABLE_TOKEN) {
      return Result.failure(IdTokenUnavailableException("Stub: simulated JWKS unavailability"))
    }
    if (!idToken.startsWith(PREFIX) || idToken == INVALID_TOKEN) {
      return Result.failure(IdTokenInvalidException("Stub: token does not match the fake-token format"))
    }

    val fields =
      idToken
        .removePrefix(PREFIX)
        .split(";")
        .filter { it.isNotBlank() }
        .associate { pair ->
          val idx = pair.indexOf('=')
          if (idx < 0) pair to "" else pair.substring(0, idx) to pair.substring(idx + 1)
        }

    val subject = fields["sub"]?.takeIf { it.isNotBlank() }
    val email = fields["email"]?.takeIf { it.isNotBlank() }
    if (subject == null || email == null) {
      return Result.failure(IdTokenInvalidException("Stub: fake token is missing sub or email"))
    }

    return Result.success(
      FederatedIdentity(
        subject = subject,
        email = email,
        emailVerified = fields["email_verified"]?.equals("true", ignoreCase = true) == true,
        name = fields["name"]?.takeIf { it.isNotBlank() },
      ),
    )
  }

  companion object {
    const val PROVIDER_ID = "stub"
    private const val PREFIX = "stub:"
    const val INVALID_TOKEN = "stub:invalid"
    const val UNAVAILABLE_TOKEN = "stub:unavailable"
  }
}
