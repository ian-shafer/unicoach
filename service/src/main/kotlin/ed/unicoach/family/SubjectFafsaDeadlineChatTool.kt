package ed.unicoach.family

import ed.unicoach.coaching.aid.FafsaDeadlineChatTool
import ed.unicoach.coaching.aid.FafsaDeadlineService
import ed.unicoach.coaching.aid.FafsaDeadlines
import ed.unicoach.coaching.noArgumentToolDefinition
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.time.ZoneId

/**
 * `fafsa_deadlines` for a supporter's coach (RFC 258): the same tool name, the
 * same description and the same payload as the student's, through
 * [FafsaDeadlineChatTool.answer] — the one function both doors call — with the
 * linked student's residency read through [SubjectReadService.residency].
 *
 * The residency is already visible to a parent inside the cost picture, so this
 * reveals nothing the seam does not already show. The link is checked BEFORE
 * anything is read, so a severed link answers "student not found" rather than
 * the federal dates.
 */
class SubjectFafsaDeadlineChatTool(
  service: FafsaDeadlineService,
  private val subjects: SubjectReadService,
  /** The deadline read, as the same seam the student tool takes. */
  private val readDeadlines: suspend (ZoneId) -> Result<FafsaDeadlines> = { zone -> service.get(zone) },
) : SubjectScopedChatTool() {
  override val name: String = FafsaDeadlineChatTool.TOOL_NAME

  override val definition: JsonObject = noArgumentToolDefinition(FafsaDeadlineChatTool.TOOL_NAME, FafsaDeadlineChatTool.DESCRIPTION)

  override suspend fun execute(
    subject: Subject,
    input: JsonObject,
  ): JsonObject {
    unknownFieldsReason(input, emptySet())?.let { return errorObject(it) }

    val read =
      subjects.residency(subject.viewer, subject.studentId).getOrElse { e ->
        logger.warn(
          "tool [{}] residency read failed for viewer=[{}] student=[{}]",
          name,
          subject.viewer.asString,
          subject.studentId.asString,
          e,
        )
        return errorObject(FafsaDeadlineChatTool.READ_FAILED_REASON)
      }
    val residency =
      when (read) {
        SubjectRead.NotFound -> return studentNotFound()
        is SubjectRead.Found -> read.value
      }
    return FafsaDeadlineChatTool.answer(readDeadlines, subject.studentId, subject.viewer) {
      residency
    }
  }

  private companion object {
    private val logger = LoggerFactory.getLogger(SubjectFafsaDeadlineChatTool::class.java)
  }
}
