package ed.unicoach.db.models

import ed.unicoach.common.models.EmailAddress
import java.time.Instant

/**
 * An immutable federated identity: the fact that a `(provider, subject)` belongs
 * to a user, established at [createdAt]. [email]/[emailVerified] are provenance
 * only — the claims the provider asserted when the row was created. They are
 * never re-synced and never read on the login path.
 */
data class AuthIdentity(
  override val id: AuthIdentityId,
  val userId: UserId,
  val provider: AuthProvider,
  val subject: ProviderSubject,
  val email: EmailAddress,
  val emailVerified: Boolean,
  override val createdAt: Instant,
) : Identifiable<AuthIdentityId>,
  Created
