package ed.unicoach.email

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmailSubjectTest {
  @Test
  fun `blank subject is rejected as Blank`() {
    val result = EmailSubject.create("   ")
    assertTrue(result is ValidationResult.Invalid && result.error is ValidationError.Blank)
  }

  @Test
  fun `subject over the maximum length is rejected as TooLong carrying the bound`() {
    val result = EmailSubject.create("a".repeat(EmailSubject.MAX_SUBJECT_LENGTH + 1))
    assertTrue(result is ValidationResult.Invalid)
    val error = result.error
    assertTrue(error is ValidationError.TooLong)
    assertEquals(EmailSubject.MAX_SUBJECT_LENGTH, error.maxLength)
  }

  @Test
  fun `subject at the maximum length is valid`() {
    val result = EmailSubject.create("a".repeat(EmailSubject.MAX_SUBJECT_LENGTH))
    assertTrue(result is ValidationResult.Valid)
  }
}
