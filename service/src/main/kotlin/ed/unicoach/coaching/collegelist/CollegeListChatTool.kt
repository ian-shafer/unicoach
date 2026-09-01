package ed.unicoach.coaching.collegelist

import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.putLivingPlan
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The `update_college_list` chat tool (RFC 136): the coach's write path into
 * the student's college list when the student asks for a school to be added,
 * restatused, or removed — or agrees to the coach's offer. Total by the
 * [ed.unicoach.chat.ChatTool] contract — malformed input, an unknown enum
 * value, or a named service outcome returns a structured `{ "error": ... }`
 * object the model reads, never a throw. A successful write echoes the full
 * post-write active list so the coach's next message reflects it.
 *
 * A thin adapter by design ([ed.unicoach.coaching.MoneyProfileChatTool]'s
 * shape): [execute] only orchestrates parse -> resolve -> write -> render; the
 * list semantics (active uniqueness, OCC, the `reasons` CHECK) live in
 * [CollegeListService], the single owner shared with REST. All model-facing
 * ids are college ids — entry ids and OCC versions are REST/iOS concerns and
 * never cross the model boundary.
 */
class CollegeListChatTool(
  private val service: CollegeListService,
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
      putJsonObject("action") {
        put("type", "string")
        putJsonArray("enum") { Action.entries.forEach { add(JsonPrimitive(it.value)) } }
        put(
          "description",
          "What to do: add a school to the list, update a listed school's status and/or reasons, " +
            "or remove a school from the list.",
        )
      }
      putJsonObject("college_id") {
        put("type", "string")
        put(
          "description",
          "The college's identifier: the `college_id` field of a college search result or a college cost " +
            "result, copied verbatim. Never construct or guess one.",
        )
      }
      putJsonObject("status") {
        put("type", "string")
        putJsonArray("enum") { CollegeListEntryStatus.entries.forEach { add(JsonPrimitive(it.value)) } }
        put(
          "description",
          "Where the student stands with this school. For add, omitted defaults to considering. " +
            "For update, omitted leaves the status unchanged. An error on remove.",
        )
      }
      putJsonObject("reasons") {
        put("type", "string")
        put(
          "description",
          "The student's own words for why this school. For update, omitted leaves the reasons " +
            "unchanged. An error on remove.",
        )
      }
      putJsonObject("living_plan") {
        put("type", "string")
        putJsonArray("enum") { LivingArrangement.entries.forEach { add(JsonPrimitive(it.value)) } }
        put(
          "description",
          "Where the student plans to live AT THIS SCHOOL, when it differs from their usual plan: " +
            LivingArrangement.entries.joinToString(", ") { "${it.value} (${it.label})" } +
            ". Set this only when the student has said so about this school. Omitted leaves it " +
            "unchanged. An error on remove.",
        )
      }
      putJsonObject("living_plan_clear") {
        put("type", "boolean")
        put("const", true)
        put(
          "description",
          "Literal true to drop this school's own living plan and go back to the student's usual " +
            "plan; omit the field entirely to leave it unchanged (false is an error). An error on remove.",
        )
      }
    }

  override val definition: JsonObject =
    buildJsonObject {
      put("name", TOOL_NAME)
      put("description", DESCRIPTION)
      putJsonObject("input_schema") {
        put("type", "object")
        put("properties", inputProperties)
        putJsonArray("required") {
          add(JsonPrimitive("action"))
          add(JsonPrimitive("college_id"))
        }
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
    val parsed =
      when (val outcome = parseInput(input)) {
        is ParsedInput.Ok -> outcome
        is ParsedInput.Invalid -> return errorObject(outcome.reason)
      }

    // All three actions resolve the active entry for (student, college) by
    // scanning the active list -- small by construction (a student curates it
    // by hand), and the same read renders the college names the errors use.
    val activeList =
      service
        .listActiveWithNames(studentId)
        .getOrElse { e ->
          // The failure precedes any write: never report a read as a failed write.
          logger.warn("tool [{}] college list read failed for student=[{}]", TOOL_NAME, studentId.value, e)
          return errorObject(READ_FAILED_REASON)
        }
    val existing = activeList.find { it.entry.collegeId == parsed.collegeId }

    return when (parsed.action) {
      Action.ADD -> executeAdd(studentId, parsed, existing)
      Action.UPDATE -> executeUpdate(studentId, parsed, existing)
      Action.REMOVE -> executeRemove(studentId, parsed, existing)
    }
  }

  private suspend fun executeAdd(
    studentId: StudentId,
    parsed: ParsedInput.Ok,
    existing: EntryWithCollegeName?,
  ): JsonObject {
    if (existing != null) {
      return errorObject("[${existing.collegeName}] is already on the list; use action [update] to change it")
    }

    val outcome =
      service
        .addToList(
          studentId,
          parsed.collegeId,
          parsed.status ?: CollegeListEntryStatus.CONSIDERING,
          parsed.reasons,
          // Exhaustive, not a cast: [parseInput] has already refused a clear on
          // an add, and a case added later must fail here loudly rather than
          // vanish into "no override".
          when (val plan = parsed.livingPlan) {
            is LivingPlanUpdate.Set -> plan.plan
            LivingPlanUpdate.Clear -> error("a clear on an add is refused in parseInput; this branch is unreachable")
            null -> null
          },
          emptyList(),
        ).getOrElse { e -> return writeFailed(studentId, Action.ADD, e) }

    return when (outcome) {
      is AddToListResult.Success -> {
        listEcho(studentId)
      }

      // Reachable only against a concurrent writer that listed the school
      // between the resolve above and this write.
      AddToListResult.AlreadyOnList -> {
        errorObject(CONFLICT_REASON)
      }

      AddToListResult.CollegeNotFound -> {
        errorObject("no college with id [${parsed.collegeId.value}]")
      }

      AddToListResult.InvalidReasons -> {
        errorObject(INVALID_REASONS_REASON)
      }

      // Unreachable: this tool never links observations. Logged loudly so a
      // future regression cannot hide behind the generic failure message.
      is AddToListResult.ObservationNotFound -> {
        impossibleOutcome(
          studentId,
          Action.ADD,
          "ObservationNotFound(${outcome.observationId.value})",
        )
      }
    }
  }

  private suspend fun executeUpdate(
    studentId: StudentId,
    parsed: ParsedInput.Ok,
    existing: EntryWithCollegeName?,
  ): JsonObject {
    if (existing == null) return errorObject(NOT_ON_LIST_REASON)
    val entry = existing.entry

    val outcome =
      service
        .updateEntry(
          studentId,
          entry.id,
          entry.version,
          // Omitted = unchanged for both fields. Clearing reasons to null via
          // chat is deliberately unsupported (RFC 136 non-goal): a student who
          // wants the note gone restates it or removes the entry.
          parsed.status ?: entry.status,
          parsed.reasons ?: entry.reasons,
          resolveLivingPlan(parsed, entry),
          emptyList(),
        ).getOrElse { e -> return writeFailed(studentId, Action.UPDATE, e) }

    return when (outcome) {
      is UpdateEntryResult.Success -> {
        listEcho(studentId)
      }

      // Both mean a concurrent writer moved the entry after the resolve above
      // (removal reads as NotFound): the read is stale either way.
      UpdateEntryResult.NotFound, UpdateEntryResult.VersionConflict -> {
        errorObject(CONFLICT_REASON)
      }

      UpdateEntryResult.InvalidReasons -> {
        errorObject(INVALID_REASONS_REASON)
      }

      // Unreachable: this tool never links observations. Logged loudly so a
      // future regression cannot hide behind the generic failure message.
      is UpdateEntryResult.ObservationNotFound -> {
        impossibleOutcome(
          studentId,
          Action.UPDATE,
          "ObservationNotFound(${outcome.observationId.value})",
        )
      }
    }
  }

  /**
   * The override to store on an update: omitted leaves this school's plan alone,
   * an explicit clear writes NULL -- "no override, use the usual plan" (RFC 152
   * D2a).
   *
   * Named rather than dropped into an argument position, so the call site reads
   * like its two siblings and the write rule is stated once, where it can be
   * read.
   */
  private fun resolveLivingPlan(
    parsed: ParsedInput.Ok,
    entry: CollegeListEntry,
  ): LivingArrangement? =
    when (val plan = parsed.livingPlan) {
      is LivingPlanUpdate.Set -> plan.plan
      LivingPlanUpdate.Clear -> null
      null -> entry.livingPlan
    }

  private suspend fun executeRemove(
    studentId: StudentId,
    parsed: ParsedInput.Ok,
    existing: EntryWithCollegeName?,
  ): JsonObject {
    if (existing == null) return errorObject(NOT_ON_LIST_REASON)
    val entry = existing.entry

    val outcome =
      service
        .removeFromList(studentId, entry.id, entry.version)
        .getOrElse { e -> return writeFailed(studentId, Action.REMOVE, e) }

    return when (outcome) {
      is RemoveEntryResult.Success -> listEcho(studentId)
      RemoveEntryResult.NotFound, RemoveEntryResult.VersionConflict -> errorObject(CONFLICT_REASON)
    }
  }

  /** The parse outcome for one tool call: the typed action and fields, or the reason the call is malformed. */
  private sealed interface ParsedInput {
    data class Ok(
      val action: Action,
      val collegeId: CollegeId,
      val status: CollegeListEntryStatus?,
      val reasons: String?,
      /**
       * The living-plan override the call asks for, or null when the call says
       * nothing about it. Sealed rather than a nullable [LivingArrangement],
       * because "leave it alone" and "drop it back to the usual plan" are two
       * different writes onto the same nullable column and a bare null cannot
       * tell them apart.
       */
      val livingPlan: LivingPlanUpdate?,
    ) : ParsedInput

    data class Invalid(
      val reason: String,
    ) : ParsedInput
  }

  /** What one call asks of this school's living-plan override; see [ParsedInput.Ok.livingPlan]. */
  private sealed interface LivingPlanUpdate {
    data class Set(
      val plan: LivingArrangement,
    ) : LivingPlanUpdate

    data object Clear : LivingPlanUpdate
  }

  /** Maps the wire shape onto the typed action and fields; every malformation is a [ParsedInput.Invalid]. */
  private fun parseInput(input: JsonObject): ParsedInput {
    unknownFieldsReason(input, knownFields)?.let { return ParsedInput.Invalid(it) }

    val actionRaw =
      when (val read = getString(input, "action")) {
        is OptRead.Present -> read.value
        OptRead.Absent -> return ParsedInput.Invalid("action is required")
        is OptRead.Mismatch -> return ParsedInput.Invalid(read.reason)
      }
    val action =
      Action.fromValue(actionRaw)
        ?: return ParsedInput.Invalid("unknown action value: [$actionRaw]")

    val collegeIdRaw =
      when (val read = getString(input, "college_id")) {
        is OptRead.Present -> read.value
        OptRead.Absent -> return ParsedInput.Invalid("college_id is required")
        is OptRead.Mismatch -> return ParsedInput.Invalid(read.reason)
      }
    val collegeId =
      try {
        CollegeId(UUID.fromString(collegeIdRaw))
      } catch (_: IllegalArgumentException) {
        return ParsedInput.Invalid("college_id is not a uuid: [$collegeIdRaw]")
      }

    val statusRaw =
      when (val read = getString(input, "status")) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return ParsedInput.Invalid(read.reason)
      }
    val status =
      statusRaw?.let {
        CollegeListEntryStatus.fromValue(it)
          ?: return ParsedInput.Invalid("unknown status value: [$it]")
      }

    val reasons =
      when (val read = getString(input, "reasons")) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return ParsedInput.Invalid(read.reason)
      }

    val livingPlanUpdate =
      when (val parsed = parseLivingPlanUpdate(input)) {
        is LivingPlanParse.Ok -> parsed.update
        is LivingPlanParse.Invalid -> return ParsedInput.Invalid(parsed.reason)
      }

    when (action) {
      Action.REMOVE -> {
        if (status != null) return ParsedInput.Invalid("status cannot be set on a remove")
        if (reasons != null) return ParsedInput.Invalid("reasons cannot be set on a remove")
        // One refusal per KEY, not one for the type both keys fold into: a call
        // carrying living_plan_clear must not be told living_plan is the
        // problem, or the caller retries the same call.
        when (livingPlanUpdate) {
          is LivingPlanUpdate.Set -> return ParsedInput.Invalid("living_plan cannot be set on a remove")
          LivingPlanUpdate.Clear -> return ParsedInput.Invalid("living_plan_clear cannot be set on a remove")
          null -> Unit
        }
      }

      Action.UPDATE -> {
        if (status == null && reasons == null && livingPlanUpdate == null) {
          return ParsedInput.Invalid("nothing to update: provide a status, reasons and/or a living plan")
        }
      }

      Action.ADD -> {
        // Status, reasons and a plan are optional on an add. A clear is not:
        // there is no override to drop yet, so the call asks for something this
        // action cannot do. Refused by name rather than accepted and dropped,
        // which would report a write that never happened.
        if (livingPlanUpdate is LivingPlanUpdate.Clear) {
          return ParsedInput.Invalid("living_plan_clear cannot be set on an add; there is no plan to clear yet")
        }
      }
    }

    return ParsedInput.Ok(action, collegeId, status, reasons, livingPlanUpdate)
  }

  /** The parse outcome for this school's override: the write it asks for (null: untouched), or why it is malformed. */
  private sealed interface LivingPlanParse {
    data class Ok(
      val update: LivingPlanUpdate?,
    ) : LivingPlanParse

    data class Invalid(
      val reason: String,
    ) : LivingPlanParse
  }

  /**
   * `living_plan` and `living_plan_clear` read, checked against each other, and
   * folded into one write -- the twin of `MoneyProfileChatTool`'s own
   * `parseLivingPlanUpdate`, so [parseInput] keeps the two lines it has for
   * every other field instead of absorbing a whole field ladder.
   *
   * `clear` is literal-true (the tool contract): a `false` is malformed rather
   * than "leave it alone", which is what omitting the key already says.
   */
  private fun parseLivingPlanUpdate(input: JsonObject): LivingPlanParse {
    val plan =
      when (val read = getString(input, "living_plan")) {
        is OptRead.Present -> {
          LivingArrangement.fromValue(read.value)
            ?: return LivingPlanParse.Invalid("unknown living_plan value: [${read.value}]")
        }

        OptRead.Absent -> {
          null
        }

        is OptRead.Mismatch -> {
          return LivingPlanParse.Invalid(read.reason)
        }
      }
    val clear =
      when (val read = getBoolean(input, "living_plan_clear")) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return LivingPlanParse.Invalid(read.reason)
      }
    if (clear == false) {
      return LivingPlanParse.Invalid("living_plan_clear must be true when present; omit it to leave the field unchanged")
    }
    if (plan != null && clear == true) {
      return LivingPlanParse.Invalid("living_plan and living_plan_clear cannot both be set in one call")
    }
    return LivingPlanParse.Ok(
      when {
        plan != null -> LivingPlanUpdate.Set(plan)
        clear == true -> LivingPlanUpdate.Clear
        else -> null
      },
    )
  }

  /** The full post-write active list echo, each school named for the coach's next message. */
  private suspend fun listEcho(studentId: StudentId): JsonObject {
    val list =
      service
        .listActiveWithNames(studentId)
        .getOrElse { e ->
          // The write itself committed; only the read-back failed. Never
          // report a successful write as a failed one.
          logger.warn("tool [{}] post-write list read failed for student=[{}]", TOOL_NAME, studentId.value, e)
          return errorObject(ECHO_FAILED_REASON)
        }
    return buildJsonObject {
      putJsonArray("college_list") {
        list.forEach { row ->
          add(
            buildJsonObject {
              put(
                "college_id",
                row.entry.collegeId.value
                  .toString(),
              )
              put("name", row.collegeName)
              put("status", row.entry.status.value)
              row.entry.reasons?.let { put("reasons", it) }
              // Absent, never null: a school with no override simply says
              // nothing, and the coach falls back to the usual plan.
              row.entry.livingPlan?.let { putLivingPlan(it) }
            },
          )
        }
      }
      put("count", list.size)
    }
  }

  /** An outcome this tool's calls make impossible; a sighting is a regression, logged as an error. */
  private fun impossibleOutcome(
    studentId: StudentId,
    action: Action,
    outcome: String,
  ): JsonObject {
    logger.error(
      "tool [{}] impossible [{}] outcome [{}] for student=[{}]: this tool never links observations",
      TOOL_NAME,
      action.value,
      outcome,
      studentId.value,
    )
    return errorObject(WRITE_FAILED_REASON)
  }

  private fun writeFailed(
    studentId: StudentId,
    action: Action,
    e: Throwable,
  ): JsonObject {
    logger.warn("tool [{}] college list [{}] failed for student=[{}]", TOOL_NAME, action.value, studentId.value, e)
    return errorObject(WRITE_FAILED_REASON)
  }

  /** The closed action vocabulary on the wire. */
  private enum class Action(
    val value: String,
  ) {
    ADD("add"),
    UPDATE("update"),
    REMOVE("remove"),
    ;

    companion object {
      fun fromValue(value: String): Action? = entries.find { it.value == value }
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(CollegeListChatTool::class.java)

    const val TOOL_NAME = "update_college_list"

    // The ethos contract rides the tool description verbatim (RFC 134's
    // pattern): offer with the value named, never write without the student's
    // say-so, honour a change of mind immediately.
    const val DESCRIPTION =
      "Maintain the student's college list - the schools they are considering, applying to, " +
        "admitted to, or rejected from - when the student asks for it or agrees to it. " +
        "When a student shows real interest in a school, offer to add it and say what tracking " +
        "unlocks (their real cost numbers, their options in one place); never add, change, or remove an " +
        "entry without the student's say-so, and never push if they'd rather not track a school. " +
        "The student can change a school's status or remove it at any time - honour that " +
        "immediately and without comment. Use the college id from a college search or cost " +
        "result. A school can also carry its own living plan - where the student plans to live at " +
        "THAT school - when it differs from their usual plan: set it only when the student says so, " +
        "and clear it to go back to their usual plan. The result echoes the full list after the write."

    private const val NOT_ON_LIST_REASON = "that school is not on the list; use action [add] to add it"
    private const val WRITE_FAILED_REASON = "college list write failed"
    private const val READ_FAILED_REASON = "college list read failed"
    private const val ECHO_FAILED_REASON = "the write succeeded, but reading the list back failed; read the list again"
    private const val CONFLICT_REASON = "the list changed underneath this call; read it again and retry"

    // Mirrors the `college_list_entries_reasons_length_check` /
    // `_not_empty_check` DB CHECKs (db/schema/0024): non-empty, at most
    // [MAX_REASONS_CHARS] chars. If the CHECKs change, this is what drifts.
    private const val MAX_REASONS_CHARS = 2048
    private const val INVALID_REASONS_REASON = "reasons must be non-empty and at most [$MAX_REASONS_CHARS] characters"
  }
}
