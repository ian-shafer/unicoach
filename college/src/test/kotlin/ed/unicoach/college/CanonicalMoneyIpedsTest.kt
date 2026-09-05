package ed.unicoach.college

import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.MoneySource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IPEDS IC_AY as a canonical money SOURCE (RFC 161): the upstream-wins
 * precedence that makes it the winner, the price cells it fills that the
 * Scorecard cannot, and the closed [MoneySource] enumeration.
 *
 * The whole slice is one number. The Scorecard collapses in-district into
 * "in", so unicoach told a family that Austin Community College's in-state
 * price is $2,550 when the real in-state price is $8,580 and $2,550 is the
 * in-DISTRICT price. Measured over the whole 2023 file, `TUITIONFEE_IN` agrees
 * with `CHG2AY3` on only 92.1% of rows and every one of the 269 mismatches
 * equals `CHG1AY3` exactly.
 */
class CanonicalMoneyIpedsTest : CollegeScorecardTestBase() {
  private val loader = CollegeScorecardLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-ic-ay-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-fixture.csv")
  private val aliasesJson = fixture("college-aliases-fixture.json")

  private fun source(file: File): SourceFile = SourceFile(file, file.path)

  private fun ipedsSources(icAy: String = "ipeds-ic2023-ay-fixture.csv") =
    IpedsSources(
      source(fixture("ipeds-hd2023-fixture.csv")),
      source(fixture("ipeds-ic2023-fixture.csv")),
      source(fixture("ipeds-adm2023-fixture.csv")),
      source(fixture("ipeds-c2023-a-fixture.csv")),
      source(fixture(icAy)),
      2023,
    )

  private fun ingest(ipeds: IpedsSources? = ipedsSources()): CollegeScorecardLoader.IngestReport =
    runBlocking { loader.ingest(source(institutionCsv), source(fieldsCsv), source(aliasesJson), ipeds) }

  private data class Figure(
    val amountUsd: Int?,
    val status: String,
    val source: String,
    val sourceVariable: String,
    val publisherFlag: String?,
  )

  private fun figure(
    ipedsUnitId: Int,
    concept: String,
    residency: String,
    arrangement: String = "not_applicable",
    academicYear: String = "2023-24",
  ): Figure? =
    query(
      "SELECT p.amount_usd, p.status, p.source, p.source_variable, p.publisher_flag " +
        "FROM price_figures p JOIN colleges g ON g.id = p.college_id " +
        "WHERE g.ipeds_unit_id = $ipedsUnitId AND p.price_concept = '$concept' " +
        "AND p.residency_basis = '$residency' AND p.arrangement = '$arrangement' " +
        "AND p.academic_year = '$academicYear'",
    ) { rs ->
      Figure(
        amountUsd = rs.getInt(1).takeUnless { rs.wasNull() },
        status = rs.getString(2),
        source = rs.getString(3),
        sourceVariable = rs.getString(4),
        publisherFlag = rs.getString(5),
      )
    }.singleOrNull()

  // ---------------------------------------------------------------------------
  // The slice, in one test
  // ---------------------------------------------------------------------------

  @Test
  fun `Austin CC's in-state price is IPEDS's 8580, not the Scorecard's in-district 2550`() {
    ingest()
    val inState = assertNotNull(figure(222992, "tuition_and_fees", "in_state"))
    assertEquals(8580, inState.amountUsd)
    assertEquals(MoneySource.IPEDS_IC_AY.value, inState.source)
    assertEquals("CHG2AY3", inState.sourceVariable)

    // The 2,550 the Scorecard called in-state is carried too -- as the
    // in-DISTRICT figure it actually is, in its own row.
    val inDistrict = assertNotNull(figure(222992, "tuition_and_fees", "in_district"))
    assertEquals(2550, inDistrict.amountUsd)
    assertEquals(MoneySource.IPEDS_IC_AY.value, inDistrict.source)
    assertEquals("CHG1AY3", inDistrict.sourceVariable)
  }

  @Test
  fun `the Scorecard's own in-state number is nowhere in the in-state cell it used to own`() {
    ingest()
    // The Scorecard writes TUITIONFEE_IN at its published-price year, which
    // IC_AY also carries, so IPEDS takes that key too. 2,550 survives ONLY
    // under in_district.
    val scorecardYear = CanonicalMoneyLoader.PUBLISHED_PRICE_ACADEMIC_YEAR
    val inState = assertNotNull(figure(222992, "tuition_and_fees", "in_state", academicYear = scorecardYear))
    assertEquals(8580, inState.amountUsd)
    assertEquals(MoneySource.IPEDS_IC_AY.value, inState.source)
    assertEquals(
      emptyList(),
      query(
        "SELECT p.amount_usd FROM price_figures p JOIN colleges g ON g.id = p.college_id " +
          "WHERE g.ipeds_unit_id = 222992 AND p.price_concept = 'tuition_and_fees' " +
          "AND p.residency_basis = 'in_state' AND p.source = 'scorecard'",
      ) { it.getInt(1) },
      "no in_state tuition row may still be sourced from the Scorecard for an IC_AY institution",
    )
  }

  @Test
  fun `a community college gains a real in-district tier distinct from in-state`() {
    ingest()
    val tiers =
      query(
        "SELECT p.residency_basis, p.amount_usd FROM price_figures p JOIN colleges g ON g.id = p.college_id " +
          "WHERE g.ipeds_unit_id = 222992 AND p.price_concept = 'tuition_and_fees' " +
          "AND p.academic_year = '2023-24' ORDER BY p.residency_basis",
      ) { rs -> rs.getString(1) to rs.getInt(2) }
    assertEquals(listOf("in_district" to 2550, "in_state" to 8580, "out_of_state" to 10590), tiers)
  }

  @Test
  fun `fees are split from tuition on all three tiers, and never equal the tuition-and-fees row`() {
    ingest()
    for (tier in listOf("in_district" to 540, "in_state" to 6570, "out_of_state" to 540)) {
      val fees = assertNotNull(figure(222992, "fees_only", tier.first))
      assertEquals(tier.second, fees.amountUsd)
      val total = assertNotNull(figure(222992, "tuition_and_fees", tier.first))
      assertTrue(
        fees.amountUsd != total.amountUsd,
        "[${tier.first}] fees_only ${fees.amountUsd} must not equal tuition_and_fees ${total.amountUsd}",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Upstream-wins
  // ---------------------------------------------------------------------------

  @Test
  fun `IPEDS is ahead of the Scorecard, and the list is precedence rather than enum order`() {
    assertEquals(listOf(MoneySource.IPEDS_IC_AY, MoneySource.SCORECARD), CanonicalMoneyLoader.ORDERED_SOURCES)
  }

  @Test
  fun `for a key both sources carry, the IPEDS row is stored and nothing is averaged`() {
    ingest()
    // UCSD's two sources agree on the number and disagree on nothing; the
    // point is WHICH row is stored, and that there is exactly one.
    val year = CanonicalMoneyLoader.PUBLISHED_PRICE_ACADEMIC_YEAR
    val row = assertNotNull(figure(110680, "tuition_and_fees", "in_state", academicYear = year))
    assertEquals(MoneySource.IPEDS_IC_AY.value, row.source)
    assertEquals("CHG2AY2", row.sourceVariable)
    // 14,906 is CHG2AY2; the Scorecard's TUITIONFEE_IN is 15,265. A blend of
    // the two would be 15,085 and belongs to neither publisher.
    assertEquals(14906, row.amountUsd)
  }

  @Test
  fun `a Scorecard institution with no IC_AY row keeps its Scorecard rows`() {
    ingest()
    // 10236801 is in the Scorecard fixture and not in IC_AY -- the IC_PY
    // remainder IC_AY does not cover. Upstream-wins must not delete it.
    val row =
      assertNotNull(
        figure(10236801, "tuition_and_fees", "in_state", academicYear = CanonicalMoneyLoader.PUBLISHED_PRICE_ACADEMIC_YEAR),
      )
    assertEquals(MoneySource.SCORECARD.value, row.source)
    assertEquals("TUITIONFEE_IN", row.sourceVariable)
    assertEquals(9792, row.amountUsd)
  }

  @Test
  fun `every cohort statistic stays the Scorecard's, because IC_AY carries none`() {
    ingest()
    val sources =
      query("SELECT DISTINCT source FROM cohort_money_stats ORDER BY source") { it.getString(1) }
    assertEquals(listOf(MoneySource.SCORECARD.value), sources)
  }

  @Test
  fun `the fill reports the per-source split, and it adds up to the row count`() {
    val result = ingest().canonicalMoney
    val bySource = result.priceFigureSourceCounts
    assertEquals(result.priceFigureRows, bySource.values.sum())
    assertTrue(bySource.getValue(MoneySource.IPEDS_IC_AY) > 0, "$bySource")
    assertTrue(bySource.getValue(MoneySource.SCORECARD) > 0, "$bySource")
  }

  @Test
  fun `with an empty staging table the fill is the Scorecard-only result it was before RFC 161`() {
    // The MoneySource retype had to be behaviour-PRESERVING where IC_AY is
    // absent. With zero `college_ipeds_charges` rows the fill must produce
    // exactly the pre-161 shape: one source, the Scorecard's own published
    // year, and Austin CC back on the in-district-collapsed 2,550.
    val result = ingest(ipeds = null).canonicalMoney
    assertEquals(0, withSession { count(it, "college_ipeds_charges") })
    assertEquals(
      listOf(MoneySource.SCORECARD.value),
      query("SELECT DISTINCT source FROM price_figures") { it.getString(1) },
    )
    assertEquals(
      listOf(CanonicalMoneyLoader.PUBLISHED_PRICE_ACADEMIC_YEAR),
      query("SELECT DISTINCT academic_year FROM price_figures") { it.getString(1) },
    )
    assertEquals(mapOf(MoneySource.SCORECARD to result.priceFigureRows), result.priceFigureSourceCounts)
    val inState =
      assertNotNull(
        figure(222992, "tuition_and_fees", "in_state", academicYear = CanonicalMoneyLoader.PUBLISHED_PRICE_ACADEMIC_YEAR),
      )
    assertEquals(2550, inState.amountUsd)
    assertEquals("TUITIONFEE_IN", inState.sourceVariable)
    assertNull(inState.publisherFlag)
  }

  // ---------------------------------------------------------------------------
  // Years, statuses and the raw flag
  // ---------------------------------------------------------------------------

  @Test
  fun `all four carried years land, each keyed by its own academic year`() {
    ingest()
    assertEquals(
      // Austin CC's published in-state figure really did fall between 2020-21
      // and 2021-22 (10,830 -> 8,580) and has held since. History is stored
      // (D15) even though only the latest year is served today, so a later
      // slice can show the change without a re-ingest.
      listOf("2020-21" to 10830, "2021-22" to 8580, "2022-23" to 8580, "2023-24" to 8580),
      query(
        "SELECT p.academic_year, p.amount_usd FROM price_figures p JOIN colleges g ON g.id = p.college_id " +
          "WHERE g.ipeds_unit_id = 222992 AND p.price_concept = 'tuition_and_fees' " +
          "AND p.residency_basis = 'in_state' ORDER BY p.academic_year",
      ) { rs -> rs.getString(1) to rs.getInt(2) },
    )
  }

  @Test
  fun `an absence stays an absence, carrying the raw X-code that explains it`() {
    ingest()
    // Austin CC is a commuter college: IPEDS says "not applicable" to the
    // on-campus figures, which is not a zero and not a missing measurement.
    val onCampus = assertNotNull(figure(222992, "housing_and_food", "not_applicable", "on_campus"))
    assertNull(onCampus.amountUsd)
    assertEquals("not_applicable", onCampus.status)
    assertEquals("A", onCampus.publisherFlag)
    assertEquals("CHG5AY3", onCampus.sourceVariable)
  }

  @Test
  fun `a reported figure carries its R flag, so the four-way status is never the only record`() {
    ingest()
    val row = assertNotNull(figure(222992, "housing_and_food", "not_applicable", "off_campus"))
    assertEquals(18240, row.amountUsd)
    assertEquals("reported", row.status)
    assertEquals("R", row.publisherFlag)
  }

  @Test
  fun `an imputed code stores its value under imputed_by_publisher, never reported`() {
    ingest(ipedsSources("ipeds-ic2023-ay-flags-fixture.csv"))
    val imputed = assertNotNull(figure(110680, "tuition_and_fees", "in_state"))
    assertEquals(15265, imputed.amountUsd)
    assertEquals("imputed_by_publisher", imputed.status)
    assertEquals("L", imputed.publisherFlag)

    // An implied zero is a REAL zero: the value survives, understated as
    // imputed, and is not collapsed into an absence.
    val impliedZero = assertNotNull(figure(110680, "books_and_supplies", "not_applicable"))
    assertEquals(0, impliedZero.amountUsd)
    assertEquals("imputed_by_publisher", impliedZero.status)
    assertEquals("Z", impliedZero.publisherFlag)
  }

  @Test
  fun `a blank or do-not-know cell stores no value, and does not fall back to the Scorecard`() {
    ingest(ipedsSources("ipeds-ic2023-ay-flags-fixture.csv"))
    val blank = assertNotNull(figure(166027, "tuition_and_fees", "in_state"))
    assertNull(blank.amountUsd)
    assertEquals("not_reported_by_institution", blank.status)
    assertEquals("B", blank.publisherFlag)
    // Upstream-wins is about the KEY, not about the value: IPEDS holding the
    // key with an absence is still IPEDS holding it. A fill that let the
    // Scorecard fill the hole would be averaging two publishers by accident.
    assertEquals(MoneySource.IPEDS_IC_AY.value, blank.source)
  }

  // ---------------------------------------------------------------------------
  // The source enumeration is closed
  // ---------------------------------------------------------------------------

  @Test
  fun `MoneySource and BOTH schema domain CHECKs name the same two sources`() {
    // Both tables carry a source column and both got a domain CHECK in 0084.
    // Asserting only the price_figures twin would let the cohort one drift --
    // exactly the failure the CHECK exists to prevent, in the table nothing
    // else in this suite writes an IPEDS row to.
    for (constraint in listOf("price_figures_source_domain_check", "cohort_money_stats_source_domain_check")) {
      val stored =
        query(
          "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '$constraint'",
        ) { it.getString(1) }.single()
      for (member in MoneySource.entries) {
        assertTrue(stored.contains("'${member.value}'"), "[$constraint] must name ${member.value}: $stored")
      }
      assertEquals(
        2,
        MoneySource.entries.size,
        "a third source needs a migration, not just an enum member: [$constraint] $stored",
      )
    }
  }

  @Test
  fun `stale staging is counted and passed over, never fatal, so a Scorecard-only run still fills`() {
    // The prune lives in the `ipeds-charges` phase, so a run with no --ic-ay
    // never runs it: the fill MUST survive whatever the last IC_AY load left
    // behind. It counts the drift instead -- an error() here would take the
    // whole money rebuild down over rows nothing asked it to serve.
    ingest(ipeds = null)
    val collegeId = query("SELECT id FROM colleges WHERE ipeds_unit_id = 222992") { it.getString(1) }.single()
    withSession { session ->
      session
        .prepareStatement(
          "INSERT INTO college_ipeds_charges (college_id, charge_variable, academic_year, amount_usd, " +
            "imputation_flag) VALUES (?::uuid, 'CHG2AT', '2023-24', 4200, 'R'), " +
            "(?::uuid, 'CHG2AY', '2019-20', 7350, 'R')",
        ).use { stmt ->
          stmt.setString(1, collegeId)
          stmt.setString(2, collegeId)
          stmt.executeUpdate()
        }
    }
    val result = ingest(ipeds = null).canonicalMoney
    // The drift AXIS is typed and the offending VALUE is nested under it: a
    // consumer reads which variable, or which year, without splitting a packed
    // key on `=`.
    assertEquals(
      mapOf(
        CanonicalMoneyLoader.ChargeDrift.UNMAPPED_CHARGE_VARIABLE to mapOf("CHG2AT" to 1),
        CanonicalMoneyLoader.ChargeDrift.UNDECODABLE_ACADEMIC_YEAR to mapOf("2019-20" to 1),
      ),
      result.ipedsChargesIgnored,
    )
    assertEquals(2, result.ipedsChargesIgnoredRows)
    // Passed over, not mapped: no price row was written for either.
    assertEquals(
      emptyList(),
      query("SELECT source_variable FROM price_figures WHERE source_variable LIKE 'CHG2AT%'") { it.getString(1) },
    )
    assertTrue(result.priceFigureRows > 0, "the rest of the fill must still have run")
  }

  @Test
  fun `the count of stale staged rows reaches the operator, in the summary and the build row`() {
    ingest(ipeds = null)
    val collegeId = query("SELECT id FROM colleges WHERE ipeds_unit_id = 222992") { it.getString(1) }.single()
    withSession { session ->
      session
        .prepareStatement(
          "INSERT INTO college_ipeds_charges (college_id, charge_variable, academic_year, amount_usd, " +
            "imputation_flag) VALUES (?::uuid, 'CHG2AT', '2023-24', 4200, 'R')",
        ).use { stmt ->
          stmt.setString(1, collegeId)
          stmt.executeUpdate()
        }
    }
    val report = ingest(ipeds = null)
    assertContains(report.humanSummary(), "[1] staged IC_AY row(s) passed over as stale")
    val summary =
      query(
        "SELECT canonical_money_summary::text FROM college_index_build WHERE id = '${report.buildId}'",
      ) { it.getString(1) }.single()
    // Nested, and keyed by the drift axis' slug: {"charge_variable": {"CHG2AT": 1}}.
    assertContains(summary, "\"charge_variable\": {\"CHG2AT\": 1}")
  }

  @Test
  fun `ORDERED_SOURCES ranks every MoneySource, which no dispatch branch could check`() {
    // The `when` over MoneySource is exhaustive, so a member with no branch is
    // a COMPILE error and needs no runtime `else`. What no branch can catch is
    // the opposite mistake: a member that HAS a branch but was never ranked
    // here would simply never be iterated, its rows would stop being written,
    // and nothing would run to say so.
    assertEquals(
      MoneySource.entries.toSet(),
      CanonicalMoneyLoader.ORDERED_SOURCES.toSet(),
    )
    assertEquals(
      CanonicalMoneyLoader.ORDERED_SOURCES.size,
      CanonicalMoneyLoader.ORDERED_SOURCES.toSet().size,
      "a source ranked twice would silently give itself two chances to win a key",
    )
    // Precedence is this list, not the enum's declaration order.
    assertEquals(MoneySource.IPEDS_IC_AY, CanonicalMoneyLoader.ORDERED_SOURCES.first())
  }

  @Test
  fun `an unknown source string is refused by the DB CHECK and by fromValue alike`() {
    ingest()
    assertNull(MoneySource.fromValue("ipeds_ic_py"))
    assertNull(MoneySource.fromValue(""))
    val error =
      assertFailsWith<Exception> {
        withSession { session ->
          session
            .prepareStatement(
              "UPDATE price_figures SET source = 'ipeds_ic_py' " +
                "WHERE source = '${MoneySource.IPEDS_IC_AY.value}'",
            ).use { it.executeUpdate() }
        }
      }
    assertContains(error.message ?: "", "price_figures_source_domain_check")
  }

  @Test
  fun `every stored source reads back as an enum member, so the counts are typed end to end`() {
    val result = ingest().canonicalMoney
    val stored = query("SELECT DISTINCT source FROM price_figures") { it.getString(1) }
    assertEquals(stored.mapNotNull { MoneySource.fromValue(it) }.size, stored.size)
    assertEquals(
      stored.toSet(),
      result.priceFigureSourceCounts.keys
        .map { it.value }
        .toSet(),
    )
  }
}
