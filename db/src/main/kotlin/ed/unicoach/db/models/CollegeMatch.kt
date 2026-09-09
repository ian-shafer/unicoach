package ed.unicoach.db.models

/**
 * A single result row from [ed.unicoach.db.dao.CollegesDao.search] (RFC 150):
 * the filter surface read off `college_search_index` plus the payload read back
 * from the source of truth for the at-most-25 rows actually returned (D60).
 *
 * [programTitles] are the `cip_codes.title`s of census programs matched by the
 * query's `cipPrefix`/`subject` filter. NULL when no program filter was applied
 * — nothing was asked about programs, so nothing is reported; the EMPTY list is
 * the different fact that the filter matched none of this college's programs.
 * A boundary omits the key entirely for the null.
 *
 * [netPriceUsdByBand] is the average annual net price by household
 * income band ($0-30k / 30,001-48k / 48,001-75k / 75,001-110k / 110k+) and
 * [medianDebtAtCompletionUsd] the median cumulative federal debt of completers (RFC 133) --
 * returned context only, never filters.
 *
 * [completionRate150pct4yrShare], [medianEarnings10yAfterEntryUsd], and [pellShare] are returned context for the
 * coach to reason over in prose; only [completionRate150pct4yrShare] is also a filter
 * (`minCompletionRate150pct4yrShare`). Earnings and Pell share are surfaced, never thresholded
 * on, because filtering on them is value-laden.
 *
 * [control], [region] and [locale] are OUR WORDS, not published codes (RFC 150
 * D61): the index stores the slug, so no code enters this type and there is no
 * code-to-word step left at the boundary. A code the codebook did not name is
 * NULL on the index (the rebuild's LEFT-JOIN discipline), so it is null here.
 *
 * [ipedsSurveyYear] and [programsCensusSurveyYear] are the vintages of THIS
 * row, read at result time rather than copied onto the index (D55/D60), and are
 * what the tool aggregates into `source_years`.
 */
data class CollegeMatch(
  val id: CollegeId,
  val ipedsUnitId: Int,
  val name: String,
  val city: String,
  val state: String,
  val control: String,
  val region: String?,
  val locale: String?,
  val undergradEnrollmentHeadcount: Int?,
  val admissionRateShare: Double?,
  val netPricePerYearUsd: Int?,
  /**
   * The price the query's own [PriceRuler] measured this row on (RFC 169): the
   * blended net price under the net ruler, this row's residency-correct
   * published on-campus total under the published one.
   *
   * It is the number the sort ordered by and the number a bound was applied to,
   * read back from the SAME expression, so a result can never print one price
   * and have been ranked on another. NULL means this row carries no figure on
   * the active ruler — dropped by a bound and counted in `excluded_unknown`,
   * sunk (never removed) by a sort.
   */
  val rulerPriceUsd: Int?,
  /**
   * WHICH tuition tiers this school publishes (brief 0006 D19), decided by the
   * ONE derivation the cost surfaces use — [ResidencyTierBasis] — from the three
   * tuition rows read live for the returned page.
   *
   * It rides here so a result can SAY, in the enum's own words, when our source
   * does not separate a district price from the state price. That school is
   * ranked exactly as every other: never dropped, never excluded, never
   * substituted. Its in-state figure is shown as it was published, and the
   * sentence beside it is the whole of what we do not know.
   */
  val residencyTierBasis: ResidencyTierBasis,
  /**
   * The average annual net price, in whole US dollars (USD), by household
   * income band, KEYED BY THE BAND it is about. A band with no served figure is ABSENT from the map, never a
   * zero and never a null entry.
   *
   * A map rather than five parallel fields because band identity then lives in
   * the key rather than in a name's `Q1..Q5` suffix. The five values are read
   * from lateral aliases that deliberately do NOT share a token with any field
   * name, so five hand-paired assignments could ship a real price under the
   * wrong dollar range and only compile.
   */
  val netPriceUsdByBand: Map<IncomeBand, Int>,
  val completionRate150pct4yrShare: Double?,
  val medianEarnings10yAfterEntryUsd: Int?,
  val medianDebtAtCompletionUsd: Int?,
  val pellShare: Double?,
  val website: String?,
  val programTitles: List<String>?,
  val ipedsSurveyYear: Int? = null,
  val programsCensusSurveyYear: Int? = null,
)
