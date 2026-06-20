package ed.unicoach.db.models

import ed.unicoach.common.models.EmailAddress

/**
 * Update-input record for [User], sibling of the [NewUser] creation input. Carries
 * the entity identity, the expected OCC [version], and only the mutable business
 * fields — never an immutable column (`createdAt`/`deletedAt`) nor the out-of-band
 * auth method (`password_hash`/`sso_provider_id`), which mutate through dedicated
 * auth flows, never through `update`.
 */
data class UserEdit(
  val id: UserId,
  val version: Int,
  val email: EmailAddress,
  val name: PersonName,
  val displayName: DisplayName?,
  val isAdmin: Boolean,
)
