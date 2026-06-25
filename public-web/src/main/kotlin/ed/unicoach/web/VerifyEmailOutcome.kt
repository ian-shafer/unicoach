package ed.unicoach.web

/**
 * The closed set of outcomes the verify flow renders, one branded page each.
 * This is public-web's render view-model, distinct from the domain
 * [ed.unicoach.auth.VerifyEmailResult]: it keeps the domain `User` out of the
 * render layer and adds the [Unavailable] case (a DB fault) that the domain type
 * does not carry.
 */
sealed interface VerifyEmailOutcome {
  /** The token was consumed and the email is now verified. */
  data object Verified : VerifyEmailOutcome

  /** The token is unrecognized, malformed, or otherwise not valid. */
  data object InvalidToken : VerifyEmailOutcome

  /** The token was valid but has expired. */
  data object Expired : VerifyEmailOutcome

  /** The token was already consumed (the email is already verified). */
  data object AlreadyUsed : VerifyEmailOutcome

  /** The verify consume failed (a DB fault); no outcome could be determined. */
  data object Unavailable : VerifyEmailOutcome
}
