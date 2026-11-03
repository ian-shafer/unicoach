package ed.unicoach.email

import ed.unicoach.db.models.UserId

/**
 * The account an `email_sends` row is recorded against (RFC 267 §6): one value,
 * naming which of the pair-CHECKed columns holds the id, so a send can never be
 * recorded against both. Null where it is used means a recipient with no
 * account.
 */
sealed interface EmailAccountKey {
  /** A live account: `email_sends.user_id`. */
  data class Live(
    val userId: UserId,
  ) : EmailAccountKey

  /** An erased account, whose receipt this send is: `email_sends.deleted_user_id`. */
  data class Deleted(
    val userId: UserId,
  ) : EmailAccountKey
}
