package ed.unicoach.web.fafsa

import ed.unicoach.coaching.aid.FafsaDeadlineService
import ed.unicoach.coaching.aid.FafsaDeadlines

/**
 * The FAFSA deadline read, as `public-web` needs it (RFC 238): nothing in, one
 * answer or a refusal out.
 *
 * The narrow port mirrors [ed.unicoach.web.report.CostReportSource]: the route
 * layer never sees a `Database`, so the page's rendering tests — and there are
 * FOUR answers to render, the resolved one and the service's three refusal arms
 * — run against hand-built values instead of against a seeded corpus and a
 * driven clock. Those four decisions are `FafsaDeadlineService`'s own and are
 * proven against real Postgres in its own suite; re-driving them through a page
 * test would be the same rules asserted twice, in the worse place.
 *
 * It takes no zone. A browser sends none, so the adapter passes null and the
 * service falls back to its one home for that decision
 * ([FafsaDeadlineService.FALLBACK_ZONE]) — a fact worth knowing when reading
 * the page's "As of" line around the turn of a filing year.
 *
 * `get` does not throw: a read that failed folds to `Result.failure` carrying a
 * [FafsaDeadlineReadFailedException], and the route renders the branded 503
 * exactly as the report page does. The failure TYPE is part of the port, and the
 * landed [ed.unicoach.web.report.CostReportSource] was FIXED for exactly this:
 * without it a Hikari checkout timeout reaches the HTTP layer as a bare
 * `java.sql.SQLException`, so "the route layer never sees a `Database`" is true
 * of the success path alone.
 */
fun interface FafsaDeadlineSource {
  suspend fun get(): Result<FafsaDeadlines>
}

/**
 * The ONE failure a [FafsaDeadlineSource] reports: the read could not be
 * performed.
 *
 * It names no driver, no SQLSTATE and no connection -- a caller renders the 503
 * and has nothing else to decide -- and its cause carries the underlying fault
 * for the log.
 */
class FafsaDeadlineReadFailedException(
  cause: Throwable,
) : Exception("FAFSA deadline read failed", cause)

/**
 * The in-process adapter: the real service, narrowed to this one read.
 *
 * It TAKES the service rather than building one, as
 * [ed.unicoach.web.report.ServiceCostReportSource] does: the boot site owns
 * every construction and names both halves in one line, and a test of this
 * adapter can stand a service in -- the clock that service takes included,
 * which is the seam its cycle and expiry arms are driven through.
 */
class ServiceFafsaDeadlineSource(
  private val service: FafsaDeadlineService,
) : FafsaDeadlineSource {
  // NO ZONE PARAMETER. A browser sends none and nothing here varies one, so the
  // adapter states the decision once rather than offering an injection point
  // that would make it look negotiable: the service falls back to its own home
  // for that decision ([FafsaDeadlineService.FALLBACK_ZONE]).
  //
  // THE PORT'S OWN FAILURE TYPE, so no `:db` object and no driver fault crosses
  // into the route layer: the service fills its failure arm with whatever it
  // caught, which is a `DaoException` over a `PSQLException` in practice. The
  // cause keeps it for the log.
  override suspend fun get(): Result<FafsaDeadlines> =
    service
      .get(zone = null)
      .recoverCatching { fault -> throw FafsaDeadlineReadFailedException(fault) }
}
