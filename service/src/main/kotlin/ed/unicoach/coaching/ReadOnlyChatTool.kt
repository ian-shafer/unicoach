package ed.unicoach.coaching

import ed.unicoach.chat.ChatTool

/**
 * A [ChatTool] that takes no subject and writes nothing (RFC 258): its answer
 * depends only on the model's input and reference data, never on whose
 * conversation it is. A tool declares this about itself by implementing the
 * marker; nothing infers it.
 *
 * It is the only kind of plain tool a supporter's coach may hold
 * ([ed.unicoach.family.SupporterToolRegistry] admits these and
 * [ed.unicoach.family.SubjectScopedChatTool]s, and refuses everything else), so
 * a new tool reaches a parent only by stating that it is safe to.
 */
interface ReadOnlyChatTool : ChatTool
