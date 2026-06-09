package ed.unicoach.email

import java.util.UUID

data class SentEmail(
  val id: UUID,
  val providerMessageId: String?,
)
