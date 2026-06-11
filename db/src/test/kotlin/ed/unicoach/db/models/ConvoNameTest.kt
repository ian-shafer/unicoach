package ed.unicoach.db.models

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConvoNameTest {
  @Test
  fun `create trims surrounding whitespace and returns Valid`() {
    val result = ConvoName.create("  College essay help  ")
    assertTrue(result is ValidationResult.Valid, "Expected Valid, got $result")
    assertEquals("College essay help", result.value.value)
  }

  @Test
  fun `create returns Invalid Blank for blank-after-trim input`() {
    val result = ConvoName.create("   ")
    assertTrue(
      result is ValidationResult.Invalid && result.error is ValidationError.Blank,
      "Expected Invalid(Blank), got $result",
    )
  }

  @Test
  fun `create returns Invalid TooLong for input longer than 255`() {
    val result = ConvoName.create("a".repeat(256))
    assertTrue(
      result is ValidationResult.Invalid && result.error is ValidationError.TooLong,
      "Expected Invalid(TooLong), got $result",
    )
  }

  @Test
  fun `create accepts a 255-character name`() {
    val name = "a".repeat(255)
    val result = ConvoName.create(name)
    assertTrue(result is ValidationResult.Valid, "Expected Valid, got $result")
    assertEquals(name, result.value.value)
  }
}
