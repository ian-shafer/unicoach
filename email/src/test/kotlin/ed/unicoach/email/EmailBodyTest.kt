package ed.unicoach.email

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmailBodyTest {
  @Test
  fun `blank body is rejected as Blank`() {
    val result = EmailBody.create("   ")
    assertTrue(result is ValidationResult.Invalid && result.error is ValidationError.Blank)
  }

  @Test
  fun `body over the maximum length is rejected as TooLong carrying the bound`() {
    val result = EmailBody.create("a".repeat(EmailBody.MAX_BODY_LENGTH + 1))
    assertTrue(result is ValidationResult.Invalid)
    val error = result.error
    assertTrue(error is ValidationError.TooLong)
    assertEquals(EmailBody.MAX_BODY_LENGTH, error.maxLength)
  }

  @Test
  fun `body at the maximum length is valid`() {
    val result = EmailBody.create("a".repeat(EmailBody.MAX_BODY_LENGTH))
    assertTrue(result is ValidationResult.Valid)
  }
}
