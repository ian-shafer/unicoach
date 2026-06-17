package ed.unicoach.db.models

import ed.unicoach.common.models.EmailAddress
import java.time.Instant

data class User(
  override val id: UserId,
  val email: EmailAddress,
  val name: PersonName,
  val displayName: DisplayName?,
  val authMethod: AuthMethod,
  val isAdmin: Boolean,
  override val version: Int,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  override val deletedAt: Instant?,
) : Identifiable<UserId>,
  Created,
  Updated,
  Versioned,
  SoftDeletable
