package ed.unicoach.rest.models

import ed.unicoach.db.models.User
import java.util.UUID

data class PublicUser(
  val id: UUID,
  val email: String,
  val name: String,
  val emailVerified: Boolean,
) {
  companion object {
    /** Projects a domain [User] onto the public-facing shape returned by the auth routes. */
    fun from(user: User): PublicUser =
      PublicUser(
        id = user.id.value,
        email = user.email.value,
        name = user.name.value,
        emailVerified = user.emailVerifiedAt != null,
      )
  }
}
