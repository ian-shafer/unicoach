package ed.unicoach.coaching.costs.canonical

import ed.unicoach.coaching.costs.CostField
import ed.unicoach.college.CanonicalMoneyLoader
import ed.unicoach.college.IpedsChargeVocabulary
import ed.unicoach.db.models.CohortAddresses
import ed.unicoach.db.models.CohortStatAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The CROSS-MODULE contract on the canonical address grid: `:college` WRITES
 * the cells, `:service` READS them, and neither list can be derived from the
 * other (`PriceAddress`'s own KDoc says why -- a publisher's variable stem on
 * one side, this repo's wire field on the other).
 *
 * So the agreement is enforced here instead. Every other test in this surface
 * is blind to it by construction: `CostsTestDb` and `FakeCostReportSource`
 * write their rows at the addresses the READER asks for, so a reader and a fill
 * that disagree still produce a green suite -- and a family is told "this school
 * does not report it" about a price we hold, or is served a different cohort's
 * number under the right label. That is not hypothetical: RFC 166 tier-0
 * blocker 1 was exactly this, caught by a human reading the loader.
 *
 * This file fails if EITHER side is edited alone.
 */
class CanonicalAddressContractTest {
  @Test
  fun `every price address this surface serves is a cell the ingest actually fills`() {
    val filled =
      IpedsChargeVocabulary.PRICE_CELLS
        .map { PriceAddress(it.concept, it.residency, it.arrangement) }
        .toSet()
    val served = CostField.entries.mapNotNull { (it.figureAddress as? FigureAddress.Price)?.address }
    assertTrue(served.isNotEmpty(), "the price grid is what this surface reads; an empty read side is a broken test")
    served.forEach { address ->
      assertTrue(
        address in filled,
        "no fill writes [$address], so the field at it would read as a silence this school never kept: " +
          "filled=[$filled]",
      )
    }
  }

  @Test
  fun `every cell the ingest fills is a cell some cost field serves`() {
    val served = CostField.entries.mapNotNull { (it.figureAddress as? FigureAddress.Price)?.address }.toSet()
    val filled =
      IpedsChargeVocabulary.PRICE_CELLS
        .map { PriceAddress(it.concept, it.residency, it.arrangement) }
        .toSet()
    // BOTH directions, because the IC_AY fill and this vocabulary are the same
    // twelve cells: a cell written and read by nobody is a figure we hold and
    // never speak, which is the state RFC 166 exists to end. (The Scorecard fill
    // writes a SUBSET of these same cells -- eight of the twelve -- so the
    // superset is the honest write side to compare against.)
    filled.forEach { address ->
      assertTrue(
        address in served,
        "the ingest fills [$address] and no CostField reads it, so the figure reaches nobody: served=[$served]",
      )
    }
    assertEquals(served, filled, "the price grid must have exactly one spelling, read and written")
  }

  @Test
  fun `every cohort address this surface serves is one the fills write`() {
    val served = CostField.entries.mapNotNull { (it.figureAddress as? FigureAddress.Cohort)?.address }
    assertTrue(served.isNotEmpty(), "the cohort statistics are what this surface reads for the net price and the debt")
    served.forEach { address ->
      val written =
        CohortStatAddress(address.measure, address.population, address.aidScope)
      assertTrue(
        written in CanonicalMoneyLoader.WRITTEN_ADDRESSES,
        "no fill writes [$written], so this field would read as a silence: " +
          "written=[${CanonicalMoneyLoader.WRITTEN_ADDRESSES}]",
      )
    }
  }

  @Test
  fun `the served net price is the Scorecard cohort, never the SFA grant-aided row at the same measure`() {
    val netPrice =
      (CostField.NET_PRICE.figureAddress as FigureAddress.Cohort).address
    val served =
      CohortStatAddress(netPrice.measure, netPrice.population, netPrice.aidScope)
    // The two rows share a MEASURE and describe different students. Keyed on the
    // measure alone -- which the reader was, until tier-0 -- the newer SFA row
    // wins and a family is quoted a grant-aided cohort's net price as "the
    // average net price".
    assertEquals(CohortAddresses.AVG_NET_PRICE, served)
    assertNotEquals(CanonicalMoneyLoader.SFA_GRANT_AIDED_NET_PRICE, served)
    assertEquals(
      CohortAddresses.AVG_NET_PRICE.measure,
      CanonicalMoneyLoader.SFA_GRANT_AIDED_NET_PRICE.measure,
      "if these ever stop sharing a measure this test has stopped guarding anything",
    )
  }
}
