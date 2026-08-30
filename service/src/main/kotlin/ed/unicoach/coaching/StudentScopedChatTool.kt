package ed.unicoach.coaching

import ed.unicoach.chat.ChatTool
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * A [ChatTool] whose execution is scoped to the turn's student. The registry
 * and wire advertisement are unchanged ([ChatTool.definition] is static); only
 * dispatch differs — [CoachingService] hands the owning student's id to
 * [execute] so the tool writes the right student's data without the model ever
 * seeing or supplying an id. The inherited single-argument [ChatTool.execute]
 * is a structured-error fallback for a dispatcher that failed to scope; it is
 * final so a subclass cannot turn the misroute guard into something plausible.
 *
 * Also the one home for the tool family's input scaffolding — the structured
 * error shape, the unknown-field rejection, and the strict optional-field
 * readers — so every student-scoped tool speaks the same protocol instead of
 * copying it.
 */
abstract class StudentScopedChatTool : ChatTool {
  abstract suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject

  final override suspend fun execute(input: JsonObject): JsonObject = errorObject("tool [$name] requires a student-scoped dispatch")

  /** The structured error object the [ChatTool] total contract requires — malformed input never throws. */
  protected fun errorObject(reason: String): JsonObject = buildJsonObject { put("error", reason) }

  /** The rejection reason for input keys outside [known], or null when the input is clean. */
  protected fun unknownFieldsReason(
    input: JsonObject,
    known: Set<String>,
  ): String? {
    val unknown = input.keys - known
    if (unknown.isEmpty()) return null
    return "unknown field(s): ${unknown.sorted().joinToString(", ") { "[$it]" }}"
  }

  /**
   * A read of one optional input field: the value, an explicit absence, or a
   * type mismatch carrying a structured parse reason that names the field,
   * the expected type, and the offending element — a present-but-wrong-type
   * field is never conflated with an absent one.
   */
  protected sealed interface OptRead<out T> {
    data class Present<T>(
      val value: T,
    ) : OptRead<T>

    data object Absent : OptRead<Nothing>

    data class Mismatch(
      val reason: String,
    ) : OptRead<Nothing>
  }

  protected fun getString(
    input: JsonObject,
    field: String,
  ): OptRead<String> {
    val element = input[field] ?: return OptRead.Absent
    val primitive = element as? JsonPrimitive
    if (primitive == null || !primitive.isString) return OptRead.Mismatch(mismatchReason(field, "string", element))
    return OptRead.Present(primitive.content)
  }

  protected fun getBoolean(
    input: JsonObject,
    field: String,
  ): OptRead<Boolean> {
    val element = input[field] ?: return OptRead.Absent
    val primitive = element as? JsonPrimitive
    // A JSON string "true" is not a boolean: the input contract is strict.
    val value = if (primitive == null || primitive.isString) null else primitive.booleanOrNull
    return value?.let { OptRead.Present(it) } ?: OptRead.Mismatch(mismatchReason(field, "boolean", element))
  }

  private fun mismatchReason(
    field: String,
    expected: String,
    element: JsonElement,
  ): String = "[$field] must be a [$expected], got: [$element]"

  // ---------------------------------------------------------------------------
  // The `college_ids` subset filter — one protocol, one implementation
  // ---------------------------------------------------------------------------

  /** The parse outcome for one `college_ids` call: the optional subset filter, or the reason the call is malformed. */
  protected sealed interface CollegeIdsInput {
    /** [collegeIds] is null for an omitted field (the whole active list) and empty for a literal `[]`. */
    data class Ok(
      val collegeIds: List<CollegeId>?,
    ) : CollegeIdsInput

    data class Invalid(
      val reason: String,
    ) : CollegeIdsInput
  }

  /**
   * Reads the `college_ids` subset filter every list-reading tool takes: the
   * unknown-field rejection, the cap, and the per-entry uuid parse whose
   * rejection names the offending element AND its index.
   *
   * Here rather than per tool because this class already declares itself the
   * one home for the tool family's input scaffolding: `college_cost_profile`
   * and `college_admissions_profile` advertise the SAME input schema and must
   * reject the same input the same way, so a second copy is a second protocol
   * waiting to drift — the RFC's "mirror the cost tool" is a shape to share,
   * not a body to duplicate.
   *
   * Absence and emptiness are different reads: an omitted field is a null
   * filter meaning the whole active list, while `[]` is a literal empty subset
   * and must stay one. Never normalise the empty list back to null — that
   * silently turns "these zero schools" into "all of them".
   */
  protected fun readCollegeIds(input: JsonObject): CollegeIdsInput {
    unknownFieldsReason(input, COLLEGE_IDS_FIELDS)?.let { return CollegeIdsInput.Invalid(it) }

    val element = input[COLLEGE_IDS_FIELD] ?: return CollegeIdsInput.Ok(null)
    val array =
      element as? JsonArray
        ?: return CollegeIdsInput.Invalid("$COLLEGE_IDS_FIELD must be an array of uuid strings, got: [$element]")
    if (array.size > MAX_COLLEGE_IDS) {
      return CollegeIdsInput.Invalid(
        "$COLLEGE_IDS_FIELD must contain at most [$MAX_COLLEGE_IDS] entries, got [${array.size}]",
      )
    }
    val ids =
      array.mapIndexed { index, item ->
        when (val parsed = parseCollegeId(item, index)) {
          is IdParse.Ok -> parsed.id
          is IdParse.Invalid -> return CollegeIdsInput.Invalid(parsed.reason)
        }
      }
    return CollegeIdsInput.Ok(ids)
  }

  /** The per-element parse outcome: one array entry as a [CollegeId], or the reason it is malformed. */
  private sealed interface IdParse {
    data class Ok(
      val id: CollegeId,
    ) : IdParse

    data class Invalid(
      val reason: String,
    ) : IdParse
  }

  /** Parses one `college_ids` entry; a rejection names the offending element and its index. */
  private fun parseCollegeId(
    item: JsonElement,
    index: Int,
  ): IdParse {
    val primitive = item as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
      return IdParse.Invalid("$COLLEGE_IDS_FIELD entry is not a uuid string: [$item] at index [$index]")
    }
    // The try wraps the UUID PARSE and nothing else: [CollegeId] validates too,
    // and a future validation failure of its own must not be relabelled "not a
    // uuid". The discarded cause is logged rather than dropped, so a rejection
    // the operator has to explain is not a sentence with no evidence behind it.
    val uuid =
      try {
        UUID.fromString(primitive.content)
      } catch (e: IllegalArgumentException) {
        logger.debug("tool [{}] rejected a college_ids entry at index [{}] as a malformed uuid", name, index, e)
        return IdParse.Invalid("$COLLEGE_IDS_FIELD entry is not a uuid: [${primitive.content}] at index [$index]")
      }
    return IdParse.Ok(CollegeId(uuid))
  }

  companion object {
    private val logger = LoggerFactory.getLogger(StudentScopedChatTool::class.java)

    /** The subset filter's field name — read by the schema, the copy and the parser from one place. */
    const val COLLEGE_IDS_FIELD = "college_ids"

    /** The subset filter reads from the student's own list; anything larger is malformed, not a bigger read. */
    const val MAX_COLLEGE_IDS = 50

    internal val COLLEGE_IDS_FIELDS = setOf(COLLEGE_IDS_FIELD)
  }
}

/**
 * The `input_schema` both `college_ids` tools advertise, written once so the
 * two definitions cannot describe the same input differently — and so the cap
 * the parser enforces is the cap the model is told about.
 */
internal fun JsonObjectBuilder.putCollegeIdsSchema() {
  put("type", "object")
  putJsonObject("properties") {
    putJsonObject(StudentScopedChatTool.COLLEGE_IDS_FIELD) {
      put("type", "array")
      putJsonObject("items") { put("type", "string") }
      put(
        "description",
        "Optional subset of college ids (from the student's list) to read; " +
          "omit the field entirely to read the whole active list. " +
          "At most ${StudentScopedChatTool.MAX_COLLEGE_IDS} entries; duplicate ids are read once.",
      )
    }
  }
  putJsonArray("required") {}
  // The published boundary states the same closed set [readCollegeIds]
  // enforces: a surplus key is refused at runtime, so the model is told the
  // allowlist rather than discovering it through an error object.
  put("additionalProperties", false)
}
