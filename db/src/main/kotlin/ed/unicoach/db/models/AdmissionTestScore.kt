package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the versioned mutable `admission_test_scores` entity (RFC 207):
 * one component of one sitting, as the student reported it. Mirrors
 * [MoneyProfile]'s shape (OCC [version], soft-delete via [deletedAt]).
 *
 * The sitting is carried as the [PartialDate] the student gave us -- the pair
 * of storage columns (`taken_on` DATE at the period start, `taken_precision`)
 * is decoded by [PartialDateColumns] inside the DAO and never escapes. Above
 * the data-access layer there is ONLY [PartialDate], so a date whose value and
 * precision disagree is unrepresentable.
 *
 * `score` is the raw stored integer. The published-scale gate is enforced by
 * the schema's CHECK and re-asserted at render by `ReportedScore` in `:service`;
 * the column is read as an [Int] exactly as `College.satAverageEquivalentScore`
 * is, and the value class wraps it at the boundary (RFC 195's pattern).
 */
data class AdmissionTestScore(
  override val id: AdmissionTestScoreId,
  val studentId: StudentId,
  val test: AdmissionTest,
  val component: AdmissionTestScoreComponent,
  val score: Int,
  val sitting: PartialDate,
  override val version: Int,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  override val deletedAt: Instant?,
) : Identifiable<AdmissionTestScoreId>,
  Created,
  Updated,
  Versioned,
  SoftDeletable

/**
 * The live refusal, and nothing else (RFC 207, brief 0010 D24). "answered" is
 * DERIVED from the existence of a live score row via [AnswerStatus.of], never
 * stored, so "answered beside zero rows" is unrepresentable.
 *
 * Not a versioned aggregate (the `cost_report_shares` shape, RFC 155): the
 * row's only mutations are insert and setting [withdrawnAt] once, so no OCC
 * [version] and no history table. [createdAt] is the time she said no; the row
 * IS the refusal. A student who declines again after a withdrawal inserts a
 * new row, and the live one is the current refusal.
 */
data class AdmissionTestScoreDecline(
  val id: AdmissionTestScoreDeclineId,
  val studentId: StudentId,
  val createdAt: Instant,
  val withdrawnAt: Instant?,
)
