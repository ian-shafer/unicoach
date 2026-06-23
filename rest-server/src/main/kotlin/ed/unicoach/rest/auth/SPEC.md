# SPEC.md — `rest-server/src/main/kotlin/ed/unicoach/rest/auth`

## I. Overview

The authentication support layer for the REST server. It holds two concerns: a
strongly typed value object that parses the HOCON `session {}` block
([`SessionConfig.kt`](./SessionConfig.kt)), and the per-request caller
resolution that turns the session cookie into the live session and user, caching
the result on the request so a single request resolves identity at most once
([`CallerResolution.kt`](./CallerResolution.kt)).

---

## II. Behavioral Contracts

### `resolveCaller` (extension on `ApplicationCall`) — [`CallerResolution.kt`](./CallerResolution.kt)

`suspend fun ApplicationCall.resolveCaller(authService, sessionConfig): ResolvedCaller?`

- **Behavior**: Resolves the authenticated caller for the current request.
  Returns the cached [`ResolvedCaller`](#resolvedcaller--callerresolutionkt)
  when `ResolvedCallerKey` is already present on the call. Otherwise it reads
  the session cookie named by `sessionConfig.cookieName`, hashes the raw token
  via `TokenHash.fromRawToken`, calls `authService.resolveSession(tokenHash)`,
  and on a non-null result builds and caches a `ResolvedCaller` carrying the
  token hash, the session, and the user. Resolves **identity only** — it makes
  no assertion about email verification.
- **Side effects**: Stores the resolved caller on `call.attributes` under
  `ResolvedCallerKey`. The DB lookup is delegated to
  `authService.resolveSession` (no direct persistence access from this layer).
- **Failure modes**:

  | Condition                           | Return                                       |
  | ----------------------------------- | -------------------------------------------- |
  | `ResolvedCallerKey` already present | the cached `ResolvedCaller` (no new lookup)  |
  | No session cookie                   | `null` (nothing cached)                      |
  | `resolveSession` yields `null`      | `null` (nothing cached)                      |
  | `resolveSession` yields a value     | a fresh `ResolvedCaller`, cached on the call |

  A DB fault surfaced by `resolveSession` as a failed `Result` propagates as a
  thrown exception via `getOrThrow` — it is not swallowed into `null`.

- **Caching semantics**: Only a successful lookup writes the attribute, so the
  cached value type is non-null and "attribute present" implies "identity
  resolved by an actual lookup." A missing cookie or a null resolution caches
  nothing; a later call in the same request re-runs the lookup.
- **Idempotency**: After the first successful resolution, repeated calls within
  the same request are no-ops that return the cached value. Across distinct
  requests each call performs its own lookup.

### `ResolvedCaller` (data class) — [`CallerResolution.kt`](./CallerResolution.kt)

The resolved caller for a request. Carries the session cookie's `TokenHash`, the
live `Session` row, and the `User`, all from `ed.unicoach.db.models`. Produced
only by `resolveCaller`; shared between the email-verification gate and the
downstream handler so they reuse one `sessions`+`users` lookup.

### `ResolvedCallerKey` (`AttributeKey<ResolvedCaller>`) — [`CallerResolution.kt`](./CallerResolution.kt)

The call-attribute key under which `resolveCaller` caches its result. Its
presence on a call signals that identity was resolved by an actual lookup.

### `SessionConfig.from(config: Config): Result<SessionConfig>` — [`SessionConfig.kt`](./SessionConfig.kt)

- **Behavior**: Reads the `session` sub-config block from the provided root
  `Config` and constructs a `SessionConfig`. Returns a wrapped `Result` instead
  of throwing. Supplies no default values — all four keys come from config.
- **Side effects**: None. Pure in-memory operation; no I/O.
- **Failure modes**:

  | Condition                     | Return                                                                               |
  | ----------------------------- | ------------------------------------------------------------------------------------ |
  | `session` block missing       | `Result.failure(IllegalArgumentException("Missing configuration section: session"))` |
  | Any key missing or wrong type | `Result.failure(<typesafe ConfigException>)`                                         |
  | All keys valid                | `Result.success(SessionConfig(...))`                                                 |

- **Idempotency**: Yes — stateless, referentially transparent.
- **Error propagation**: Every exception from the `typesafe-config` library is
  caught by the surrounding `try/catch(Exception)` and wrapped in
  `Result.failure`. No exception escapes `from()`.

### `SessionConfig` (data class) — [`SessionConfig.kt`](./SessionConfig.kt)

| Property       | Type                 | Source HOCON key       | Semantics                           |
| -------------- | -------------------- | ---------------------- | ----------------------------------- |
| `expiration`   | `java.time.Duration` | `session.expiration`   | Session TTL (HOCON duration string) |
| `cookieName`   | `String`             | `session.cookieName`   | Name of the HTTP session cookie     |
| `cookieDomain` | `String`             | `session.cookieDomain` | `Domain` attribute on the cookie    |
| `cookieSecure` | `Boolean`            | `session.cookieSecure` | `Secure` flag on the cookie         |

`expiration` is typed as `java.time.Duration`; HOCON duration strings (e.g.
`"7d"`, `"14d"`) are resolved by `config.getDuration("expiration")`.
`SessionConfig` carries no behavior — it neither reads nor writes HTTP cookie
headers.

---

## III. Infrastructure & Environment

### HOCON Configuration

`SessionConfig` requires the following keys under the `session {}` block in
`rest-server.conf` (or an environment-variable override). All four are required;
a missing key produces a `Result.failure`, which `Application.kt` propagates to
abort startup.

| Key                    | Type            | Example                  |
| ---------------------- | --------------- | ------------------------ |
| `session.expiration`   | Duration string | `"7d"`                   |
| `session.cookieName`   | String          | `"UNICOACH_SESSION"`     |
| `session.cookieDomain` | String          | `"unicoach.example.com"` |
| `session.cookieSecure` | Boolean         | `true`                   |

### Collaborators

- `resolveCaller` takes an `AuthService` (from `service/`); identity resolution
  is delegated to `authService.resolveSession`, so this layer holds no
  persistence access of its own.
- `SessionConfig` is constructed once at startup via
  `SessionConfig.from(config)` and injected into the routing and plugin layers
  (notably the session-cookie reader for expiry extension and the
  email-verification gate).

---

## IV. History

- [x] [RFC-11: Sessions](../../../../../../../../rfc/11-sessions.md) —
      Introduced `SessionConfig.kt` (replacing the former `JwtConfig.kt`) with
      its four required fields and `Result<SessionConfig>` return.
- [x] [RFC-25: Auth Routes Refactor](../../../../../../../../rfc/25-auth-routes-refactor.md)
      — Established this directory's `SPEC.md` as part of the auth route
      restructuring.
- [x] [RFC-69: Email Verification Gate](../../../../../../../../rfc/69-email-verification-gate.md)
      — Added `CallerResolution.kt` (`ResolvedCaller`, `ResolvedCallerKey`,
      `ApplicationCall.resolveCaller`) for per-request caller resolution shared
      between the email-verification gate and downstream handlers.
