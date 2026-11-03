package ed.unicoach.email

import ed.unicoach.common.models.ValidationError
import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError
import kotlinx.serialization.json.JsonObject

// Misconfigured sender: email.defaultFrom failed EmailAddress.create. Permanent
// because retrying an unchanged config cannot succeed.
class EmailConfigException(
  message: String = "Configured sender address is invalid",
) : RuntimeException(message),
  PermanentError

// Provider permanently rejected the message; no retry helps.
class EmailRejectedException(
  reason: String,
) : RuntimeException(reason),
  PermanentError

// Provider transiently failed to deliver; a retry may succeed.
class EmailDeliveryException(
  reason: String,
) : RuntimeException(reason),
  TransientError

// A SEND_EMAIL job's payload named a recipient that fails EmailAddress.create.
// Permanent (poison message): the address will never parse on retry. The typed
// [validationError] survives to the log/JobResult rather than being flattened to
// a string at the failure site.
class InvalidRecipientException(
  val recipient: String,
  val template: EmailTemplate,
  val validationError: ValidationError,
) : RuntimeException("Invalid recipient [$recipient] for template [$template]: [$validationError]"),
  PermanentError

// A SEND_EMAIL job named a template with no registered renderer. Permanent: the
// renderer set is fixed at worker construction, so a retry resolves nothing.
class UnresolvableTemplateException(
  val recipient: String,
  val template: EmailTemplate,
) : RuntimeException("No renderer registered for template [$template] to [$recipient]"),
  PermanentError

// A renderer rejected the job's context (unparseable context or an invalid
// subject/body). Permanent (poison message): identical bytes render the same way
// on retry. [cause] is the renderer's root failure, preserved unaltered — a
// [ValidationError]-carrying failure from subject/body construction, or the
// deserialization throwable. [context] is the raw offending JSON, so a
// deserialization failure logs what was malformed without querying the jobs table.
class EmailRenderException(
  val recipient: String,
  val template: EmailTemplate,
  val context: JsonObject,
  override val cause: Throwable,
) : RuntimeException(
    "Render failed for template [$template] to [$recipient] with context [$context]: [${cause.message}]",
    cause,
  ),
  PermanentError

// A renderer's subject/body construction failed its ValidationResult. Carries the
// typed [ValidationError] so the ADT survives to the log rather than being
// flattened into a message string at the renderer.
class RenderValidationException(
  val field: String,
  val validationError: ValidationError,
) : RuntimeException("[$field] construction failed: [$validationError]")
