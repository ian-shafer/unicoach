package ed.unicoach.rest.models

import java.util.UUID

data class PublicUser(
  val id: UUID,
  val email: String,
  val name: String,
)
