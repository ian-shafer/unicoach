package ed.unicoach.family

import ed.unicoach.coaching.ReplyEvent
import ed.unicoach.coaching.VisibleExchange
import ed.unicoach.coaching.budget.Entitlement
import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.ConvoWithActivity
import ed.unicoach.error.FieldError
import kotlinx.coroutines.flow.Flow

/**
 * Why a supporter was refused a conversation. For the operator's log only: the
 * wire answers every reason with the same `404`, so a caller cannot tell a
 * stale id from a probe of somebody else's conversation, but the operator can.
 */
enum class ConvoRefusal {
  /** No active row: never existed, or soft-deleted. */
  MISSING_OR_DELETED,

  /** The row is the student's own conversation, never a supporter's to read. */
  STUDENT_PARTY,

  /** The row is another supporter's conversation. */
  OTHER_SUPPORTER,
}

/**
 * Why a supporter's turn could not reach the student. For the operator's log
 * only: the wire answers every reason with the same `404 student_not_found`, so
 * a caller cannot confirm that a student exists.
 */
enum class StudentRefusal {
  /** No live link: never linked, severed, or either account closed. */
  NO_LIVE_LINK,

  /** A live link was found, but the subject seam refused the student's shared list. */
  SUBJECT_REFUSED,

  /** A live link was found, but the student is missing from the supporter's roster. */
  NOT_ON_ROSTER,
}

/** Outcome of [SupporterCoachingService.listConvos] (RFC 258). */
sealed interface SupporterConvoList {
  /** The caller's own conversations, every student they are about, most-recent activity first. */
  data class Found(
    val conversations: List<ConvoWithActivity>,
  ) : SupporterConvoList

  /** The caller holds no live `supporters` row. */
  data object NoSupporter : SupporterConvoList
}

/**
 * Outcome of [SupporterCoachingService.listTurns] (RFC 258). Reading needs no
 * live link: after an unlink a parent's conversations stay readable, because
 * they are the parent's own words.
 */
sealed interface SupporterTurns {
  data class Found(
    val exchanges: List<VisibleExchange>,
  ) : SupporterTurns

  data object NoSupporter : SupporterTurns

  /**
   * Missing, soft-deleted, or not this supporter's conversation — one answer on
   * the wire, no existence leak; [reason] is for the log.
   */
  data class ConvoNotFound(
    val reason: ConvoRefusal,
  ) : SupporterTurns
}

/**
 * Outcome of [SupporterCoachingService.startConvo] and
 * [SupporterCoachingService.postTurn] (RFC 258): one type for both, because a
 * parent's turn can be refused for the same reasons either way —
 * [ConvoNotFound] simply never arises on a start.
 *
 * Every refusal is decided in the turn's pre-flight transaction, before anything
 * is persisted, so the route answers each before a single SSE byte.
 */
sealed interface SupporterTurnStart {
  /** The two-phase shape of the student's turn: the persisted user turn, and the cold reply flow. */
  data class Started(
    val convo: Convo,
    val userTurn: ConvoRequest,
    val reply: Flow<ReplyEvent>,
  ) : SupporterTurnStart

  /** The caller holds no live `supporters` row. */
  data object NoSupporter : SupporterTurnStart

  /**
   * No live link to the student: never linked, severed, or a closed student —
   * one answer on the wire for all, so a caller cannot confirm a student
   * exists; [reason] is for the log. On a conversation that already exists,
   * this is a severed link refusing the very next message (brief 0014 D14).
   */
  data class StudentNotFound(
    val reason: StudentRefusal,
  ) : SupporterTurnStart

  /** Missing, soft-deleted, or not this supporter's conversation; [reason] is for the log. */
  data class ConvoNotFound(
    val reason: ConvoRefusal,
  ) : SupporterTurnStart

  /**
   * The parent's OWN allowance is spent. Carries the deciding [Entitlement] for
   * the operator's log, as the student's refusal does; it never reaches the wire.
   */
  data class BudgetExhausted(
    val entitlement: Entitlement,
  ) : SupporterTurnStart

  /** The message is blank or too long. */
  data class Invalid(
    val fieldErrors: List<FieldError>,
  ) : SupporterTurnStart
}
