package ed.unicoach.coaching

import ed.unicoach.coaching.moneyprofile.FieldUpdate
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.moneyprofile.MoneyProfileUpdate
import ed.unicoach.coaching.moneyprofile.UpsertMoneyProfileResult
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyProfile
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * The `update_money_profile` chat tool (RFC 134): the coach's write path into
 * the student's money profile when the student volunteers or declines money
 * facts mid-conversation. Total by the [ed.unicoach.chat.ChatTool] contract — malformed input,
 * a value+decline conflict, or an unknown enum value returns a structured
 * `{ "error": ... }` object the model reads, never a throw. A successful write
 * echoes the full post-write profile so the coach's next message reflects it.
 *
 * A thin input adapter by design: [execute] only orchestrates parse -> write ->
 * render; the field semantics (residency normalization, the tri-state fold)
 * live in [MoneyProfileService], the single owner shared with REST.
 */
class MoneyProfileChatTool(
  private val service: MoneyProfileService,
) : StudentScopedChatTool() {
  override val name: String = TOOL_NAME

  override val definition: JsonObject =
    buildJsonObject {
      put("name", TOOL_NAME)
      put("description", DESCRIPTION)
      putJsonObject("input_schema") {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("income_band") {
            put("type", "string")
            putJsonArray("enum") { IncomeBand.entries.forEach { add(JsonPrimitive(it.value)) } }
            put(
              "description",
              "The household income band the student shared: " +
                IncomeBand.entries.joinToString(", ") { "${it.value} (${it.bracket})" } + ".",
            )
          }
          putJsonObject("income_band_declined") {
            put("type", "boolean")
            put("const", true)
            put(
              "description",
              "Literal true when the student declined to share their household income band; " +
                "omit the field entirely to leave it unchanged (false is an error).",
            )
          }
          putJsonObject("residency_state") {
            put("type", "string")
            put("description", "Two-letter USPS state of residency the student shared (e.g. \"CA\").")
          }
          putJsonObject("residency_declined") {
            put("type", "boolean")
            put("const", true)
            put(
              "description",
              "Literal true when the student declined to share their state of residency; " +
                "omit the field entirely to leave it unchanged (false is an error).",
            )
          }
        }
        // All fields optional: a call carries only what the student just said.
        putJsonArray("required") {}
      }
    }

  override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    val update =
      when (val parsed = parseInput(input)) {
        is ParsedInput.Ok -> parsed.update
        is ParsedInput.Invalid -> return errorObject(parsed.reason)
      }

    val outcome =
      service
        .upsert(studentId, update)
        .getOrElse { e ->
          logger.warn("tool [{}] money profile write failed for student=[{}]", TOOL_NAME, studentId.value, e)
          return errorObject("money profile write failed")
        }

    return when (outcome) {
      is UpsertMoneyProfileResult.Success -> profileObject(outcome.profile)
      UpsertMoneyProfileResult.StudentNotFound -> errorObject("student not found")
    }
  }

  /** The parse outcome for one tool call: a typed [MoneyProfileUpdate] or the reason it is malformed. */
  private sealed interface ParsedInput {
    data class Ok(
      val update: MoneyProfileUpdate,
    ) : ParsedInput

    data class Invalid(
      val reason: String,
    ) : ParsedInput
  }

  /** Maps the wire shape onto the service's [FieldUpdate] vocabulary; every malformation is an [ParsedInput.Invalid]. */
  private fun parseInput(input: JsonObject): ParsedInput {
    val unknown = input.keys - KNOWN_FIELDS
    if (unknown.isNotEmpty()) {
      return ParsedInput.Invalid("unknown field(s): ${unknown.sorted().joinToString(", ") { "[$it]" }}")
    }

    val incomeBandRaw = optString(input, "income_band") ?: return ParsedInput.Invalid("income_band must be a string")
    val incomeDeclined =
      optBoolean(input, "income_band_declined") ?: return ParsedInput.Invalid("income_band_declined must be a boolean")
    val residencyRaw = optString(input, "residency_state") ?: return ParsedInput.Invalid("residency_state must be a string")
    val residencyDeclined =
      optBoolean(input, "residency_declined") ?: return ParsedInput.Invalid("residency_declined must be a boolean")

    // The decline flags are literal-true (RFC 134 tool contract): `false` is
    // not "don't decline", it is a malformed call -- omission is the only way
    // to leave a field unchanged.
    if (incomeDeclined.value == false) {
      return ParsedInput.Invalid("income_band_declined must be true when present; omit it to leave the field unchanged")
    }
    if (residencyDeclined.value == false) {
      return ParsedInput.Invalid("residency_declined must be true when present; omit it to leave the field unchanged")
    }

    if (incomeBandRaw.value != null && incomeDeclined.value == true) {
      return ParsedInput.Invalid("income_band and income_band_declined cannot both be set in one call")
    }
    if (residencyRaw.value != null && residencyDeclined.value == true) {
      return ParsedInput.Invalid("residency_state and residency_declined cannot both be set in one call")
    }

    val incomeUpdate =
      when (val parsed = parseIncomeUpdate(incomeBandRaw.value, incomeDeclined.value == true)) {
        is FieldParse.Ok -> parsed.update
        is FieldParse.Invalid -> return ParsedInput.Invalid(parsed.reason)
      }
    val residencyUpdate =
      when (val parsed = parseResidencyUpdate(residencyRaw.value, residencyDeclined.value == true)) {
        is FieldParse.Ok -> parsed.update
        is FieldParse.Invalid -> return ParsedInput.Invalid(parsed.reason)
      }

    if (incomeUpdate == null && residencyUpdate == null) {
      return ParsedInput.Invalid("nothing to update: provide a value or a decline for at least one field")
    }

    return ParsedInput.Ok(MoneyProfileUpdate(income = incomeUpdate, residency = residencyUpdate))
  }

  /** The parse outcome for one field: its [FieldUpdate] (null: untouched) or the reason it is malformed. */
  private sealed interface FieldParse<out T> {
    data class Ok<T>(
      val update: FieldUpdate<T>?,
    ) : FieldParse<T>

    data class Invalid(
      val reason: String,
    ) : FieldParse<Nothing>
  }

  private fun parseIncomeUpdate(
    raw: String?,
    declined: Boolean,
  ): FieldParse<IncomeBand> =
    when {
      raw != null -> {
        IncomeBand.fromValue(raw)?.let { FieldParse.Ok(FieldUpdate.Set(it)) }
          ?: FieldParse.Invalid("unknown income_band value: [$raw]")
      }

      declined -> {
        FieldParse.Ok(FieldUpdate.Decline)
      }

      else -> {
        FieldParse.Ok(null)
      }
    }

  private fun parseResidencyUpdate(
    raw: String?,
    declined: Boolean,
  ): FieldParse<String> =
    when {
      raw != null -> {
        MoneyProfileService.parseResidencyState(raw)?.let { FieldParse.Ok(FieldUpdate.Set(it)) }
          ?: FieldParse.Invalid("residency_state must be a two-letter US state postal code, got: [$raw]")
      }

      declined -> {
        FieldParse.Ok(FieldUpdate.Decline)
      }

      else -> {
        FieldParse.Ok(null)
      }
    }

  /** The full post-write profile echo: per-field status, value present iff answered. */
  private fun profileObject(profile: MoneyProfile): JsonObject =
    buildJsonObject {
      putJsonObject("money_profile") {
        put("income_band_status", profile.incomeBandStatus.value)
        profile.incomeBand?.let { put("income_band", it.value) }
        put("residency_status", profile.residencyStatus.value)
        profile.residencyState?.let { put("residency_state", it) }
      }
    }

  private fun errorObject(reason: String): JsonObject = buildJsonObject { put("error", reason) }

  /** An optional field: [value] null when absent; an unwrapped null from a reader signals a type mismatch. */
  private class Opt<T>(
    val value: T?,
  )

  private fun optString(
    input: JsonObject,
    field: String,
  ): Opt<String>? {
    val element = input[field] ?: return Opt(null)
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return Opt(primitive.content)
  }

  private fun optBoolean(
    input: JsonObject,
    field: String,
  ): Opt<Boolean>? {
    val element = input[field] ?: return Opt(null)
    val primitive = element as? JsonPrimitive ?: return null
    // A JSON string "true" is not a boolean: the input contract is strict.
    if (primitive.isString) return null
    return primitive.booleanOrNull?.let { Opt(it) }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(MoneyProfileChatTool::class.java)

    const val TOOL_NAME = "update_money_profile"

    // The ethos contract rides the tool description verbatim (RFC 134): value
    // before ask, never force, declined stays declined.
    const val DESCRIPTION =
      "Record household income band and/or state of residency that the student just shared " +
        "or declined to share, so cost estimates can use their real numbers. " +
        "Ask about money only when cost comes up naturally in the conversation - never open with it, " +
        "and always explain what the answer unlocks (their real net price, in-state vs out-of-state tuition). " +
        "If the student declines, record the decline and accept it without pushing. " +
        "Never re-ask a declined field unless the student reopens the topic themselves. " +
        "Setting a value and declining the same field in one call is an error. " +
        "The result echoes the full profile after the write."

    private val KNOWN_FIELDS = setOf("income_band", "income_band_declined", "residency_state", "residency_declined")
  }
}
