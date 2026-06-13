package ed.unicoach.rest.models

data class PostMessageResponse(
  val userMessage: Message,
  val coachMessage: Message,
)
