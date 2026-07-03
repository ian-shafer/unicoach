package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the mutable `commitments` entity (RFC 93): a coach-owned intention
 * derived from reflection over the student's model. It resolves from `open` to
 * `fulfilled` (surfaced to the student as a promise kept) or `dropped` (its
 * basis went away). Lifecycle is captured by [status] plus the resolution
 * columns, not by a versions table. [triggerAt] is advisory provenance (the
 * future date a `timing` insight references); it is recorded but never acted on
 * (no scheduler reads it).
 */
data class Commitment(
  override val id: CommitmentId,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  val studentId: StudentId,
  val lens: CommitmentLens,
  val disclosure: CommitmentDisclosure,
  val status: CommitmentStatus,
  val statement: String,
  val triggerKind: CommitmentTriggerKind,
  val triggerAt: Instant?,
  val fulfilledAt: Instant?,
  val disclosedInConvoId: ConvoId?,
  val droppedAt: Instant?,
  val dropReason: String?,
) : Identifiable<CommitmentId>,
  Created,
  Updated
