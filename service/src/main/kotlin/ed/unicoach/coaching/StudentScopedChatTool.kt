package ed.unicoach.coaching

import ed.unicoach.chat.ChatTool
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonObject
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
 */
abstract class StudentScopedChatTool : ChatTool {
  abstract suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject

  final override suspend fun execute(input: JsonObject): JsonObject =
    buildJsonObject { put("error", "tool [$name] requires a student-scoped dispatch") }
}
