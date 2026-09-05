package ed.unicoach.db.models

import java.util.UUID

/*
 * Row inputs for the canonical money store (RFC 158): the five authored
 * vocabulary rows the money-vocabulary phase upserts, and the two fact rows
 * the canonical-money phase batch-inserts after a wholesale delete (P12).
 * The [NewSubject]/[NewIpedsRegion] convention: one input type per table,
 * write-shaped, no surrogate ids.
 */

/** One `residency_bases` row (RFC 158, D4). */
data class NewResidencyBasis(
  val slug: String,
  val description: String,
)

/** One `arrangements` row (RFC 158, D4/P3). */
data class NewArrangement(
  val slug: String,
  val description: String,
  val isLivingArrangement: Boolean,
)

/** One `figure_statuses` row (RFC 158, D3). */
data class NewFigureStatus(
  val slug: String,
  val description: String,
  val valueBearing: Boolean,
)

/** One `price_concepts` row (RFC 158, D2/P3). */
data class NewPriceConcept(
  val slug: String,
  val description: String,
  val arrangementVaries: Boolean,
)

/** One `income_bands` row (RFC 158, D7): the five dollar cut-points as data. */
data class NewIncomeBand(
  val slug: String,
  val minUsd: Int,
  /** Exclusive upper bound; null for the open-ended top band. */
  val maxUsd: Int?,
  val bracketLabel: String,
  val sortOrder: Int,
)

/**
 * One `price_figures` row (RFC 158, D2): a published price figure whose
 * [reading] carries a value exactly when its status bears one (D3), by
 * construction. The enums are the write-side type safety; the DAO binds
 * their `.value`.
 */
data class NewPriceFigure(
  val collegeId: UUID,
  val priceConcept: PriceConcept,
  val residencyBasis: ResidencyBasis,
  val arrangement: FigureArrangement,
  /** Always a real 'YYYY-YY' academic year (P5). */
  val academicYear: String,
  /** The USD amount and its status as ONE reading (D3): the invalid pairings do not compile. */
  val reading: FigureReading<Int>,
  /** The publisher, as the owned enumeration (RFC 161 decision 6) -- never a free string. */
  val source: MoneySource,
  val sourceVariable: String,
  val publisherFlag: String? = null,
)

/**
 * One `cohort_money_stats` row (RFC 158, D2/P4): a number about a population.
 * [incomeBand] null means "the overall figure" -- the natural key treats NULL
 * as a value (`NULLS NOT DISTINCT`).
 */
data class NewCohortMoneyStat(
  val collegeId: UUID,
  val measure: MoneyMeasure,
  val population: CohortPopulation,
  val residencyScope: CohortResidencyScope,
  val aidScope: CohortAidScope,
  val incomeBand: IncomeBand?,
  /** 'YYYY-YY' where the source dates the cohort, the literal 'undated' where it does not (P5). */
  val vintage: String,
  /** The numeric value and its status as ONE reading (D3): the invalid pairings do not compile. */
  val reading: FigureReading<Double>,
  /** The publisher, as the owned enumeration (RFC 161 decision 6) -- never a free string. */
  val source: MoneySource,
  val sourceVariable: String,
  val publisherFlag: String? = null,
)

/**
 * One `cohort_population_counts` row (RFC 162): how many students of a cohort
 * sit on one residency basis and one living arrangement. A headcount, not
 * money -- so it carries no [MoneyMeasure] and no unit, and its two axes are
 * the authored [ResidencyBasis] / [FigureArrangement] vocabularies rather
 * than `cohort_money_stats`' two-value residency scope.
 */
data class NewCohortPopulationCount(
  val collegeId: UUID,
  val population: CohortPopulation,
  val residencyBasis: ResidencyBasis,
  val arrangement: FigureArrangement,
  /** 'YYYY-YY' where the source dates the cohort, the literal 'undated' where it does not (P5). */
  val vintage: String,
  /** The headcount and its status as ONE reading (D3): the invalid pairings do not compile. */
  val reading: FigureReading<Int>,
  /** The publisher, as the owned enumeration (RFC 161 decision 6) -- never a free string. */
  val source: MoneySource,
  val sourceVariable: String,
  val publisherFlag: String? = null,
)
