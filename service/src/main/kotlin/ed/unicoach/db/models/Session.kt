package ed.unicoach.db.models

import java.time.Instant
import java.util.UUID

data class Session(
  val id: UUID,
  val version: Int,
  val createdAt: Instant,
  val userId: UserId?,
  val metadata: String?,
  val userAgent: String?,
  val initialIp: String?,
)
