package ed.unicoach.email

import ed.unicoach.common.models.ValidationResult

/**
 * The output of an [EmailTemplateRenderer]: a validated subject and body ready
 * to hand to [EmailService.send].
 */
data class RenderedEmail(
  val subject: EmailSubject,
  val body: EmailBody,
)

/**
 * Validates a template's raw [subject] and [body] into a [RenderedEmail]. An
 * invalid part fails as a [RenderValidationException] naming [template] and
 * the part — a permanent condition the handler dead-letters.
 */
fun renderedEmail(
  template: EmailTemplate,
  subject: String,
  body: String,
): Result<RenderedEmail> {
  val validSubject =
    when (val s = EmailSubject.create(subject)) {
      is ValidationResult.Valid -> s.value
      is ValidationResult.Invalid -> return Result.failure(RenderValidationException("$template subject", s.error))
    }
  val validBody =
    when (val b = EmailBody.create(body)) {
      is ValidationResult.Valid -> b.value
      is ValidationResult.Invalid -> return Result.failure(RenderValidationException("$template body", b.error))
    }
  return Result.success(RenderedEmail(validSubject, validBody))
}
