package ed.unicoach.db.models

import java.time.Instant

data class AccountToken(
  override val id: AccountTokenId,
  val userId: UserId,
  val type: AccountTokenType,
  val userVersion: Int,
  val expiresAt: Instant,
  val consumedAt: Instant?,
  override val createdAt: Instant,
) : Identifiable<AccountTokenId>,
  Created
