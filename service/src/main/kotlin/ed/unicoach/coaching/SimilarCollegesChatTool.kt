package ed.unicoach.coaching

import ed.unicoach.coaching.moneyprofile.GetMoneyProfileResult
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.college.SimilarCollegesTool
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonObject

/**
 * Registers the chat-free [SimilarCollegesTool] with the coach, so "what schools
 * are like Bowdoin?" reaches the real query with no reshaping in between (RFC
 * 153 D72).
 *
 * Residency-scoped since RFC 169, for the same reason [CollegeChatTool] is: the
 * price axis, `cheaper_than_anchor` and the anchor's own figure must all be on
 * the ruler THIS family is on. Two tools that return "a college" describe it the
 * same way (RFC 153 D70), so they must also RANK it the same way — which is why
 * the residency read they share lives in [ResidencyScopedChatTool] rather than
 * in two copies.
 */
class SimilarCollegesChatTool(
  private val tool: SimilarCollegesTool,
  moneyProfiles: MoneyProfileService,
  readProfile: suspend (StudentId) -> Result<GetMoneyProfileResult> = moneyProfiles::getForStudent,
) : ResidencyScopedChatTool(moneyProfiles, readProfile) {
  override val name: String = SimilarCollegesTool.TOOL_NAME

  override val definition: JsonObject = tool.definition

  override suspend fun search(
    input: JsonObject,
    familyResidencyState: String?,
  ): JsonObject = tool.execute(input, familyResidencyState)
}
