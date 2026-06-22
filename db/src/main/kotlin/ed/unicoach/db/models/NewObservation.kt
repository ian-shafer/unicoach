package ed.unicoach.db.models

import java.time.Instant

/** Insert input for the `observations` log; omits the DB-generated id and `created_at`. */
data class NewObservation(
  val studentId: StudentId,
  val convoId: ConvoId,
  val sourceRequestId: ConvoRequestId,
  val utteredAt: Instant,
  val quote: String,
)
