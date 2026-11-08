package ed.unicoach.auth

import kotlinx.serialization.Serializable

/**
 * The render context for the [ed.unicoach.email.EmailTemplate.PASSWORD_RESET]
 * template (RFC 276). As with [VerificationEmailContext], the raw single-use
 * token is placed here at enqueue and the worker renders the `?token=` link
 * from it.
 */
@Serializable
data class PasswordResetEmailContext(
  val resetToken: String,
)
