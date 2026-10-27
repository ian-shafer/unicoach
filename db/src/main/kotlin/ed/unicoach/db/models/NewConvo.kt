package ed.unicoach.db.models

/**
 * A conversation to create. [studentId] is whom it is about; [party] is who it
 * is with (RFC 258). Undefaulted: `convos.party` has no default, so every
 * caller states which side of the house it writes for.
 */
data class NewConvo(
  val studentId: StudentId,
  val name: ConvoName,
  val party: ConvoParty,
)
