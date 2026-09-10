package ed.unicoach.college

import ed.unicoach.common.util.AcademicYear
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The one cell the corrected Scorecard year forces us to withhold (RFC 183 D1).
 *
 * Honestly dated 2024-25 the Scorecard becomes the NEWEST published price and
 * so the one a family is served -- which would move every district-based
 * college off IPEDS's three residency tiers onto the Scorecard's two, and tell
 * a Texas student that Austin Community College costs 2,550 in-state when it
 * costs 8,580. A right label bought with a wrong number is not a fix, so that
 * one cell is refused at the write seam.
 *
 * The pin on the years themselves is `ScorecardDictionaryPinTest`, which reads
 * a committed CSV and needs no database. This suite proves what the fill
 * WRITES, so it ingests.
 */
class ScorecardResidencyCollapseTest : CollegeScorecardTestBase() {
  private val loader = CollegeScorecardLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-ic-ay-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-fixture.csv")
  private val aliasesJson = fixture("college-aliases-fixture.json")

  /**
   * The same [IC_AY_FIXTURE_RECORDS]-institution IC_AY corpus `CanonicalMoneyIpedsTest` ingests, for the same
   * reason: it is real.
   */
  private fun ingest(): CollegeScorecardLoader.IngestReport =
    runBlocking { loader.ingest(source(institutionCsv), source(fieldsCsv), source(aliasesJson), ipedsCorpusSources()) }

  @Test
  fun `Austin CC keeps its real 8580 in-state price, and no Scorecard in-state row is written`() {
    ingest()
    // Austin CC (222992) charges 2,550 in-district and 8,580 in-state. The
    // Scorecard publishes ONE "in" cell and it holds the 2,550. Written under
    // in_state at the newer 2024-25 it would become the served price and
    // understate a Texas commuter's tuition by $6,030, so it is not written.
    assertNull(
      figure(222992, "tuition_and_fees", "in_state", academicYear = CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR),
      "the Scorecard's in-district amount must not be stored as an in-state price at any year",
    )
    assertEquals(
      emptyList(),
      query(
        "SELECT p.amount_usd FROM price_figures p JOIN colleges g ON g.id = p.college_id " +
          "WHERE g.ipeds_unit_id = 222992 AND p.price_concept = 'tuition_and_fees' " +
          "AND p.residency_basis = 'in_state' AND p.source = 'scorecard'",
      ) { it.getInt(1) },
    )
    // What the family is served instead: IPEDS's own in-state figure, at the
    // coherent IPEDS year. The read side reaches it with no edit -- 2024-25 no
    // longer completely prices an in-state arrangement, so it is not chosen.
    val ipeds =
      assertNotNull(
        figure(222992, "tuition_and_fees", "in_state", academicYear = AcademicYear(IC_AY_FIXTURE_SURVEY_YEAR)),
      )
    assertEquals(8580, ipeds.amountUsd)
    assertEquals("CHG2AY3", ipeds.sourceVariable)
  }

  @Test
  fun `a college whose two in-state tiers agree DOES take the Scorecard's newer figure`() {
    ingest()
    // Anti-vacuity, and the whole reason the predicate is structural rather
    // than "prefer IPEDS": UCSD (110680) files 15,265 as BOTH its in-district
    // and its in-state price, so the Scorecard collapses nothing and its
    // 2024-25 cell is exactly as true as IPEDS's 2023-24 one. Withholding here
    // would drop a newer figure for no reason. A rule that suppressed
    // everything would still pass the test above; it fails this one.
    val row =
      assertNotNull(
        figure(110680, "tuition_and_fees", "in_state", academicYear = CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR),
      )
    assertEquals(15265, row.amountUsd)
    assertEquals("scorecard", row.source)
    assertEquals("TUITIONFEE_IN", row.sourceVariable)
  }

  @Test
  fun `a college that merged its tiers is judged on its NEWEST filing, not on an older difference`() {
    ingest()
    // Florida State College at Jacksonville (133702) really did this: IPEDS
    // publishes 2,878 in-district against 2,938 in-state for 2022-23, and
    // 2,878 on BOTH tiers for 2023-24. The question the rule asks is whether
    // the college charges two rates NOW, so the answer is read off the newest
    // year that files BOTH tiers and off that year alone. ORing over every
    // stored year would keep a merged-tier college from ever taking a newer
    // Scorecard price again -- dropping a 2024-25 figure for a distinction
    // that no longer exists.
    //
    // Every figure here is the publisher's own: the fixture rows for this
    // institution are IPEDS's IC2023_AY row and the Scorecard's own row,
    // copied verbatim. A fixture that misstated a real college's price would
    // be the very defect this RFC exists to repair.
    val superseded =
      assertNotNull(figure(133702, "tuition_and_fees", "in_district", academicYear = AcademicYear(2022)))
    val stillFiled = assertNotNull(figure(133702, "tuition_and_fees", "in_state", academicYear = AcademicYear(2022)))
    assertEquals(2878, superseded.amountUsd, "the older year really does file a DIFFERENT in-district rate")
    assertEquals(2938, stillFiled.amountUsd)

    val row =
      assertNotNull(
        figure(133702, "tuition_and_fees", "in_state", academicYear = CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR),
        "the newest IC_AY year files equal tiers, so nothing is collapsed and the Scorecard's cell writes",
      )
    assertEquals(2878, row.amountUsd)
    assertEquals("scorecard", row.source)
    assertEquals("TUITIONFEE_IN", row.sourceVariable)
  }

  @Test
  fun `the withheld cell is COUNTED, and its count reaches the fill result`() {
    val fill = ingest().canonicalMoney
    // A withheld cell is a loss this fill reports, like a malformed row or a
    // row without a CONTROL -- never a silent `if`. Exactly one of the five
    // ingested colleges collapses residency at its newest filed year (222992);
    // 110680, 166027 and 133702 file equal tiers there.
    assertEquals(1, fill.inStateTuitionWithheld)
    // And the OTHER refusal reason reads zero, because this fill staged real
    // IC_AY residency evidence: the two counts are different facts about the
    // run and must not be summed into one.
    assertEquals(0, fill.inStateTuitionUnevidenced)
  }

  @Test
  fun `a college IPEDS filed no tier pair for is written UNMEASURED, and counted as such`() {
    val fill = ingest().canonicalMoney
    // 10236801 (Troy University-Phenix City Campus) is in the Scorecard corpus
    // and in no IC_AY row at all, so nothing about it was measured. Its cell is
    // still WRITTEN: withholding here would strip the in-state price from every
    // college IPEDS does not cover, in exchange for a risk nobody has measured.
    // What must not happen is that the write reads like a cleared one --
    // "IPEDS filed nothing usable" is a different fact from "IPEDS measured
    // this college and its tiers agree", and only the counter tells them apart.
    assertEquals(1, fill.inStateTuitionWrittenUnmeasured)
    val row =
      assertNotNull(
        figure(10236801, "tuition_and_fees", "in_state", academicYear = CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR),
        "an unmeasured college keeps the Scorecard's newer figure -- that is the policy, and the count is its price",
      )
    assertEquals(9792, row.amountUsd)
    assertEquals("scorecard", row.source)
    assertEquals("TUITIONFEE_IN", row.sourceVariable)
    // And the three measured colleges are NOT in that count: a rule whose
    // counter fired for everybody would say nothing about anybody.
    assertEquals(0, fill.inStateTuitionUnevidenced)
  }

  @Test
  fun `an IC_AY staging whose in-district tier bears no amount clears NOBODY`() {
    // The same corpus, with every in-district charge cell filed by the
    // publisher as "not applicable" -- the KEYS are all still staged. The
    // evidence D1 reads is a COMPARISON, so it takes two sides and both must
    // carry an AMOUNT: a run that can compare nothing must clear nobody, and
    // fails CLOSED at every college rather than writing the Scorecard's "in"
    // cell under a label nothing checked.
    val fill =
      runBlocking {
        loader.ingest(
          source(institutionCsv),
          source(fieldsCsv),
          source(aliasesJson),
          ipedsCorpusSources("ipeds-ic2023-ay-in-district-absent-fixture.csv"),
        )
      }.canonicalMoney
    assertEquals(
      emptyList(),
      query(
        "SELECT p.amount_usd FROM price_figures p " +
          "WHERE p.price_concept = 'tuition_and_fees' AND p.residency_basis = 'in_state' " +
          "AND p.source = 'scorecard'",
      ) { it.getInt(1) },
      "with no comparable tier pair anywhere, no Scorecard in-state cell may be written",
    )
    assertEquals(5, fill.inStateTuitionUnevidenced, "every matched row is refused, and every refusal is counted")
    assertEquals(0, fill.inStateTuitionWithheld)
    assertEquals(0, fill.inStateTuitionWrittenUnmeasured)
  }

  @Test
  fun `at a collapsing college every other Scorecard cell still writes`() {
    ingest()
    // EXACTLY ONE cell is refused. Out-of-state carries no residency ambiguity
    // -- the Scorecard's own second tuition column -- and nothing else the
    // Scorecard publishes has a residency axis at all.
    val year = CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR
    val outOfState = assertNotNull(figure(222992, "tuition_and_fees", "out_of_state", academicYear = year))
    assertEquals(10590, outOfState.amountUsd)
    assertEquals("scorecard", outOfState.source)
    assertEquals("TUITIONFEE_OUT", outOfState.sourceVariable)

    val housing = assertNotNull(figure(222992, "housing_and_food", "not_applicable", "off_campus", year))
    assertEquals(18240, housing.amountUsd)
    assertEquals("scorecard", housing.source)

    val books = assertNotNull(figure(222992, "books_and_supplies", "not_applicable", academicYear = year))
    assertEquals(1200, books.amountUsd)
    assertEquals("scorecard", books.source)

    // And the net-price series, which is keyed by vintage rather than by
    // residency and was never in question.
    assertEquals(
      listOf(1278),
      query(
        "SELECT cms.value::int FROM cohort_money_stats cms JOIN colleges g ON g.id = cms.college_id " +
          "WHERE g.ipeds_unit_id = 222992 AND cms.measure = 'avg_net_price' AND cms.income_band = 'under_30k' " +
          "AND cms.vintage = ${CanonicalMoneyLoader.BLENDED_AVERAGE_VINTAGE.firstCalendarYear}",
      ) { it.getInt(1) },
    )
  }

  @Test
  fun `every Scorecard row is stored at the corrected year, and no row is left at the old one`() {
    ingest()
    assertEquals(
      listOf(CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR),
      query("SELECT DISTINCT academic_year FROM price_figures WHERE source = 'scorecard'") {
        AcademicYear(it.getInt(1))
      },
    )
    assertEquals(
      listOf(CanonicalMoneyLoader.BLENDED_AVERAGE_VINTAGE),
      query(
        "SELECT DISTINCT vintage FROM cohort_money_stats WHERE source = 'scorecard' AND vintage IS NOT NULL",
      ) { AcademicYear(it.getInt(1)) },
    )
  }
}
