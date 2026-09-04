package ed.unicoach.db.models

/**
 * One row of the migration-seeded `policy_parameters` reference table
 * (RFC 159): a federal policy figure for one award year, with the citation it
 * was verified against. [awardYear] is the STARTING calendar year of the
 * award year (2026 = "2026-27"); the spoken label is rendered in `:service`
 * through `AcademicYear`, never here.
 *
 * [value] is whole dollars for `_usd` parameters, percent points for `_pct`
 * parameters, and a dimensionless index for [PolicyParameter.SAI_FLOOR].
 * [sourceName] is the SPOKEN document name the coach cites (the CdsCitation
 * `cited_as` precedent); [sourceUrl] is the primary Federal Student Aid page.
 */
data class PolicyParameterRow(
  val awardYear: Int,
  val parameter: PolicyParameter,
  val value: Int,
  val sourceName: String,
  val sourceUrl: String,
)
