package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear
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

/** One `aid_forms` row (RFC 170, D4). */
data class NewAidForm(
  val slug: String,
  val description: String,
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
  /** The academic year the price is published for; the 'YYYY-YY' label is rendered at read time (D14, P5). */
  val academicYear: AcademicYear,
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
  /** The year the source dates the cohort, or null where it pools or does not date it (RFC 170 D14, P5). */
  val vintage: AcademicYear?,
  /** The numeric value and its status as ONE reading (D3): the invalid pairings do not compile. */
  val reading: FigureReading<Double>,
  /** The publisher, as the owned enumeration (RFC 161 decision 6) -- never a free string. */
  val source: MoneySource,
  val sourceVariable: String,
  val publisherFlag: String? = null,
  /**
   * The published document this figure was read out of (RFC 170, D13), or null
   * for a publisher with no per-school document -- the Scorecard and the two
   * IPEDS surveys cite a national release. The reference, not the urls: they
   * live on the document row, once.
   */
  val sourceDocumentId: SourceDocumentId? = null,
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
  /** The year the source dates the cohort with; never absent here (RFC 170 D14, P5). */
  val vintage: AcademicYear,
  /** The headcount and its status as ONE reading (D3): the invalid pairings do not compile. */
  val reading: FigureReading<Int>,
  /** The publisher, as the owned enumeration (RFC 161 decision 6) -- never a free string. */
  val source: MoneySource,
  val sourceVariable: String,
  val publisherFlag: String? = null,
  /**
   * The published document this figure was read out of (RFC 170, D13), or null
   * for a publisher with no per-school document -- the Scorecard and the two
   * IPEDS surveys cite a national release. The reference, not the urls: they
   * live on the document row, once.
   */
  val sourceDocumentId: SourceDocumentId? = null,
)

/**
 * One `aid_form_requirements` row (RFC 170, D4/D5): this college requires this
 * form of this applicant group in this academic year.
 *
 * A REQUIREMENT, not a statistic -- which is why it is a relation and not a
 * `cohort_money_stats` measure. [reading] carries the value exactly when its
 * status bears one (D3), by construction, and the value is only ever `true`:
 * no source publishes "not required", and a form a school's CDS does not list
 * gets NO row at all (D5).
 */
data class NewAidFormRequirement(
  val collegeId: UUID,
  val form: AidForm,
  val applicantGroup: AidFormApplicantGroup,
  val academicYear: AcademicYear,
  /** The requirement and its status as ONE reading: the invalid pairings do not compile. */
  val reading: FigureReading<Boolean>,
  /** The publisher, as the owned enumeration -- never a free string. */
  val source: MoneySource,
  /** The published cell this row was read from: a CDS field id, raw. */
  val sourceVariable: String,
  /** The document it was read out of (D13): the urls live there, once. */
  val sourceDocumentId: SourceDocumentId,
)
