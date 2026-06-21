package ed.unicoach.db.models

import java.time.Instant

data class VerificationToken(
  override val id: VerificationTokenId,
  val userId: UserId,
  val expiresAt: Instant,
  val consumedAt: Instant?,
  override val createdAt: Instant,
) : Identifiable<VerificationTokenId>,
  Created
