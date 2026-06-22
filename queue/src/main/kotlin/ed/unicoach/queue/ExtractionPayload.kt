package ed.unicoach.queue

import kotlinx.serialization.Serializable

/**
 * Payload of an [JobType.EXTRACT_CONVERSATION] job (RFC 66): the conversation to
 * extract and the user-turn id the window runs through. [convoId] is the
 * `convos.id` UUID as a string; [throughRequestId] is the `convo_requests.id`
 * (BIGINT) of the user turn just persisted.
 */
@Serializable
data class ExtractionPayload(
  val convoId: String,
  val throughRequestId: Long,
)
