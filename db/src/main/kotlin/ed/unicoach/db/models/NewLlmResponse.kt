package ed.unicoach.db.models

import ed.unicoach.common.money.Nanodollars

/**
 * Insert input for the `llm_responses` log (RFC 106); omits the DB-generated id
 * and `created_at`. [outcome] is the sealed [LlmCallOutcome] ADT: a `Completed`
 * carries content/model/stop-reason, a `Failed` the kind + reason — a
 * `completed`-with-a-reason or a `failed`-with-content cannot be constructed.
 * The four token fields and [providerRequestId] are nullable and vary
 * independently of the outcome (a failed or cancelled call may still carry
 * partial usage or a provider id), so they stay flat. [cost] is the frozen
 * dollar cost of this call (RFC 108) as a single [FrozenCost] — nanodollars and
 * whether it was priced at the price book's default rate never disagree, so
 * they are carried as one nullable value rather than an independently-nullable
 * pair; it is undefaulted so every write site — the priced terminal and the
 * never-ran repair alike — must state a cost, and a third can never be added
 * silently. [latencyMs] is always recorded (the time to the terminal or to the
 * disconnect).
 */
data class NewLlmResponse(
  val requestId: LlmRequestId,
  val outcome: LlmCallOutcome,
  val providerRequestId: String?,
  val inputTokens: Int?,
  val outputTokens: Int?,
  val cacheReadTokens: Int?,
  val cacheWriteTokens: Int?,
  val cost: FrozenCost?,
  val latencyMs: Int,
)
