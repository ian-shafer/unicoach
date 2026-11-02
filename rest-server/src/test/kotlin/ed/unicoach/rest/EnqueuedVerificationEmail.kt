package ed.unicoach.rest

import ed.unicoach.db.dao.RawSql
import java.sql.Connection

/**
 * Observes the `SEND_EMAIL` job a request enqueues (RFC 96), for the rest-server
 * integration tests. The enqueue commits inside the request transaction, so the
 * `jobs` row is present the moment the HTTP response returns — these queries are
 * deterministic and require no polling.
 *
 * The tests deliberately prove **enqueue only**: they do not boot a worker or
 * exercise delivery (`email_sends`) — that end-to-end path is a future RFC. When a
 * verify-flow test needs the raw single-use token, it reads it straight from the
 * enqueued payload's `context.verifyToken`, where `EmailVerificationService`
 * placed it (the same raw token the worker would render into the verify link).
 */
object EnqueuedVerificationEmail {
  /**
   * Count of `SEND_EMAIL` jobs enqueued to [recipient]. Recipient-scoped so a
   * concurrent test's enqueue cannot perturb the count.
   */
  fun countTo(
    connection: Connection,
    recipient: String,
  ): Int =
    RawSql.count(
      connection,
      "SELECT COUNT(*) FROM jobs WHERE job_type = 'SEND_EMAIL' AND payload->>'to' = ?",
      recipient,
    )

  /**
   * The raw verification token carried by the most-recent `SEND_EMAIL` job
   * enqueued to [recipient] (`payload.context.verifyToken`). Fails if no such job
   * exists — the enqueue is transactional, so the row is present the instant the
   * enqueueing response returns.
   */
  fun verifyTokenFor(
    connection: Connection,
    recipient: String,
  ): String {
    // "No job" and "a job without a token" fail with different messages: the
    // read refuses a NULL token, so a null from singleOrNull can only mean no job.
    val token =
      RawSql.singleOrNull(
        connection,
        """
        SELECT payload->'context'->>'verifyToken' AS verify_token
        FROM jobs
        WHERE job_type = 'SEND_EMAIL' AND payload->>'to' = ?
        ORDER BY created_at DESC
        LIMIT 1
        """.trimIndent(),
        recipient,
      ) { it.getString("verify_token") ?: error("SEND_EMAIL job to [$recipient] carries no context.verifyToken") }
    return requireNotNull(token) { "No SEND_EMAIL job was enqueued to [$recipient]" }
  }
}
