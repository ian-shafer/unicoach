package ed.unicoach.queue

import kotlinx.serialization.Serializable

/**
 * Payload of a [JobType.SYNTHESIZE_STUDENT] job (RFC 93): the student whose
 * accumulated model the synthesis pass reflects over. [studentId] is the
 * `students.id` UUID as a string.
 */
@Serializable
data class SynthesisPayload(
  val studentId: String,
)
