package ed.unicoach.rest.models

import java.time.Instant

/**
 * One of a supporter's own coach conversations (RFC 258) in the list read:
 * which student it is about, so the client can show the conversations about
 * the student the parent has selected. No archive field: the parent surface has
 * no archive.
 */
data class SupporterConversation(
  val id: String,
  val studentId: String,
  val name: String,
  val createdAt: Instant,
  val updatedAt: Instant,
)

/** `GET /api/v1/supporters/me/conversations`. */
data class SupporterConversationListResponse(
  val conversations: List<SupporterConversation>,
)

/**
 * `POST /api/v1/supporters/me/conversations/stream`: the subject is a body
 * field on the one route that creates (RFC 258), so no route binds a student.
 * A string, parsed by the handler, so a malformed id is a named 400 and not an
 * untyped deserialization failure.
 */
data class CreateSupporterConversationRequest(
  val studentId: String,
  val message: String,
)
