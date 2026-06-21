package ed.unicoach.db.models

/**
 * Creation input for the immutable `system_prompts` catalog (RFC 63). Carries
 * only the three immutable domain columns as plain strings; `id`, `created_at`,
 * and `row_created_at` are DB-defaulted and never client-supplied.
 * Canonicalization and bounds are DB-enforced (the table's `CHECK`/`UNIQUE`
 * constraints), mirroring the [SystemPrompt] model's `String` fields.
 */
data class NewSystemPrompt(
  val name: String,
  val version: String,
  val body: String,
)
