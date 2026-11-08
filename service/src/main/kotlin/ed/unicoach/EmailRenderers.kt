package ed.unicoach

import ed.unicoach.account.AccountErasedEmailRenderer
import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.auth.PasswordResetConfig
import ed.unicoach.auth.PasswordResetEmailRenderer
import ed.unicoach.auth.VerificationEmailRenderer
import ed.unicoach.email.EmailTemplateRenderer
import ed.unicoach.family.LinkEndedEmailRenderer
import ed.unicoach.family.LinkRequestedEmailRenderer

/**
 * Every email the queue worker can send, one renderer per
 * [ed.unicoach.email.EmailTemplate]. `EmailRenderersTest` holds this list to the
 * enum's entries, so a template added without a renderer fails the build
 * instead of dead-lettering every job of it at runtime.
 */
fun emailRenderers(
  verification: EmailVerificationConfig,
  passwordReset: PasswordResetConfig,
): List<EmailTemplateRenderer> =
  listOf(
    VerificationEmailRenderer(verification.verifyUrlBase),
    LinkEndedEmailRenderer(),
    LinkRequestedEmailRenderer(),
    AccountErasedEmailRenderer(),
    PasswordResetEmailRenderer(passwordReset.resetUrlBase),
  )
