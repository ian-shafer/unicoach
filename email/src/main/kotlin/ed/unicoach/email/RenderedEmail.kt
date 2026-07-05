package ed.unicoach.email

/**
 * The output of an [EmailTemplateRenderer]: a validated subject and body ready
 * to hand to [EmailService.send].
 */
data class RenderedEmail(
  val subject: EmailSubject,
  val body: EmailBody,
)
