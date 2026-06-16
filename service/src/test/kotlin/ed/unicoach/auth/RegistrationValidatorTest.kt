package ed.unicoach.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class RegistrationValidatorTest {
  private val validator = RegistrationValidator()

  private fun validInput(email: String) = RegistrationInput(email = email, name = "Real Name", password = "Password123")

  @Test
  fun `blank email is rejected`() {
    val errors = validator.validate(validInput(email = ""))
    assertTrue(errors.fieldErrors.any { it.field == "email" }, "Expected an email field error for blank email")
  }

  @Test
  fun `malformed email without interior at-sign is rejected`() {
    val errors = validator.validate(validInput(email = "useratexample.com"))
    assertTrue(errors.fieldErrors.any { it.field == "email" }, "Expected an email field error for malformed email")
  }

  @Test
  fun `valid email passes`() {
    val errors = validator.validate(validInput(email = "user@example.com"))
    assertTrue(errors.fieldErrors.none { it.field == "email" }, "Valid email must not yield an email field error")
  }

  private fun inputWithPassword(password: String) =
    RegistrationInput(
      email = "user@example.com",
      name = "Real Name",
      password = password,
    )

  // A single astral (supplementary) code point: U+20000, one code point / two UTF-16 units.
  private val astral = String(Character.toChars(0x20000))

  @Test
  fun `astral password under 8 code points is rejected`() {
    // "𸀀񀇓jP3R񈇰" — 7 code points, 10 UTF-16 units, with ASCII P/R/j/3.
    val password = astral + astral + "jP3R" + astral
    assertTrue(
      password.codePointCount(0, password.length) == 7 && password.length == 10,
      "Test fixture must be 7 code points / 10 UTF-16 units",
    )
    val errors = validator.validate(inputWithPassword(password))
    assertTrue(
      errors.fieldErrors.any { it.field == "password" },
      "A 7-code-point password must yield a password field error",
    )
  }

  @Test
  fun `astral password of 8 code points passes length`() {
    // Same shape plus one extra ASCII letter: 8 code points.
    val password = astral + astral + "jP3Ra" + astral
    assertTrue(
      password.codePointCount(0, password.length) == 8,
      "Test fixture must be 8 code points",
    )
    val errors = validator.validate(inputWithPassword(password))
    assertTrue(
      errors.fieldErrors.none { it.field == "password" && it.message.contains("at least 8") },
      "An 8-code-point password must not yield a minLength error",
    )
  }

  @Test
  fun `128 code points does not trigger maxLength`() {
    // 128 code points (256 UTF-16 units would have tripped the prior length > 128).
    // 124 astral fillers (248 UTF-16 units) + ASCII "aB3z" => 128 code points.
    val password = astral.repeat(124) + "aB3z"
    assertTrue(
      password.codePointCount(0, password.length) == 128,
      "Test fixture must be 128 code points",
    )
    val errors = validator.validate(inputWithPassword(password))
    assertTrue(
      errors.fieldErrors.none { it.field == "password" && it.message.contains("at most 128") },
      "A 128-code-point password must not yield a maxLength error",
    )
  }

  @Test
  fun `129 code points triggers maxLength`() {
    val password = astral.repeat(125) + "aB3z"
    assertTrue(
      password.codePointCount(0, password.length) == 129,
      "Test fixture must be 129 code points",
    )
    val errors = validator.validate(inputWithPassword(password))
    assertTrue(
      errors.fieldErrors.any { it.field == "password" && it.message.contains("at most 128") },
      "A 129-code-point password must yield a maxLength error",
    )
  }

  @Test
  fun `non-ASCII uppercase does not satisfy the uppercase rule`() {
    // "Ωassword1" — Greek Ω + ASCII lowercase + digit, no ASCII uppercase.
    val errors = validator.validate(inputWithPassword("Ωassword1"))
    assertTrue(
      errors.fieldErrors.any { it.field == "password" && it.message.contains("uppercase") },
      "A password whose only uppercase is non-ASCII must yield the uppercase field error",
    )
  }

  @Test
  fun `non-ASCII lowercase does not satisfy the lowercase rule`() {
    // "PASSWORDσ1" — Greek σ is the only lowercase.
    val errors = validator.validate(inputWithPassword("PASSWORDσ1"))
    assertTrue(
      errors.fieldErrors.any { it.field == "password" && it.message.contains("lowercase") },
      "A password whose only lowercase is non-ASCII must yield the lowercase field error",
    )
  }

  @Test
  fun `non-ASCII digit does not satisfy the digit rule`() {
    // "Password٣" — Arabic-Indic digit ٣ (U+0663) is the only digit.
    val errors = validator.validate(inputWithPassword("Password٣"))
    assertTrue(
      errors.fieldErrors.any { it.field == "password" && it.message.contains("digit") },
      "A password whose only digit is non-ASCII must yield the digit field error",
    )
  }

  @Test
  fun `valid ASCII password passes all rules`() {
    val errors = validator.validate(inputWithPassword("Password123"))
    assertTrue(
      errors.fieldErrors.none { it.field == "password" },
      "A valid ASCII password must not yield any password field error",
    )
  }
}
