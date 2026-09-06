package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.dao.CorruptPersistedValueException

/*
 * Read models for the canonical money store (RFC 166, D1): the row shapes the
 * consumer side reads back, one per fact table. They are deliberately NOT the
 * `New*` write inputs of [NewPriceFigure]/[NewCohortMoneyStat] -- a read row
 * carries a [CollegeId] rather than a bare `UUID`, because everything above
 * the DAO already speaks the id type, and the write side takes the raw UUID
 * the ingest holds.
 *
 * The two stored columns (`amount_usd`/`value` and `status`) come back as ONE
 * [FigureReading], decoded through [FigureReading.of], so the invalid pairings
 * are unrepresentable on the read side exactly as they are on the write side.
 */

/** One `price_figures` row, read back (RFC 158, D2). */
data class PriceFigure(
  val collegeId: CollegeId,
  val priceConcept: PriceConcept,
  val residencyBasis: ResidencyBasis,
  val arrangement: FigureArrangement,
  /** Always a real academic year, never absent here (P5). */
  val academicYear: AcademicYear,
  val reading: FigureReading<Int>,
  val source: MoneySource,
  val sourceVariable: String,
  val publisherFlag: String?,
)

/**
 * One `cohort_money_stats` row, read back (RFC 158, D2/P4): a number about a
 * population, never a price anyone is quoted. [incomeBand] null means "the
 * overall figure".
 */
data class CohortMoneyStat(
  val collegeId: CollegeId,
  val measure: MoneyMeasure,
  val population: CohortPopulation,
  val residencyScope: CohortResidencyScope,
  val aidScope: CohortAidScope,
  val incomeBand: IncomeBand?,
  /** The year the source dates the cohort, or null where it pools or does not date it (RFC 170 D14, P5). */
  val vintage: AcademicYear?,
  val reading: FigureReading<Double>,
  val source: MoneySource,
  val sourceVariable: String,
  val publisherFlag: String?,
)

/**
 * One batched read of a canonical fact table: the rows this build could decode,
 * grouped by college, beside the ones it could not (RFC 166).
 *
 * The pair exists because the reads are BATCHED over every college a request
 * selected. A row whose stored code this build does not know is a fact about
 * ONE row, so it may not fail the answer for every other school on the list --
 * it leaves the batch here, and the address it would have answered is then
 * simply one this request holds no row for, which the caller already has words
 * for. [unreadable] is what stops that being a silent drop: the reader logs it,
 * so a deploy-skew gap is visible to an operator rather than looking like
 * missing source coverage.
 */
data class CanonicalMoneyRead<T>(
  val byCollege: Map<CollegeId, List<T>>,
  val unreadable: List<UnreadableMoneyRow>,
)

/**
 * One row the running build could not decode: which [table] it came from, the
 * [stored] value that could not be read, and [location] -- the column and the
 * row's natural key, as [ed.unicoach.db.dao.CorruptPersistedValueException]
 * already phrases them, so the cell can be found from the log line alone.
 *
 * [location] is that key ALONE, never the exception's rendered sentence: the
 * words belong to the layer that writes the log line, and a field named
 * `location` that carried a whole message would make `DaoException`'s wording
 * an implicit contract of this type.
 *
 * [cause] carries the decode failure ITSELF, unaltered. This is the money
 * path's only tolerated failure, so the warning written over these rows is the
 * entire evidence trail that a figure we hold was withdrawn from a family's
 * answer and then spoken as one we have not collected -- and a warning that
 * cannot log the throwable cannot show the operator which frame threw
 * (`decode` vs `FigureReading.of`) in the deploy-skew incident this design
 * exists for. Nothing in `:db` logs it; it travels out as data, like the rows.
 */
data class UnreadableMoneyRow(
  val table: String,
  val stored: String,
  val location: String,
  val cause: CorruptPersistedValueException,
)
