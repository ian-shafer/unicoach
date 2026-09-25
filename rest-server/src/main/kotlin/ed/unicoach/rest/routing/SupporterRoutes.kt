package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.family.EnsureSupporterOutcome
import ed.unicoach.family.SupporterHome
import ed.unicoach.family.SupporterService
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.models.toResponse
import ed.unicoach.rest.rejectUnsupportedMethods
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** The door that makes an account parent-shaped (RFC 241). */
const val SUPPORTERS_PATH = "/api/v1/supporters"

/** The home read, derived from the mount rather than retyped. */
const val SUPPORTER_ME_SEGMENT = "/me"

const val SUPPORTER_ME_PATH = "$SUPPORTERS_PATH$SUPPORTER_ME_SEGMENT"

/**
 * The two supporter doors (RFC 241): become a supporter
 * (`POST /api/v1/supporters`), and read the supporter home
 * (`GET /api/v1/supporters/me`).
 *
 * Both sit behind the session check and, unlike RFC 232's accept path, behind
 * `EmailVerificationGate` — neither is on the path of an invite a stranger
 * followed, so neither needs the X8 exemption and a parent verifies their email
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
) : CallerAuthentication by SessionCallerAuthentication(authService, sessionConfig) {
  fun registerRoutes(route: Route) {
    route.route(SUPPORTERS_PATH) {
      post { handleCreate() }
      rejectUnsupportedMethods(HttpMethod.Post)
      route(SUPPORTER_ME_SEGMENT) {
        get { handleGetMe() }
        rejectUnsupportedMethods(HttpMethod.Get)
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
   * The mirror of `GET /api/v1/students/me`'s `404 student_not_found`: the same
   * "you do not have one of these" fact, in the same shape, on the parallel
   * route. It is not a 409 `student_profile_required` — that refusal tells a
   * caller to go and make a STUDENT, which is the wrong instruction here and the
   * one the client is being taught to stop obeying.
   */
  private suspend fun RoutingContext.respondSupporterNotFound() {
    call.respond(
      HttpStatusCode.NotFound,
      ErrorResponse(ErrorCode.SUPPORTER_NOT_FOUND, "No supporter profile for the current user"),
    )
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
