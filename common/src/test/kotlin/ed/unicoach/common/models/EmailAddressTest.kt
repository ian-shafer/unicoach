package ed.unicoach.common.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmailAddressTest {
  @Test
  fun `blank input is rejected as Blank`() {
    val result = EmailAddress.create("   ")
    assertTrue(result is ValidationResult.Invalid && result.error is ValidationError.Blank)
  }

  @Test
  fun `input without an at sign is rejected as InvalidFormat`() {
    val result = EmailAddress.create("noat")
    assertTrue(result is ValidationResult.Invalid && result.error is ValidationError.InvalidFormat)
  }

  @Test
  fun `invalid-format error carries the expected email shape`() {
    val result = EmailAddress.create("nope")
    assertTrue(result is ValidationResult.Invalid)
    val error = result.error
    assertTrue(error is ValidationError.InvalidFormat)
    assertEquals("local@domain", error.expected)
  }

  @Test
  fun `at sign at the start is rejected as InvalidFormat`() {
    val result = EmailAddress.create("@x")
    assertTrue(result is ValidationResult.Invalid && result.error is ValidationError.InvalidFormat)
  }

  @Test
  fun `at sign at the end is rejected as InvalidFormat`() {
    val result = EmailAddress.create("x@")
    assertTrue(result is ValidationResult.Invalid && result.error is ValidationError.InvalidFormat)
  }

  @Test
  fun `valid input is trimmed and lowercased`() {
    val result = EmailAddress.create("  A@B.io ")
    assertTrue(result is ValidationResult.Valid)
    assertEquals("a@b.io", result.value.value)
  }
}
