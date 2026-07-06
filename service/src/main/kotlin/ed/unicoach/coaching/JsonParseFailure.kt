package ed.unicoach.coaching

import ed.unicoach.db.models.JsonParseFailureCategory

/**
 * Why an LLM output document could not be used (RFC 101, tier-A forced tool use
 * per RFC 104), shared by `ExtractionService` and `SynthesisService` — their
 * shapes are byte-for-byte identical, so one shared top-level type serves both
 * rather than two duplicate private definitions. The direct analog of RFC 98's
 * `FailureReason` living in `FitLensResult.kt`.
 *
 * The payload arrives as the forced tool's `tool_use.input` object, so
 * "malformed JSON" and "root is not an object" are unrepresentable; the residual
 * surfaces are:
 *
 * - [NoToolUse]: the response carried no `tool_use` block — the structured object
 *   the model was forced to produce never arrived. Carries the provider
 *   [stopReason] and a bounded [excerpt] of whatever text the model returned
 *   instead (refusal, clarifying prose, or a `max_tokens` truncation), so the
 *   persisted `failure_reason` retains the context needed to tell those apart.
 * - [BadField]: the input was an object, but a field was missing, wrong-shape, or
 *   failed enum membership ([field]/[value] name the offender).
 *
 * [category] is the coarse persisted bucket, derived from the variant identity;
 * [toDisplay] renders the free-text diagnostic persisted as `failure_reason`.
 */
sealed interface JsonParseFailure {
  data class NoToolUse(
    val stopReason: String,
    val excerpt: String,
  ) : JsonParseFailure

  data class BadField(
    val field: String,
    val value: String,
  ) : JsonParseFailure
}

/**
 * The coarse, persisted [JsonParseFailureCategory] a [JsonParseFailure] falls
 * into — a pure function of which variant it is, so a category can never drift
 * out of sync with the reason it was derived from.
 */
val JsonParseFailure.category: JsonParseFailureCategory
  get() =
    when (this) {
      // A forced tool payload that never arrived is the tier-A analogue of the
      // old prose-not-an-object failure, so it reuses NOT_A_JSON_OBJECT (a
      // deliberate overload; no DB enum migration). MALFORMED_JSON remains valid
      // historical vocabulary with no new live producer.
      is JsonParseFailure.NoToolUse -> JsonParseFailureCategory.NOT_A_JSON_OBJECT

      is JsonParseFailure.BadField -> JsonParseFailureCategory.INVALID_FIELD
    }

/** Renders a [JsonParseFailure] to the human string logged and persisted as `failure_reason`. */
fun JsonParseFailure.toDisplay(): String =
  when (this) {
    is JsonParseFailure.NoToolUse -> "response carried no tool_use block (stop_reason=[$stopReason], text=[$excerpt])"
    is JsonParseFailure.BadField -> "field [$field]=[$value]"
  }
