package ed.unicoach.coaching

import ed.unicoach.chat.ChatTool
import ed.unicoach.college.FindCollegeTool
import kotlinx.serialization.json.JsonObject

/**
 * Bridges the chat-free [FindCollegeTool] (in `:college`, no `:chat` dependency
 * by RFC 67's deliberate separation) to the [ChatTool] registration contract.
 * The same thin verbatim delegate [CollegeChatTool] is: the definition and
 * execute are the wrapped tool's own — no reshaping — so the coach can turn a
 * school the student NAMED into the `college_id` every other college tool takes
 * (RFC 154).
 */
class FindCollegeChatTool(
  private val tool: FindCollegeTool,
) : ChatTool {
  override val name: String = FindCollegeTool.TOOL_NAME

  override val definition: JsonObject = tool.definition

  override suspend fun execute(input: JsonObject): JsonObject = tool.execute(input)
}
