package ed.unicoach.db.models

/**
 * What a row of `account_tokens` can do (RFC 276). Backs `account_tokens.type`;
 * a new kind of mailed token is one entry here and one value in that `CHECK`.
 */
enum class AccountTokenType(
  val value: String,
) {
  PASSWORD_RESET("password_reset"),
  ;

  companion object {
    fun fromValue(value: String): AccountTokenType? = entries.find { it.value == value }
  }
}
