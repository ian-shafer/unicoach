package ed.unicoach.coaching

import ed.unicoach.coaching.moneyprofile.GetMoneyProfileResult
import ed.unicoach.college.SimilarCollegesTool
import ed.unicoach.db.models.IntendedSubjectAnswer
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonObject

/**
 * Registers the chat-free [SimilarCollegesTool] with the coach, so "what schools
 * are like Bowdoin?" reaches the real query with no reshaping in between (RFC
 * 153 D72).
 *
 * Search-scoped since RFC 169, for the same reason [CollegeChatTool] is: the
 * price axis, `cheaper_than_anchor` and the anchor's own figure must all be on
 * the ruler THIS family is on. RFC 245 widened the same seam to carry the
 * student's stored subjects, so a peer list labels a school exactly as a search
 * does. Two tools that return "a college" describe it the same way (RFC 153
 * D70), so they must also RANK and LABEL it the same way — which is why the two
 * reads they share live in [SearchScopedChatTool] rather than in two copies.
 *
 * The subjects LABEL these peers; they do not rank them. The SUBJECTS similarity
 * axis stays out of `SimilarityAxis.DEFAULTS` and no weight changes, so the
 * ordered list and every `distance` are what an unlabelled call returns.
 */
class SimilarCollegesChatTool(
  private val tool: SimilarCollegesTool,
  readProfile: suspend (StudentId) -> Result<GetMoneyProfileResult>,
  readAnswer: suspend (StudentId) -> Result<IntendedSubjectAnswer>,
) : SearchScopedChatTool(readProfile, readAnswer) {
  override val name: String = SimilarCollegesTool.TOOL_NAME

  override val definition: JsonObject = tool.definition

  override suspend fun search(
    input: JsonObject,
    familyResidencyState: String?,
    storedSubjectSlugs: Set<String>,
  ): JsonObject = tool.execute(input, familyResidencyState, storedSubjectSlugs)
}
