package ed.unicoach.db.models

import java.time.Instant

data class Session(
  override val id: SessionId,
  override val version: Int,
  override val createdAt: Instant,
  val userId: UserId?,
  val metadata: String?,
  val userAgent: String?,
  val initialIp: String?,
  val expiresAt: Instant,
) : Identifiable<SessionId>,
  Created,
  Versioned
