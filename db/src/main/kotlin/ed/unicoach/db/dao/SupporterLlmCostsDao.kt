package ed.unicoach.db.dao

import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.SupporterId
import java.sql.SQLException

/**
 * The writer of `supporter_llm_costs` (RFC 244's owner row, first written by RFC
 * 258): one row per LLM call a supporter's own coach makes, attributing the call
 * to that supporter. `account_llm_cost` re-keys it onto the supporter's account,
 * which is what meters a parent against their own allowance and never against
 * the student's.
 *
 * Stateless `object`, one [SqlSession] per call, the transaction owned by the
 * caller — which writes this row in the same transaction as the call's
 * `convo_requests` row, so a parent call is never attributed to nobody.
 */
object SupporterLlmCostsDao {
  /** Attributes [llmRequestId] to [supporterId]. The primary key refuses a second attribution of one call. */
  fun insert(
    session: SqlSession,
    llmRequestId: LlmRequestId,
    supporterId: SupporterId,
  ): Result<Unit> =
    session
      .execute(
        "INSERT INTO supporter_llm_costs (llm_request_id, supporter_id) VALUES (?, ?)",
        mapError = ::mapCostError,
      ) { stmt ->
        stmt.setLong(1, llmRequestId.value)
        stmt.setObject(2, supporterId.value)
      }.map { }

  private val FOREIGN_KEY_MESSAGES =
    mapOf(
      "supporter_llm_costs_llm_request_id_fkey" to "LLM request not found",
      "supporter_llm_costs_supporter_id_fkey" to "Supporter not found",
    )

  private fun mapCostError(e: SQLException): Exception = mapChildWriteError(e, FOREIGN_KEY_MESSAGES)
}
