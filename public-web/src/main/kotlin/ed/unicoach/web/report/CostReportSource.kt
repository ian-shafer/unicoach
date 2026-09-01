package ed.unicoach.web.report

import ed.unicoach.coaching.costs.CollegeCostProfile

/**
 * The Family Cost Report read, as `public-web` needs it (RFC 155 D-E): a raw
 * share token in, one student's cost profile or nothing out.
 *
 * The narrow port mirrors [ed.unicoach.auth.EmailVerifier]: the route layer
 * never sees a `Database`, a DAO or a hash, so the page's rendering and
 * degradation tests stay DB-free behind a hand-written fake, and the
 * in-process adapter ([ServiceCostReportSource]) is the only thing that knows
 * how a token becomes a student.
 *
 * `getByShareToken` does not throw: a database fault folds to `Result.failure`
 * carrying a [CostReportReadFailedException], leaving the caller to render its
 * own branded outcome (the 503), exactly as the verify-email port does. The
 * failure TYPE is part of the port: without it, a Hikari checkout timeout
 * reached the HTTP layer as a bare `java.sql.SQLException`, so "the route layer
 * never sees a DB" was true of the success path only.
 */
interface CostReportSource {
  suspend fun getByShareToken(rawToken: String): Result<CostReportOutcome>
}

/**
 * The ONE failure a [CostReportSource] reports: the read could not be performed.
 *
 * It names no driver, no SQLSTATE and no connection — a caller renders the 503
 * and has nothing else to decide — and its cause carries the underlying fault
 * for the log.
 */
class CostReportReadFailedException(
  cause: Throwable,
) : Exception("Family Cost Report read failed", cause)

/**
 * What a presented token resolved to.
 *
 * [NotFound] is deliberately ONE RENDERED PAGE for blank, malformed, unknown and
 * revoked tokens alike (RFC 155's read path): distinguishing them on the wire
 * would tell a stranger holding a dead link that the token was once real, so the
 * same branded 404 is served for every one of them, byte for byte.
 *
 * The DOMAIN does not have to be blind for the page to be. [NotFound.reason] is
 * LOG ONLY — never rendered, never on the wire — because an operator answering
 * "the link I sent my wife 404s" otherwise cannot tell a truncated URL from a
 * revoked share.
 */
sealed interface CostReportOutcome {
  /** A live share resolved to this student's current cost profile. */
  data class Found(
    val profile: CollegeCostProfile,
  ) : CostReportOutcome

  /** No live share for this token. [reason] is for the log alone; the page is identical for every case. */
  data class NotFound(
    val reason: MissReason,
  ) : CostReportOutcome
}

/**
 * WHY a presented token resolved to nothing. Log-only, never rendered and never
 * on the wire — the four cases were one argument-less object, so no log could
 * say which of them fired.
 */
enum class MissReason {
  /** No `token` query parameter, or a blank one: nothing was presented. */
  BLANK_TOKEN,

  /** More than one `token` parameter: two credentials presented, a request we have no reading of. */
  REPEATED_TOKEN_PARAM,

  /** A token was presented in a shape this server could never have minted, so it was never hashed. */
  MALFORMED_TOKEN,

  /** A well-formed token, and no live share carries its hash — unknown or revoked, never told apart. */
  NO_LIVE_SHARE,
}
