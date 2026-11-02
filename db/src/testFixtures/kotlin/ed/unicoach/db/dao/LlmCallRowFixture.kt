package ed.unicoach.db.dao

import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The one way a test writes a costed call onto the RFC 106 log: one
 * `llm_requests` row plus its 1:1 completed `llm_responses` row carrying the
 * frozen cost every cost view reads.
 *
 * Beside [StudentRowFixture] because four suites in two modules seed this same
 * pair, and the three copies this replaced had already drifted on the response's
 * `created_at` and on the token counts — a copy that writes the wrong column
 * turns a windowing test green against a timestamp the views do not read.
 */
object LlmCallRowFixture {
  /**
   * Appends one completed call and answers its request id.
   *
   * [costNanodollars] and [costIsEstimated] are both null for an uncosted call or
   * both non-null otherwise — the `llm_responses_cost_estimated_check` pairing
   * (0041), which the default satisfies for a costed call. [createdAt] is the
   * RESPONSE's, because that is the column every cost view windows on; null means
   * the DB clock's now.
   */
  fun insert(
    connection: Connection,
    costNanodollars: Long?,
    costIsEstimated: Boolean? = costNanodollars?.let { false },
    createdAt: Instant? = null,
  ): Long {
    val requestId =
      RawSql.single(
        connection,
        "INSERT INTO llm_requests (provider, model_requested, content, max_tokens) " +
          "VALUES ('anthropic', 'claude-opus-4-8', '[]'::jsonb, 1024) RETURNING id",
      ) { it.getLong("id") }
    RawSql.execute(
      connection,
      """
      INSERT INTO llm_responses
        (request_id, created_at, outcome, content, model_resolved, stop_reason,
         cost_nanodollars, cost_is_estimated, input_tokens, output_tokens, latency_ms)
      VALUES (?, COALESCE(?, NOW()), 'completed', '[]'::jsonb, 'm', 'end_turn', ?, ?, 3, 5, 1)
      """.trimIndent(),
      requestId,
      createdAt?.atOffset(ZoneOffset.UTC),
      costNanodollars,
      costIsEstimated,
    )
    return requestId
  }

  /** The owner row `family/06` will write per parent-coaching call: this call is this supporter's spend. */
  fun attributeToSupporter(
    connection: Connection,
    supporterId: UUID,
    llmRequestId: Long,
  ) {
    RawSql.execute(connection, "INSERT INTO supporter_llm_costs (llm_request_id, supporter_id) VALUES (?, ?)", llmRequestId, supporterId)
  }
}
