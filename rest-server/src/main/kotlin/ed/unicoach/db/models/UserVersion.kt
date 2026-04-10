package ed.unicoach.db.models

import java.time.Instant

data class UserVersion(
  override val id: UserId,
  override val versionId: UserVersionId,
  val email: EmailAddress,
  val name: PersonName,
  val displayName: DisplayName?,
  val authMethod: AuthMethod,
  override val createdAt: Instant,
  val rowCreatedAt: Instant,
  override val updatedAt: Instant,
  val rowUpdatedAt: Instant,
  val deletedAt: Instant?,
) : BaseVersionEntity<UserId>
