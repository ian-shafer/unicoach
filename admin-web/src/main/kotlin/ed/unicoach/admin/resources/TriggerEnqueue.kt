package ed.unicoach.admin.resources

import ed.unicoach.admin.render.respondDaoError
import ed.unicoach.queue.EnqueueResult
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import org.slf4j.Logger

/**
 * Shared outcome handler for the admin manual-trigger routes (RFC 100). The three
 * trigger handlers (extraction on [ConvosResource]; synthesis and fit-lens on
 * [StudentsResource]) enqueue their feature's existing job type/payload and then
 * route the [EnqueueResult] identically: [EnqueueResult.Success] plain-redirects
 * to the acting row's detail page (no confirmation banner, matching
 * `PeriodicJobsResource`); [EnqueueResult.DatabaseFailure] logs the acting
 * entity id plus the error and renders the shared DAO-error page.
 *
 * [redirectTo] is the row's own detail path; [jobLabel] names the trigger for the
 * warn line; [rowId] identifies the acting row; [logger] is the caller
 * resource's logger so the warning is attributed to that resource.
 */
internal suspend fun <ID> ApplicationCall.respondEnqueueOutcome(
  result: EnqueueResult,
  redirectTo: String,
  jobLabel: String,
  rowId: ID,
  logger: Logger,
) {
  when (result) {
    is EnqueueResult.Success -> {
      respondRedirect(redirectTo)
    }

    is EnqueueResult.DatabaseFailure -> {
      logger.warn("[{}] trigger enqueue failed for entity=[{}]", jobLabel, rowId, result.error)
      respondDaoError(result.error)
    }
  }
}
