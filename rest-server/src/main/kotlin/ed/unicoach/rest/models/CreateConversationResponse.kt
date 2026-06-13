package ed.unicoach.rest.models

data class CreateConversationResponse(
  val conversation: Conversation,
  val userMessage: Message,
  val coachMessage: Message,
)
