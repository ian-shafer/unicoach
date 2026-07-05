package ed.unicoach.queue

import kotlinx.serialization.Serializable

/**
 * Payload of a [JobType.FIT_LENS] job (RFC 98): the student whose accumulated
 * model the fit-lens discovery pass reasons over. [studentId] is the
 * `students.id` UUID as a string. Mirrors [SynthesisPayload].
 */
@Serializable
data class FitLensPayload(
  val studentId: String,
)
