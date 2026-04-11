package ed.unicoach.db.models

data class NewUser(
  val email: EmailAddress,
  val name: PersonName,
  val displayName: DisplayName?,
  val authMethod: AuthMethod,
)
