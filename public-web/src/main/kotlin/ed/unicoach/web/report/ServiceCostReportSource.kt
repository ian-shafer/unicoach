package ed.unicoach.web.report

import ed.unicoach.coaching.costs.CollegeCostService
import ed.unicoach.coaching.report.ShareToken
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CostReportSharesDao
import ed.unicoach.db.models.TokenHash
import kotlinx.coroutines.CancellationException

/**
 * The in-process [CostReportSource] (RFC 155 D-E): hash the presented token,
 * resolve the live share row, then read that student's cost profile from the
 * same computation the coach reads in chat ([CollegeCostService]) — no HTTP
 * hop to `rest-server`, mirroring [ed.unicoach.auth.DbEmailVerifier].
 *
 * Nothing here writes: the report is a pure read, so a mail client or chat app
 * prefetching the shared link changes nothing (D-H).
 *
 * ONE connection for the whole view, as RFC 155's read path declares: the
 * share lookup and the cost read share the single session this class opens,
 * through [CollegeCostService.readInSession] — the same-session entry point
 * `:service` publishes for exactly this caller.
 *
 * The reason is CONSISTENCY, not load. Two connections would be two points in
 * time: the share row could be revoked, or the student's list edited, between
 * resolving the token and reading the figures, and the page would render a
 * report the second read no longer agrees the first read authorised. One
 * session is one snapshot, so what the token resolved to IS what is printed.
 * (Nesting a second checkout inside `withConnection` is also untidy, but this
 * page runs at roughly one request per second — a claim about pool exhaustion
 * would not be honest.)
 *
 * The cost read is unchanged inside that session — still batched, still
 * list-size independent — so a fifty-school report is still one round.
 */
class ServiceCostReportSource(
  private val database: Database,
  private val costs: CollegeCostService,
) : CostReportSource {
  override suspend fun getByShareToken(rawToken: String): Result<CostReportOutcome> =
    try {
      Result.success(getOutcome(rawToken))
    } catch (e: CancellationException) {
      // The caller unwinding, never a read that failed: cancellation must keep
      // propagating rather than be reported as a database fault (the
      // [CollegeCostService.getForStudent] rule, kept here too).
      throw e
    } catch (e: Exception) {
      // The port's own failure type, so a Hikari checkout timeout does not reach
      // the HTTP layer as a bare java.sql.SQLException. The cause carries the
      // driver fault for the log.
      Result.failure(CostReportReadFailedException(e))
    }

  /**
   * A token this server could never have minted never reaches [TokenHash],
   * because hashing it yields a perfectly valid hash that would simply miss — a
   * SHA-256 and an indexed lookup done to learn something [ShareToken] already
   * answers. It also costs no connection at all.
   *
   * The gate is the MINT SHAPE, not "not blank": the previous underflow check
   * turned `?token=` plus a megabyte of newlines into real work.
   */
  private suspend fun getOutcome(rawToken: String): CostReportOutcome {
    // The port answers for ANY caller, not only the route that also checks: a
    // blank token is its own log reason (BLANK_TOKEN, a truncated URL), and the
    // shape gate below would otherwise report it as MALFORMED_TOKEN.
    if (rawToken.isBlank()) return CostReportOutcome.NotFound(MissReason.BLANK_TOKEN)
    if (!ShareToken.isWellFormed(rawToken)) return CostReportOutcome.NotFound(MissReason.MALFORMED_TOKEN)
    val tokenHash = TokenHash.fromRawToken(rawToken)
    return database.withConnection { session ->
      // Absence and revocation are ONE null from the DAO (a revoked row is
      // invisible there), so this read cannot tell them apart even by accident.
      // Any real fault is still a failed Result, never a dead link.
      val share =
        CostReportSharesDao
          .findLiveByTokenHash(session, tokenHash)
          .getOrThrow()
          ?: return@withConnection CostReportOutcome.NotFound(MissReason.NO_LIVE_SHARE)
      CostReportOutcome.Found(costs.readInSession(session, share.studentId))
    }
  }
}
