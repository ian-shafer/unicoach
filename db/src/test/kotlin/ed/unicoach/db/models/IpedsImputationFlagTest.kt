package ed.unicoach.db.models

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The IPEDS X-flag mapping (RFC 162), pinned code by code. The published list
 * is thirteen letters (SFA2223_Dict.zip -> sfa2223.xlsx, sheet "imputation
 * values"), and the one mapping this repo could get quietly wrong is `Z`: it
 * is a REAL published zero, not an imputation, and mislabelling it would call
 * 17,357 honest zeros in one file publisher guesses.
 */
class IpedsImputationFlagTest {
  @Test
  fun `every published code maps to the stated status`() {
    val expected =
      mapOf(
        "R" to FigureStatus.REPORTED,
        "C" to FigureStatus.REPORTED,
        "Z" to FigureStatus.REPORTED,
        "A" to FigureStatus.NOT_APPLICABLE,
        "P" to FigureStatus.IMPUTED_BY_PUBLISHER,
        "N" to FigureStatus.IMPUTED_BY_PUBLISHER,
        "G" to FigureStatus.IMPUTED_BY_PUBLISHER,
        "J" to FigureStatus.IMPUTED_BY_PUBLISHER,
        "K" to FigureStatus.IMPUTED_BY_PUBLISHER,
        "L" to FigureStatus.IMPUTED_BY_PUBLISHER,
        "B" to FigureStatus.NOT_REPORTED_BY_INSTITUTION,
        "D" to FigureStatus.NOT_REPORTED_BY_INSTITUTION,
        "H" to FigureStatus.NOT_REPORTED_BY_INSTITUTION,
      )
    // Both ways: a thirteenth code appearing here with no enum member, or a
    // fourteenth member appearing with no line here, must fail.
    assertEquals(expected.keys, IpedsImputationFlag.entries.map { it.code }.toSet())
    for ((code, status) in expected) {
      assertEquals(status, IpedsImputationFlag.fromCode(code)?.status, "flag [$code]")
    }
  }

  @Test
  fun `Z is reported, not imputed -- the correction this enum exists for`() {
    assertEquals(FigureStatus.REPORTED, IpedsImputationFlag.IMPLIED_ZERO.status)
    assertEquals(true, IpedsImputationFlag.IMPLIED_ZERO.status.valueBearing, "an implied zero carries its 0")
  }

  @Test
  fun `a not-applicable flag bears no value and an imputed one does`() {
    assertEquals(false, IpedsImputationFlag.NOT_APPLICABLE.status.valueBearing)
    assertEquals(true, IpedsImputationFlag.CARRY_FORWARD.status.valueBearing)
  }

  @Test
  fun `a code is read trimmed and case-insensitively, and an unknown letter has no mapping`() {
    assertEquals(IpedsImputationFlag.REPORTED, IpedsImputationFlag.fromCode(" r "))
    // No default: the caller decides how loudly to fail, because a letter the
    // publisher invented is a review, never one of our six statuses.
    assertNull(IpedsImputationFlag.fromCode("Q"))
    assertNull(IpedsImputationFlag.fromCode(""))
  }
}
