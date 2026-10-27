package ed.unicoach.db.models

import java.time.Instant

data class Convo(
  override val id: ConvoId,
  val studentId: StudentId,
  // Who the conversation is with (RFC 258); [studentId] is whom it is about.
  val party: ConvoParty,
  val name: ConvoName,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  override val deletedAt: Instant?,
  val archivedAt: Instant?,
) : Identifiable<ConvoId>,
  Created,
  Updated,
  SoftDeletable
