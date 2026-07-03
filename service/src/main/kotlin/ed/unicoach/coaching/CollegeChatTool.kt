package ed.unicoach.coaching

import ed.unicoach.chat.ChatTool
import ed.unicoach.college.CollegeSearchTool
import kotlinx.serialization.json.JsonObject

/**
 * Bridges the chat-free [CollegeSearchTool] (in `:college`, no `:chat`
 * dependency by RFC 67's deliberate separation) to the [ChatTool] registration
 * contract. A thin verbatim delegate: the definition and execute are the wrapped
 * tool's own — no reshaping — so the coach runs live college search in chat.
 */
class CollegeChatTool(
  private val tool: CollegeSearchTool,
) : ChatTool {
  override val name: String = CollegeSearchTool.TOOL_NAME

  override val definition: JsonObject = tool.definition

  override suspend fun execute(input: JsonObject): JsonObject = tool.execute(input)
}
