package ed.unicoach.db.models

import ed.unicoach.db.session.SessionMetadata
import java.time.Duration

data class NewSession(
  val userId: UserId?,
  val tokenHash: TokenHash,
  val userAgent: String?,
  val initialIp: String?,
  /** The row's first metadata; see [Session.metadata]. */
  val metadata: SessionMetadata,
  val expiration: Duration,
  val loginMethod: LoginMethod? = null,
)
