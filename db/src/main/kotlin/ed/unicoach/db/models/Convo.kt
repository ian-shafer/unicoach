package ed.unicoach.db.models

import java.time.Instant

data class Convo(
  override val id: ConvoId,
  val studentId: StudentId,
  val name: ConvoName,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  override val deletedAt: Instant?,
  val archivedAt: Instant?,
) : Identifiable<ConvoId>,
  Created,
  Updated,
  SoftDeletable
