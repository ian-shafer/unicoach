package ed.unicoach.coaching

import ed.unicoach.coaching.moneyprofile.GetMoneyProfileResult
import ed.unicoach.college.CollegeSearchTool
import ed.unicoach.db.models.IntendedSubjectAnswer
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonObject

/**
 * Registers the chat-free [CollegeSearchTool] with the coach, so the coach runs
 * live college search in chat.
 *
 * It was a [DelegatingChatTool] and nothing more until RFC 169. It is now
 * search-scoped, for two reasons of one shape: search must rank on the price
 * THIS family would pay, and it must say whether each school teaches what THIS
 * student wants to study (RFC 245) — and both answers live on student-scoped
 * tables that only a student-scoped dispatch can reach. Everything that is true
 * of both search doors lives in [SearchScopedChatTool]; what is left here is the
 * wrapped tool and its definition.
 *
 * `:college` stays free of `:chat`, of the money profile and of the
 * intended-subject store (RFC 67): it receives a two-letter code and a set of
 * subject words, and nothing else.
 */
class CollegeChatTool(
  private val tool: CollegeSearchTool,
  readProfile: suspend (StudentId) -> Result<GetMoneyProfileResult>,
  readAnswer: suspend (StudentId) -> Result<IntendedSubjectAnswer>,
) : SearchScopedChatTool(readProfile, readAnswer) {
  override val name: String = CollegeSearchTool.TOOL_NAME

  override val definition: JsonObject = tool.definition

  override suspend fun search(
    input: JsonObject,
    familyResidencyState: String?,
    storedSubjectSlugs: Set<String>,
  ): JsonObject = tool.execute(input, familyResidencyState, storedSubjectSlugs)
}
