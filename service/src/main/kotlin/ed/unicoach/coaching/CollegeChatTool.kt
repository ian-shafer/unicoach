package ed.unicoach.coaching

import ed.unicoach.college.CollegeSearchTool

/**
 * Registers the chat-free [CollegeSearchTool] with the coach, so the coach runs
 * live college search in chat. A [DelegatingChatTool] and nothing more: the
 * definition and execute are the wrapped tool's own, with no reshaping.
 */
class CollegeChatTool(
  tool: CollegeSearchTool,
) : DelegatingChatTool(CollegeSearchTool.TOOL_NAME, tool.definition, tool::execute)
