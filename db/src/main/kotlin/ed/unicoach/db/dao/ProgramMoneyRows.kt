package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.ProgramMoneyMeasure
import java.util.UUID

/**
 * A `college_programs` row named the way the PUBLISHER's field-of-study file
 * names it (RFC 218): the institution's IPEDS unit id, the CIP code as a
 * zero-padded string, and the raw `CREDLEV`.
 *
 * The publisher's key, not ours, because the loader has a CSV row in hand and
 * needs the program id: resolving the college first and the program second is
 * two lookups where the ingest asks one question. `cip_code` is TEXT for the
 * reason `college_programs` stores it as TEXT -- `'0901'` through an integer
 * becomes `'901'`.
 */
data class ProgramKey(
  val ipedsUnitId: Int,
  val cipCode: String,
  val credentialLevel: Int,
)

/**
 * One `program_money_stats` row (RFC 218): what one PROGRAM returns, as the
 * College Scorecard field-of-study file publishes it.
 *
 * The row points at the program rather than restating its three-column grain:
 * `college_programs` already holds (college_id, cip_code, credential_level)
 * under a unique index, and a second copy here would be a second place for them
 * to disagree.
 *
 * [reading] closes the amount and its status into ONE field, on
 * [ed.unicoach.db.models.NewCohortMoneyStat]'s precedent: the invalid pairings
 * -- a null amount with `reported`, an amount with `suppressed_by_publisher` --
 * do not compile, and `program_money_stats_amount_iff_status_check` is the
 * backstop for any writer that bypasses this type.
 *
 * [cohortFirstYear] and [cohortLastYear] are the first and last
 * academic year the publisher's cohort POOLS, each named by its start year, and
 * they are EQUAL when a figure pools nothing. They belong on the row because
 * they are what the figure is about; everything else the publisher says about
 * the variable -- which calendar years earnings were measured in, the inflation
 * base -- is a fact about the VARIABLE and lives once, in
 * `ScorecardFieldOfStudyColumns`, reached through [sourceVariable].
 */
data class NewProgramMoneyStat(
  val collegeProgramId: UUID,
  val measure: ProgramMoneyMeasure,
  /** The FIRST academic year the publisher's cohort pools, as its start year. */
  val cohortFirstYear: AcademicYear,
  /** The LAST academic year the publisher's cohort pools; never earlier than the first. */
  val cohortLastYear: AcademicYear,
  /** Whole US dollars and the status as ONE reading (RFC 158 D3): the invalid pairings do not compile. */
  val reading: FigureReading<Int>,
  /** The publisher, as the owned enumeration -- never a free string. */
  val source: MoneySource,
  /** The publisher's own variable name (`EARN_MDN_4YR`, ...), never a column of ours. */
  val sourceVariable: String,
)
