package ed.unicoach.auth

private const val MIN_PASSWORD_CODE_POINTS = 8
private const val MAX_PASSWORD_CODE_POINTS = 128

/** A password rule a password breaks. [sentence] is the one wording of it, for the person typing the password. */
sealed interface PasswordViolation {
  val sentence: String

  data class TooShort(
    val minCodePoints: Int,
  ) : PasswordViolation {
    override val sentence: String get() = "Password must be at least $minCodePoints characters long"
  }

  data class TooLong(
    val maxCodePoints: Int,
  ) : PasswordViolation {
    override val sentence: String get() = "Password must be at most $maxCodePoints characters long"
  }

  data object NoUppercase : PasswordViolation {
    override val sentence: String get() = "Password must contain at least 1 uppercase letter"
  }

  data object NoLowercase : PasswordViolation {
    override val sentence: String get() = "Password must contain at least 1 lowercase letter"
  }

  data object NoDigit : PasswordViolation {
    override val sentence: String get() = "Password must contain at least 1 digit"
  }
}

/**
 * Every rule [password] breaks; empty when it is acceptable. The one statement
 * of the password rules, so registration and a password reset cannot disagree.
 */
fun passwordViolations(password: String): List<PasswordViolation> =
  buildList {
    val codePoints = password.codePointCount(0, password.length)
    if (codePoints < MIN_PASSWORD_CODE_POINTS) add(PasswordViolation.TooShort(MIN_PASSWORD_CODE_POINTS))
    if (codePoints > MAX_PASSWORD_CODE_POINTS) add(PasswordViolation.TooLong(MAX_PASSWORD_CODE_POINTS))
    if (!password.any { it in 'A'..'Z' }) add(PasswordViolation.NoUppercase)
    if (!password.any { it in 'a'..'z' }) add(PasswordViolation.NoLowercase)
    if (!password.any { it in '0'..'9' }) add(PasswordViolation.NoDigit)
  }
