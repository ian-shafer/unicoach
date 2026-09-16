package ed.unicoach.db.models

import java.time.Instant

/**
 * One row of `supporter_student_links` (RFC 221): the relationship between a
 * supporter and a student. A row with a null [deletedAt] is an ACTIVE link; at
 * most one active row exists per (supporter, student) pair.
 *
 * [supporterId] and [studentId] never change after insert
 * (`trigger_00c_prevent_supporter_student_links_column_updates`): re-pointing a
 * link would make "this supporter has had access since March" a statement about
 * somebody else, and a different pair is a different link.
 *
 * [relationship] is the one business column here that legitimately changes in
 * place. A mother recorded as [SupporterRelationship.PARENT] and then corrected
 * to [SupporterRelationship.GUARDIAN] is one relationship throughout, which is
 * also why the word is not part of the uniqueness key.
 *
 * Unlinking is a soft delete, and re-linking is a NEW row with its own
 * [createdAt] — so the severed period stays visible in the table instead of
 * being erased by resurrecting an old row.
 */
data class SupporterStudentLink(
  override val id: SupporterStudentLinkId,
  val supporterId: SupporterId,
  val studentId: StudentId,
  val relationship: SupporterRelationship,
  override val version: Int,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  override val deletedAt: Instant?,
) : Identifiable<SupporterStudentLinkId>,
  Created,
  Updated,
  Versioned,
  SoftDeletable
