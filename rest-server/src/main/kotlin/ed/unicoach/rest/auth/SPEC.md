# SPEC.md — `rest-server/src/main/kotlin/ed/unicoach/rest/auth`

## I. Overview

The session configuration layer for the REST server. This directory contains a
single file — [`SessionConfig.kt`](./SessionConfig.kt) — that parses the
HOCON `session {}` block into a strongly typed value object injected into the
routing and plugin layers. It owns no business logic; its sole responsibility is
safe, fail-fast configuration parsing.

---

## II. Invariants

- `SessionConfig.from(config)` MUST return `Result.failure(...)` if the
  `session` block is absent from the provided `Config` instance, with the
  message `"Missing configuration section: session"`.
- `SessionConfig.from(config)` MUST return `Result.failure(...)` if any
  required key within the `session` block is absent or has an incompatible
  type (e.g., non-boolean `cookieSecure`).
- `SessionConfig.from(config)` MUST return `Result.success(SessionConfig)`
  only when all four required keys (`expiration`, `cookieName`,
  `cookieDomain`, `cookieSecure`) are present and parseable.
- `SessionConfig` MUST be a `data class`; equality and hashing are
  structurally derived from all four fields.
- The `expiration` field MUST be typed as `java.time.Duration`, not a raw
  integer or string. HOCON duration strings (e.g., `"7d"`, `"14d"`) are
  resolved by `config.getDuration("expiration")`.
- No default values are supplied for any field. The caller MUST provide all
  four keys; omitting any MUST produce a `Result.failure`.
- `SessionConfig` MUST NOT perform cookie lifecycle operations (reading or
  writing HTTP headers). It is a plain value object.

---

## III. Behavioral Contracts

### `SessionConfig.from(config: Config): Result<SessionConfig>` — [`SessionConfig.kt`](./SessionConfig.kt)

- **Behavior**: Reads the `session` sub-config block from the provided root
  `Config` and constructs a `SessionConfig`. Returns a wrapped `Result`
  instead of throwing.
- **Side effects**: None. Pure in-memory operation; no I/O.
- **Failure modes**:

  | Condition | Return |
  |-----------|--------|
  | `session` block missing | `Result.failure(IllegalArgumentException("Missing configuration section: session"))` |
  | Any key missing or wrong type | `Result.failure(<typesafe ConfigException>)` |
  | All keys valid | `Result.success(SessionConfig(...))` |

- **Idempotency**: Yes — stateless, referentially transparent.
- **Error propagation**: All exceptions from the `typesafe-config` library are
  caught by the surrounding `try/catch(Exception)` and wrapped in
  `Result.failure`. No exception escapes `from()`.

### `SessionConfig` (data class) — [`SessionConfig.kt`](./SessionConfig.kt)

| Property | Type | Source HOCON key | Semantics |
|----------|------|-----------------|-----------|
| `expiration` | `java.time.Duration` | `session.expiration` | Session TTL; passed to `SessionsDao.create()` and `SessionsDao.remintToken()` |
| `cookieName` | `String` | `session.cookieName` | Name of the HTTP session cookie |
| `cookieDomain` | `String` | `session.cookieDomain` | `Domain` attribute on the session cookie |
| `cookieSecure` | `Boolean` | `session.cookieSecure` | `Secure` flag on the session cookie |

---

## IV. Infrastructure & Environment

### HOCON Configuration

`SessionConfig` requires the following keys under the `session {}` block in
`rest-server.conf` (or an environment-variable override):

| Key | Type | Example |
|-----|------|---------|
| `session.expiration` | Duration string | `"7d"` |
| `session.cookieName` | String | `"UNICOACH_SESSION"` |
| `session.cookieDomain` | String | `"unicoach.example.com"` |
| `session.cookieSecure` | Boolean | `true` |

All four keys are **required**. Missing any one key causes startup to abort
via `Result.failure` propagation in `Application.kt`.

### Consumers

`SessionConfig` is instantiated once at application startup via
`SessionConfig.from(config)` in `Application.kt` and injected into:

- `Routing.kt` — passes it to `authRoutes(...)`.
- `plugins/SessionExpiryPlugin.kt` — uses `cookieName` to read the session
  cookie for expiry extension.

---

## V. History

- [x] [RFC-11: Sessions](../../../../../../../../rfc/11-sessions.md) — Introduced
  `SessionConfig.kt` to replace the former `JwtConfig.kt`. Defined the four
  required fields (`expiration`, `cookieName`, `cookieDomain`, `cookieSecure`)
  and mandated `Result<SessionConfig>` return from `from()`.
