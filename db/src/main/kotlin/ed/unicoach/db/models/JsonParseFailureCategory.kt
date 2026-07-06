package ed.unicoach.db.models

/**
 * Why an LLM output document could not be parsed, on a `failed`
 * `extraction_runs`/`synthesis_runs` row (RFC 101). A 1:1 mirror of
 * `ExtractionService`/`SynthesisService`'s private `ParseFailure` variants —
 * their shapes are byte-for-byte identical, so one shared persisted vocabulary
 * serves both tables rather than two duplicate enums. Persisted as the
 * lowercase [value] string matching the `*_failure_category_check` CHECKs.
 * Always paired with a free-text `failure_reason` carrying the interpolated
 * diagnostic (the offending field/value, a raw JSON error) that the category
 * alone would lose.
 */
enum class JsonParseFailureCategory(
  val value: String,
) {
  /** The root of the parsed output wasn't a JSON object at all. */
  NOT_A_JSON_OBJECT("not_a_json_object"),

  /** The raw text didn't parse as JSON. */
  MALFORMED_JSON("malformed_json"),

  /** The output parsed as a JSON object, but a field was missing, wrong-shape, or failed enum membership. */
  INVALID_FIELD("invalid_field"),

  /**
   * The row is a pre-RFC-101 failure whose detail was logged-only and never
   * persisted, backfilled by migration 0035. Read-only/historical: the write
   * path (`JsonParseFailure.category`) never produces it, so it appears only on
   * rows that predate failure capture. Not a member of `fit_lens_runs`' CHECK
   * (that table postdates capture and has no such rows).
   */
  UNRECORDED("unrecorded"),
  ;

  companion object {
    fun fromValue(value: String): JsonParseFailureCategory? = entries.find { it.value == value }
  }
}
