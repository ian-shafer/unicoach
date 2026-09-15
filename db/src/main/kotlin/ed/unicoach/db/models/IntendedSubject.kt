package ed.unicoach.db.models

import java.time.Instant
import java.util.UUID

/**
 * One row of `intended_subjects` (RFC 205): ONE subject a student named when
 * asked what they want to study, referenced by [slug] — our taxonomy's
 * own key, never a federal code (D38/D39).
 *
 * [studentId] and [slug] never change after insert, and the
 * `trigger_00c_prevent_intended_subjects_column_updates` guard enforces it.
 * That is the rule that makes the version history worth keeping: if a row could
 * be updated from one subject to another, "she was planning nursing in March"
 * would be answered by a row that is now a biology row. A student swaps a
 * subject by removing one row and inserting another; [deletedAt] and the
 * bookkeeping columns are the only things that ever change.
 */
data class IntendedSubject(
  override val id: IntendedSubjectId,
  val studentId: StudentId,
  val slug: String,
  override val version: Int,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  override val deletedAt: Instant?,
) : Identifiable<IntendedSubjectId>,
  Created,
  Updated,
  Versioned,
  SoftDeletable

@JvmInline
value class IntendedSubjectId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}
