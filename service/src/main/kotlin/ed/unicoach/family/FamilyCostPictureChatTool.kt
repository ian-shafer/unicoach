package ed.unicoach.family

import ed.unicoach.coaching.costs.CollegeCostChatTool
import ed.unicoach.coaching.noArgumentToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * `family_cost_picture` (RFC 258): what the student's college list costs the
 * family, for a supporter's coach — the cost picture the Family Cost Report
 * shows a parent, read through [SubjectReadService.costPicture].
 *
 * Rendered by [CollegeCostChatTool.payloadOf], the payload builder
 * `college_cost_profile` uses, so the supporter's coach reads the same keys and
 * the same words the student's does. What is withheld is withheld by the seam
 * ([SharedCostPicture.SubjectsWithheld] has no program return); this tool only
 * says so, under [INTENDED_SUBJECTS_SHARED_KEY], so "the student has not shared
 * what they want to study" is never read as "has not said".
 *
 * Takes no argument: the subject is the conversation's, injected by the
 * dispatcher, and the picture is the whole active list.
 */
class FamilyCostPictureChatTool(
  private val subjects: SubjectReadService,
) : SubjectScopedChatTool() {
  override val name: String = TOOL_NAME

  override val definition: JsonObject = noArgumentToolDefinition(TOOL_NAME, DESCRIPTION)

  override suspend fun execute(
    subject: Subject,
    input: JsonObject,
  ): JsonObject {
    unknownFieldsReason(input, emptySet())?.let { return errorObject(it) }

    val read =
      subjects.costPicture(subject.viewer, subject.studentId).getOrElse { e ->
        logger.warn(
          "tool [{}] cost read failed for viewer=[{}] student=[{}]",
          TOOL_NAME,
          subject.viewer.asString,
          subject.studentId.asString,
          e,
        )
        return errorObject(READ_FAILED_REASON)
      }
    return when (read) {
      SubjectRead.NotFound -> studentNotFound()
      is SubjectRead.Found -> payloadOf(read.value)
    }
  }

  private fun payloadOf(picture: SharedCostPicture): JsonObject =
    when (picture) {
      is SharedCostPicture.Full -> {
        CollegeCostChatTool.payloadOf(picture.profile)
      }

      is SharedCostPicture.SubjectsWithheld -> {
        JsonObject(CollegeCostChatTool.payloadOf(picture.profile) + (INTENDED_SUBJECTS_SHARED_KEY to JsonPrimitive(false)))
      }
    }

  companion object {
    private val logger = LoggerFactory.getLogger(FamilyCostPictureChatTool::class.java)

    const val TOOL_NAME = "family_cost_picture"

    /** Present, and false, exactly when the student has not shared what they want to study. */
    const val INTENDED_SUBJECTS_SHARED_KEY = "intended_subjects_shared"

    const val READ_FAILED_REASON = "family cost picture read failed"

    val DESCRIPTION by lazy {
      "What the colleges on the student's list would cost this family: tuition and fees, living costs, the " +
        "price the family would pay after grants and scholarships, borrowing, and what graduates earn — the " +
        "cost picture the student's family can see. It takes no input; the student is the one this " +
        "conversation is about. It changes nothing.\n" +
        "$INTENDED_SUBJECTS_SHARED_KEY - present and false when the student has not shared what they want to " +
        "study, which is why the payload carries no ${CollegeCostChatTool.PROGRAM_RETURN_KEY}.\n" +
        "A legend of the payload's keys follows, in the order they appear.\n" +
        CollegeCostChatTool.PAYLOAD_LEGEND
    }
  }
}
