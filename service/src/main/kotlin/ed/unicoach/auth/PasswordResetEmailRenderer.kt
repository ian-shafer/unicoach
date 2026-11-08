package ed.unicoach.auth

import ed.unicoach.common.json.deserialize
import ed.unicoach.email.EmailTemplate
import ed.unicoach.email.EmailTemplateRenderer
import ed.unicoach.email.RenderedEmail
import ed.unicoach.email.renderedEmail
import kotlinx.serialization.json.JsonObject

/**
 * Renders the password-reset message (RFC 276) and builds its link,
 * `"$resetUrlBase?token=$resetToken"`. The copy states no duration, so a
 * changed `passwordReset.tokenTtl` cannot make it wrong.
 *
 * [render] returns [Result.failure] for a context that cannot be deserialized or
 * that produces an invalid subject/body — a permanent, non-retriable condition
 * the handler folds to a dead-letter.
 */
class PasswordResetEmailRenderer(
  private val resetUrlBase: String,
) : EmailTemplateRenderer {
  override val template = EmailTemplate.PASSWORD_RESET

  override fun render(context: JsonObject): Result<RenderedEmail> {
    val resetToken =
      runCatching { context.deserialize<PasswordResetEmailContext>().resetToken }
        .getOrElse { return Result.failure(it) }

    val link = "$resetUrlBase?token=$resetToken"

    return renderedEmail(
      template = template,
      subject = "Reset your Unicoach password",
      body =
        "Someone asked to reset the password for your Unicoach account. To choose a new password, visit:\n\n$link\n\n" +
          "This link works once and expires soon. If you did not ask for this, you can ignore this message " +
          "and your password will stay the same.",
    )
  }
}
