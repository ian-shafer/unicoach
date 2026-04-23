package ed.unicoach.queue

import kotlinx.serialization.Serializable

@Serializable
data class SessionExpiryPayload(
  val tokenHash: String,
)
