package ed.unicoach.db.models

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class IncomeBandTest {
  /**
   * A search result row carrying only the five band prices this suite is
   * about. The band prices reach [CollegeMatch] from `cohort_money_stats`
   * since RFC 176, keyed by the band they are about; the lookup
   * [IncomeBand.getNetPrice] makes over them is what is under test here, and
   * an ABSENT key is how the row says "no figure for that bracket" — there is
   * no null entry to mistake for one.
   */
  private fun match(
    q1: Int?,
    q2: Int?,
    q3: Int?,
    q4: Int?,
    q5: Int?,
  ): CollegeMatch =
    CollegeMatch(
      id = CollegeId(UUID.randomUUID()),
      ipedsUnitId = 1,
      name = "C",
      city = "X",
      state = "CA",
      control = "public",
      region = null,
      locale = null,
      undergradEnrollmentHeadcount = null,
      admissionRateShare = null,
      netPricePerYearUsd = null,
      rulerPriceUsd = null,
      residencyTierBasis = ResidencyTierBasis.NO_PUBLISHED_TUITION,
      netPriceUsdByBand =
        listOf(
          IncomeBand.UNDER_30K to q1,
          IncomeBand.K30_TO_48K to q2,
          IncomeBand.K48_TO_75K to q3,
          IncomeBand.K75_TO_110K to q4,
          IncomeBand.OVER_110K to q5,
        ).mapNotNull { (band, amount) -> amount?.let { band to it } }.toMap(),
      completionRate150pct4yrShare = null,
      medianEarnings10yAfterEntryUsd = null,
      medianDebtAtCompletionUsd = null,
      pellShare = null,
      website = null,
      programTitles = null,
    )

  @Test
  fun `each band selects its own net price quintile`() {
    val c = match(10, 20, 30, 40, 50)
    assertEquals(10, IncomeBand.UNDER_30K.getNetPrice(c))
    assertEquals(20, IncomeBand.K30_TO_48K.getNetPrice(c))
    assertEquals(30, IncomeBand.K48_TO_75K.getNetPrice(c))
    assertEquals(40, IncomeBand.K75_TO_110K.getNetPrice(c))
    assertEquals(50, IncomeBand.OVER_110K.getNetPrice(c))
  }

  @Test
  fun `an unreported bracket is null, not a fallback to another bracket`() {
    val c = match(null, 20, null, null, null)
    assertEquals(null, IncomeBand.UNDER_30K.getNetPrice(c))
    assertEquals(20, IncomeBand.K30_TO_48K.getNetPrice(c))
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
