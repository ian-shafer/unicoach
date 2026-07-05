package ed.unicoach.email

import kotlinx.serialization.json.JsonObject

/**
 * The render port for one [EmailTemplate]. Implementations live alongside the
 * domain that owns their copy (e.g. verification in `service/auth/`), keeping the
 * `email/` module a generic transmitter with no domain knowledge.
 *
 * [render] returns [Result.failure] for a context that cannot be deserialized or
 * that produces an invalid subject/body — a permanent, non-retriable condition
 * the handler folds to a dead-letter.
 */
interface EmailTemplateRenderer {
  val template: EmailTemplate

  fun render(context: JsonObject): Result<RenderedEmail>
}
