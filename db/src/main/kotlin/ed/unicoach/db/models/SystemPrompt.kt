package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the immutable `system_prompts` catalog (RFC 33). Rows are authored
 * by migration and never updated; [SystemPromptsDao] is a read-only reader.
 */
data class SystemPrompt(
  override val id: SystemPromptId,
  val name: String,
  val version: String,
  val body: String,
  override val createdAt: Instant,
) : Identifiable<SystemPromptId>,
  Created
