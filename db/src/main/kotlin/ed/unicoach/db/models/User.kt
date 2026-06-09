package ed.unicoach.db.models

import ed.unicoach.common.models.EmailAddress
import java.time.Instant

data class User(
  override val id: UserId,
  val email: EmailAddress,
  val name: PersonName,
  val displayName: DisplayName?,
  val authMethod: AuthMethod,
  override val versionId: UserVersionId,
  override val createdAt: Instant,
  override val rowCreatedAt: Instant,
  override val updatedAt: Instant,
  override val rowUpdatedAt: Instant,
  val deletedAt: Instant?,
) : BaseEntity<UserId, UserVersionId>,
  AdvancedEntity
