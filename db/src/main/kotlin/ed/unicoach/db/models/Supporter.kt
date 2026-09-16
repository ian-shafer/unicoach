package ed.unicoach.db.models

import java.time.Instant

/**
 * One row of `supporters` (RFC 221): the adult side of a family relationship, as
 * its own entity rather than a flag on `users` or a property derived from a link
 * row. It is true of an adult who has signed up and not yet linked anybody.
 *
 * [userId] never changes after insert, and
 * `trigger_00c_prevent_supporters_column_updates` enforces it: re-pointing a
 * supporter row at another user would silently reassign every link that row
 * holds.
 */
data class Supporter(
  override val id: SupporterId,
  val userId: UserId,
  override val version: Int,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  override val deletedAt: Instant?,
) : Identifiable<SupporterId>,
  Created,
  Updated,
  Versioned,
  SoftDeletable
