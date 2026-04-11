package ed.unicoach.util

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

class JwtGenerator(
  private val secret: String,
  private val issuer: String,
  private val clock: Clock = Clock.systemUTC(),
) {
  fun mint(
    subjectId: String,
    claims: Map<String, Any> = emptyMap(),
  ): String {
    val now = Instant.now(clock)
    val expiresAt = now.plus(7, ChronoUnit.DAYS)

    val builder =
      JWT
        .create()
        .withIssuer(issuer)
        .withSubject(subjectId)
        .withIssuedAt(now)
        .withExpiresAt(expiresAt)

    claims.forEach { (key, value) ->
      when (value) {
        is String -> builder.withClaim(key, value)
        is Int -> builder.withClaim(key, value)
        is Boolean -> builder.withClaim(key, value)
        is Double -> builder.withClaim(key, value)
        is Long -> builder.withClaim(key, value)
      }
    }

    return builder.sign(Algorithm.HMAC256(secret))
  }
}
