package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the mutable `fit_suggestions` entity (RFC 98): the coach's proposed
 * school for a student, discovered by fit-lens reaching into the college dataset.
 * It resolves from `open` (proposed, not yet raised) to `surfaced` (raised in the
 * next-session opener). Lifecycle is captured by [status] plus the surfacing
 * columns, not by a versions table. Modeled on `commitments`: the four-timestamp
 * split, no versioning, no `deleted_at`.
 */
data class FitSuggestion(
  override val id: FitSuggestionId,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  val studentId: StudentId,
  val collegeId: CollegeId,
  val status: FitSuggestionStatus,
  val rationale: String,
  val surfacedAt: Instant?,
  val surfacedInConvoId: ConvoId?,
) : Identifiable<FitSuggestionId>,
  Created,
  Updated
