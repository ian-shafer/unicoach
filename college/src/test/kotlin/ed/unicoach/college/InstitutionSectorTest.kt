package ed.unicoach.college

import ed.unicoach.db.models.InstitutionSector
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [InstitutionSector] (RFC 150 D61b) — the authored vocabulary for `HD.SECTOR`.
 *
 * It lives in `:college` rather than `:db`, where the enum does, because the
 * assertion that matters compares the enum against
 * [IpedsLoader.SECTOR_CODES] — the authoritative code set, which is on this side
 * of the module boundary. A published code the enum forgets breaks THIS test
 * rather than an ingest.
 */
class InstitutionSectorTest {
  @Test
  fun `the enum's code set is exactly IpedsLoader SECTOR_CODES`() {
    assertEquals(
      IpedsLoader.SECTOR_CODES,
      InstitutionSector.entries.map { it.code }.toSet(),
      "the enum and the ingest's accepted code set are one vocabulary, not two",
    )
    assertEquals(11, InstitutionSector.entries.size, "0..9 plus 99; 10..98 are values IPEDS does not publish")
  }

  @Test
  fun `every word is underscored, so sector and control speak one dialect`() {
    val slugLike = Regex("^[a-z0-9]+(_[a-z0-9]+)*$")
    for (sector in InstitutionSector.entries) {
      assertEquals(true, slugLike.matches(sector.value), "[${sector.value}] is not an underscored word")
    }
  }

  @Test
  fun `99 is the word unknown, and it is a reported fact rather than an absence`() {
    assertEquals(InstitutionSector.UNKNOWN, InstitutionSector.fromCode(99))
    assertEquals("unknown", InstitutionSector.fromCode(99)?.value)
    // The absence half is asserted where it can be: the rebuild's own suite
    // (CollegeSearchIndexRebuildTest) shows a college with no college_ipeds row
    // storing NULL, which is a different outcome from this word.
  }

  @Test
  fun `a code IPEDS does not publish is not defined`() {
    for (code in listOf(-1, 10, 50, 98, 100)) {
      assertNull(InstitutionSector.fromCode(code), "code $code must not resolve to a sector")
    }
  }

  @Test
  fun `fromValue round-trips every word and refuses anything else`() {
    for (sector in InstitutionSector.entries) {
      assertEquals(sector, InstitutionSector.fromValue(sector.value))
    }
    assertNull(InstitutionSector.fromValue("public-four-year"), "hyphens are not this vocabulary")
    assertNull(InstitutionSector.fromValue(""))
  }
}
