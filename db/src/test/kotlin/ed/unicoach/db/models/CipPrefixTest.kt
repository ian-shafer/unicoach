package ed.unicoach.db.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CipPrefixTest {
  @Test
  fun `canonical digit-only prefixes pass through unchanged`() {
    for (prefix in listOf("26", "2607", "260702", "05", "050103")) {
      assertEquals(prefix, CipPrefix.parseOrNull(prefix), "[$prefix] should pass through")
    }
  }

  @Test
  fun `the conventional dotted notation is canonicalized to digits`() {
    assertEquals("2607", CipPrefix.parseOrNull("26.07"))
    assertEquals("260702", CipPrefix.parseOrNull("26.0702"))
    assertEquals("5138", CipPrefix.parseOrNull("51.38"))
    assertEquals("513801", CipPrefix.parseOrNull("51.3801"))
    // A trailing dot is a family written with an empty detail.
    assertEquals("26", CipPrefix.parseOrNull("26."))
    assertEquals("260702", CipPrefix.parseOrNull("  26.0702  "))
  }

  @Test
  fun `a family that lost its leading zero is recovered, not truncated`() {
    assertEquals("050103", CipPrefix.parseOrNull("5.0103"))
    assertEquals("010901", CipPrefix.parseOrNull("1.0901"))
    assertEquals("0501", CipPrefix.parseOrNull("5.01"))
  }

  @Test
  fun `an ambiguous prefix is rejected rather than silently reinterpreted`() {
    // "5.138" could be 05.138 (not a prefix length) or 51.38 -- deleting the dot
    // would answer confidently about 51.38 Nursing. Refuse instead.
    assertNull(CipPrefix.parseOrNull("5.138"))
  }

  @Test
  fun `malformed prefixes are rejected`() {
    for (prefix in listOf(
      "",
      "2",
      "260",
      "26070",
      "2607021",
      "bio",
      "26.b7",
      "26.07.02",
      ".2607",
      "2 607",
      "-26",
      "26.070",
      "260.7",
      "%",
      "26%",
    )) {
      assertNull(CipPrefix.parseOrNull(prefix), "[$prefix] should be rejected")
    }
  }
}
