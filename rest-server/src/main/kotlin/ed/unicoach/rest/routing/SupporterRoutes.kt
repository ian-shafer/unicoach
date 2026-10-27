package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.coaching.budget.Entitlement
import ed.unicoach.db.models.SupporterStudentLinkId
import ed.unicoach.family.EnsureSupporterOutcome
import ed.unicoach.family.SupporterHome
import ed.unicoach.family.SupporterService
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.CoachingCoverage
import ed.unicoach.rest.models.CoachingCoverageKind
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.models.SupporterCoachingUsageResponse
import ed.unicoach.rest.models.toCoachingUsage
import ed.unicoach.rest.models.toResponse
import ed.unicoach.rest.pathId
import ed.unicoach.rest.rejectUnsupportedMethods
import ed.unicoach.rest.respondSupporterNotFound
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** The door that makes an account parent-shaped (RFC 241). */
const val SUPPORTERS_PATH = "/api/v1/supporters"

/** The home read, derived from the mount rather than retyped. */
const val SUPPORTER_ME_SEGMENT = "/me"

const val SUPPORTER_ME_PATH = "$SUPPORTERS_PATH$SUPPORTER_ME_SEGMENT"

/** The coaching meter for the supporter's own account (RFC 244), under the home read. */
const val SUPPORTER_COACHING_USAGE_SEGMENT = "/coaching-usage"

const val SUPPORTER_COACHING_USAGE_PATH = "$SUPPORTER_ME_PATH$SUPPORTER_COACHING_USAGE_SEGMENT"

/** The supporter's disconnect from one student (RFC 249), under the home read. */
const val SUPPORTER_STUDENT_LINK_SEGMENT = "/students/{$LINK_ID_PARAM}"

const val SUPPORTER_STUDENT_LINK_PATH = "$SUPPORTER_ME_PATH$SUPPORTER_STUDENT_LINK_SEGMENT"

/**
 * The four supporter doors: become a supporter (`POST /api/v1/supporters`), read
 * the supporter home (`GET /api/v1/supporters/me`) — both RFC 241 — read how
 * the parent's own coaching is paid for
 * (`GET /api/v1/supporters/me/coaching-usage`, RFC 244), and end one's own
 * access to a student (`DELETE /api/v1/supporters/me/students/{linkId}`, RFC 249).
 *
 * All four sit behind the session check and, unlike RFC 232's accept path,
 * behind `EmailVerificationGate` — none is on the path of an invite a stranger
 * followed, so none needs the X8 exemption and a parent verifies their email
 * exactly as a student does.
 *
 * The delegated seam is [CallerAuthentication], NOT [CallerResolution]:
 * `resolveStudent` and its 409 `student_profile_required` are not this door's
 * to send — a supporter is precisely the caller who has no student profile, and
 * answering them "a student profile is required" is the bug this slice exists
 * to remove. The split keeps that refusal off the class rather than off a
 * comment.
 */
class SupporterRoutes(
  authService: AuthService,
  sessionConfig: SessionConfig,
  private val supporterService: SupporterService,
  private val budgetService: BudgetService,
) : CallerAuthentication by SessionCallerAuthentication(authService, sessionConfig) {
  fun registerRoutes(route: Route) {
    route.route(SUPPORTERS_PATH) {
      post { handleCreate() }
      rejectUnsupportedMethods(HttpMethod.Post)
      route(SUPPORTER_ME_SEGMENT) {
        get { handleGetMe() }
        rejectUnsupportedMethods(HttpMethod.Get)
        route(SUPPORTER_COACHING_USAGE_SEGMENT) {
          get { handleGetCoachingUsage() }
          rejectUnsupportedMethods(HttpMethod.Get)
        }
        route(SUPPORTER_STUDENT_LINK_SEGMENT) {
          delete { handleDisconnect() }
          rejectUnsupportedMethods(HttpMethod.Delete)
        }
      }
    }
  }

  /**
   * Makes the caller a supporter, idempotently: `201` when this call minted the
   * row, `200` when it was already there, the same body either way.
   *
   * It reads NO request body. There is nothing to ask — the row carries only the
   * user it belongs to — and a body a client may omit is a deserialization
   * failure waiting for the first client that omits it.
   *
   * A caller who also holds a `students` row is not refused, on purpose: a
   * `supporters` row is a home surface, not a claim about what somebody is not.
   *
   * The one refusal is `409 supporter_closed`, for a caller whose supporter row
   * was CLOSED: the one-per-user index is total, so no live row can be minted
   * for them again and `family/09` owns re-opening.
   */
  private suspend fun RoutingContext.handleCreate() {
    val user = resolveUser() ?: return respondUnauthorized()

    when (val outcome = supporterService.ensure(user.id).getOrThrow()) {
      is EnsureSupporterOutcome.Created -> respondSupporterMinted(outcome.home)
      is EnsureSupporterOutcome.AlreadyPresent -> respondSupporterHome(outcome.home)
      EnsureSupporterOutcome.Closed -> respondSupporterClosed()
    }
  }

  private suspend fun RoutingContext.handleGetMe() {
    val user = resolveUser() ?: return respondUnauthorized()

    val home = supporterService.homeFor(user.id).getOrThrow() ?: return respondSupporterNotFound()
    respondSupporterHome(home)
  }

  /**
   * The supporter ends their own access to one student (RFC 249). A caller with
   * no supporter row holds no link, so they get the same `404
   * supporter_link_not_found` as for a link they never held — not
   * `supporter_not_found`, which would answer a different question.
   */
  private suspend fun RoutingContext.handleDisconnect() {
    val user = resolveUser() ?: return respondUnauthorized()

    respondDisconnect(pathId(LINK_ID_PARAM, ::SupporterStudentLinkId)) { linkId -> supporterService.disconnectAsSupporter(user.id, linkId) }
  }

  /**
   * How the parent's OWN coaching is paid for (RFC 244): the same abstract meter
   * the student route reports, plus which allowance it is metering.
   *
   * It meters the CALLER'S OWN ACCOUNT — the account that pays, since every
   * account pays for itself, and the two branches the gate has are the two
   * [CoachingCoverageKind] members. The `supporters` read above it is for the
   * `404` and nothing else: the gate's subject is the account the session already
   * names, so handing back a `SupporterId` would only buy a second read of the
   * row this handler just read. A caller who holds both a `students` and a
   * `supporters` row sees ONE subscription's meter on both surfaces, because both
   * read that one account's ledger.
   *
   * `404` for a caller with no supporter row, the same answer
   * `GET /api/v1/supporters/me` gives for the same absence, rather than a 200 with
   * a zero bar for a parent surface this caller does not have.
   *
   * A failed read is a 500 and never a zero: [BudgetService] fails closed, and the
   * app renders "unavailable" rather than 0% — a silent zero would tell a parent
   * their allowance is untouched at the moment the meter cannot be read.
   */
  private suspend fun RoutingContext.handleGetCoachingUsage() {
    val user = resolveUser() ?: return respondUnauthorized()

    supporterService.find(user.id).getOrThrow() ?: return respondSupporterNotFound()
    respondCoachingUsage(budgetService.entitlement(user.id).getOrThrow())
  }

  /** `200` — the account's own meter, and which of the two allowances it reads. */
  private suspend fun RoutingContext.respondCoachingUsage(entitlement: Entitlement) {
    call.respond(
      HttpStatusCode.OK,
      SupporterCoachingUsageResponse(
        usage = entitlement.toCoachingUsage(),
        coverage = CoachingCoverage(CoachingCoverageKind.from(entitlement.basis)),
      ),
    )
  }

  /** `201` — THIS call wrote the row. */
  private suspend fun RoutingContext.respondSupporterMinted(home: SupporterHome) {
    call.respond(HttpStatusCode.Created, home.toResponse())
  }

  /**
   * `200` with the home — the row was already there, or the `GET` asked for it.
   * ONE place decides that status/body pair, so the two doors cannot drift.
   */
  private suspend fun RoutingContext.respondSupporterHome(home: SupporterHome) {
    call.respond(HttpStatusCode.OK, home.toResponse())
  }

  /**
   * The mint's one refusal: a CLOSED `supporters` row. `409`, because the
   * request was well formed and was refused on the state of the account — and
   * because the alternative was the DAO's own "already exists" violation
   * reaching the client as an untyped `400` about a row that does not exist.
   */
  private suspend fun RoutingContext.respondSupporterClosed() {
    call.respond(
      HttpStatusCode.Conflict,
      ErrorResponse(ErrorCode.SUPPORTER_CLOSED, "This account's supporter profile was closed and cannot be reopened"),
    )
  }
}
