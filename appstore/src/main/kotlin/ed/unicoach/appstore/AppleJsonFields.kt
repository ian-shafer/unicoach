package ed.unicoach.appstore

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Reading fields off Apple-supplied JSON — the one place in `:appstore` that
 * knows how a wire field is reached and how a missing one is refused.
 *
 * Every reader in this module (the Server API's decoded transactions, the
 * notification verifier's payloads) faces the same concern: Apple hands over an
 * untyped [JsonObject], and a field this integration relies on going missing
 * means the shape changed. That is bug-grade, not a domain outcome, so it
 * throws rather than degrading — a new Apple payload shape must be looked at,
 * not guessed at. [subject] names whose JSON it was, so the message stands on
 * its own without the reader tracing back to the call site.
 *
 * The refusal carries the offending object itself, not just the field name.
 * These payloads arrive inside a verified JWS that is held in memory and never
 * persisted or otherwise logged, so the exception message is the entire
 * forensic record of the new shape — without it there is nothing left to look
 * at once the request is over.
 */
internal fun JsonObject.optionalText(field: String): String? = this[field]?.jsonPrimitive?.content

/** @see optionalText */
internal fun JsonObject.requiredText(
  field: String,
  subject: String,
): String =
  optionalText(field)
    ?: throw IllegalArgumentException(missingField(subject, field, "a text", this))

/** @see optionalText */
internal fun JsonObject.requiredNumber(
  field: String,
  subject: String,
): Long =
  this[field]?.jsonPrimitive?.longOrNull
    ?: throw IllegalArgumentException(missingField(subject, field, "an integer", this))

private fun missingField(
  subject: String,
  field: String,
  kind: String,
  json: JsonObject,
): String =
  "$subject has no [$field] holding $kind value — a new Apple payload shape must be looked at, not guessed at. " +
    "Raw: [$json]"
