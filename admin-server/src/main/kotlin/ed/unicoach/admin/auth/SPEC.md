# SPEC.md — `admin-server/src/main/kotlin/ed/unicoach/admin/auth`

## I. Overview

The authentication and authorization layer for the admin server. This directory
contains a single file — [`AdminAuth.kt`](./AdminAuth.kt) — owning the
unauthenticated `/login` and `/logout` routes and the `is_admin` gate that
fronts every other request. It translates the shared session/cookie auth
machinery into the admin server's two-stage access control: **authentication**
(any valid user may log in) and **authorization** (only an `is_admin` user may
reach gated pages), kept as separate stages.

---

## II. Behavioral Contracts

### `Route.adminAuthRoutes(authService, config)` — [`AdminAuth.kt`](./AdminAuth.kt)

Registers the unauthenticated `/login` (GET + POST) and `/logout` (POST) routes.
Login authenticates against the shared
[`AuthService`](../../../../../../../../service/src/main/kotlin/ed/unicoach/auth/AuthService.kt);
this layer does not verify credentials, hash passwords, or read the user store
itself. Login does not enforce authorization — a successful login grants a
session to **any** valid user regardless of `is_admin`, and reaching a gated
page still requires passing the gate.

- **`GET /login`** — Renders the login form with no error.
  - **Side effects**: None.
  - **Status**: `200 OK`.

- **`POST /login`** — Reads `email`/`password` form parameters (absent → empty
  string), calls `AuthService.login` (with `oldCookieToken = null`, expiration
  from config, `User-Agent` and remote host from the request), and unwraps the
  `Result`.
  - **Side effects**: Delegates to `AuthService.login`, which performs the
    session write on success. On `Success`, writes the session cookie carrying
    the **raw** token (not a hash). A non-`Success` outcome emits no session
    cookie.
  - **Outcomes**:

    | `LoginResult`     | Response                                            |
    | ----------------- | --------------------------------------------------- |
    | `Success`         | Set cookie (raw token), `302` redirect to `/`       |
    | any other variant | Re-render form, `401 Unauthorized`, generic message |

    Every non-`Success` variant (`UserNotFound`, `PasswordMismatch`,
    `PasswordNotSet`, `InvalidEmail`) re-renders the form with the single
    literal message `"invalid email or password"`; the response does not branch
    on the specific failure variant, so no variant is disclosed.
  - **Failure modes**: A non-`Success` result is a **contractual** login failure
    → `401` form re-render, never an exception. A `Result.failure` from
    `AuthService.login` is a **system** error and propagates (via `getOrThrow`)
    to the server's error handling — it is not caught here.
  - **Idempotency**: No. A successful call mints a new session.

- **`POST /logout`** — Reads the cookie; if present, hashes it via
  `TokenHash.fromRawToken` and calls `AuthService.logout`. Always clears the
  cookie and redirects to `/login`.
  - **Side effects**: Revokes the session via `AuthService.logout` only when a
    cookie is present; clears the cookie unconditionally.
  - **Failure modes**: A `Result.failure` from `AuthService.logout` propagates
    (via `getOrThrow`). A missing cookie is **not** an error — logout still
    clears and redirects.
  - **Idempotency**: Yes. Repeated logout (or logout with no cookie) clears the
    cookie and redirects identically.

The session cookie is written `HttpOnly` with `SameSite=Strict`; its
`path`/`domain`/`secure`/`maxAge` attributes are sourced from
[`AdminConfig`](../AdminConfig.kt). A blank configured `cookieDomain` produces a
host-only cookie (no `Domain` attribute).

### `Application.installAdminGate(authService, config)` — [`AdminAuth.kt`](./AdminAuth.kt)

Installs a pipeline interceptor on `ApplicationCallPipeline.Plugins` that gates
every request except the exact paths `/login`, `/logout`, and `/healthz`. The
exemption set is matched against the request path with any query string
stripped. For a non-exempt request the gate hashes the raw cookie token via
[`TokenHash.fromRawToken`](../../../../../../../../db/src/main/kotlin/ed/unicoach/db/models/TokenHash.kt)
before resolving it — the raw token is never passed to `AuthService`.

- **Side effects**: For non-exempt paths with a present cookie, calls
  `AuthService.getCurrentUser`. Writes a redirect, a `403` page, or a call
  attribute depending on outcome; otherwise none. A missing or blank cookie
  short-circuits to a redirect without invoking `AuthService`.
- **Outcomes**:

  | Condition                             | Response                                      |
  | ------------------------------------- | --------------------------------------------- |
  | Path in `/login` `/logout` `/healthz` | Pass through; gate not applied                |
  | Cookie missing or blank               | `302` redirect to `/login`, pipeline finished |
  | Token resolves to no user             | `302` redirect to `/login`, pipeline finished |
  | User resolved, `isAdmin == false`     | `403 Forbidden` HTML page, pipeline finished  |
  | User resolved, `isAdmin == true`      | Put `User` in `CurrentAdminKey`, proceed      |

  The `isAdmin == false` case responds `403` rather than redirecting: `/login`
  is exempt from the gate, so a redirect would loop. Each
  unauthenticated/unauthorized/forbidden outcome calls `finish()`, terminating
  the pipeline so no downstream handler runs.
- **Failure modes**: A `Result.failure` from `AuthService.getCurrentUser` is a
  **system** error and propagates (via `getOrThrow`); unauthenticated and
  unauthorized are **contractual** outcomes (redirect / `403`), not exceptions.
- **Idempotency**: Yes. Read-only with respect to durable state; resolving the
  same cookie repeatedly yields the same outcome.

### `CurrentAdminKey` / `ApplicationCall.currentAdmin` — [`AdminAuth.kt`](./AdminAuth.kt)

- The typed attribute key (and accessor) under which the gate publishes the
  resolved admin `User` to downstream handlers.
- **Contract**: Present **only** on requests that passed the gate as an admin.
  Reading `currentAdmin` on an exempt route (e.g. `/login`) is unsupported.

---

## III. Infrastructure & Environment

This module reads no configuration directly; all settings arrive via the
injected [`AdminConfig`](../AdminConfig.kt). The fields it consumes:

| `AdminConfig` field        | Use                                                   |
| -------------------------- | ----------------------------------------------------- |
| `cookieName`               | Name of the session cookie read/written by this layer |
| `cookieDomain`             | Cookie `Domain` attribute; blank → host-only cookie   |
| `cookieSecure`             | Cookie `Secure` flag                                  |
| `sessionExpirationSeconds` | Cookie `Max-Age` and the login session TTL            |

The HOCON keys backing these (`admin.session.*`) and their parsing are owned by
`AdminConfig`, not this module.

### Dependencies

- [`AuthService`](../../../../../../../../service/src/main/kotlin/ed/unicoach/auth/AuthService.kt)
  (`login`, `logout`, `getCurrentUser`) — the sole credential/session authority.
- [`TokenHash`](../../../../../../../../db/src/main/kotlin/ed/unicoach/db/models/TokenHash.kt)
  /
  [`User`](../../../../../../../../db/src/main/kotlin/ed/unicoach/db/models/User.kt)
  domain types.
- `kotlinx.html` + Ktor's HTML builder for form/error rendering, via the shared
  [`adminPage`](../render/Layout.kt) layout.

---

## IV. History

- [x] [RFC-60: Admin Website (Framework + Users Spine)](../../../../../../../../rfc/60-admin-website.md)
      — Introduced the admin server's authorization model: unauthenticated
      `/login`/`/logout` delegating to `AuthService`, the generic "invalid email
      or password" failure with no variant disclosure, and the `is_admin` gate
      (exempting `/login`/`/logout`/`/healthz`) that redirects the
      unauthenticated, returns `403` to authenticated non-admins, and publishes
      the resolved admin to handlers.
