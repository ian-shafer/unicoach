package ed.unicoach.chat

data class ChatMessage(
  val role: ChatRole,
  val text: String,
)
