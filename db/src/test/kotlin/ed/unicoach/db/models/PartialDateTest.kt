package ed.unicoach.db.models

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import org.junit.jupiter.api.Test
import java.time.Month
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PartialDateTest {
  private fun parseValid(iso: String): PartialDate {
    val result = PartialDate.parse(iso)
    assertTrue(result is ValidationResult.Valid, "Expected Valid for '$iso', got $result")
    return result.value
  }

  private fun assertInvalid(iso: String) {
    val result = PartialDate.parse(iso)
    assertTrue(
      result is ValidationResult.Invalid && result.error is ValidationError.InvalidFormat,
      "Expected Invalid(InvalidFormat) for '$iso', got $result",
    )
  }

  @Test
  fun `parse accepts the three zero-padded canonical forms`() {
    val yearOnly = parseValid("2028")
    assertTrue(yearOnly is PartialDate.YearOnly)
    assertEquals(2028, yearOnly.year.value)

    val yearAndMonth = parseValid("2028-06")
    assertTrue(yearAndMonth is PartialDate.YearAndMonth)
    assertEquals(Month.JUNE, yearAndMonth.monthOf)

    val fullDate = parseValid("2028-06-15")
    assertTrue(fullDate is PartialDate.FullDate)
    assertEquals(15, fullDate.date.dayOfMonth)
  }

  @Test
  fun `parse rejects unpadded components`() {
    assertInvalid("2028-6")
    assertInvalid("2028-6-5")
    // Sanity: the zero-padded equivalents do parse.
    assertTrue(PartialDate.parse("2028-06") is ValidationResult.Valid)
  }

  @Test
  fun `parse rejects empty non-numeric and out-of-range values`() {
    assertInvalid("")
    assertInvalid("notadate")
    assertInvalid("2028-13")
    assertInvalid("2028-02-31")
    assertInvalid("2028-00")
  }

  @Test
  fun `parse rejects signed and overlong years`() {
    assertInvalid("+2028")
    assertInvalid("20281")
  }

  @Test
  fun `toIso round-trips each precision`() {
    assertEquals("2028", parseValid("2028").toIso())
    assertEquals("2028-06", parseValid("2028-06").toIso())
    assertEquals("2028-06-15", parseValid("2028-06-15").toIso())
  }

  @Test
  fun `of reconstructs the correct variant`() {
    assertTrue(
      (PartialDate.of(2028, null, null) as ValidationResult.Valid).value is PartialDate.YearOnly,
    )
    assertTrue(
      (PartialDate.of(2028, 6, null) as ValidationResult.Valid).value is PartialDate.YearAndMonth,
    )
    assertTrue(
      (PartialDate.of(2028, 6, 15) as ValidationResult.Valid).value is PartialDate.FullDate,
    )
  }

  @Test
  fun `of rejects impossible combinations and orphan day`() {
    assertTrue(PartialDate.of(2028, 2, 31) is ValidationResult.Invalid)
    assertTrue(PartialDate.of(2028, 13, null) is ValidationResult.Invalid)
    assertTrue(PartialDate.of(2028, null, 15) is ValidationResult.Invalid)
  }

  @Test
  fun `parse rejects with InvalidFormat carrying the canonical forms`() {
    val outOfRange = PartialDate.parse("2028-13")
    assertTrue(outOfRange is ValidationResult.Invalid)
    val rangeError = outOfRange.error
    assertTrue(rangeError is ValidationError.InvalidFormat)
    assertEquals("YYYY | YYYY-MM | YYYY-MM-DD", rangeError.expected)

    val nonCanonical = PartialDate.parse("notadate")
    assertTrue(nonCanonical is ValidationResult.Invalid)
    val shapeError = nonCanonical.error
    assertTrue(shapeError is ValidationError.InvalidFormat)
    assertEquals("YYYY | YYYY-MM | YYYY-MM-DD", shapeError.expected)
  }

  @Test
  fun `of rejects day-without-month with InvalidFormat carrying the canonical forms`() {
    val result = PartialDate.of(2028, null, 15)
    assertTrue(result is ValidationResult.Invalid)
    val error = result.error
    assertTrue(error is ValidationError.InvalidFormat)
    assertEquals("YYYY | YYYY-MM | YYYY-MM-DD", error.expected)
  }
}
