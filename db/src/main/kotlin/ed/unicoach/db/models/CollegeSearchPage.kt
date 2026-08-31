package ed.unicoach.db.models

/**
 * One page of structured college search results (RFC 150): the returned
 * [matches] (capped by the query's clamped `limit`), [totalMatches] — the
 * unclamped count of every college satisfying the same filters, so a consumer
 * can honestly say "312 match; showing 25" — plus the two honesty figures the
 * index made cheap.
 *
 * [excludedUnknown] is per-filter: for each supplied filter over a nullable
 * index column, how many colleges of the DEFAULT UNIVERSE could not be judged
 * on that axis because the column is NULL there (RFC 150 D55, brief 0004 D11).
 * Unknown is never silently "no": a filter excludes those rows AND says how
 * many it excluded. The counts are computed against the universe, not against
 * the other filters, so the number answers "how many schools could not be
 * judged on this axis" rather than an order-dependent residue. It is keyed by
 * the INDEX COLUMN the filter reads — one entry per axis, because
 * `minAdmissionRateShare` and `maxAdmissionRateShare` exclude the same rows —
 * and is empty when no supplied filter can exclude an unknown.
 *
 * [sourceYears] is read at RESULT time from the rows actually returned, never
 * from a per-row copy of a per-build constant (D55/D60): `ipeds` is the range of
 * `college_ipeds.survey_year` over the returned rows and `programs_census` the
 * range of `max(college_programs_census.survey_year)`.
 *
 * A RANGE, not one year, because a page can be MIXED. The vintages used to be
 * reduced with `singleOrNull()`, so a page whose rows carried 2022 and 2023
 * reported nothing at all for that source — indistinguishable from a page that
 * carries no vintage, and quietly dropping the one fact a reader would want to
 * know about it. A single-year range is the ordinary case; a wider one says the
 * page mixes vintages, which is the truth. A key is ABSENT only when no returned
 * row carries that vintage at all — an empty page reports no years.
 */
data class CollegeSearchPage(
  val matches: List<CollegeMatch>,
  val totalMatches: Int,
  val excludedUnknown: Map<String, Int> = emptyMap(),
  val sourceYears: Map<String, IntRange> = emptyMap(),
)
