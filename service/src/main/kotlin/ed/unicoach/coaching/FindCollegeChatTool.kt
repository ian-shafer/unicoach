package ed.unicoach.coaching

import ed.unicoach.college.FindCollegeTool

/**
 * Registers the chat-free [FindCollegeTool] with the coach, so a school the
 * student NAMED becomes the `college_id` every other college tool takes (RFC
 * 154). A [DelegatingChatTool] and nothing more.
 */
class FindCollegeChatTool(
  tool: FindCollegeTool,
) : DelegatingChatTool(FindCollegeTool.TOOL_NAME, tool.definition, tool::execute)
