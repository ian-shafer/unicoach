package ed.unicoach.db.models

import java.time.Duration

data class NewSession(
  val userId: UserId?,
  val tokenHash: TokenHash,
  val userAgent: String?,
  val initialIp: String?,
  val metadata: String?,
  val expiration: Duration,
)
