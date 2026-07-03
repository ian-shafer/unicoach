package ed.unicoach.db.models

import java.time.Instant

/**
 * Insert input for a fresh `commitments` row. Omits the DB-generated id,
 * timestamps, and resolution columns; `status` defaults to `open` and
 * `trigger_kind` to `next_session` in the DB. [triggerAt] is optional advisory
 * provenance for a `timing` insight.
 */
data class NewCommitment(
  val studentId: StudentId,
  val lens: CommitmentLens,
  val disclosure: CommitmentDisclosure,
  val statement: String,
  val triggerAt: Instant? = null,
)
