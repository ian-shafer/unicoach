package ed.unicoach.college

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The IPEDS SFA half of the `canonical-money` fill (RFC 162), over the
 * verbatim seven-row `sfa2223.csv` subset and a Scorecard fixture carrying
 * the same seven real institutions.
 *
 * Every number asserted here is the publisher's own. The one Scorecard-side
 * number that matters -- Portland State's `NPT41_PUB` = 16,348 for AY2021-22
 * -- is the measured value of the snapshot the repo pins, and SFA's own
 * restatement of that same cell is 10,311.
 *
 * Three of the seven institutions are here for their FLAGS, not their money:
 * Kairos University (219240) publishes `Z` on the four aid-mix shares and
 * Luther Rice (135364) on two arrangement headcounts -- the implied zero this
 * RFC exists to read correctly, on both fact tables -- and Northeast
 * Technology Center (461087) is the `P` case, the carry-forward imputation.
 * None carries a Scorecard money column, so they add flag coverage without
 * re-basing a single number above.
 */
class CanonicalMoneySfaTest : CollegeScorecardTestBase() {
  private val scorecardLoader = CollegeScorecardLoader(database)
  private val sfaLoader = CollegeSfaLoader(database)
  private val loader = CanonicalMoneyLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-sfa-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-fixture.csv")
  private val sfaCsv = fixture("ipeds-sfa2223-fixture.csv")

  private fun sfaSources() = SfaSources(SourceFile(sfaCsv, sfaCsv.path), SFA_AID_YEAR_START)

  private fun fill(withSfa: Boolean = true): CanonicalMoneyLoader.FillResult =
    runBlocking {
      scorecardLoader.load(institutionCsv, fieldsCsv)
      val sources = sfaSources()
      if (withSfa) {
        sfaLoader.assertHeaders(sources)
        sfaLoader.load(sources)
      }
      loader.fill(institutionCsv, sfa = sources.takeIf { withSfa })
    }

  // ---------------------------------------------------------------------------
  // Upstream-wins (D5)
  // ---------------------------------------------------------------------------

  @Test
  fun `the two publishers' band series coexist, one row per real year, never averaged`() {
    fill()
    // BEHAVIOUR MOVED (RFC 183 D2), and this test is re-pointed rather than
    // left passing at a key nothing contests any more.
    //
    // It used to name a COLLISION: the Scorecard's band figure was mis-stamped
    // AY2021-22, landing exactly on SFA's suffix-1 aid year, and upstream-wins
    // suppressed it. That collision was an artefact of the wrong year and it is
    // gone -- SFA writes 2020/2021/2022, the Scorecard's cohort is 2023-24, and
    // the two can no longer share a key at all. So the fact worth asserting is
    // the one the correction produces: BOTH numbers are stored, each under the
    // year its own publisher states, and neither is blended into the other.
    // Measured over the pinned artifacts this is 19,780 band rows that were
    // suppressed and are now served.
    //
    // Portland State, band 1: SFA2223 restates AY2021-22 as 10,311; the
    // Scorecard's own cell is 16,348 and describes AY2023-24.
    val rows =
      query(
        "SELECT cms.vintage, cms.value, cms.source, cms.source_variable, cms.publisher_flag " +
          "FROM cohort_money_stats cms JOIN colleges c ON c.id = cms.college_id " +
          "WHERE c.ipeds_unit_id = 209807 AND cms.measure = 'avg_net_price' AND cms.income_band = 'under_30k' " +
          "ORDER BY cms.vintage",
      ) { rs ->
        listOf(
          rs.getInt(1).toString(),
          rs.getBigDecimal(2).toInt().toString(),
          rs.getString(3),
          rs.getString(4),
          rs.getString(5),
        )
      }
    assertEquals(
      listOf(
        listOf("2020", "11840", "ipeds_sfa", "npis410", "R"),
        listOf("2021", "10311", "ipeds_sfa", "npis411", "R"),
        listOf("2022", "11558", "ipeds_sfa", "npis412", "R"),
        listOf("2023", "16348", "scorecard", "NPT41_PUB", null),
      ),
      rows,
    )
  }

  @Test
  fun `the Scorecard keeps the facts SFA does not publish`() {
    fill()
    // GRAD_DEBT_MDN (NSLDS) and MD_EARN_WNE_P10 (Treasury) have no SFA twin,
    // so the demotion of the Scorecard is scoped, not wholesale.
    val sources =
      query(
        "SELECT DISTINCT measure, source FROM cohort_money_stats " +
          "WHERE measure IN ('median_debt_at_completion', 'median_earnings_10y') ORDER BY measure",
      ) { rs -> rs.getString(1) to rs.getString(2) }
    assertEquals(
      listOf("median_debt_at_completion" to "scorecard", "median_earnings_10y" to "scorecard"),
      sources,
    )
  }

  @Test
  fun `NPT4_PUB and NPIST are two populations, so they coexist as two rows`() {
    fill()
    // The Scorecard's NPT4_PUB is the band-count-weighted mean over the TITLE
    // IV-aided population; NPIST is the GRANT-AIDED one. Overwriting either
    // with the other would be the mistake this pair of rows exists to refuse.
    //
    // RE-DATED (RFC 183): the two rows used to sit at one vintage, 2021, and
    // the aid scope alone kept them apart. The Scorecard's cell is AcadYr
    // 2023-24, so the query is no longer pinned to one year -- what it still
    // proves is that the two populations are TWO rows, which the aid scope
    // decides and the vintage never did.
    val rows =
      query(
        "SELECT cms.aid_scope, cms.vintage, cms.source, cms.source_variable, cms.value FROM cohort_money_stats cms " +
          "JOIN colleges c ON c.id = cms.college_id " +
          "WHERE c.ipeds_unit_id = 209807 AND cms.measure = 'avg_net_price' AND cms.income_band IS NULL " +
          "AND cms.vintage IN (2021, ${CanonicalMoneyLoader.BLENDED_AVERAGE_VINTAGE.firstCalendarYear}) " +
          "ORDER BY cms.aid_scope",
      ) { rs ->
        listOf(
          rs.getString(1),
          rs.getInt(2).toString(),
          rs.getString(3),
          rs.getString(4),
          rs.getBigDecimal(5).toInt().toString(),
        )
      }
    assertEquals(
      listOf(
        listOf("federal_aid_receiving", "2023", "scorecard", "NPT4_PUB", "17000"),
        listOf("grant_aided", "2021", "ipeds_sfa", "npist1", "12510"),
      ),
      rows,
    )
  }

  // ---------------------------------------------------------------------------
  // What SFA adds
  // ---------------------------------------------------------------------------

  @Test
  fun `a Pell average exists where the Scorecard has only a share`() {
    fill()
    // The University of Alabama: $5,202 per Pell recipient, flag R. The
    // Scorecard publishes PCTPELL and no dollars at all.
    val rows =
      query(
        "SELECT cms.measure, cms.aid_scope, cms.value, cms.source_variable FROM cohort_money_stats cms " +
          "JOIN colleges c ON c.id = cms.college_id " +
          "WHERE c.ipeds_unit_id = 100751 AND cms.measure = 'pell_average_award'",
      ) { rs -> listOf(rs.getString(1), rs.getString(2), rs.getBigDecimal(3).toInt().toString(), rs.getString(4)) }
    assertEquals(listOf(listOf("pell_average_award", "pell_receiving", "5202", "upgrnta")), rows)
  }

  @Test
  fun `the Pell recipient COUNT is a headcount, not a money measure with an invented unit`() {
    fill()
    // The same institution's UPGRNTN = 5,839 people. A number of people is not
    // money: it lands in cohort_population_counts under the population it
    // counts, and cohort_money_stats never learns a "count" unit for it.
    val counted =
      query(
        "SELECT cpc.headcount, cpc.status, cpc.source, cpc.source_variable FROM cohort_population_counts cpc " +
          "JOIN colleges c ON c.id = cpc.college_id " +
          "WHERE c.ipeds_unit_id = 100751 AND cpc.population = 'pell_receiving_undergraduates'",
      ) { rs -> listOf(rs.getInt(1).toString(), rs.getString(2), rs.getString(3), rs.getString(4)) }
    assertEquals(listOf(listOf("5839", "reported", "ipeds_sfa", "upgrntn")), counted)
    assertEquals(
      emptyList(),
      query("SELECT measure FROM cohort_money_stats WHERE measure LIKE '%count%'") { rs -> rs.getString(1) },
      "no cohort_money_stats measure counts people",
    )
  }

  @Test
  fun `a published integer percent lands as the 0-1 share every share measure carries`() {
    fill()
    // UPGRNTP is published as 40 (percent); PELL_SHARE is a 0-1 share, so the
    // stored value is 0.40 -- a unit conversion, not a derivation.
    val share =
      query(
        "SELECT cms.value FROM cohort_money_stats cms JOIN colleges c ON c.id = cms.college_id " +
          "WHERE c.ipeds_unit_id = 209807 AND cms.measure = 'pell_share' AND cms.source = 'ipeds_sfa'",
      ) { rs -> rs.getBigDecimal(1).toDouble() }
    assertEquals(listOf(0.40), share)
  }

  @Test
  fun `the fill reads no variable the staging phase did not stage`() {
    // The two halves build their variable names from ONE vocabulary
    // (SfaVariables and SfaFamily). A stem that drifts apart makes a whole
    // measure write no row for any college in the country, and this counter is
    // what turns that into a number instead of a shorter table.
    assertEquals(emptyMap(), fill().sfaCellsNotStaged)
  }

  @Test
  fun `no share row can hold the source's published percent, whichever measure it is`() {
    fill()
    // The scale is derived from MeasureUnit.SHARE, so a `_P` variable added
    // without a scale argument is no longer possible -- and the schema refuses
    // one anyway (cohort_money_stats_share_range_check). Asserted over EVERY
    // share row rather than one measure: the point is that no call site can be
    // the exception.
    val outOfRange =
      query(
        "SELECT cms.measure, cms.value FROM cohort_money_stats cms " +
          "WHERE cms.measure LIKE '%share' AND cms.value IS NOT NULL AND (cms.value < 0 OR cms.value > 1)",
      ) { rs -> rs.getString(1) to rs.getBigDecimal(2).toDouble() }
    assertEquals(emptyList(), outOfRange)
    assertTrue(
      query("SELECT count(*) FROM cohort_money_stats WHERE measure LIKE '%share' AND value IS NOT NULL") {
        it.getInt(1)
      }.single() > 0,
      "and there are share rows to have checked",
    )
  }

  @Test
  fun `the aid mix lands as a share of the cohort and an average over recipients`() {
    fill()
    // College of DuPage's four sources, published shares and averages, each
    // with the aid scope that says WHOSE number it is. The scope is asserted
    // beside the measure and the value on purpose: a per-recipient average
    // filed under `all` reads as a cohort average -- a different, smaller,
    // never-published number -- and measure+value alone cannot see that.
    val rows =
      query(
        "SELECT cms.measure, cms.aid_scope, cms.value FROM cohort_money_stats cms " +
          "JOIN colleges c ON c.id = cms.college_id " +
          "WHERE c.ipeds_unit_id = 144865 AND cms.source = 'ipeds_sfa' " +
          "AND (cms.measure LIKE '%grant%' OR cms.measure LIKE 'student_loan%') ORDER BY cms.measure",
      ) { rs -> Triple(rs.getString(1), rs.getString(2), rs.getBigDecimal(3).toDouble()) }
    assertEquals(
      listOf(
        Triple("federal_grant_average_award", "federal_grant_receiving", 6574.0),
        Triple("federal_grant_share", "all", 0.42),
        Triple("institutional_grant_average_award", "institutional_grant_receiving", 2999.0),
        Triple("institutional_grant_share", "all", 0.17),
        Triple("state_local_grant_average_award", "state_local_grant_receiving", 2438.0),
        Triple("state_local_grant_share", "all", 0.34),
        // LOAN_A/LOAN_P are ANY loan -- federal, institutional or private --
        // so the average is scoped `loan_receiving`, never the Scorecard's
        // federal-only `federal_loan_borrowing`.
        Triple("student_loan_average_amount", "loan_receiving", 5220.0),
        Triple("student_loan_share", "all", 0.19),
      ),
      rows,
    )
  }

  @Test
  fun `one pin yields three vintages of net price`() {
    fill()
    val rows =
      query(
        "SELECT cms.vintage, cms.value, cms.source_variable FROM cohort_money_stats cms " +
          "JOIN colleges c ON c.id = cms.college_id " +
          "WHERE c.ipeds_unit_id = 209807 AND cms.measure = 'avg_net_price' AND cms.aid_scope = 'grant_aided' " +
          "ORDER BY cms.vintage",
      ) { rs -> Triple(rs.getInt(1), rs.getBigDecimal(2).toInt(), rs.getString(3)) }
    assertEquals(
      listOf(
        Triple(2020, 12788, "npist0"),
        Triple(2021, 12510, "npist1"),
        Triple(2022, 12509, "npist2"),
      ),
      rows,
    )
  }

  @Test
  fun `public and private each land their own family and never the other's`() {
    fill()
    // The private's rows are NPGRN/NPT4x with residency scope `all`; the
    // public's are NPIST/NPIS4x scoped to the in-state rate payers. Neither
    // carries a row from the other family.
    val private =
      query(
        "SELECT DISTINCT cms.source_variable, cms.residency_scope FROM cohort_money_stats cms " +
          "JOIN colleges c ON c.id = cms.college_id " +
          "WHERE c.ipeds_unit_id = 164988 AND cms.measure = 'avg_net_price' AND cms.source = 'ipeds_sfa' " +
          "ORDER BY cms.source_variable",
      ) { rs -> rs.getString(1) to rs.getString(2) }
    assertTrue(private.contains("npgrn2" to "all"), "the private publishes NPGRN: $private")
    assertTrue(private.contains("npt412" to "all"), "and its bands are named NPT4x inside the SFA file")
    assertTrue(private.none { it.first.startsWith("npist") || it.first.startsWith("npis4") }, "and no public-family row: $private")
    val public =
      query(
        "SELECT DISTINCT cms.source_variable, cms.residency_scope FROM cohort_money_stats cms " +
          "JOIN colleges c ON c.id = cms.college_id " +
          "WHERE c.ipeds_unit_id = 144865 AND cms.measure = 'avg_net_price' AND cms.source = 'ipeds_sfa' " +
          "ORDER BY cms.source_variable",
      ) { rs -> rs.getString(1) to rs.getString(2) }
    assertTrue(public.contains("npist2" to "in_state_rate_paying"), "the public publishes NPIST: $public")
    assertTrue(public.none { it.first.startsWith("npgrn") || it.first.startsWith("npt4") }, "and no private-family row: $public")
  }

  @Test
  fun `an institution publishing NEITHER family is a NAMED, counted skip`() {
    val result = fill()
    // Kairos University (219240) flags both NPIST and NPGRN not applicable, so
    // it names neither family: its net price and its arrangement headcounts
    // contribute no row at all. That is a loss the operator can see, not a
    // silently shorter table -- and the family-free measures (Pell, the aid
    // mix) still land, because they are the same variables everywhere.
    assertEquals(1, result.sfaInstitutionsWithoutFamily)
    assertEquals(
      emptyList(),
      query(
        "SELECT cms.source_variable FROM cohort_money_stats cms JOIN colleges c ON c.id = cms.college_id " +
          "WHERE c.ipeds_unit_id = 219240 AND cms.measure = 'avg_net_price' AND cms.source = 'ipeds_sfa'",
      ) { rs -> rs.getString(1) },
    )
    assertTrue(
      query(
        "SELECT count(*) FROM cohort_money_stats cms JOIN colleges c ON c.id = cms.college_id " +
          "WHERE c.ipeds_unit_id = 219240 AND cms.source = 'ipeds_sfa' AND cms.measure = 'pell_share'",
      ) { rs -> rs.getInt(1) }.single() > 0,
      "the ungated measures still land",
    )
  }

  // ---------------------------------------------------------------------------
  // The population counts
  // ---------------------------------------------------------------------------

  @Test
  fun `the residency split sums to the fall cohort it splits`() {
    fill()
    // College of DuPage: 1,488 in-district + 553 in-state + 58 out-of-state +
    // 0 unknown = the 2,099 SCFA1N total. The in-district price applies to
    // 71% of that entering class, and that is now a query, not a claim.
    val rows =
      query(
        "SELECT cpc.residency_basis, cpc.headcount, cpc.source_variable FROM cohort_population_counts cpc " +
          "JOIN colleges c ON c.id = cpc.college_id " +
          "WHERE c.ipeds_unit_id = 144865 AND cpc.population = 'first_time_full_time_aid_cohort' " +
          "ORDER BY cpc.source_variable",
      ) { rs -> Triple(rs.getString(1), rs.getInt(2), rs.getString(3)) }
    // ORDER BY source_variable is the TEXT order the database actually
    // applies, so `scfa1n` sorts AFTER `scfa11n`..`scfa14n` ('1' < 'n'). The
    // expectation states that order rather than the reading order a human
    // would guess.
    assertEquals(
      listOf(
        Triple("in_district", 1488, "scfa11n"),
        Triple("in_state", 553, "scfa12n"),
        Triple("out_of_state", 58, "scfa13n"),
        Triple("unknown", 0, "scfa14n"),
        Triple("not_applicable", 2099, "scfa1n"),
      ),
      rows,
    )
    assertEquals(
      2099,
      rows.filterNot { it.third == "scfa1n" }.sumOf { it.second },
      "the four parts sum to the total",
    )
  }

  @Test
  fun `a not-applicable arrangement lands as its own status beside a reported sibling`() {
    fill()
    // DuPage has no residence halls: xgis4on2 = A. The row EXISTS and says
    // not_applicable, carrying no headcount, beside the reported 501 living
    // with family. Deleting the row would lose the answer; a zero would
    // invent one.
    val rows =
      query(
        "SELECT cpc.arrangement, cpc.headcount, cpc.status, cpc.publisher_flag FROM cohort_population_counts cpc " +
          "JOIN colleges c ON c.id = cpc.college_id " +
          "WHERE c.ipeds_unit_id = 144865 AND cpc.population = 'title_iv_aided_undergraduates' " +
          "AND cpc.vintage = 2022 AND cpc.arrangement IN ('on_campus', 'with_family') " +
          "ORDER BY cpc.arrangement",
      ) { rs -> listOf(rs.getString(1), rs.getObject(2)?.toString(), rs.getString(3), rs.getString(4)) }
    assertEquals(
      listOf(
        listOf("on_campus", null, "not_applicable", "A"),
        listOf("with_family", "501", "reported", "R"),
      ),
      rows,
    )
  }

  @Test
  fun `the private arrangement family lands under its own residency basis`() {
    fill()
    val rows =
      query(
        "SELECT cpc.residency_basis, cpc.arrangement, cpc.headcount, cpc.source_variable FROM cohort_population_counts cpc " +
          "JOIN colleges c ON c.id = cpc.college_id " +
          "WHERE c.ipeds_unit_id = 164988 AND cpc.population = 'title_iv_aided_undergraduates' " +
          "AND cpc.vintage = 2022 ORDER BY cpc.arrangement",
      ) { rs -> listOf(rs.getString(1), rs.getString(2), rs.getInt(3).toString(), rs.getString(4)) }
    assertEquals(
      listOf(
        listOf("not_applicable", "not_applicable", "1410", "grn4n2"),
        listOf("not_applicable", "off_campus", "0", "grn4of2"),
        listOf("not_applicable", "on_campus", "1404", "grn4on2"),
        listOf("not_applicable", "unknown", "0", "grn4un2"),
        listOf("not_applicable", "with_family", "6", "grn4wf2"),
      ),
      rows,
    )
  }

  // ---------------------------------------------------------------------------
  // The X flags, end to end (RFC 162's whole reason)
  // ---------------------------------------------------------------------------

  @Test
  fun `an implied zero lands as a REPORTED zero, never as an imputation or an absence`() {
    fill()
    // Kairos University publishes Z on all four aid-mix shares: it awarded
    // none of that aid, and NCES writes the cell as 0. Reading Z as an
    // imputation would label an honest zero a publisher guess; reading it as
    // an absence would lose the fact entirely. Both halves are asserted --
    // the status AND the value -- because either one alone passes for the
    // wrong reason.
    val rows =
      query(
        "SELECT cms.measure, cms.value, cms.status, cms.publisher_flag FROM cohort_money_stats cms " +
          "JOIN colleges c ON c.id = cms.college_id " +
          "WHERE c.ipeds_unit_id = 219240 AND cms.source = 'ipeds_sfa' AND cms.publisher_flag = 'Z' " +
          "ORDER BY cms.measure, cms.vintage",
      ) { rs -> listOf(rs.getString(1), rs.getBigDecimal(2).toDouble().toString(), rs.getString(3), rs.getString(4)) }
    assertEquals(
      listOf(
        listOf("federal_grant_share", "0.0", "reported", "Z"),
        listOf("institutional_grant_share", "0.0", "reported", "Z"),
        listOf("state_local_grant_share", "0.0", "reported", "Z"),
        listOf("student_loan_share", "0.0", "reported", "Z"),
      ),
      rows,
    )
    // And the same letter on a HEADCOUNT, at Luther Rice: zero students,
    // reported. (Kairos names NEITHER variable family -- its NPIST and NPGRN
    // are both `A` -- so its own arrangement cells contribute no row at all,
    // which is the family gate, not the flag.)
    val counts =
      query(
        "SELECT cpc.source_variable, cpc.headcount, cpc.status FROM cohort_population_counts cpc " +
          "JOIN colleges c ON c.id = cpc.college_id " +
          "WHERE c.ipeds_unit_id = 135364 AND cpc.publisher_flag = 'Z' ORDER BY cpc.source_variable",
      ) { rs -> Triple(rs.getString(1), rs.getInt(2), rs.getString(3)) }
    assertEquals(
      listOf(Triple("grn4n0", 0, "reported"), Triple("grn4n1", 0, "reported")),
      counts,
    )
  }

  @Test
  fun `a publisher-imputed cell keeps its value and says the publisher imputed it`() {
    fill()
    // Northeast Technology Center's average Pell award is carried forward
    // from the prior year (P). The number is real and is kept; what changes
    // is the status, so a reader can tell a measured award from an imputed
    // one. The institution's OTHER cells stay `reported` -- the flag is per
    // cell, never per institution.
    val imputed =
      query(
        "SELECT cms.measure, cms.value, cms.status, cms.publisher_flag FROM cohort_money_stats cms " +
          "JOIN colleges c ON c.id = cms.college_id " +
          "WHERE c.ipeds_unit_id = 461087 AND cms.source = 'ipeds_sfa' AND cms.publisher_flag = 'P'",
      ) { rs -> listOf(rs.getString(1), rs.getBigDecimal(2).toInt().toString(), rs.getString(3), rs.getString(4)) }
    assertEquals(listOf(listOf("pell_average_award", "5539", "imputed_by_publisher", "P")), imputed)
    val pellShare =
      query(
        "SELECT cms.status, cms.publisher_flag FROM cohort_money_stats cms " +
          "JOIN colleges c ON c.id = cms.college_id " +
          "WHERE c.ipeds_unit_id = 461087 AND cms.measure = 'pell_share' AND cms.source = 'ipeds_sfa'",
      ) { rs -> rs.getString(1) to rs.getString(2) }
    assertEquals(listOf("reported" to "R"), pellShare)
  }

  // ---------------------------------------------------------------------------
  // The fill's own bookkeeping
  // ---------------------------------------------------------------------------

  @Test
  fun `a fill with no staged cells writes no SFA row and says so`() {
    val result = fill(withSfa = false)
    assertEquals(0, result.sfaCellsRead)
    assertEquals(0, result.sfaCollegesMatched)
    assertEquals(0, result.cohortPopulationCountRows, "the counts table is SFA's alone today")
    assertEquals(
      0,
      query("SELECT count(*) FROM cohort_money_stats WHERE source = 'ipeds_sfa'") { it.getInt(1) }.single(),
    )
  }

  @Test
  fun `the fill reports what the SFA source contributed, and a rebuild repeats it`() {
    val first = fill()
    assertEquals(7 * SfaVariables.ALL.size, first.sfaCellsRead)
    assertEquals(7, first.sfaCollegesMatched)
    assertTrue(first.cohortPopulationCountRows > 0)
    assertEquals(
      first.cohortPopulationCountRows,
      query("SELECT count(*) FROM cohort_population_counts") { it.getInt(1) }.single(),
    )
    val second = fill()
    assertEquals(first.cohortMoneyStatRows, second.cohortMoneyStatRows, "the rebuild is idempotent (P12)")
    assertEquals(first.cohortPopulationCountRows, second.cohortPopulationCountRows)
  }

  @Test
  fun `every SFA row carries its raw publisher flag`() {
    fill()
    assertEquals(
      0,
      query("SELECT count(*) FROM cohort_money_stats WHERE source = 'ipeds_sfa' AND publisher_flag IS NULL") {
        it.getInt(1)
      }.single(),
      "the raw X code rides on every row, whatever status it mapped to",
    )
  }

  private companion object {
    const val SFA_AID_YEAR_START = 2022
  }
}
