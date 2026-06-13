# SPEC: `ed.unicoach.rest.config`

## I. Overview

This directory owns the typed, validated in-memory view of the REST server's
**request-size limit** configuration. It reads the `server.requestSize` HOCON
block once at startup and produces an immutable value object — a global default
size plus two optional override maps, one keyed by **exact request path** and one
keyed by **path prefix** — that the request-limit enforcement plugin consumes.
Exposing both an exact-match and a prefix-match map is the directory's reason to
exist beyond the default: exact overrides cannot cover dynamic paths (e.g.
`/api/v1/conversations/{id}/messages`), so prefix overrides carry those. The
directory holds both maps verbatim; the precedence between them (exact, then
longest matching prefix, then default) is decided by the consuming plugin, not
here. Size strings in human units (`"8 KiB"`) are
resolved to absolute byte counts here so that no consumer ever parses a size
string. The directory does **not** enforce the limit, locate or merge config
sources, or know anything about the HTTP pipeline that applies the result.

## II. Invariants

- `RequestSizeConfig.from` MUST be **total**: it NEVER throws. Every failure —
  missing section, malformed size string, negative size — MUST be returned as
  `Result.failure`, and every success as `Result.success`.
- Absence of the `server.requestSize` section MUST yield
  `Result.failure(IllegalArgumentException)`. The module NEVER substitutes a
  default-populated configuration for a missing section.
- Every configured size — `defaultMax` and every entry of `routeOverrides` and
  `routePrefixOverrides` — MUST be carried as a `DataSize`. The module NEVER
  exposes a raw `Long` byte primitive across its boundary, and any value that
  violates `DataSize`'s non-negative construction invariant MUST surface as
  `Result.failure`, never as a constructed `RequestSizeConfig`.
- `routeOverrides` and `routePrefixOverrides` MUST each be an **empty map**,
  never null, when the corresponding `server.requestSize.routeOverrides` /
  `server.requestSize.routePrefixOverrides` sub-block is absent.
- Keys of both `routeOverrides` and `routePrefixOverrides` MUST be the **literal
  path strings** exactly as declared in config (e.g. `/api/v1/auth/register`,
  `/api/v1/conversations`), with no normalization, trimming, or logical-name
  translation. The module NEVER applies matching semantics to either map — it
  neither resolves an exact key against a request path nor selects a longest
  prefix; that precedence is the consuming plugin's responsibility.
- A constructed `RequestSizeConfig` MUST be **immutable**: its `defaultMax`,
  `routeOverrides`, and `routePrefixOverrides` reflect a single point-in-time
  read of the source `Config` and never change thereafter.

## III. Behavioral Contracts

### `RequestSizeConfig` (data class)

- Immutable value object: `defaultMax: DataSize`,
  `routeOverrides: Map<String, DataSize>`,
  `routePrefixOverrides: Map<String, DataSize>`.
- Carries no behavior beyond holding the validated values; structural equality
  by its three fields.

### `RequestSizeConfig.from(config: Config): Result<RequestSizeConfig>`

- **Caller**: invoked at server startup with the already-loaded, already-merged
  application `Config`. The directory does not load or merge config itself.
- **Side effects**: **None.** Pure read of the supplied `Config`. No IO, no
  network, no mutation, no logging.
- **Reads**:
  - `server.requestSize.maxSize` — a HOCON size string, resolved to bytes via
    `Config.getBytes` and wrapped as `defaultMax`.
  - `server.requestSize.routeOverrides` (optional) — each child key is a request
    path; its value is a size string resolved via `getBytes` and wrapped as a
    `DataSize` in `routeOverrides`.
  - `server.requestSize.routePrefixOverrides` (optional) — same shape, read by
    the same override-parsing path; each child key is a path **prefix** and its
    value is wrapped as a `DataSize` in `routePrefixOverrides`. The two override
    sub-blocks are read independently; neither's presence depends on the other.
- **Error handling** (all returned as `Result.failure`, never thrown):
  - Missing `server.requestSize` → `IllegalArgumentException`
    (`"Missing configuration section: server.requestSize"`).
  - Missing or malformed `maxSize`, or a malformed override size string → the
    `com.typesafe.config.ConfigException` raised by `getBytes`, captured into
    `Result.failure`.
  - Any configured size that resolves to a negative byte count →
    `IllegalArgumentException` from `DataSize` construction, captured into
    `Result.failure`.
- **Idempotency**: Yes. A pure function of its input `Config`; repeated calls
  with an equal `Config` yield equal results.

## IV. Infrastructure & Environment

- **Config keys read** (under the application `Config`):
  - `server.requestSize.maxSize` — required HOCON size string (e.g. `"8 KiB"`,
    IEC binary / SI decimal / bare byte integer).
  - `server.requestSize.routeOverrides."<path>"` — optional; per-path size
    strings keyed by full request path.
  - `server.requestSize.routePrefixOverrides."<prefix>"` — optional; per-prefix
    size strings keyed by a path prefix (e.g. `/api/v1/conversations`).
- **Cross-module dependency**: `DataSize` (`ed.unicoach.common.util`, in the
  `common` module), the non-negative byte-count value type used for all sizes.
- **Library dependency**: Typesafe Config (`com.typesafe.config`) for
  size-string resolution (`Config.getBytes`) and the exception types raised on
  missing or malformed values.
- No environment variables are read by this directory. Operator overrides such
  as `SERVER_MAX_REQUEST_SIZE` are bound at the config-source layer
  (`rest-server.conf`), outside this module's scope.

## V. History

- [x] [RFC-29: Request Payload Size Limits](../../../../../../../../rfc/29-request-payload-limits.md)
- [x] [RFC-45: Coaching Service and Conversation REST Surface](../../../../../../../../rfc/45-coaching-service.md)
