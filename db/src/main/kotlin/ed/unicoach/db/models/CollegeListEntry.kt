package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the versioned mutable `college_list_entries` entity (RFC 91): a
 * student's status and free-text reasons for a specific college. Mirrors
 * [Student]'s shape (OCC [version], soft-delete via [deletedAt]).
 */
data class CollegeListEntry(
  override val id: CollegeListEntryId,
  val studentId: StudentId,
  val collegeId: CollegeId,
  val status: CollegeListEntryStatus,
  val reasons: String?,
  override val version: Int,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  override val deletedAt: Instant?,
) : Identifiable<CollegeListEntryId>,
  Created,
  Updated,
  Versioned,
  SoftDeletable
