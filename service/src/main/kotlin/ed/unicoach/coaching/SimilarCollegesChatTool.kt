package ed.unicoach.coaching

import ed.unicoach.college.SimilarCollegesTool

/**
 * Registers the chat-free [SimilarCollegesTool] with the coach, so "what
 * schools are like Bowdoin?" reaches the real query with no reshaping in
 * between (RFC 153 D72). A [DelegatingChatTool] and nothing more.
 */
class SimilarCollegesChatTool(
  tool: SimilarCollegesTool,
) : DelegatingChatTool(SimilarCollegesTool.TOOL_NAME, tool.definition, tool::execute)
