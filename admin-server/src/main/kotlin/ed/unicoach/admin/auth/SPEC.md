# SPEC.md — `admin-server/src/main/kotlin/ed/unicoach/admin/auth`

## I. Overview

The authentication and authorization layer for the admin server. This directory
contains a single file — [`AdminAuth.kt`](./AdminAuth.kt) — owning the
unauthenticated `/login` and `/logout` routes and the `is_admin` gate that
fronts every other request. Its singular purpose is to translate the existing
session/cookie auth machinery into the admin server's two-stage access control:
**authentication** (any valid user may log in) and **authorization** (only an
`is_admin` user may reach gated pages), kept strictly separate.

---

## II. Invariants

- Login MUST authenticate against the shared [`AuthService`](../../../../../../../../service/src/main/kotlin/ed/unicoach/auth/AuthService.kt);
  this module MUST NOT verify credentials, hash passwords, or read the user
  store itself.
- Login MUST NOT enforce authorization. A successful login grants a session to
  **any** valid user regardless of `is_admin`; reaching a gated page still
  requires passing the gate. Authentication and authorization MUST remain two
  separate stages.
- Every non-`Success` `LoginResult` MUST re-render the login form with the
  single literal message `"invalid email or password"`. The module MUST NOT
  branch its response on the specific failure variant
  (`UserNotFound`, `PasswordMismatch`, `PasswordNotSet`, `InvalidEmail`) — no
  variant disclosure.
- A failed login MUST NOT set, clear, or otherwise emit a session cookie.
- A successful login MUST set the session cookie carrying the **raw** token (not
  a hash) and redirect to `/`.
- The session cookie MUST be written `HttpOnly` with `SameSite=Strict` and the
  `path`/`domain`/`secure`/`maxAge` attributes sourced from
  [`AdminConfig`](../AdminConfig.kt). A blank configured `cookieDomain` MUST
  produce a host-only cookie (no `Domain` attribute).
- The gate MUST run on every request **except** the exact paths `/login`,
  `/logout`, and `/healthz`. The exemption set is matched against the path with
  any query string stripped.
- A gated request with a missing or blank cookie MUST redirect to `/login` and
  MUST NOT invoke `AuthService`.
- The gate MUST hash the raw cookie token via
  [`TokenHash.fromRawToken`](../../../../../../../../db/src/main/kotlin/ed/unicoach/db/models/TokenHash.kt)
  before resolving it; it MUST NOT pass the raw token to `AuthService`.
- A gated request whose token resolves to no user (unknown/expired/revoked) MUST
  redirect to `/login`.
- A gated request resolving to a user with `isAdmin == false` MUST respond
  `403 Forbidden` with an HTML page. It MUST NOT redirect — `/login` is exempt
  from the gate, so a redirect would loop.
- A gated request resolving to a user with `isAdmin == true` MUST place that
  `User` under `CurrentAdminKey` in the call attributes and let the request
  proceed. Downstream gated handlers MAY rely on `currentAdmin` being present.
- Logout MUST revoke the session via `AuthService.logout` only when a cookie is
  present, MUST clear the cookie unconditionally, and MUST redirect to `/login`.
- Each unauthorized/unauthenticated/forbidden outcome MUST terminate the
  pipeline so no downstream handler runs.

---

## III. Behavioral Contracts

### `Route.adminAuthRoutes(authService, config)` — [`AdminAuth.kt`](./AdminAuth.kt)

Registers the unauthenticated `/login` (GET + POST) and `/logout` (POST) routes.

- **`GET /login`** — Renders the login form with no error.
  - **Side effects**: None.
  - **Status**: `200 OK`.

- **`POST /login`** — Reads `email`/`password` form parameters (absent →
  empty string), calls `AuthService.login` (with `oldCookieToken = null`,
  expiration from config, `User-Agent` and remote host from the request), and
  unwraps the `Result`.
  - **Side effects**: Delegates to `AuthService.login`, which performs the
    session write on success. On `Success`, writes the session cookie.
  - **Outcomes**:

    | `LoginResult`                | Response                                                |
    | ---------------------------- | ------------------------------------------------------ |
    | `Success`                    | Set cookie (raw token), `302` redirect to `/`          |
    | any other variant            | Re-render form, `401 Unauthorized`, generic message    |

  - **Failure modes**: A non-`Success` result is a **contractual** login
    failure → `401` form re-render, never an exception. A `Result.failure` from
    `AuthService.login` is a **system** error and propagates (via `getOrThrow`)
    to the server's error handling — it is not caught here.
  - **Idempotency**: No. A successful call mints a new session.

- **`POST /logout`** — Reads the cookie; if present, hashes it via
  `TokenHash.fromRawToken` and calls `AuthService.logout`. Always clears the
  cookie and redirects to `/login`.
  - **Side effects**: Revokes the session (when a cookie is present); clears the
    cookie.
  - **Failure modes**: A `Result.failure` from `AuthService.logout` propagates
    (via `getOrThrow`). A missing cookie is **not** an error — logout still
    clears and redirects.
  - **Idempotency**: Yes. Repeated logout (or logout with no cookie) clears the
    cookie and redirects identically.

### `Application.installAdminGate(authService, config)` — [`AdminAuth.kt`](./AdminAuth.kt)

Installs a pipeline interceptor on `ApplicationCallPipeline.Plugins` that gates
every non-exempt request.

- **Side effects**: For non-exempt paths with a present cookie, calls
  `AuthService.getCurrentUser`. Writes a redirect, a `403` page, or a call
  attribute depending on outcome; otherwise none.
- **Outcomes**:

  | Condition                                   | Response                                          |
  | ------------------------------------------- | ------------------------------------------------- |
  | Path in `/login` `/logout` `/healthz`       | Pass through; gate not applied                     |
  | Cookie missing or blank                     | `302` redirect to `/login`, pipeline finished      |
  | Token resolves to no user                   | `302` redirect to `/login`, pipeline finished      |
  | User resolved, `isAdmin == false`           | `403 Forbidden` HTML page, pipeline finished       |
  | User resolved, `isAdmin == true`            | Put `User` in `CurrentAdminKey`, proceed           |

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

## IV. Infrastructure & Environment

This module reads no configuration directly; all settings arrive via the
injected [`AdminConfig`](../AdminConfig.kt). The fields it consumes:

| `AdminConfig` field          | Use                                                   |
| ---------------------------- | ----------------------------------------------------- |
| `cookieName`                 | Name of the session cookie read/written by this layer |
| `cookieDomain`               | Cookie `Domain` attribute; blank → host-only cookie   |
| `cookieSecure`               | Cookie `Secure` flag                                  |
| `sessionExpirationSeconds`   | Cookie `Max-Age` and the login session TTL            |

The HOCON keys backing these (`admin.session.*`) and their parsing are owned by
`AdminConfig`, not this module.

### Dependencies

- [`AuthService`](../../../../../../../../service/src/main/kotlin/ed/unicoach/auth/AuthService.kt)
  (`login`, `logout`, `getCurrentUser`) — the sole credential/session authority.
- [`TokenHash`](../../../../../../../../db/src/main/kotlin/ed/unicoach/db/models/TokenHash.kt)
  / [`User`](../../../../../../../../db/src/main/kotlin/ed/unicoach/db/models/User.kt)
  domain types.
- `kotlinx.html` + Ktor's HTML builder for form/error rendering, via the shared
  [`adminPage`](../render/Layout.kt) layout.

---

## V. History

- [x] [RFC-60: Admin Website (Framework + Users Spine)](../../../../../../../../rfc/60-admin-website.md) —
      Introduced the admin server's authorization model: unauthenticated
      `/login`/`/logout` delegating to `AuthService`, the generic
      "invalid email or password" failure with no variant disclosure, and the
      `is_admin` gate (exempting `/login`/`/logout`/`/healthz`) that redirects
      the unauthenticated, returns `403` to authenticated non-admins, and
      publishes the resolved admin to handlers.
