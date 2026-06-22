package ed.unicoach.db.models

import ed.unicoach.common.models.EmailAddress

/** Creation input for an [AuthIdentity] row. */
data class NewAuthIdentity(
  val userId: UserId,
  val provider: AuthProvider,
  val subject: ProviderSubject,
  val email: EmailAddress,
  val emailVerified: Boolean,
)
