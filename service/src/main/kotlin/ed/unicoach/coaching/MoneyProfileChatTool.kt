package ed.unicoach.coaching

import ed.unicoach.coaching.moneyprofile.FieldUpdate
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.moneyprofile.MoneyProfileUpdate
import ed.unicoach.coaching.moneyprofile.UpsertMoneyProfileResult
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyProfile
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.putIncomeBand
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
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
    unknownFieldsReason(input, KNOWN_FIELDS)?.let { return ParsedInput.Invalid(it) }

    val incomeBandRaw =
      when (val read = getString(input, "income_band")) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return ParsedInput.Invalid(read.reason)
      }
    val incomeDeclined =
      when (val read = getBoolean(input, "income_band_declined")) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return ParsedInput.Invalid(read.reason)
      }
    val residencyRaw =
      when (val read = getString(input, "residency_state")) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return ParsedInput.Invalid(read.reason)
      }
    val residencyDeclined =
      when (val read = getBoolean(input, "residency_declined")) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return ParsedInput.Invalid(read.reason)
      }

    // The decline flags are literal-true (RFC 134 tool contract): `false` is
    // not "don't decline", it is a malformed call -- omission is the only way
    // to leave a field unchanged.
    if (incomeDeclined == false) {
      return ParsedInput.Invalid("income_band_declined must be true when present; omit it to leave the field unchanged")
    }
    if (residencyDeclined == false) {
      return ParsedInput.Invalid("residency_declined must be true when present; omit it to leave the field unchanged")
    }

    if (incomeBandRaw != null && incomeDeclined == true) {
      return ParsedInput.Invalid("income_band and income_band_declined cannot both be set in one call")
    }
    if (residencyRaw != null && residencyDeclined == true) {
      return ParsedInput.Invalid("residency_state and residency_declined cannot both be set in one call")
    }

    val incomeUpdate =
      when (val parsed = parseIncomeUpdate(incomeBandRaw, incomeDeclined == true)) {
        is FieldParse.Ok -> parsed.update
        is FieldParse.Invalid -> return ParsedInput.Invalid(parsed.reason)
      }
    val residencyUpdate =
      when (val parsed = parseResidencyUpdate(residencyRaw, residencyDeclined == true)) {
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

  /**
   * The full post-write profile echo: per-field status, value present iff
   * answered. An answered band echoes through [putIncomeBand], so the code
   * always arrives with its spoken dollar range (RFC 142) — this is the moment
   * right after the student stated their band, and the likeliest place for the
   * coach to read a bare code back to them.
   */
  private fun profileObject(profile: MoneyProfile): JsonObject =
    buildJsonObject {
      putJsonObject("money_profile") {
        put("income_band_status", profile.incomeBandStatus.value)
        profile.incomeBand?.let { putIncomeBand(it) }
        put("residency_status", profile.residencyStatus.value)
        profile.residencyState?.let { put("residency_state", it) }
      }
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
