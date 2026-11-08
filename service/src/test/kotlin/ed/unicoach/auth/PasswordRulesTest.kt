package ed.unicoach.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PasswordRulesTest {
  private fun sentences(password: String): List<String> = passwordViolations(password).map { it.sentence }

  @Test
  fun `an acceptable password breaks no rule`() {
    assertEquals(emptyList(), passwordViolations("GoodEnough1"))
  }

  @Test
  fun `too short reports the violation with its limit and the words registration and reset show`() {
    assertEquals(listOf(PasswordViolation.TooShort(8)), passwordViolations("Short1a"))
    assertEquals(listOf("Password must be at least 8 characters long"), sentences("Short1a"))
  }

  @Test
  fun `too long reports the violation with its limit and the words registration and reset show`() {
    val password = "Aa1" + "x".repeat(126)

    assertEquals(listOf(PasswordViolation.TooLong(128)), passwordViolations(password))
    assertEquals(listOf("Password must be at most 128 characters long"), sentences(password))
  }

  @Test
  fun `each missing character class reports its violation and its words`() {
    assertEquals(listOf(PasswordViolation.NoUppercase), passwordViolations("lowercase1"))
    assertEquals(listOf("Password must contain at least 1 uppercase letter"), sentences("lowercase1"))
    assertEquals(listOf(PasswordViolation.NoLowercase), passwordViolations("UPPERCASE1"))
    assertEquals(listOf("Password must contain at least 1 lowercase letter"), sentences("UPPERCASE1"))
    assertEquals(listOf(PasswordViolation.NoDigit), passwordViolations("NoDigitsHere"))
    assertEquals(listOf("Password must contain at least 1 digit"), sentences("NoDigitsHere"))
  }
}
