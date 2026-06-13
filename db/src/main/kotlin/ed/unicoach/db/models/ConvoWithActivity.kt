package ed.unicoach.db.models

import java.time.Instant

/**
 * Listing projection carrying the derived recency timestamp the
 * `Conversation.lastActivityAt` field of the REST contract requires.
 *
 * [lastActivityAt] is `MAX(convo_requests.created_at)` over **all** request
 * rows (including failed turns — a failed attempt is still activity); it is
 * null for a conversation with no turns yet.
 */
data class ConvoWithActivity(
  val convo: Convo,
  val lastActivityAt: Instant?,
)
