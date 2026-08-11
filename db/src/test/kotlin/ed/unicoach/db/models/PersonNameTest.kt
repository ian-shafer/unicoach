package ed.unicoach.db.models

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonNameTest {
  @Test
  fun `create trims surrounding whitespace and returns Valid`() {
    val result = PersonName.create("  Ada Lovelace  ")
    assertTrue(result is ValidationResult.Valid, "Expected Valid, got $result")
    assertEquals("Ada Lovelace", result.value.value)
  }

  @Test
  fun `create returns Invalid Blank for blank-after-trim input`() {
    val result = PersonName.create("   ")
    assertTrue(
      result is ValidationResult.Invalid && result.error is ValidationError.Blank,
      "Expected Invalid(Blank), got $result",
    )
  }

  // 255 is the users_name_length_check bound; a longer name must be rejected in
  // Kotlin rather than reaching Postgres as a 23514.
  @Test
  fun `create returns Invalid TooLong for input longer than 255`() {
    val result = PersonName.create("a".repeat(256))
    assertTrue(
      result is ValidationResult.Invalid && result.error is ValidationError.TooLong,
      "Expected Invalid(TooLong), got $result",
    )
  }

  @Test
  fun `create accepts a 255-character name`() {
    val name = "a".repeat(255)
    val result = PersonName.create(name)
    assertTrue(result is ValidationResult.Valid, "Expected Valid, got $result")
    assertEquals(name, result.value.value)
  }
}
