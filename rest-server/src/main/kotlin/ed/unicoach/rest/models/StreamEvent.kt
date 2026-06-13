package ed.unicoach.rest.models

/**
 * SSE frame payloads, one per `event:` type. Each carries a fixed `type`
 * discriminator string matching the OpenAPI StreamEvent mapping; no Jackson
 * polymorphic configuration is needed since each is serialized concretely.
 */

data class ConversationCreatedEvent(
  val conversation: Conversation,
  val userMessage: Message,
  val type: String = "conversation",
)

data class UserMessageEvent(
  val userMessage: Message,
  val type: String = "user_message",
)

data class MessageDeltaEvent(
  val text: String,
  val type: String = "delta",
)

data class MessageCompletedEvent(
  val message: Message,
  val type: String = "message",
)

data class StreamErrorEvent(
  val error: ErrorResponse,
  val type: String = "error",
)
