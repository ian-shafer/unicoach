package ed.unicoach.rest.models

import java.time.Instant

data class Conversation(
  val id: String,
  val name: String,
  val createdAt: Instant,
  val updatedAt: Instant,
  val lastActivityAt: Instant?,
  val archivedAt: Instant?,
)
