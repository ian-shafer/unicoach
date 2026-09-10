package ed.unicoach.db.dao

import ed.unicoach.db.models.CohortAddresses
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CohortStatAddress
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneySource
import java.sql.Types

/**
 * `cohort_money_stats` rows for tests, written RAW -- the
 * `CanonicalMoneyDaoTest` precedent: [CanonicalMoneyDao]'s write API is
 * wholesale-only and carries change detection a fixture has no business
 * faking, and every reader under test reads the TABLE.
 *
 * A test fixture, not a test helper, because two modules need it: `:db`
 * (`CollegesDaoTest`'s search payload, `CollegeSearchIndexRebuildTest`, and
 * `CollegeFixtures`) and `:college` (`CollegeSearchToolTest`,
 * `SimilarCollegesToolTest`, `SearchCanonicalMoneyTest`) both read money that
 * now comes from this table.
 *
 * [MoneyVocabularyFixture.seed] is its write precondition (`income_bands` and
 * `figure_statuses` are foreign keys).
 */
object CanonicalCohortFixture {
  /**
   * One `cohort_money_stats` row at one [address]. A NULL [value] writes a row that BEARS no
   * value -- the publisher's suppression, which is an absence to report and
   * never a zero. To model the other absence, the cell no source carries, seed
   * no row at all.
   */
  fun seedStat(
    session: SqlSession,
    collegeId: CollegeId,
    address: CohortStatAddress,
    value: Double?,
    incomeBand: IncomeBand? = null,
    residencyScope: CohortResidencyScope = CohortResidencyScope.ALL,
    vintage: Int? = null,
    source: MoneySource = MoneySource.SCORECARD,
    sourceVariable: String = "FIXTURE",
  ) {
    session
      .prepareStatement(
        "INSERT INTO cohort_money_stats (college_id, measure, population, residency_scope, aid_scope, " +
          "income_band, vintage, value, status, source, source_variable) " +
          "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
      ).use { stmt ->
        // A CURSOR, never eleven hand-typed ordinals -- `CollegesDao`'s own
        // idiom for a bind list (`bindPredicate`'s `var idx = from` /
        // `idx++`). Slots 4 and 5 are `residency_scope` and `aid_scope`, and
        // both admit the literal value 'all', so a one-slot renumber slip
        // writes a row at a DIFFERENT address that passes every CHECK and
        // every foreign key -- and the suite reads green about a cell the
        // reader never finds.
        var idx = 1
        stmt.setObject(idx++, collegeId.value)
        stmt.setString(idx++, address.measure.value)
        stmt.setString(idx++, address.population.value)
        stmt.setString(idx++, residencyScope.value)
        stmt.setString(idx++, address.aidScope.value)
        if (incomeBand == null) stmt.setNull(idx++, Types.VARCHAR) else stmt.setString(idx++, incomeBand.value)
        if (vintage == null) stmt.setNull(idx++, Types.SMALLINT) else stmt.setInt(idx++, vintage)
        if (value == null) stmt.setNull(idx++, Types.NUMERIC) else stmt.setDouble(idx++, value)
        stmt.setString(
          idx++,
          if (value == null) FigureStatus.SUPPRESSED_BY_PUBLISHER.value else FigureStatus.REPORTED.value,
        )
        stmt.setString(idx++, source.value)
        stmt.setString(idx++, sourceVariable)
        stmt.executeUpdate()
      }
  }

  /**
   * The money a test wants a college to HAVE, written to `cohort_money_stats`
   * at the addresses the canonical fill writes them to.
   *
   * Its defaults are the ones `newCollegeFixture` used to carry as `colleges`
   * columns, so a suite that seeded a college and expected it to search with a
   * net price keeps meaning what it meant after RFC 176 moved those readers.
   *
   * The addresses are [CohortAddresses], the one catalogue both the fill that
   * writes these cells and the DAO that reads them name, so this fixture
   * cannot be a second vocabulary.
   *
   * A null value writes NO row, which is how the store says "no figure" — not
   * a suppression, and never a zero.
   */
  data class CollegeMoney(
    val netPricePerYearUsd: Int? = 20000,
    /**
     * The band net prices, KEYED BY THE BAND, exactly as [CollegeMatch] now
     * carries them. A band absent from the map has no row seeded.
     *
     * Not five `...IncomeQ1Usd..Q5Usd` fields: that is the `q1..q5` positional
     * convention RFC 176 just deleted from production, and a fixture keeps it
     * alive by hand-pairing five names to five [IncomeBand] members -- five
     * places a transposition compiles and seeds a real price under the wrong
     * bracket, which is the defect the whole band-keyed shape exists to
     * prevent.
     */
    val netPriceUsdByBand: Map<IncomeBand, Int> = emptyMap(),
    val medianEarnings10yAfterEntryUsd: Int? = 55000,
    val medianDebtAtCompletionUsd: Int? = null,
    val pellShare: Double? = 0.4,
  )

  /**
   * Deletes every `cohort_money_stats` row this college holds.
   *
   * NAMED, and called by the caller rather than hidden inside a "seed" verb:
   * a suite that stages a second series -- two residency scopes at one
   * address, an SFA row beside a Scorecard one -- and then seeds the defaults
   * would lose the staged rows SILENTLY and stay green. Destruction is stated
   * where it happens.
   */
  fun deleteMoney(
    session: SqlSession,
    collegeId: CollegeId,
  ) {
    session.prepareStatement("DELETE FROM cohort_money_stats WHERE college_id = ?").use { stmt ->
      stmt.setObject(1, collegeId.value)
      stmt.executeUpdate()
    }
  }

  /**
   * Seeds [money] for [collegeId], REPLACING whatever money this college had:
   * wholesale, as the fill itself is (P12), so a suite that re-seeds one
   * college is re-stating its money rather than filing a second copy the
   * natural key would refuse anyway. The replacement is
   * [deleteMoney], named so the destruction is readable from here.
   */
  fun seedMoney(
    session: SqlSession,
    collegeId: CollegeId,
    money: CollegeMoney = CollegeMoney(),
  ) {
    // The vocabulary tables `cohort_money_stats` foreign-keys into are a WRITE
    // PRECONDITION (P2), and this fixture is called from suites that never had
    // a reason to seed them before. [MoneyVocabularyFixture.seed] is
    // idempotent, so asking for it here costs a no-op and removes the failure
    // mode where a suite gains money rows and loses them to a missing FK.
    MoneyVocabularyFixture.seed(session)
    deleteMoney(session, collegeId)

    fun netPrice(
      band: IncomeBand?,
      amount: Int?,
    ) = amount?.let {
      seedStat(
        session,
        collegeId,
        CohortAddresses.AVG_NET_PRICE,
        it.toDouble(),
        incomeBand = band,
        vintage = SCORECARD_BLEND_VINTAGE,
      )
    }

    /**
     * The three OVERALL cells, named like the band write above rather than
     * left as three inline `?.let` blocks: each is one address, one value and
     * one vintage, and the differences between them (the Pell share is a share
     * and carries the blend vintage; the two undated measures carry no year)
     * are then the only thing this step says.
     */
    fun overall(
      address: CohortStatAddress,
      value: Double?,
      vintage: Int? = null,
    ) = value?.let {
      seedStat(session, collegeId, address, it, vintage = vintage)
    }

    netPrice(null, money.netPricePerYearUsd)
    // ITERATED over the map's own keys: the band that KEYS the value is the
    // band the row is filed under, so there is no pairing left to transpose.
    money.netPriceUsdByBand.forEach { (band, amount) -> netPrice(band, amount) }
    overall(CohortAddresses.MEDIAN_EARNINGS_10Y, money.medianEarnings10yAfterEntryUsd?.toDouble())
    overall(CohortAddresses.MEDIAN_DEBT_AT_COMPLETION, money.medianDebtAtCompletionUsd?.toDouble())
    overall(CohortAddresses.PELL_SHARE, money.pellShare, vintage = SCORECARD_BLEND_VINTAGE)
  }

  /**
   * A vintage for the Scorecard blended averages, as a first calendar year.
   *
   * A FIXTURE year and nothing more: `:db` cannot see
   * `CanonicalMoneyLoader.BLENDED_AVERAGE_VINTAGE` (which RFC 183 corrected to
   * 2023), and nothing here depends on the number -- only on the two
   * net-price series being separable without it. It is deliberately not
   * chased when the loader's constant moves, because a fixture that tracked it
   * would be claiming to test a year it cannot read.
   */
  private const val SCORECARD_BLEND_VINTAGE = 2021
}
