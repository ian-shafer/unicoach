package ed.unicoach.db.models

import ed.unicoach.common.models.EmailAddress
import java.time.Instant

data class UserVersion(
  override val id: UserId,
  override val version: Int,
  val email: EmailAddress,
  val name: PersonName,
  val displayName: DisplayName?,
  val authMethod: AuthMethod,
  override val createdAt: Instant,
  val updatedAt: Instant,
  val deletedAt: Instant?,
) : Identifiable<UserId>,
  Created,
  Versioned
