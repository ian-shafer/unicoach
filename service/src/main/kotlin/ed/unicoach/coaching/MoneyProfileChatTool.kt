package ed.unicoach.coaching

import ed.unicoach.coaching.moneyprofile.FieldUpdate
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.moneyprofile.MoneyProfileUpdate
import ed.unicoach.coaching.moneyprofile.UpsertMoneyProfileResult
import ed.unicoach.db.models.DependencyStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.MoneyProfile
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.putDependency
import ed.unicoach.db.models.putIncomeBand
import ed.unicoach.db.models.putLivingPlan
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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

  /**
   * The input fields this tool accepts, built once and put into [definition]
   * below -- so the schema the model is given and the guard that rejects unknown
   * keys ([knownFields]) are the SAME object rather than two readings of one,
   * and nothing has to be traversed or cast to recover it.
   */
  private val inputProperties: JsonObject =
    buildJsonObject {
      putJsonObject("income_band") {
        put("type", "string")
        putJsonArray("enum") { IncomeBand.entries.forEach { add(JsonPrimitive(it.value)) } }
        put(
          "description",
          "The household income band the student shared: " +
            IncomeBand.entries.joinToString(", ") { "${it.value} (${it.bracket})" } + ".",
        )
      }
      putDeclinedFlag("income_band_declined", "share their household income band")
      putJsonObject("residency_state") {
        put("type", "string")
        put("description", "Two-letter USPS state of residency the student shared (e.g. \"CA\").")
      }
      putDeclinedFlag("residency_declined", "share their state of residency")
      putJsonObject("living_plan") {
        put("type", "string")
        putJsonArray("enum") { LivingArrangement.entries.forEach { add(JsonPrimitive(it.value)) } }
        put(
          "description",
          "Where the student plans to live when they have the choice: " +
            LivingArrangement.entries.joinToString(", ") { "${it.value} (${it.label})" } +
            ". This is their usual plan, not a claim about any one school; a school they have " +
            "decided differently about carries its own plan on the college list.",
        )
      }
      putDeclinedFlag("living_plan_declined", "say where they plan to live")
      putJsonObject("dependency") {
        put("type", "string")
        putJsonArray("enum") { DependencyStatus.entries.forEach { add(JsonPrimitive(it.value)) } }
        put(
          "description",
          "Whether the student is dependent or independent for federal student aid: " +
            DependencyStatus.entries.joinToString(", ") { "${it.value} (${it.label})" } +
            ". Most students applying straight from high school are dependent; the answer picks " +
            "which federal loan limits and which maximum-Pell income test apply to them.",
        )
      }
      putDeclinedFlag("dependency_declined", "say whether they are dependent or independent for federal aid")
    }

  override val definition: JsonObject =
    buildJsonObject {
      put("name", TOOL_NAME)
      put("description", DESCRIPTION)
      putJsonObject("input_schema") {
        put("type", "object")
        put("properties", inputProperties)
        // All fields optional: a call carries only what the student just said.
        putJsonArray("required") {}
      }
    }

  /**
   * The input fields this tool accepts, read off the very object the schema
   * publishes rather than listed a second time.
   *
   * The schema IS the contract the model is given, so a property added there and
   * forgotten in a hand-kept reject-list would have the tool refuse a call it
   * advertised -- and a stale entry would mute the unknown-field guard for a key
   * nobody accepts. Read off [inputProperties] itself, so there is no key to
   * look up and no JSON node to cast on the way.
   */
  private val knownFields: Set<String> = inputProperties.keys

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
    unknownFieldsReason(input, knownFields)?.let { return ParsedInput.Invalid(it) }

    val incomeUpdate =
      when (val parsed = getIncomeUpdate(input)) {
        is FieldParse.Ok -> parsed.update
        is FieldParse.Invalid -> return ParsedInput.Invalid(parsed.reason)
      }
    val residencyUpdate =
      when (val parsed = getResidencyUpdate(input)) {
        is FieldParse.Ok -> parsed.update
        is FieldParse.Invalid -> return ParsedInput.Invalid(parsed.reason)
      }
    val livingUpdate =
      when (val parsed = getLivingPlanUpdate(input)) {
        is FieldParse.Ok -> parsed.update
        is FieldParse.Invalid -> return ParsedInput.Invalid(parsed.reason)
      }
    val dependencyUpdate =
      when (val parsed = getDependencyUpdate(input)) {
        is FieldParse.Ok -> parsed.update
        is FieldParse.Invalid -> return ParsedInput.Invalid(parsed.reason)
      }

    if (incomeUpdate == null && residencyUpdate == null && livingUpdate == null && dependencyUpdate == null) {
      return ParsedInput.Invalid("nothing to update: provide a value or a decline for at least one field")
    }

    return ParsedInput.Ok(
      MoneyProfileUpdate(
        income = incomeUpdate,
        residency = residencyUpdate,
        living = livingUpdate,
        dependency = dependencyUpdate,
      ),
    )
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

  /**
   * One declinable field read whole: the value key and its literal-true
   * decline flag, checked against each other and folded into one write -- the
   * shared shape of all four money-profile fields (RFC 134 tool contract,
   * RFC 159). The decline flag is literal-true: a `false` is a malformed call
   * rather than "leave it alone", which is what omitting the key already says.
   */
  private fun <T : Any> getDeclinableUpdate(
    input: JsonObject,
    valueKey: String,
    declineKey: String,
    parse: (String) -> T?,
    invalidValueReason: (String) -> String = { "unknown $valueKey value: [$it]" },
  ): FieldParse<T> {
    val raw =
      when (val read = getString(input, valueKey)) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return FieldParse.Invalid(read.reason)
      }
    val declined =
      when (val read = getBoolean(input, declineKey)) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return FieldParse.Invalid(read.reason)
      }
    if (declined == false) {
      return FieldParse.Invalid("$declineKey must be true when present; omit it to leave the field unchanged")
    }
    if (raw != null && declined == true) {
      return FieldParse.Invalid("$valueKey and $declineKey cannot both be set in one call")
    }
    return when {
      raw != null -> {
        parse(raw)?.let { FieldParse.Ok(FieldUpdate.Set(it)) }
          ?: FieldParse.Invalid(invalidValueReason(raw))
      }

      declined == true -> {
        FieldParse.Ok(FieldUpdate.Decline)
      }

      else -> {
        FieldParse.Ok(null)
      }
    }
  }

  private fun getIncomeUpdate(input: JsonObject): FieldParse<IncomeBand> =
    getDeclinableUpdate(input, "income_band", "income_band_declined", { IncomeBand.fromValue(it) })

  /** Residency is the one field with a normalizer beside its parse, and the one custom rejection wording. */
  private fun getResidencyUpdate(input: JsonObject): FieldParse<String> =
    getDeclinableUpdate(input, "residency_state", "residency_declined", { MoneyProfileService.parseResidencyState(it) }) {
      "residency_state must be a two-letter US state postal code, got: [$it]"
    }

  private fun getLivingPlanUpdate(input: JsonObject): FieldParse<LivingArrangement> =
    getDeclinableUpdate(input, "living_plan", "living_plan_declined", { LivingArrangement.fromValue(it) })

  private fun getDependencyUpdate(input: JsonObject): FieldParse<DependencyStatus> =
    getDeclinableUpdate(input, "dependency", "dependency_declined", { DependencyStatus.fromValue(it) })

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
        put("living_plan_status", profile.livingPlanStatus.value)
        // Through [putLivingPlan], the pair's one emitter: the wire name is a
        // key, never something to read out to a family.
        profile.livingPlan?.let { putLivingPlan(it) }
        put("dependency_status", profile.dependencyStatus.value)
        // Through [putDependency], the same one-emitter rule (RFC 159).
        profile.dependency?.let { putDependency(it) }
      }
    }

  companion object {
    private val logger = LoggerFactory.getLogger(MoneyProfileChatTool::class.java)

    const val TOOL_NAME = "update_money_profile"

    // The ethos contract rides the tool description verbatim (RFC 134): value
    // before ask, never force, declined stays declined.
    const val DESCRIPTION =
      "Record household income band, state of residency, where the student plans to live, and/or " +
        "whether they are dependent or independent for federal aid " +
        "that the student just shared or declined to share, so cost estimates can use their real numbers. " +
        "Ask about money only when cost comes up naturally in the conversation - never open with it, " +
        "and always explain what the answer unlocks (their real net price, in-state vs out-of-state tuition, " +
        "one price picture instead of three). " +
        "A living plan here is the student's usual plan; a school they have decided differently about " +
        "carries its own plan on the college list, so a correction about one school goes there. " +
        "If the student declines, record the decline and accept it without pushing. " +
        "Never re-ask a declined field unless the student reopens the topic themselves. " +
        "Setting a value and declining the same field in one call is an error. " +
        "The result echoes the full profile after the write."
  }
}

/**
 * One decline-flag schema block, stated once for every declinable field: a
 * literal-true boolean whose description names what the student [declinedTo].
 * The four flags must read identically -- the model learns the contract from
 * any one of them -- so the shared wording has exactly one author.
 */
private fun JsonObjectBuilder.putDeclinedFlag(
  key: String,
  declinedTo: String,
) {
  putJsonObject(key) {
    put("type", "boolean")
    put("const", true)
    put(
      "description",
      "Literal true when the student declined to $declinedTo; " +
        "omit the field entirely to leave it unchanged (false is an error).",
    )
  }
}
