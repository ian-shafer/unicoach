package ed.unicoach.family

import ed.unicoach.chat.ChatTool
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.toolErrorObject
import ed.unicoach.coaching.unknownToolFieldsReason
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.UserId
import kotlinx.serialization.json.JsonObject

/**
 * Whom a supporter's coach is reading about, and who is reading (RFC 258): the
 * signed-in [viewer] and the linked [studentId] the conversation is about.
 *
 * The dispatcher builds it from the conversation row, never from the model, so
 * the model can neither see nor supply a subject (brief 0014 X10, D15).
 */
data class Subject(
  val viewer: UserId,
  val studentId: StudentId,
)

/**
 * A [ChatTool] a supporter's coach dispatches with a [Subject] (RFC 258): the
 * read-only counterpart of [StudentScopedChatTool]. Every subject tool reads
 * through [SubjectReadService], which re-checks the live link on each call, so
 * a severed link refuses the very next tool call, mid-conversation.
 *
 * The inherited single-argument [ChatTool.execute] is a structured-error
 * fallback for a dispatcher that failed to scope, and final, exactly as on the
 * student-scoped base.
 *
 * It lives in `:service` and not in `:chat`, beside the student-scoped base: the
 * subject is a pair of database ids, and `:chat` knows nothing of `:db`.
 */
abstract class SubjectScopedChatTool : ChatTool {
  abstract suspend fun execute(
    subject: Subject,
    input: JsonObject,
  ): JsonObject

  final override suspend fun execute(input: JsonObject): JsonObject = errorObject("tool [$name] requires a subject-scoped dispatch")

  /** The structured error object every coach tool refuses with — the one envelope ([toolErrorObject]). */
  protected fun errorObject(reason: String): JsonObject = toolErrorObject(reason)

  /** The rejection reason for input keys outside [known], or null when the input is clean. */
  protected fun unknownFieldsReason(
    input: JsonObject,
    known: Set<String>,
  ): String? = unknownToolFieldsReason(input, known)

  /**
   * The refusal for [SubjectRead.NotFound]: one sentence for every reason the
   * seam refuses (never linked, severed, a closed student), so the coach cannot
   * tell which. The parent prompt reads it as "this person's connection to the
   * student is no longer active".
   */
  protected fun studentNotFound(): JsonObject = errorObject(STUDENT_NOT_FOUND_REASON)

  companion object {
    const val STUDENT_NOT_FOUND_REASON = "student not found"
  }
}
