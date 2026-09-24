package ed.unicoach.db.models

/**
 * The role of the inviter creating a supporter invite (RFC 232).
 * Backs `supporter_invites.inviter_role`.
 */
enum class InviterRole(
  val value: String,
) {
  STUDENT("student"),
  SUPPORTER("supporter"),
  ;

  companion object {
    fun fromValue(value: String): InviterRole? = entries.find { it.value == value }
  }
}
