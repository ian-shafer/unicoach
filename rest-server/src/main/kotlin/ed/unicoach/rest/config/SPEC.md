# SPEC: `ed.unicoach.rest.config`

## I. Overview

This directory owns the typed, validated in-memory view of the REST server's
**request-size limit** configuration. It reads the `server.requestSize` HOCON
block once at startup and produces an immutable value object — a global default
size plus an optional set of per-path overrides — that the request-limit
enforcement plugin consumes. Size strings in human units (`"8 KiB"`) are
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
- Every configured size — `defaultMax` and every entry of `routeOverrides` —
  MUST be carried as a `DataSize`. The module NEVER exposes a raw `Long` byte
  primitive across its boundary, and any value that violates `DataSize`'s
  non-negative construction invariant MUST surface as `Result.failure`, never as
  a constructed `RequestSizeConfig`.
- `routeOverrides` MUST be an **empty map**, never null, when the
  `server.requestSize.routeOverrides` sub-block is absent.
- `routeOverrides` keys MUST be the **full request-path strings** exactly as
  declared in config (e.g. `/api/v1/auth/register`), with no normalization,
  trimming, or logical-name translation.
- A constructed `RequestSizeConfig` MUST be **immutable**: its `defaultMax` and
  `routeOverrides` reflect a single point-in-time read of the source `Config`
  and never change thereafter.

## III. Behavioral Contracts

### `RequestSizeConfig` (data class)

- Immutable value object: `defaultMax: DataSize`,
  `routeOverrides: Map<String, DataSize>`.
- Carries no behavior beyond holding the validated values; structural equality
  by its two fields.

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
