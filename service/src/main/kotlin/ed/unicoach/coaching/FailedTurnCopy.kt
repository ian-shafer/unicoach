package ed.unicoach.coaching

/**
 * What a student is told about a failed turn (RFC 262 §2c), by the one fact the
 * sentence depends on: whether a retry may succeed. The reason never reaches
 * them — [ReplyEvent.Failed.reason] is server-side only, and this function does
 * not take it.
 *
 * One home for both doors, so the iOS app (through rest-server's error body)
 * and the web chat (through its `failed` event, and the composer's sentence for
 * a failure no event reports) say the same sentence for the same failure. The
 * retriable sentence invites a retry; the permanent one does not promise that a
 * retry of the same text will succeed.
 */
fun studentSentence(retriable: Boolean): String =
  if (retriable) {
    "The coach is temporarily unavailable — try again."
  } else {
    "The coach could not respond to this message."
  }

/** A failed turn's sentence: [studentSentence] for its [ReplyEvent.Failed.retriable]. */
fun ReplyEvent.Failed.studentSentence(): String = studentSentence(retriable)
