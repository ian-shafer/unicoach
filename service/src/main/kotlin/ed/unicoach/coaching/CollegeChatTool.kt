package ed.unicoach.coaching

import ed.unicoach.coaching.moneyprofile.GetMoneyProfileResult
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.college.CollegeSearchTool
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonObject

/**
 * Registers the chat-free [CollegeSearchTool] with the coach, so the coach runs
 * live college search in chat.
 *
 * It was a [DelegatingChatTool] and nothing more until RFC 169. It is now
 * residency-scoped, for one reason: search must rank on the price THIS family
 * would pay, and the family's state of residency lives on `money_profiles`,
 * which only a student-scoped dispatch can reach. Everything that is true of
 * both search doors lives in [ResidencyScopedChatTool]; what is left here is the
 * wrapped tool and its definition.
 *
 * `:college` stays free of `:chat` and of the money profile (RFC 67): it
 * receives a two-letter code and nothing else.
 */
class CollegeChatTool(
  private val tool: CollegeSearchTool,
  moneyProfiles: MoneyProfileService,
  readProfile: suspend (StudentId) -> Result<GetMoneyProfileResult> = moneyProfiles::getForStudent,
) : ResidencyScopedChatTool(moneyProfiles, readProfile) {
  override val name: String = CollegeSearchTool.TOOL_NAME

  override val definition: JsonObject = tool.definition

  override suspend fun search(
    input: JsonObject,
    familyResidencyState: String?,
  ): JsonObject = tool.execute(input, familyResidencyState)
}
