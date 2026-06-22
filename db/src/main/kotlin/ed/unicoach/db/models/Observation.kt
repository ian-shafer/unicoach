package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the append-only `observations` log (RFC 66): an immutable verbatim
 * record of what a student said. [createdAt] is the ingest time (when
 * extraction recorded it); [utteredAt] is the event time (the source turn's
 * `created_at`).
 */
data class Observation(
  override val id: ObservationId,
  override val createdAt: Instant,
  val studentId: StudentId,
  val convoId: ConvoId,
  val sourceRequestId: ConvoRequestId,
  val utteredAt: Instant,
  val quote: String,
) : Identifiable<ObservationId>,
  Created
