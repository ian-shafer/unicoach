package ed.unicoach.auth

import kotlinx.serialization.Serializable

/**
 * The render context for the [ed.unicoach.email.EmailTemplate.EMAIL_VERIFICATION]
 * template (RFC 96). The raw single-use token is placed here at enqueue — it is
 * available in the issuing transaction and stored nowhere else — and the worker
 * renders the `?token=` link from it. This is the same token exposure as the
 * rendered body, which `email_sends` already persists.
 */
@Serializable
data class VerificationEmailContext(
  val verifyToken: String,
)
