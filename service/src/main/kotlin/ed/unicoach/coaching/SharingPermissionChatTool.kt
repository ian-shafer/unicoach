package ed.unicoach.coaching

import ed.unicoach.db.models.SharingPermission
import ed.unicoach.db.models.SharingPermissionStatus
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * The words every door says about a student's sharing permission (RFC 251),
 * so the coach's ask, the share result and the update result cannot describe
 * one permission three ways.
 */
object SharingPermissionCopy {
  /**
   * What the student is told BEFORE a grant is written (brief 0011 D18). The
   * default is off, so a door that says these words before every grant keeps
   * D18 by construction.
   *
   * Byte-identical to `SharingPermissionCopy.caption` in
   * `ios-app/UnicoachiOS/YourDetailsView.swift`, the words the "Your details"
   * control carries. Nothing checks that the two agree: edit one without the
   * other and the two consent doors tell the student different things.
   */
  const val CONSENT_WORDS =
    "Off until you say yes. If you say yes, people you share with can see the subjects you chose and what " +
      "graduates of them earn and owe — that includes anyone holding a Family Cost Report link you've already " +
      "sent. Your coach uses them either way, and you can change this whenever you like."

  /** What every stored answer's reach sentence ends with: nothing the coach does changes, and the answer can. */
  private const val EITHER_WAY =
    "The coach uses the subjects either way, and the student can change this at any time, in chat or in Your details."

  /** What a stored answer means for the people the student shares with, said back after it is written. */
  fun reachOf(status: SharingPermissionStatus): String =
    when (status) {
      SharingPermissionStatus.GRANTED -> {
        "People the student shares with can now see the subjects the student chose and what graduates of them " +
          "earn and owe, including anyone holding a Family Cost Report link already sent. $EITHER_WAY"
      }

      SharingPermissionStatus.REFUSED -> {
        "People the student shares with do not see the subjects the student chose, and a Family Cost Report " +
          "link already sent stops showing them the next time it is opened. $EITHER_WAY"
      }
    }
}

/**
 * The `update_sharing_permission` chat tool (RFC 251): the coach's door to the
 * student's sharing permission (RFC 248), through the same
 * [SharingPermissionService.set] the REST door writes through.
 *
 * Only `granted` or `refused` can be written: "never asked" is the absence of
 * an answer, so neither the schema nor the parser offers it. Total by the
 * [ed.unicoach.chat.ChatTool] contract: a malformed call or a failed write is a
 * structured `{ "error": ... }`, never a throw.
 */
class UpdateSharingPermissionChatTool(
  private val service: SharingPermissionService,
) : StudentScopedChatTool() {
  override val name: String = TOOL_NAME

  override val definition: JsonObject =
    buildJsonObject {
      put("name", TOOL_NAME)
      put("description", DESCRIPTION)
      putJsonObject("input_schema") {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject(PERMISSION_FIELD) {
            put("type", "string")
            putJsonArray("enum") { SharingPermission.entries.forEach { add(it.value) } }
            put("description", "Which of the student's data the answer is about.")
          }
          putJsonObject(STATUS_FIELD) {
            put("type", "string")
            putJsonArray("enum") { SharingPermissionStatus.entries.forEach { add(it.value) } }
            put("description", "The student's answer: yes is granted, no or a withdrawal is refused.")
          }
        }
        putJsonArray("required") {
          add(PERMISSION_FIELD)
          add(STATUS_FIELD)
        }
        put("additionalProperties", false)
      }
    }

  override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    unknownFieldsReason(input, setOf(PERMISSION_FIELD, STATUS_FIELD))?.let { return errorObject(it) }
    val permission =
      requiredValue(input, PERMISSION_FIELD, SharingPermission::fromValue, ::unknownPermissionReason) { return errorObject(it) }
    val status =
      requiredValue(input, STATUS_FIELD, SharingPermissionStatus::fromValue, ::unknownStatusReason) { return errorObject(it) }
    val stored = storedOrNull(studentId, permission, status) ?: return errorObject("sharing permission update failed")
    return storedAnswerOf(permission, stored)
  }

  /** One required enum field: its value, or [refuse] with why it cannot be read. */
  private inline fun <T : Any> requiredValue(
    input: JsonObject,
    field: String,
    fromValue: (String) -> T?,
    unknownReason: (String) -> String,
    refuse: (String) -> Nothing,
  ): T =
    when (val read = getString(input, field)) {
      is OptRead.Present -> fromValue(read.value) ?: refuse(unknownReason(read.value))
      OptRead.Absent -> refuse("[$field] is required")
      is OptRead.Mismatch -> refuse(read.reason)
    }

  private fun unknownPermissionReason(value: String): String = "unknown [$PERMISSION_FIELD]: [$value]"

  private fun unknownStatusReason(value: String): String =
    "[$STATUS_FIELD] must be one of ${SharingPermissionStatus.entries.map { it.value }}, got: [$value]"

  /** The answer as stored, or null when the write failed -- logged with what was being written. */
  private suspend fun storedOrNull(
    studentId: StudentId,
    permission: SharingPermission,
    status: SharingPermissionStatus,
  ): SharingPermissionStatus? =
    service.set(studentId, permission, status).getOrElse { e ->
      logger.warn(
        "tool [{}] permission write failed for student=[{}] permission=[{}] status=[{}]",
        TOOL_NAME,
        studentId.asString,
        permission.value,
        status.value,
        e,
      )
      null
    }

  private fun storedAnswerOf(
    permission: SharingPermission,
    stored: SharingPermissionStatus,
  ): JsonObject =
    buildJsonObject {
      putJsonObject(RESULT_KEY) {
        put(PERMISSION_FIELD, permission.value)
        put(STATUS_FIELD, stored.value)
        put(STATEMENT_KEY, SharingPermissionCopy.reachOf(stored))
      }
    }

  companion object {
    private val logger = LoggerFactory.getLogger(UpdateSharingPermissionChatTool::class.java)

    const val TOOL_NAME = "update_sharing_permission"

    const val RESULT_KEY = "sharing_permission"

    const val PERMISSION_FIELD = "permission"

    const val STATUS_FIELD = "status"

    /**
     * The wire words are read from the enums the input schema is built from,
     * and the consent words ride here rather than only in a share result's
     * ask, so a yes given in chat with no share in hand is preceded by them too.
     */
    val DESCRIPTION: String =
      "Record the student's answer to whether people they share with may see their own data - today, " +
        "\"${SharingPermission.INTENDED_SUBJECTS.value}\": the subjects they want to study and what graduates of " +
        "them earn and owe, on every Family Cost Report link they have shared, including links already sent. " +
        "Call it only with an answer the student actually gave: \"${SharingPermissionStatus.GRANTED.value}\" for " +
        "yes, \"${SharingPermissionStatus.REFUSED.value}\" for no or to withdraw an earlier yes. " +
        "Before recording a yes, say these words to the student, unchanged, unless you already said them in " +
        "this conversation: \"${SharingPermissionCopy.CONSENT_WORDS}\" " +
        "Never call it to ask again after a no, and nothing you do for the student changes with either answer. " +
        "Returns the stored answer and a statement of what it means for the people the student shares with."
  }
}
