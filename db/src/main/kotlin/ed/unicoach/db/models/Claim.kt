package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the mutable `claims` entity (RFC 66): the coach's current, revisable
 * belief about a student. [confidence] is a 0..1000 fixed-point value recomputed
 * in code from the claim's support set (never assigned by the LLM). Lifecycle is
 * captured by [status] plus the supersession/retraction pointers, not by a
 * versions table.
 */
data class Claim(
  override val id: ClaimId,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  val studentId: StudentId,
  val origin: ClaimOrigin,
  val status: ClaimStatus,
  val kind: ClaimKind,
  val subject: ClaimSubject,
  val topic: ClaimTopic,
  val visibility: ClaimVisibility,
  val statement: String,
  val confidence: Int,
  val supersededById: ClaimId?,
  val supersededAt: Instant?,
  val retractedAt: Instant?,
) : Identifiable<ClaimId>,
  Created,
  Updated
