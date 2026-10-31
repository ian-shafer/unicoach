package ed.unicoach.email.dao

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.db.models.UserId
import ed.unicoach.email.EmailBody
import ed.unicoach.email.EmailSendStatus
import ed.unicoach.email.EmailSubject

data class NewEmailSend(
  val recipient: EmailAddress, // -> recipient_email column
  val userId: UserId?, // -> user_id column; the recipient's account, null for a non-account recipient
  val sender: EmailAddress, // -> sender_email column
  val subject: EmailSubject,
  val body: EmailBody,
  val status: EmailSendStatus, // SENT | REJECTED
  val provider: String,
  val providerMessageId: String?,
  val errorMessage: String?,
)
