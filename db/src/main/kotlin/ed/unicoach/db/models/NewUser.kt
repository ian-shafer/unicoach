package ed.unicoach.db.models

import ed.unicoach.common.models.EmailAddress

data class NewUser(
  val email: EmailAddress,
  val name: PersonName,
  val displayName: DisplayName?,
  val authMethod: AuthMethod,
  val isAdmin: Boolean = false,
)
