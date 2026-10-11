package ed.unicoach.db.models

import java.time.Instant
import java.util.UUID

/**
 * One row of `sharing_permissions` (RFC 248): one student's answer to one
 * [SharingPermission].
 *
 * [studentId] and [permission] never change after insert
 * (`trigger_00c_prevent_sharing_permissions_column_updates`); [status] is the
 * one business column that changes, and each change is a version.
 */
data class SharingPermissionRow(
  override val id: SharingPermissionId,
  val studentId: StudentId,
  val permission: SharingPermission,
  val status: SharingPermissionStatus,
  override val version: Int,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  override val deletedAt: Instant?,
) : Identifiable<SharingPermissionId>,
  Created,
  Updated,
  Versioned,
  SoftDeletable

@JvmInline
value class SharingPermissionId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}
