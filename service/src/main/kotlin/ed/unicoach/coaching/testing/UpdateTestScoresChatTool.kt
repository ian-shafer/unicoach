package ed.unicoach.coaching.testing

import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.models.AdmissionTestScoreComponent
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * The `update_test_scores` chat tool (RFC 207): the coach's write path into the
 * student's own scores when she states, corrects, removes or declines them
 * mid-conversation. Total by the [ed.unicoach.chat.ChatTool] contract —
 * malformed input, an impossible score, a future sitting, or a value+decline
 * conflict returns a structured `{ "error": ... }` object the model reads,
 * never a throw. A successful write echoes the full post-write state so the
 * coach's next message reflects it.
 *
 * A thin input adapter by design: [execute] only orchestrates parse -> write ->
 * render; the field semantics (the published-scale gate, the future-sitting
 * refusal, the decline/record transaction) live in [TestScoreService].
 */
class UpdateTestScoresChatTool(
  private val service: TestScoreService,
) : StudentScopedChatTool() {
  override val name: String = TOOL_NAME

  private val inputProperties: JsonObject =
    buildJsonObject {
      putJsonObject("component") {
        put("type", "string")
        putJsonArray("enum") { AdmissionTestScoreComponent.entries.forEach { add(JsonPrimitive(it.value)) } }
        put(
          "description",
          "Which score she reported: " +
            AdmissionTestScoreComponent.entries.joinToString(", ") { "${it.value} (${it.label})" } + ".",
        )
      }
      putJsonObject("score") {
        put("type", "integer")
        put(
          "description",
          "The number she reported. SAT scores move in steps of ten (400-1600 total, 200-800 a section); ACT scores are whole points (1-36).",
        )
      }
      putJsonObject("taken_on") {
        put("type", "string")
        put(
          "description",
          "When she sat it, at whatever precision she knows: ${PartialDate.EXPECTED_FORM}. " +
            "A month with no day is fine; never ask for a day she has not said.",
        )
      }
      putDeclinedFlag()
      putJsonObject("remove") {
        put("type", "boolean")
        put("const", true)
        put(
          "description",
          "Set true to forget the score named by component + taken_on. The student asked to remove it; removed scores never come back in this conversation.",
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
        putJsonArray("required") {}
      }
    }

  private val knownFields: Set<String> = inputProperties.keys

  override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    val write =
      when (val parsed = parseInput(input)) {
        is ParsedInput.Ok -> parsed.write
        is ParsedInput.Invalid -> return errorObject(parsed.reason)
      }

    val outcome =
      service
        .write(studentId, write)
        .getOrElse { e ->
          logger.warn("tool [{}] test-score write failed for student=[{}]", TOOL_NAME, studentId.value, e)
          return errorObject("test score write failed")
        }

    return when (outcome) {
      is WriteTestScoresResult.Success -> scoresObject(outcome.scores)
      is WriteTestScoresResult.Rejected -> errorObject(outcome.reason)
      WriteTestScoresResult.StudentNotFound -> errorObject("student not found")
    }
  }

  private sealed interface ParsedInput {
    data class Ok(
      val write: TestScoreWrite,
    ) : ParsedInput

    data class Invalid(
      val reason: String,
    ) : ParsedInput
  }

  private fun parseInput(input: JsonObject): ParsedInput {
    unknownFieldsReason(input, knownFields)?.let { return ParsedInput.Invalid(it) }

    val component =
      when (val read = getString(input, "component")) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return ParsedInput.Invalid(read.reason)
      }
    val score =
      when (val read = readInt(input, "score")) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return ParsedInput.Invalid(read.reason)
      }
    val takenOn =
      when (val read = getString(input, "taken_on")) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return ParsedInput.Invalid(read.reason)
      }
    val remove =
      when (val read = getBoolean(input, "remove")) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return ParsedInput.Invalid(read.reason)
      }
    val declined =
      when (val read = getBoolean(input, "declined")) {
        is OptRead.Present -> read.value
        OptRead.Absent -> null
        is OptRead.Mismatch -> return ParsedInput.Invalid(read.reason)
      }

    if (declined == false) return ParsedInput.Invalid("declined must be true when present; omit it to leave the answer unchanged")
    if (remove == false) return ParsedInput.Invalid("remove must be true when present; omit it to leave the answer unchanged")

    val componentValue = component?.let { AdmissionTestScoreComponent.fromValue(it) }
    if (component != null && componentValue == null) {
      return ParsedInput.Invalid("unknown component value: [$component]")
    }

    val sitting =
      takenOn?.let {
        when (val parsed = PartialDate.parse(it)) {
          is ValidationResult.Valid -> parsed.value
          is ValidationResult.Invalid -> return ParsedInput.Invalid("taken_on must be ${PartialDate.EXPECTED_FORM}, got: [$it]")
        }
      }

    return when {
      declined == true -> {
        if (component != null || score != null || takenOn != null || remove == true) {
          ParsedInput.Invalid("declined cannot be set in the same call as a score, a date or a remove")
        } else {
          ParsedInput.Ok(TestScoreWrite.Decline)
        }
      }

      remove == true -> {
        if (score != null) return ParsedInput.Invalid("remove cannot be set in the same call as score")
        if (componentValue == null || sitting == null) {
          ParsedInput.Invalid("remove needs component and taken_on to name the score being removed")
        } else {
          ParsedInput.Ok(TestScoreWrite.Remove(componentValue, sitting))
        }
      }

      componentValue != null -> {
        if (score == null || sitting == null) {
          ParsedInput.Invalid("recording a score needs component, score and taken_on")
        } else {
          ParsedInput.Ok(TestScoreWrite.Record(componentValue, score, sitting))
        }
      }

      else -> {
        ParsedInput.Invalid("nothing to update: provide a score, a remove, or a decline")
      }
    }
  }

  /** One strict optional integer read; a present-but-not-an-integer field is a mismatch, never an absence. */
  private fun readInt(
    input: JsonObject,
    field: String,
  ): OptRead<Int> {
    val element: JsonElement = input[field] ?: return OptRead.Absent
    val primitive = element as? JsonPrimitive
    // A JSON string "1340" is not an integer: the input contract is strict, as
    // the boolean reader above is.
    if (primitive == null || primitive.isString || primitive.content.toIntOrNull() == null) {
      return OptRead.Mismatch("[$field] must be an [integer], got: [$element]")
    }
    return OptRead.Present(primitive.content.toInt())
  }

  /** The full post-write echo: the derived status and every live score, newest sitting first. */
  private fun scoresObject(scores: TestScores): JsonObject =
    buildJsonObject {
      putJsonObject("test_scores") {
        put("status", scores.status.value)
        putJsonArray("scores") {
          scores.scores.forEach { rendered ->
            add(
              buildJsonObject {
                put("component", rendered.component.value)
                put("component_label", rendered.component.label)
                put("test", rendered.component.test.value)
                put("score", rendered.score.score)
                put("taken_on", rendered.sitting.toIso())
                put("self_reported", true)
              },
            )
          }
        }
      }
    }

  companion object {
    private val logger = LoggerFactory.getLogger(UpdateTestScoresChatTool::class.java)

    const val TOOL_NAME = "update_test_scores"

    const val DESCRIPTION =
      "Record, correct, remove or decline the student's own admission-test scores - what she got, on which " +
        "test and when she sat it - so a later conversation still has the number she gave us in this one. " +
        "Scores here are always self-reported: she typed them into a chat message, so they are never an official " +
        "score from the testing agency. Every field is described in the schema and every field is optional; a call " +
        "carries only what the student just said. Exactly one intent per call: a score, a remove, or a decline - " +
        "declining and recording in one call is an error. The result echoes the full post-write state."
  }
}

/** One decline-flag schema block: a literal-true boolean whose description names what the student refused. */
private fun kotlinx.serialization.json.JsonObjectBuilder.putDeclinedFlag() {
  putJsonObject("declined") {
    put("type", "boolean")
    put("const", true)
    put(
      "description",
      "Set true when she would rather not share scores at all. Record it, and never raise the question again yourself.",
    )
  }
}
