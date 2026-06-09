package ed.unicoach.email

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import kotlin.test.Test
import kotlin.test.assertTrue

class EmailBodyTest {
  @Test
  fun `blank body is rejected as BlankString`() {
    val result = EmailBody.create("   ")
    assertTrue(result is ValidationResult.Invalid && result.error is ValidationError.BlankString)
  }

  @Test
  fun `body over the maximum length is rejected as TooLong`() {
    val result = EmailBody.create("a".repeat(EmailBody.MAX_BODY_LENGTH + 1))
    assertTrue(result is ValidationResult.Invalid && result.error is ValidationError.TooLong)
  }

  @Test
  fun `body at the maximum length is valid`() {
    val result = EmailBody.create("a".repeat(EmailBody.MAX_BODY_LENGTH))
    assertTrue(result is ValidationResult.Valid)
  }
}
