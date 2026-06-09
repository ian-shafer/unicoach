package ed.unicoach.email

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import kotlin.test.Test
import kotlin.test.assertTrue

class EmailSubjectTest {
  @Test
  fun `blank subject is rejected as BlankString`() {
    val result = EmailSubject.create("   ")
    assertTrue(result is ValidationResult.Invalid && result.error is ValidationError.BlankString)
  }

  @Test
  fun `subject over the maximum length is rejected as TooLong`() {
    val result = EmailSubject.create("a".repeat(EmailSubject.MAX_SUBJECT_LENGTH + 1))
    assertTrue(result is ValidationResult.Invalid && result.error is ValidationError.TooLong)
  }

  @Test
  fun `subject at the maximum length is valid`() {
    val result = EmailSubject.create("a".repeat(EmailSubject.MAX_SUBJECT_LENGTH))
    assertTrue(result is ValidationResult.Valid)
  }
}
