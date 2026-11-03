package ed.unicoach.account

import ed.unicoach.email.EmailTemplate
import ed.unicoach.email.EmailTemplateRenderer
import ed.unicoach.email.RenderedEmail
import ed.unicoach.email.renderedEmail
import kotlinx.serialization.json.JsonObject

/**
 * Renders [EmailTemplate.ACCOUNT_ERASED] (RFC 267 §6): the one email that says
 * an account is deleted. Its context is empty: the account's name is erased
 * with it, and the email has no other record to take one from. A context that
 * carries anything is refused, since no field of it could be rendered.
 *
 * The copy is interim; deletion/05/the-words owns it.
 */
class AccountErasedEmailRenderer : EmailTemplateRenderer {
  override val template = EmailTemplate.ACCOUNT_ERASED

  override fun render(context: JsonObject): Result<RenderedEmail> {
    if (context.isNotEmpty()) {
      return Result.failure(IllegalArgumentException("[$template] takes an empty context; got keys ${context.keys}"))
    }
    return renderedEmail(
      template = template,
      subject = "Your unicoach account has been deleted",
      body =
        "Your unicoach account has been deleted, as you asked, and the information in it has been erased.\n\n" +
          "We keep a record that the account existed and when it was deleted, your subscription record with " +
          "Apple, and our usage records with your words removed.\n\n" +
          "If you subscribed through Apple, the subscription continues until you cancel it in your Apple ID " +
          "settings.\n\n" +
          "— unicoach",
    )
  }
}
