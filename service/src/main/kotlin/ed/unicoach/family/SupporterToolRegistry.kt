package ed.unicoach.family

import ed.unicoach.chat.ChatTool
import ed.unicoach.chat.ToolRegistry
import ed.unicoach.coaching.FindCollegeChatTool
import ed.unicoach.coaching.ReadOnlyChatTool
import ed.unicoach.coaching.aid.FafsaDeadlineService
import ed.unicoach.coaching.aid.FafsaProcessChatTool
import ed.unicoach.college.FindCollegeTool

/**
 * The supporter coach's registry (RFC 258, brief 0014 D15): a SEPARATE
 * [ToolRegistry] instance, read-only by construction.
 *
 * The constructor is private, so the only way to hold one is [of], which
 * admits by type and refuses everything else: a [SubjectScopedChatTool] (reads
 * about the linked student through the subject seam) or a [ReadOnlyChatTool]
 * (takes no subject, writes nothing — declared by the tool itself). An
 * allowlist and not a denylist, because the thing to keep out is "any tool that
 * writes", and a new write-capable family — not only the student's own
 * `StudentScopedChatTool`s — must not reach a parent just because nobody named
 * it. [SupporterCoachingService] takes this type, not a bare [ToolRegistry], so
 * an unvetted registry does not compile there, and the refusal is at
 * construction: a boot failure, never a turn.
 */
class SupporterToolRegistry private constructor(
  /** The vetted registry the turn loop dispatches through. */
  val registry: ToolRegistry,
) {
  companion object {
    /** A registry over [tools]; throws [IllegalArgumentException] naming the first tool outside the allowlist. */
    fun of(tools: List<ChatTool>): SupporterToolRegistry {
      tools.firstOrNull { !isPermitted(it) }?.let { tool ->
        throw IllegalArgumentException(
          "tool [${tool.name}] (${tool::class.qualifiedName}) is neither a SubjectScopedChatTool nor a ReadOnlyChatTool, " +
            "and cannot be registered for a supporter's coach: it registers only tools that read",
        )
      }
      return SupporterToolRegistry(ToolRegistry(tools))
    }

    private fun isPermitted(tool: ChatTool): Boolean = tool is SubjectScopedChatTool || tool is ReadOnlyChatTool

    /**
     * The production registry: the tools RFC 258 §5 names, and nothing that
     * writes.
     *
     * - `family_cost_picture` — the shared cost picture, through the subject seam.
     * - `fafsa_deadlines` — the FAFSA calendar, with the linked student's state.
     * - `fafsa_process` — the cited federal rulebook; it takes no subject.
     * - `find_college` — which school a named college is; it takes no subject.
     */
    fun standard(
      subjects: SubjectReadService,
      fafsaDeadlines: FafsaDeadlineService,
      findCollege: FindCollegeTool,
    ): SupporterToolRegistry =
      of(
        listOf(
          FamilyCostPictureChatTool(subjects),
          SubjectFafsaDeadlineChatTool(fafsaDeadlines, subjects),
          FafsaProcessChatTool(),
          FindCollegeChatTool(findCollege),
        ),
      )
  }
}
