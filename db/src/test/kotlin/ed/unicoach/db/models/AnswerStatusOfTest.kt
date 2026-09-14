package ed.unicoach.db.models

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * The derived answer status for a question whose values live in a sibling
 * table (RFC 209, brief 0010 D24).
 *
 * There is no caller in the product yet: `test/06/your-scores` is the first,
 * and this rule exists BEFORE it precisely so that slice cannot invent a second
 * version of it under time pressure.
 */
class AnswerStatusOfTest {
  private val refusedAt = Instant.parse("2026-09-14T12:00:00Z")

  @Test
  fun `a refusal is a decline whatever rows exist`() {
    // The case that makes the rule worth having rather than obvious: a family
    // that answered and then asked us to forget it has DECLINED, even while a
    // soft-deleted or racing row still reads as present.
    assertEquals(AnswerStatus.DECLINED, AnswerStatus.of(refusedAt, hasValue = true))
    assertEquals(AnswerStatus.DECLINED, AnswerStatus.of(refusedAt, hasValue = false))
  }

  @Test
  fun `rows without a refusal are an answer and no rows are silence`() {
    assertEquals(AnswerStatus.ANSWERED, AnswerStatus.of(null, hasValue = true))
    assertEquals(AnswerStatus.UNANSWERED, AnswerStatus.of(null, hasValue = false))
  }

  @Test
  fun `the three words are the ones the schema spells`() {
    assertEquals("unanswered", AnswerStatus.UNANSWERED.value)
    assertEquals("answered", AnswerStatus.ANSWERED.value)
    assertEquals("declined", AnswerStatus.DECLINED.value)
    assertEquals(AnswerStatus.DECLINED, AnswerStatus.fromValue("declined"))
    assertEquals(null, AnswerStatus.fromValue("undecided"), "the money spelling excludes the fourth word")
  }
}
