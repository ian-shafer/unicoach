package ed.unicoach.db.models

import java.time.Instant

/**
 * [userVersion] is the `users.version` the link is mailed under: the
 * `users_versions` row at that version holds the address it went to.
 */
data class NewAccountToken(
  val userId: UserId,
  val type: AccountTokenType,
  val userVersion: Int,
  val tokenHash: TokenHash,
  val expiresAt: Instant,
)
