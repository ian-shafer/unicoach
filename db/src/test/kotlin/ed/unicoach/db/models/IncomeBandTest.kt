package ed.unicoach.db.models

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class IncomeBandTest {
  private fun college(
    q1: Int?,
    q2: Int?,
    q3: Int?,
    q4: Int?,
    q5: Int?,
  ): College =
    College(
      id = CollegeId(UUID.randomUUID()),
      version = 1,
      unitId = 1,
      opeid = null,
      name = "C",
      city = "X",
      state = "CA",
      region = null,
      locale = null,
      latitude = null,
      longitude = null,
      control = 1,
      undergradEnrollment = null,
      admissionRate = null,
      satAvg = null,
      costAttendance = null,
      netPrice = null,
      netPriceQ1 = q1,
      netPriceQ2 = q2,
      netPriceQ3 = q3,
      netPriceQ4 = q4,
      netPriceQ5 = q5,
      tuitionInState = null,
      tuitionOutState = null,
      graduationRate = null,
      medianEarnings = null,
      medianDebt = null,
      pctPell = null,
      website = null,
      aliases = emptyList(),
      createdAt = Instant.EPOCH,
      updatedAt = Instant.EPOCH,
    )

  @Test
  fun `each band selects its own net price quintile`() {
    val c = college(10, 20, 30, 40, 50)
    assertEquals(10, IncomeBand.UNDER_30K.netPriceFor(c))
    assertEquals(20, IncomeBand.K30_TO_48K.netPriceFor(c))
    assertEquals(30, IncomeBand.K48_TO_75K.netPriceFor(c))
    assertEquals(40, IncomeBand.K75_TO_110K.netPriceFor(c))
    assertEquals(50, IncomeBand.OVER_110K.netPriceFor(c))
  }

  @Test
  fun `an unreported bracket is null, not a fallback to another bracket`() {
    val c = college(null, 20, null, null, null)
    assertEquals(null, IncomeBand.UNDER_30K.netPriceFor(c))
    assertEquals(20, IncomeBand.K30_TO_48K.netPriceFor(c))
  }

  @Test
  fun `each bracket is a spoken dollar range, not a spreadsheet label`() {
    // bracket is display copy the coach says aloud (RFC 142): it rides the wire
    // as income_band_label, so an abbreviation here becomes one in the answer.
    assertEquals("\$0 to \$30,000", IncomeBand.UNDER_30K.bracket)
    assertEquals("\$30,001 to \$48,000", IncomeBand.K30_TO_48K.bracket)
    assertEquals("\$48,001 to \$75,000", IncomeBand.K48_TO_75K.bracket)
    assertEquals("\$75,001 to \$110,000", IncomeBand.K75_TO_110K.bracket)
    assertEquals("\$110,000 or more", IncomeBand.OVER_110K.bracket)
  }

  @Test
  fun `fromValue round-trips every member and rejects an unknown label`() {
    for (band in IncomeBand.entries) {
      assertEquals(band, IncomeBand.fromValue(band.value))
    }
    assertEquals(null, IncomeBand.fromValue("rich"))
  }
}
