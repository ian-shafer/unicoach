package ed.unicoach.rest.models

data class CreateConversationRequest(
  val message: String,
  val name: String? = null,
)
