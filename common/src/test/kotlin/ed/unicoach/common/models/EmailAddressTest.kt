package ed.unicoach.common.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmailAddressTest {
  @Test
  fun `blank input is rejected as BlankString`() {
    val result = EmailAddress.create("   ")
    assertTrue(result is ValidationResult.Invalid && result.error is ValidationError.BlankString)
  }

  @Test
  fun `input without an at sign is rejected as InvalidFormat`() {
    val result = EmailAddress.create("noat")
    assertTrue(result is ValidationResult.Invalid && result.error is ValidationError.InvalidFormat)
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
