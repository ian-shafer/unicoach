package ed.unicoach.email

import ed.unicoach.common.models.EmailAddress

data class OutboundEmail(
  val from: EmailAddress,
  val to: EmailAddress,
  val subject: EmailSubject,
  val body: EmailBody,
)
