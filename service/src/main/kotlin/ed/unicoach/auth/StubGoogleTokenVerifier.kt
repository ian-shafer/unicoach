package ed.unicoach.auth

/**
 * Offline [GoogleTokenVerifier] for test/dev, selected by configuration and
 * never wired in production. It performs no network access and no cryptographic
 * verification.
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
 * the literal `stub:invalid`) surfaces as [GoogleTokenInvalidException]. The
 * literal `stub:unavailable` surfaces as [GoogleTokenUnavailableException] so the
 * transient path is exercisable offline.
 */
class StubGoogleTokenVerifier : GoogleTokenVerifier {
  override fun verify(idToken: String): Result<GoogleIdentity> {
    if (idToken == UNAVAILABLE_TOKEN) {
      return Result.failure(GoogleTokenUnavailableException("Stub: simulated JWKS unavailability"))
    }
    if (!idToken.startsWith(PREFIX) || idToken == INVALID_TOKEN) {
      return Result.failure(GoogleTokenInvalidException("Stub: token does not match the fake-token format"))
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
      return Result.failure(GoogleTokenInvalidException("Stub: fake token is missing sub or email"))
    }

    return Result.success(
      GoogleIdentity(
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
