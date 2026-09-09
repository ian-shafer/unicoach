package ed.unicoach.college

import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CanonicalCohortFixture
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.MoneyVocabularyFixture
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.CohortAddresses
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.College
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeMatch
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.CollegeSearchOutcome
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.InstitutionControl
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NewCollege
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The search payload's money, read from `cohort_money_stats` (RFC 176) --
 * proved against the two things a green suite would otherwise not prove.
 *
 * 1. **Equivalence.** A search over a college whose canonical rows hold the
 *    publisher's figures returns exactly those figures, cell for cell, under
 *    unchanged wire keys. Written while the `colleges` columns still existed
 *    and compared against them; migration `0094` dropped them, so the seeded
 *    figures -- all distinct, so a transposed address is a wrong NUMBER -- are
 *    the standard now.
 * 2. **The two-series trap.** `cohort_money_stats` holds TWO `avg_net_price`
 *    series -- the Scorecard's NPT4 family and IPEDS SFA's grant-aided cohort
 *    -- which differ by population and aid scope and NEVER by vintage. This
 *    file seeds BOTH, with SFA's at a NEWER vintage, and pins that the payload
 *    and the price ruler serve the Scorecard cell.
 * 3. **The two-scope trap.** One college can hold two rows at ONE address that
 *    differ only by `residency_scope`, because both fills choose that scope
 *    from the institution's CONTROL. The reader's order is what decides which
 *    one is served, so it is asserted for a public and for a private college
 *    rather than left to the planner.
 *
 * It lives in `:college` because it seeds against the WRITE side's own
 * vocabulary -- [CanonicalMoneyLoader]'s `SFA_GRANT_AIDED_NET_PRICE` and its
 * blended-average vintage, which `:db` cannot see -- while reading through
 * `:db`'s search. The shared addresses themselves are one catalogue now
 * ([CohortAddresses]), so what this file still proves is that the ROWS the
 * fill writes are the rows the search serves.
 */
class SearchCanonicalMoneyTest {
  companion object {
    private lateinit var database: Database

    /**
     * The Scorecard blended-average vintage, FROM ITS OWNER: a snapshot bump
     * moves this with it. A copied literal would leave the two-series tests
     * green while "newest vintage would pick SFA" -- their whole premise --
     * had quietly become false.
     */
    private val SCORECARD_VINTAGE = CanonicalMoneyLoader.BLENDED_AVERAGE_VINTAGE.firstCalendarYear

    /** A LATER aid year, so "newest vintage wins" alone would pick the SFA row. */
    private val SFA_VINTAGE = SCORECARD_VINTAGE + 2

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
      database = Database(DatabaseConfig.from(config).getOrThrow())
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
    }
  }

  @BeforeEach
  fun resetDatabase() =
    runBlocking {
      database.withConnection { session ->
        session
          .prepareStatement(
            "TRUNCATE TABLE colleges, college_search_index, cohort_money_stats, price_figures CASCADE",
          ).use { it.execute() }
        CodebookReferenceFixture.seed(session)
        // The vocabulary `cohort_money_stats` foreign-keys into: a WRITE
        // precondition (P2), and this suite's whole subject is rows in that
        // table.
        MoneyVocabularyFixture.seed(session)
      }
      Unit
    }

  private fun <T> withSession(block: (SqlSession) -> T): T = runBlocking { database.withConnection { block(it) } }

  /**
   * A college carrying the publisher money on the columns. The figures are all
   * distinct, so a cell served from the wrong address shows up as the wrong
   * NUMBER rather than as a coincidence.
   */
  private fun newCollege(
    ipedsUnitId: Int,
    control: Int = InstitutionControl.PUBLIC.code,
  ) = NewCollege(
    ipedsUnitId = ipedsUnitId,
    opeid = null,
    name = "Test U $ipedsUnitId",
    city = "Townsville",
    state = "CA",
    region = 8,
    locale = 13,
    latitude = null,
    longitude = null,
    control = control,
    undergradEnrollmentHeadcount = 5000,
    admissionRateShare = 0.5,
    satAverageEquivalentScore = 1200,
    completionRate150pct4yrShare = 0.7,
    website = null,
  )

  /**
   * The publisher's figures for one college, all distinct, so a cell served
   * from the wrong address shows up as the wrong NUMBER rather than as a
   * coincidence. Before RFC 176 these were `colleges` columns; the drop moved
   * them here, and the addresses they are written at are the point of the file.
   */
  private data class PublisherFigures(
    val netPricePerYearUsd: Int? = 20000,
    val netPricePerYearIncomeQ1Usd: Int? = 9001,
    val netPricePerYearIncomeQ2Usd: Int? = 11002,
    val netPricePerYearIncomeQ3Usd: Int? = 14003,
    val netPricePerYearIncomeQ4Usd: Int? = 17004,
    val netPricePerYearIncomeQ5Usd: Int? = 21005,
    val medianEarnings10yAfterEntryUsd: Int? = 55006,
    val medianDebtAtCompletionUsd: Int? = 23007,
    val pellShare: Double? = 0.4,
  )

  /**
   * The canonical rows for one college, written at [CanonicalMoneyLoader]'s OWN
   * addresses -- the point of this file.
   */
  private fun seedCanonicalTwin(
    session: SqlSession,
    collegeId: CollegeId,
    figures: PublisherFigures,
  ) {
    // A null column seeds NO row, which is how the source layer says "this
    // cell came from no source" -- distinct from the suppression a test below
    // seeds on purpose, which IS a row and bears no value.
    val bands =
      listOf(
        null to figures.netPricePerYearUsd,
        IncomeBand.UNDER_30K to figures.netPricePerYearIncomeQ1Usd,
        IncomeBand.K30_TO_48K to figures.netPricePerYearIncomeQ2Usd,
        IncomeBand.K48_TO_75K to figures.netPricePerYearIncomeQ3Usd,
        IncomeBand.K75_TO_110K to figures.netPricePerYearIncomeQ4Usd,
        IncomeBand.OVER_110K to figures.netPricePerYearIncomeQ5Usd,
      )
    bands.forEach { (band, amount) ->
      amount?.let {
        CanonicalCohortFixture.seedStat(
          session,
          collegeId,
          CohortAddresses.AVG_NET_PRICE,
          it.toDouble(),
          incomeBand = band,
          vintage = SCORECARD_VINTAGE,
        )
      }
    }
    figures.medianEarnings10yAfterEntryUsd?.let {
      CanonicalCohortFixture.seedStat(session, collegeId, CohortAddresses.MEDIAN_EARNINGS_10Y, it.toDouble())
    }
    figures.medianDebtAtCompletionUsd?.let {
      CanonicalCohortFixture.seedStat(
        session,
        collegeId,
        CohortAddresses.MEDIAN_DEBT_AT_COMPLETION,
        it.toDouble(),
      )
    }
    figures.pellShare?.let {
      CanonicalCohortFixture.seedStat(
        session,
        collegeId,
        CohortAddresses.PELL_SHARE,
        it,
        vintage = SCORECARD_VINTAGE,
      )
    }
  }

  private fun searchOne(session: SqlSession): CollegeMatch {
    val outcome = CollegesDao.search(session, CollegeQuery(limit = 25)).getOrThrow()
    val page = (outcome as CollegeSearchOutcome.Page).page
    return page.matches.single()
  }

  @Test
  fun `the payload money read from canonical is the figure written at the loader's address`() {
    val figures = PublisherFigures()
    val match =
      withSession { session ->
        val college = CollegesDao.upsert(session, newCollege(970100)).getOrThrow()
        seedCanonicalTwin(session, college.id, figures)
        CollegesDao.rebuildSearchIndex(session).getOrThrow()
        searchOne(session)
      }

    // Cell for cell against what was WRITTEN, at the loader's own addresses.
    // Before RFC 176 this compared the payload with the `colleges` columns;
    // those are gone, so the fixture's own figures are the standard, and every
    // one of them is distinct so a transposed address is a wrong number.
    assertPayloadServes(figures, match)
  }

  private fun assertPayloadServes(
    figures: PublisherFigures,
    match: CollegeMatch,
  ) {
    // Band by band, and each seeded figure is DISTINCT, so a pair swapped
    // anywhere between the lateral alias and the map key is a wrong number
    // under a named band rather than a green run.
    assertEquals(figures.netPricePerYearIncomeQ1Usd, IncomeBand.UNDER_30K.getNetPrice(match), "q1")
    assertEquals(figures.netPricePerYearIncomeQ2Usd, IncomeBand.K30_TO_48K.getNetPrice(match), "q2")
    assertEquals(figures.netPricePerYearIncomeQ3Usd, IncomeBand.K48_TO_75K.getNetPrice(match), "q3")
    assertEquals(figures.netPricePerYearIncomeQ4Usd, IncomeBand.K75_TO_110K.getNetPrice(match), "q4")
    assertEquals(figures.netPricePerYearIncomeQ5Usd, IncomeBand.OVER_110K.getNetPrice(match), "q5")
    assertEquals(figures.medianEarnings10yAfterEntryUsd, match.medianEarnings10yAfterEntryUsd, "earnings")
    assertEquals(figures.medianDebtAtCompletionUsd, match.medianDebtAtCompletionUsd, "debt")
    assertEquals(figures.pellShare, match.pellShare, "pell share")
    // The ruler's value travels through `college_search_index`, whose source
    // moved in the same commit -- so it is part of the same claim.
    assertEquals(figures.netPricePerYearUsd, match.netPricePerYearUsd, "the net-price ruler")
  }

  @Test
  fun `a band whose canonical row is suppressed reads as no number, not as a zero`() {
    val figures = PublisherFigures(netPricePerYearIncomeQ3Usd = null)
    val match =
      withSession { session ->
        val college = CollegesDao.upsert(session, newCollege(970200)).getOrThrow()
        seedCanonicalTwin(session, college.id, figures)
        // The publisher's own suppression: a row that BEARS no value, which is
        // an absence to report and never a zero.
        CanonicalCohortFixture.seedStat(
          session,
          college.id,
          CohortAddresses.AVG_NET_PRICE,
          null,
          incomeBand = IncomeBand.K48_TO_75K,
          vintage = SCORECARD_VINTAGE,
        )
        CollegesDao.rebuildSearchIndex(session).getOrThrow()
        searchOne(session)
      }

    assertNull(IncomeBand.K48_TO_75K.getNetPrice(match))
    assertEquals(9001, IncomeBand.UNDER_30K.getNetPrice(match), "the other bands are untouched")
  }

  @Test
  fun `with BOTH avg_net_price series present the payload and the ruler serve the Scorecard cell`() {
    val match =
      withSession { session ->
        val college = CollegesDao.upsert(session, newCollege(970300)).getOrThrow()
        seedCanonicalTwin(session, college.id, PublisherFigures())
        // IPEDS SFA's OVERALL net price: the SAME measure about DIFFERENT
        // students, at a NEWER aid year. Keyed on the measure alone -- which
        // the RFC 166 reader was -- this row wins every one of them.
        CanonicalCohortFixture.seedStat(
          session,
          college.id,
          CanonicalMoneyLoader.SFA_GRANT_AIDED_NET_PRICE,
          SFA_NET_PRICE.toDouble(),
          vintage = SFA_VINTAGE,
          source = MoneySource.IPEDS_SFA,
        )
        CollegesDao.rebuildSearchIndex(session).getOrThrow()
        searchOne(session)
      }

    assertEquals(20000, match.netPricePerYearUsd, "the ruler must be the Scorecard cohort's net price")
    assertNotEquals(SFA_NET_PRICE, match.netPricePerYearUsd, "the SFA grant-aided figure reached the ruler")
    // And the bands, which share the Scorecard address, are unmoved by it.
    assertEquals(9001, IncomeBand.UNDER_30K.getNetPrice(match))
    assertEquals(21005, IncomeBand.OVER_110K.getNetPrice(match))
  }

  @Test
  fun `an SFA band row at a NEWER vintage displaces the Scorecard band in the payload`() {
    // The positive half of the two-series pair (RFC 176 D12), and the intended
    // behaviour of the store rather than a bug to suppress.
    //
    // IPEDS SFA writes its per-band net price at EXACTLY the Scorecard's NPT4x
    // address -- same measure, same population, same aid scope -- because they
    // are the same measured fact, and the SFA aid years are newer. So
    // newest-vintage-wins serves the PUBLISHER'S OWN number in place of the
    // Scorecard's copy of it. `CanonicalMoneyLoader` calls this the
    // "upstream-wins bite", and every other money surface has served the
    // upstream figure since RFC 166; this is search joining them.
    //
    // Its twin, one test above, is the address that does NOT collide -- SFA's
    // OVERALL grant-aided net price, a different population and aid scope,
    // which must never reach the ruler. The PAIR is what stops a future reader
    // "fixing" either direction into the other.
    val figures = PublisherFigures()
    val match =
      withSession { session ->
        val college = CollegesDao.upsert(session, newCollege(970500)).getOrThrow()
        seedCanonicalTwin(session, college.id, figures)
        // The SAME address as the Scorecard band, at a newer aid year.
        CanonicalCohortFixture.seedStat(
          session,
          college.id,
          CohortAddresses.AVG_NET_PRICE,
          SFA_BAND_NET_PRICE.toDouble(),
          incomeBand = IncomeBand.UNDER_30K,
          vintage = SFA_VINTAGE,
          source = MoneySource.IPEDS_SFA,
        )
        CollegesDao.rebuildSearchIndex(session).getOrThrow()
        searchOne(session)
      }

    assertEquals(
      SFA_BAND_NET_PRICE,
      IncomeBand.UNDER_30K.getNetPrice(match),
      "the newer IPEDS SFA row must DISPLACE the Scorecard's copy at the shared band address",
    )
    assertNotEquals(
      figures.netPricePerYearIncomeQ1Usd,
      IncomeBand.UNDER_30K.getNetPrice(match),
      "the Scorecard band survived a newer upstream row at the same address",
    )
    // Only the colliding cell moves. The four other bands and the ruler are
    // the Scorecard's, because SFA wrote nothing at their addresses.
    assertEquals(figures.netPricePerYearIncomeQ2Usd, IncomeBand.K30_TO_48K.getNetPrice(match), "q2 is untouched")
    assertEquals(figures.netPricePerYearIncomeQ5Usd, IncomeBand.OVER_110K.getNetPrice(match), "q5 is untouched")
    assertEquals(figures.netPricePerYearUsd, match.netPricePerYearUsd, "the overall ruler is untouched")
  }

  /**
   * ONE address, TWO residency scopes, and the row at the WRONG scope is the
   * NEWER one -- so a reader that ordered by vintage alone would serve it.
   *
   * The store allows this: `cohort_money_stats_natural_key` separates two rows
   * of one cell by `residency_scope`, and both fills choose that scope from a
   * fact about the INSTITUTION (`CanonicalMoneyLoader.blendScope`,
   * `CollegeSfaLoader.SfaFamily`) -- public reads in-state-rate-paying,
   * everyone else reads `all`. So the two can meet on one college, and which
   * one is served must be a rule and not the planner's row order.
   */
  private fun createCollegeServedUnderTwoScopes(
    ipedsUnitId: Int,
    control: Int,
  ): CollegeMatch =
    withSession { session ->
      val college = CollegesDao.upsert(session, newCollege(ipedsUnitId, control = control)).getOrThrow()
      val matched =
        if (control == InstitutionControl.PUBLIC.code) {
          CohortResidencyScope.IN_STATE_RATE_PAYING
        } else {
          CohortResidencyScope.ALL
        }
      CohortResidencyScope.entries.forEach { scope ->
        val overall = if (scope == matched) MATCHED_SCOPE_NET_PRICE else OTHER_SCOPE_NET_PRICE
        val band = if (scope == matched) MATCHED_SCOPE_BAND_NET_PRICE else OTHER_SCOPE_BAND_NET_PRICE
        // The control-matched row is the OLDER of the two, so "newest vintage
        // wins" alone would answer with the other one.
        val vintage = if (scope == matched) SCORECARD_VINTAGE else SFA_VINTAGE
        CanonicalCohortFixture.seedStat(
          session,
          college.id,
          CohortAddresses.AVG_NET_PRICE,
          overall.toDouble(),
          residencyScope = scope,
          vintage = vintage,
        )
        CanonicalCohortFixture.seedStat(
          session,
          college.id,
          CohortAddresses.AVG_NET_PRICE,
          band.toDouble(),
          incomeBand = IncomeBand.UNDER_30K,
          residencyScope = scope,
          vintage = vintage,
        )
      }
      CollegesDao.rebuildSearchIndex(session).getOrThrow()
      searchOne(session)
    }

  @Test
  fun `a PUBLIC college holding both residency scopes at one address is served the in-state-rate-paying row`() {
    val match = createCollegeServedUnderTwoScopes(970600, InstitutionControl.PUBLIC.code)

    assertEquals(
      MATCHED_SCOPE_NET_PRICE,
      match.netPricePerYearUsd,
      "a public school's net price is quoted on its in-state-rate-paying cohort (RFC 157)",
    )
    assertEquals(
      MATCHED_SCOPE_BAND_NET_PRICE,
      IncomeBand.UNDER_30K.getNetPrice(match),
      "the band cell must follow the same scope rule as the ruler",
    )
  }

  @Test
  fun `a PRIVATE college holding both residency scopes at one address is served the all-students row`() {
    val match = createCollegeServedUnderTwoScopes(970700, InstitutionControl.PRIVATE_NONPROFIT.code)

    assertEquals(
      MATCHED_SCOPE_NET_PRICE,
      match.netPricePerYearUsd,
      "a private school charges one price, so `all` is its scope and the in-state row is not its figure",
    )
    assertEquals(
      MATCHED_SCOPE_BAND_NET_PRICE,
      IncomeBand.UNDER_30K.getNetPrice(match),
      "the band cell must follow the same scope rule as the ruler",
    )
  }

  @Test
  fun `a college with no canonical money reports none on every payload key`() {
    val match =
      withSession { session ->
        CollegesDao.upsert(session, newCollege(970400)).getOrThrow()
        // No canonical rows at all, and no columns to fall back on.
        CollegesDao.rebuildSearchIndex(session).getOrThrow()
        searchOne(session)
      }

    assertNull(match.netPricePerYearUsd, "an empty cohort_money_stats leaves the ruler NULL")
    assertNull(IncomeBand.UNDER_30K.getNetPrice(match))
    assertNull(match.medianEarnings10yAfterEntryUsd)
    assertNull(match.medianDebtAtCompletionUsd)
    assertNull(match.pellShare)
    // The ladder half of "not ranked as free" is asserted where the ladder is
    // written: `CollegeSearchIndexRebuildTest`, "a college with no canonical
    // money keeps a NULL ruler and no place on the price ladder".
  }
}

/** IPEDS SFA's grant-aided net price in the trap fixture: a number nothing else here holds. */
private const val SFA_NET_PRICE = 13579

/**
 * IPEDS SFA's own LOW-BAND net price, at the address the Scorecard band shares
 * (RFC 176 D12). A second distinct number, so "the SFA row won" and "the SFA
 * row leaked into the overall ruler" cannot look alike.
 */
private const val SFA_BAND_NET_PRICE = 8123

/** The overall net price at the scope this college's CONTROL selects. Distinct from every other figure here. */
private const val MATCHED_SCOPE_NET_PRICE = 15111

/** The overall net price at the OTHER scope, written at a NEWER vintage so it wins on vintage alone. */
private const val OTHER_SCOPE_NET_PRICE = 16222

/** The low band's net price at the control-selected scope. */
private const val MATCHED_SCOPE_BAND_NET_PRICE = 7111

/** The low band's net price at the other scope, at the newer vintage. */
private const val OTHER_SCOPE_BAND_NET_PRICE = 7222
