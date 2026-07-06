package ed.unicoach.coaching

import ed.unicoach.db.models.JsonParseFailureCategory

/**
 * Why an LLM output document could not be parsed (RFC 101), shared by
 * `ExtractionService` and `SynthesisService` — their parse shapes are
 * byte-for-byte identical, so one shared top-level type serves both rather than
 * two duplicate private definitions. The direct analog of RFC 98's `FailureReason`
 * living in `FitLensResult.kt`.
 *
 * - [NotAnObject]: the root of the parsed output wasn't a JSON object at all.
 * - [MalformedJson]: the raw text didn't parse as JSON ([detail] carries the raw error).
 * - [BadField]: it parsed as an object, but a field was missing, wrong-shape, or
 *   failed enum membership ([field]/[value] name the offender).
 *
 * [category] is the coarse persisted bucket, derived from the variant identity;
 * [toDisplay] renders the free-text diagnostic persisted as `failure_reason`.
 */
sealed interface JsonParseFailure {
  data object NotAnObject : JsonParseFailure

  data class MalformedJson(
    val detail: String?,
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
      is JsonParseFailure.NotAnObject -> JsonParseFailureCategory.NOT_A_JSON_OBJECT
      is JsonParseFailure.MalformedJson -> JsonParseFailureCategory.MALFORMED_JSON
      is JsonParseFailure.BadField -> JsonParseFailureCategory.INVALID_FIELD
    }

/** Renders a [JsonParseFailure] to the human string logged and persisted as `failure_reason`. */
fun JsonParseFailure.toDisplay(): String =
  when (this) {
    is JsonParseFailure.NotAnObject -> "root is not a JSON object"
    is JsonParseFailure.MalformedJson -> "malformed JSON: [$detail]"
    is JsonParseFailure.BadField -> "field [$field]=[$value]"
  }
