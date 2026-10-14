package ed.unicoach

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
fun emailRenderers(verifyUrlBase: String): List<EmailTemplateRenderer> =
  listOf(VerificationEmailRenderer(verifyUrlBase), LinkEndedEmailRenderer(), LinkRequestedEmailRenderer())
