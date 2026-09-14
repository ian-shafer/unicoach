package ed.unicoach.coaching

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.models.AnswerStatus

/**
 * One line for one declinable answer: `answered (value)` | `declined` |
 * `unanswered` (RFC 134, extracted here by RFC 209).
 *
 * It used to be `CoachingService.renderMoneyField`, and it took a
 * [ed.unicoach.db.models.MoneyProfile] -- which it needed only to name a row in
 * an error. That signature made the money profile the only thing in the product
 * that could have a declinable answer, so the next surface with one would have
 * written a second ladder with the same three arms. This takes the answer and
 * where it came from, and nothing else.
 *
 * [location] is the caller's own description of the cell -- a table, a column
 * and a row id -- because only the caller knows it.
 */
fun renderDeclinableAnswer(
  status: AnswerStatus,
  value: String?,
  location: String,
): String =
  when (status) {
    AnswerStatus.ANSWERED -> {
      // An `answered` status with no value violates the schema's
      // value-iff-answered CHECK: row corruption, surfaced with the cell named
      // (the DAO's convention for a corrupt read), never rendered into a prompt
      // as an empty answer.
      value?.let { "answered ($it)" }
        ?: throw CorruptPersistedValueException(
          "null",
          ValidationError.InvalidFormat(expected = "a value present when status is 'answered'"),
          location = location,
        )
    }

    AnswerStatus.DECLINED -> {
      "declined"
    }

    AnswerStatus.UNANSWERED -> {
      "unanswered"
    }
  }
