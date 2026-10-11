package ed.unicoach.family

/**
 * The ceiling in one voice: what a connected adult can see, and what stays
 * private, as two ordered lists of sentences.
 */
data class CeilingStatement(
  val canSee: List<String>,
  val staysPrivate: List<String>,
)

/**
 * What connecting gives a supporter and what it never gives them (brief 0014
 * D3) — the ONE home of that claim for every surface that renders it as a list
 * (RFC 249).
 *
 * Each item carries BOTH wordings on adjacent lines: [toSupporter], the third
 * person the `/invite` page shows a stranger deciding whether to connect, and
 * [toStudent], the second person a student reads about the adults already
 * connected to them. A reword of one is visibly a reword beside the other, so
 * the promise an adult accepted and the one a student is told cannot drift
 * apart unnoticed.
 *
 * It states the CEILING, not today's reads: a connected adult reads only the
 * roster today, and `family/05`/`06` deliver the rest. Telling a student MORE
 * is visible than is, never less, is the safe side to err on.
 *
 * The coach's spoken restatement (`InviteSupporterChatTool`, the system
 * prompt, `SynthesisService.INVITE_NUDGE_STATEMENT`) is a separate home: it is
 * one sentence for speech, pinned by prompt tests.
 */
object SupporterCeiling {
  /**
   * One line of the ceiling in both voices. [toStudent] is null for a line that
   * is about the supporter's OWN account and says nothing about the student's
   * data, so the student's list has nothing to say for it.
   */
  private class Item(
    val toSupporter: String,
    val toStudent: String?,
  )

  private val CAN_SEE =
    listOf(
      Item(
        toSupporter = "See the student's college list and key application deadlines.",
        toStudent = "Your college list and key application deadlines.",
      ),
      Item(
        toSupporter = "See estimated college costs and financial-aid calculations.",
        toStudent = "Estimated college costs and financial-aid calculations.",
      ),
      Item(
        toSupporter = "Use your own coach workspace for financial-aid and timeline help.",
        toStudent = null,
      ),
      Item(
        toSupporter = "Other supporters connected to this student will see your name.",
        toStudent = "The names of the other people connected to you.",
      ),
    )

  private val STAYS_PRIVATE =
    listOf(
      Item(
        toSupporter = "Student coach conversations stay 100% private to the student. Transcripts are never shared.",
        toStudent = "Your coach conversations. Transcripts are never shared.",
      ),
      Item(
        toSupporter = "Supporters cannot edit the student's college list, profile, or tasks — parents support, students direct.",
        toStudent = "They cannot change your college list, profile or tasks.",
      ),
    )

  /** The ceiling as the `/invite` page states it to the adult being invited. */
  val toSupporter: CeilingStatement =
    CeilingStatement(
      canSee = CAN_SEE.map { it.toSupporter },
      staysPrivate = STAYS_PRIVATE.map { it.toSupporter },
    )

  /** The ceiling as a student reads it, once, above the adults connected to them. */
  val toStudent: CeilingStatement =
    CeilingStatement(
      canSee = CAN_SEE.mapNotNull { it.toStudent },
      staysPrivate = STAYS_PRIVATE.mapNotNull { it.toStudent },
    )
}
