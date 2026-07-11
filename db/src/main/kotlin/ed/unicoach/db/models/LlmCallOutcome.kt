package ed.unicoach.db.models

import kotlinx.serialization.json.JsonElement

/**
 * The classified terminal of one logged LLM call, a two-arm sealed ADT carrying
 * the payload that outcome — and only that outcome — has (RFC 106). [Completed]
 * carries the assistant content, the resolved model, and the verbatim stop
 * reason; [Failed] carries a typed [LlmFailureKind] discriminant plus the
 * failure [Failed.reason] (the classifier's per-arm diagnostic). A
 * `completed`-with-a-reason or a `failed`-without-one is unrepresentable.
 *
 * This mirrors the [ExtractionOutcome] / [SynthesisOutcome] / [FitLensOutcome]
 * convention (RFC 101): a binary `Completed`/`Failed` split with a typed
 * discriminant on the failure arm plus a reason string. The four failure modes
 * carry identical data and nothing branches on them structurally, so they are a
 * single [Failed] arm parameterized by [LlmFailureKind] rather than five arms.
 *
 * Persisted as the lowercase [value] string matching the
 * `llm_responses_outcome_valid_check` CHECK; the DAO is the sole boundary that
 * maps this ADT to and from the flat `outcome` column plus its dependent columns.
 * Token usage and `provider_request_id` are orthogonal to the outcome and stay
 * flat on [NewLlmResponse] / [LlmResponse], never on this ADT.
 */
sealed interface LlmCallOutcome {
  val value: String

  data class Completed(
    val content: JsonElement,
    val modelResolved: String,
    val stopReason: String,
  ) : LlmCallOutcome {
    override val value: String get() = "completed"
  }

  data class Failed(
    val kind: LlmFailureKind,
    val reason: String,
  ) : LlmCallOutcome {
    override val value: String get() = kind.value
  }
}

/**
 * The four failure discriminants an [LlmCallOutcome.Failed] can carry, each the
 * lowercase `outcome`-column value (RFC 106). `rejected` / `transient_failure`
 * are provider-reported terminals; `cancelled` is a client disconnect written
 * under `NonCancellable`; `internal_error` is a defect — an exception that
 * escaped the `ChatProvider` flow, kept distinct from `transient_failure` so a
 * code defect and a legitimate transient provider failure are never
 * bit-identical rows.
 */
enum class LlmFailureKind(
  val value: String,
) {
  REJECTED("rejected"),
  TRANSIENT_FAILURE("transient_failure"),
  CANCELLED("cancelled"),
  INTERNAL_ERROR("internal_error"),
  ;

  companion object {
    fun fromValue(value: String): LlmFailureKind? = entries.find { it.value == value }
  }
}
