package ed.unicoach.db.models

import java.time.Instant

/**
 * Input for inserting a new supporter invite row (RFC 232).
 */
data class NewSupporterInvite(
  val tokenHash: TokenHash,
  val inviterUserId: UserId,
  val inviterRole: InviterRole,
  val studentId: StudentId?,
  val supporterId: SupporterId?,
  val relationship: SupporterRelationship,
  val expiresAt: Instant,
)
