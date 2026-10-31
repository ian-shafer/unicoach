package ed.unicoach.coaching

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FailedTurnCopyTest {
  @Test
  fun `a retriable failure invites a retry`() {
    assertEquals(
      "The coach is temporarily unavailable — try again.",
      ReplyEvent.Failed(retriable = true, reason = "transient").studentSentence(),
    )
  }

  @Test
  fun `a permanent failure says the coach could not respond`() {
    assertEquals(
      "The coach could not respond to this message.",
      ReplyEvent.Failed(retriable = false, reason = "permanent").studentSentence(),
    )
  }

  @Test
  fun `a failed turn says the sentence for its retriability`() {
    for (retriable in listOf(true, false)) {
      assertEquals(studentSentence(retriable), ReplyEvent.Failed(retriable, reason = "any").studentSentence(), "[$retriable]")
    }
  }

  @Test
  fun `the reason never reaches the sentence`() {
    val sentence = ReplyEvent.Failed(retriable = true, reason = "provider defect: secret internals").studentSentence()
    assertEquals(false, sentence.contains("secret internals"))
  }
}
