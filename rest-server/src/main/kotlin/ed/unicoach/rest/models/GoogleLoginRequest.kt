package ed.unicoach.rest.models

import kotlinx.serialization.Serializable

@Serializable
data class GoogleLoginRequest(
  val idToken: String,
)
