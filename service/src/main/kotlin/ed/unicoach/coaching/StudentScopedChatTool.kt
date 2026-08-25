package ed.unicoach.coaching

import ed.unicoach.chat.ChatTool
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
}
