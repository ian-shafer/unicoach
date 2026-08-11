package ed.unicoach.rest.models

import kotlinx.serialization.Serializable

@Serializable
data class AppleLoginRequest(
  // Named for parity with GoogleLoginRequest.idToken, not Apple's own
  // "identityToken" vocabulary.
  val idToken: String,
  // Optional; used only when provisioning a new user. Apple's identity token
  // never carries a name claim — the client supplies it out of band, once, at
  // first authorization.
  val name: String? = null,
)
