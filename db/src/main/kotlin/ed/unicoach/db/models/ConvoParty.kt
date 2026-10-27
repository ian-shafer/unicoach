package ed.unicoach.db.models

/**
 * Closed enum backing `convos.party` (RFC 258): who a conversation is WITH. The
 * column is a storage detail of [ConvoParty]; nothing above the DAO reads it.
 */
enum class ConvoPartyKind(
  val value: String,
) {
  STUDENT("student"),
  SUPPORTER("supporter"),
  ;

  companion object {
    fun fromValue(value: String): ConvoPartyKind? = entries.find { it.value == value }
  }
}

/**
 * Who a conversation is with (RFC 258). `convos.student_id` keeps its meaning of
 * "about whom"; this is the other half — the student themself, or a supporter
 * talking to their own coach about that student.
 *
 * Sealed rather than the stored pair (`party`, `supporter_id`) because the pair
 * is one fact held in two columns: a supporter conversation has an author and a
 * student conversation has none, and this type cannot represent the other two
 * combinations the CHECK refuses. The DAO maps the pair into it and nowhere
 * else, so no caller ever sees the redundant shape.
 */
sealed interface ConvoParty {
  /** The student's own conversation with their coach. */
  data object Student : ConvoParty

  /** A supporter's conversation with their own coach about the student. */
  data class Supporter(
    val supporterId: SupporterId,
  ) : ConvoParty
}

/** The stored `convos.party` value for this party — the one mapping from the sealed type to the column. */
val ConvoParty.kind: ConvoPartyKind
  get() =
    when (this) {
      ConvoParty.Student -> ConvoPartyKind.STUDENT
      is ConvoParty.Supporter -> ConvoPartyKind.SUPPORTER
    }
