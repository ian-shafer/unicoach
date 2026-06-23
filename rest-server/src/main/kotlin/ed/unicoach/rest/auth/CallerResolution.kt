package ed.unicoach.rest.auth

import ed.unicoach.auth.AuthService
import ed.unicoach.db.models.Session
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

/**
 * The caller resolved for a request: the session cookie's token hash, the live
 * session row, and the user. Cached on `call.attributes` so the email-verification
 * gate and the downstream handler share one `sessions`+`users` lookup.
 */
data class ResolvedCaller(
  val tokenHash: TokenHash,
  val session: Session,
  val user: User,
)

/**
 * Present on `call.attributes` only after an actual lookup resolved a caller.
 * Its presence therefore implies "identity resolved" — a missing cookie or a
 * null resolution caches nothing.
 */
val ResolvedCallerKey = AttributeKey<ResolvedCaller>("ResolvedCaller")

/**
 * Resolves the caller for this request, caching the result on `call.attributes`.
 *
 * Returns the cached [ResolvedCaller] if [ResolvedCallerKey] is present;
 * otherwise reads the session cookie, computes [TokenHash.fromRawToken] once,
 * calls [AuthService.resolveSession], and — only on a non-null result — stores
 * and returns a [ResolvedCaller]. A missing cookie or a null resolution returns
 * `null` and caches nothing, so the cached type is non-null and "attribute
 * present" implies "identity resolved by an actual lookup."
 *
 * Resolves identity only; asserts nothing about email verification. A DB fault
 * inside resolution propagates via `getOrThrow`.
 */
suspend fun ApplicationCall.resolveCaller(
  authService: AuthService,
  sessionConfig: SessionConfig,
): ResolvedCaller? {
  attributes.getOrNull(ResolvedCallerKey)?.let { return it }

  val token = request.cookies[sessionConfig.cookieName] ?: return null
  val tokenHash = TokenHash.fromRawToken(token)
  val authenticated = authService.resolveSession(tokenHash).getOrThrow() ?: return null

  val resolved = ResolvedCaller(tokenHash, authenticated.session, authenticated.user)
  attributes.put(ResolvedCallerKey, resolved)
  return resolved
}
