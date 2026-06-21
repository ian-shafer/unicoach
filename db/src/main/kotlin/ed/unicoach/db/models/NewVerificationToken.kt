package ed.unicoach.db.models

import java.time.Instant

data class NewVerificationToken(
  val userId: UserId,
  val tokenHash: TokenHash,
  val expiresAt: Instant,
)
