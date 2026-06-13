package ed.unicoach.rest.models

import java.time.Instant

data class Message(
  val id: String,
  val role: String,
  val content: String,
  val createdAt: Instant,
)
