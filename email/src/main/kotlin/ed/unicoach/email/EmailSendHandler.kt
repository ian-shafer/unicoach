package ed.unicoach.email

import ed.unicoach.common.json.deserialize
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError
import ed.unicoach.queue.JobHandler
import ed.unicoach.queue.JobResult
import ed.unicoach.queue.JobType
import ed.unicoach.queue.JobTypeConfig
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The [JobHandler] for [JobType.SEND_EMAIL] (RFC 96): the worker's sole path to
 * transmitting outbound email. It resolves the [EmailTemplateRenderer] for the
 * payload's template, renders the self-contained context, and sends through the
 * unchanged [EmailService] (transmit-then-record) — reading no domain state.
 *
 * Failure classification mirrors [ed.unicoach.coaching.extraction.ExtractionHandler]:
 * the worker's `catch` defaults any uncaught throwable to `RetriableFailure`, so a
 * `Result.failure` returned normally by [EmailService.send] carries no automatic
 * classification. This handler therefore folds the `Result` itself against the
 * exception's marker interface, and *rethrows* an exception it does not recognize
 * so the worker's default handles it and the root cause reaches the log unaltered.
 */
class EmailSendHandler(
  private val emailService: EmailService,
  renderers: List<EmailTemplateRenderer>,
) : JobHandler {
  private val logger = LoggerFactory.getLogger(EmailSendHandler::class.java)

  private val renderersByTemplate: Map<EmailTemplate, EmailTemplateRenderer> =
    buildMap {
      for (renderer in renderers) {
        require(!containsKey(renderer.template)) {
          "Duplicate renderer registered for template: [${renderer.template}]"
        }
        put(renderer.template, renderer)
      }
    }

  override val jobType = JobType.SEND_EMAIL

  // A send is a short network call and distinct jobs are independent, so several
  // run in parallel. executionTimeout is strictly less than lockDuration so a
  // slow send cannot outlive its queue lock.
  override val config =
    JobTypeConfig(
      concurrency = 4,
      maxAttempts = 5,
      lockDuration = 2.minutes,
      executionTimeout = 30.seconds,
    )

  override suspend fun execute(payload: JsonObject): JobResult {
    // Steps 1-4: any failure here is a poison message — permanent, before any send.
    // Each builds a typed PermanentError exception carrying its structured cause
    // ([to]/[template]/ValidationError), which foldSendFailure logs and folds to a
    // PermanentFailure — the same path a PermanentError from the send takes.
    val job =
      try {
        payload.deserialize<EmailJobPayload>()
      } catch (e: Exception) {
        // No [to]/[template] survives an unparseable payload; log the raw payload.
        logger.warn("Discarding email job with malformed payload [{}]", payload, e)
        return JobResult.PermanentFailure("Malformed payload: [${e.message}]")
      }

    val to =
      when (val parsed = EmailAddress.create(job.to)) {
        is ValidationResult.Valid -> {
          parsed.value
        }

        is ValidationResult.Invalid -> {
          return foldSendFailure(InvalidRecipientException(job.to, job.template, parsed.error), job.to, job.template)
        }
      }

    val renderer =
      renderersByTemplate[job.template]
        ?: return foldSendFailure(UnresolvableTemplateException(job.to, job.template), job.to, job.template)

    val rendered =
      renderer.render(job.context).getOrElse { error ->
        return foldSendFailure(EmailRenderException(job.to, job.template, job.context, error), job.to, job.template)
      }

    // Step 5-6: send, then fold the Result against the exception's marker.
    return emailService
      .send(to, rendered.subject, rendered.body)
      .fold(
        onSuccess = { JobResult.Success },
        onFailure = { error -> foldSendFailure(error, job.to, job.template) },
      )
  }

  // [to] and [template] are threaded in so a provider-side EmailRejectedException /
  // EmailDeliveryException — which carry only the provider's reason — yields a
  // JobResult/log message that names the recipient and template. A dead-lettered
  // real SES failure's job_attempts.error_message is then self-sufficient for
  // operator triage without joining back to jobs.payload. (The handler-internal
  // step-1-4 exceptions already carry [to]/[template] in their own message; the
  // brackets below simply prefix them again, which is harmless and keeps one path.)
  private fun foldSendFailure(
    error: Throwable,
    to: String,
    template: EmailTemplate,
  ): JobResult =
    when (error) {
      is PermanentError -> {
        val message = "Permanent email failure to [$to] for template [$template]: [${error.message}]"
        // Permanent failures have no dead-letter alerting, so this log is the only
        // real-time visibility. Its cause is preserved.
        logger.warn("Permanent email job failure: [{}]", message, error)
        JobResult.PermanentFailure(message)
      }

      is TransientError -> {
        val message = "Transient email failure to [$to] for template [$template]: [${error.message}]"
        JobResult.RetriableFailure(message, error)
      }

      // Unmarked failure (e.g. a raw DB error writing the email_sends row after a
      // successful provider send): the handler never invents a classification.
      // Rethrow so the worker's catch default maps it to RetriableFailure and the
      // root cause reaches the worker log unaltered.
      else -> {
        throw error
      }
    }
}
