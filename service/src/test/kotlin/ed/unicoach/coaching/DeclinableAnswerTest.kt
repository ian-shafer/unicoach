package ed.unicoach.coaching

import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.models.AnswerStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The shared declinable-answer renderer (RFC 209), extracted from
 * `CoachingService.renderMoneyField` with the money taken out of its signature.
 *
 * The three strings are asserted literally because they are what the model
 * reads: RFC 209 promises no user-visible change, and these are the bytes that
 * promise is about.
 */
class DeclinableAnswerTest {
  private val location = "money_profiles.income_band (row 00000000-0000-0000-0000-000000000001)"

  @Test
  fun `the three arms render exactly as they did`() {
    assertEquals("answered (under \$30k)", renderDeclinableAnswer(AnswerStatus.ANSWERED, "under \$30k", location))
    assertEquals("declined", renderDeclinableAnswer(AnswerStatus.DECLINED, null, location))
    assertEquals("unanswered", renderDeclinableAnswer(AnswerStatus.UNANSWERED, null, location))
  }

  @Test
  fun `a declined or unanswered field never smuggles a stale value into the prompt`() {
    // The schema forbids the pairing, so this is defence in depth -- but a
    // renderer that printed "declined (under $30k)" would put a value in front
    // of the coach that the family asked us not to use.
    assertEquals("declined", renderDeclinableAnswer(AnswerStatus.DECLINED, "under \$30k", location))
    assertEquals("unanswered", renderDeclinableAnswer(AnswerStatus.UNANSWERED, "under \$30k", location))
  }

  @Test
  fun `an answered field with no value is corruption and names the cell`() {
    val failure =
      assertFailsWith<CorruptPersistedValueException> {
        renderDeclinableAnswer(AnswerStatus.ANSWERED, null, location)
      }
    assertEquals(location, failure.location, "the caller's cell, carried as data and not only in prose")
    assertTrue(failure.message!!.contains(location), "and in the message, so a log line is enough to find the row")
  }
}
