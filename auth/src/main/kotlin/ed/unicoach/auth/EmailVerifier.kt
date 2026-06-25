package ed.unicoach.auth

/**
 * The single-use email-verification consume, shared across services so both
 * `rest-server` and `public-web` call the same in-process verify rather than
 * one hopping to the other over HTTP. The default production implementation is
 * [DbEmailVerifier]; tests supply a hand-written fake.
 *
 * `verify` does not throw: a DB fault folds to `Result.failure`, leaving the
 * caller to render its own branded outcome.
 */
interface EmailVerifier {
  suspend fun verify(rawToken: String): Result<VerifyEmailResult>
}
