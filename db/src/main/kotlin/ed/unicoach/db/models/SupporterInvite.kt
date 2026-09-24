package ed.unicoach.db.models

import java.time.Instant

/**
 * One row of `supporter_invites` (RFC 232): a single-use, hashed, revocable,
 * and expiring credential allowing family accounts to link.
 */
data class SupporterInvite(
  override val id: SupporterInviteId,
  val tokenHash: TokenHash,
  val inviterUserId: UserId,
  val inviterRole: InviterRole,
  val studentId: StudentId?,
  val supporterId: SupporterId?,
  val relationship: SupporterRelationship,
  val expiresAt: Instant,
  val consumedAt: Instant?,
  val revokedAt: Instant?,
  override val createdAt: Instant,
) : Identifiable<SupporterInviteId>,
  Created
