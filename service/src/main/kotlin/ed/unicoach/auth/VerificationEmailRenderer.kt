package ed.unicoach.auth

import ed.unicoach.common.json.deserialize
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.email.EmailBody
import ed.unicoach.email.EmailSubject
import ed.unicoach.email.EmailTemplate
import ed.unicoach.email.EmailTemplateRenderer
import ed.unicoach.email.RenderValidationException
import ed.unicoach.email.RenderedEmail
import kotlinx.serialization.json.JsonObject

/**
 * Renders the email-verification message (RFC 96): it holds the verification copy
 * (relocated from the former `EmailVerificationService.sendVerificationEmail`) and
 * builds the verify link `"$verifyUrlBase?token=$verifyToken"`. [verifyUrlBase]
 * comes from `EmailVerificationConfig` (the `emailVerification.verifyUrlBase` key
 * of `service.conf`), which the worker already loads.
 *
 * [render] returns [Result.failure] for a context that cannot be deserialized or
 * that produces an invalid subject/body — a permanent, non-retriable condition
 * the handler folds to a dead-letter.
 */
class VerificationEmailRenderer(
  private val verifyUrlBase: String,
) : EmailTemplateRenderer {
  override val template = EmailTemplate.EMAIL_VERIFICATION

  override fun render(context: JsonObject): Result<RenderedEmail> {
    val verifyToken =
      runCatching { context.deserialize<VerificationEmailContext>().verifyToken }
        .getOrElse { return Result.failure(it) }

    val link = "$verifyUrlBase?token=$verifyToken"

    val subject =
      when (val s = EmailSubject.create("Verify your email address")) {
        is ValidationResult.Valid -> {
          s.value
        }

        is ValidationResult.Invalid -> {
          return Result.failure(RenderValidationException("verification subject", s.error))
        }
      }

    val body =
      when (
        val b =
          EmailBody.create(
            "Welcome to Unicoach. Confirm your email address by visiting:\n\n$link\n\n" +
              "If you did not create this account, you can ignore this message.",
          )
      ) {
        is ValidationResult.Valid -> {
          b.value
        }

        is ValidationResult.Invalid -> {
          return Result.failure(RenderValidationException("verification body", b.error))
        }
      }

    return Result.success(RenderedEmail(subject, body))
  }
}
