# SPEC: `common/src/main/kotlin/ed/unicoach/error`

## I. Overview

This package is the **cross-cutting error abstraction layer** for the entire
`unicoach` JVM codebase. It defines the root `AppError` interface and
`ExceptionWrapper` implementation for propagating typed error chains across
module boundaries, and the `FieldError` value type for structured per-field
validation failures consumed by both the service and REST presentation layers.

---

## II. Invariants

- **INV-1**: Every `AppError` implementation MUST expose a `rootCause: AppError?`
  property. Leaf errors (no upstream cause) MUST set `rootCause = null`.

- **INV-2**: `ExceptionWrapper` MUST be the sole concrete `AppError` used to
  wrap JVM `Throwable` instances. No other class in any module MUST wrap a
  `Throwable` directly into an `AppError` without using `ExceptionWrapper`.

- **INV-3**: `ExceptionWrapper.rootCause` MUST always be `null`. It is a
  terminal node in the error chain; it wraps a `Throwable`, not another
  `AppError`.

- **INV-4**: `FieldError` MUST remain a plain data class with exactly two
  non-nullable `String` fields: `field` (the logical field name) and `message`
  (a human-readable error description). The Kotlin type system enforces
  non-nullability; no runtime blank-check is performed.

- **INV-5**: `AppError` MUST NOT carry HTTP semantics (status codes, headers).
  HTTP mapping is exclusively the responsibility of the `rest-server` layer.

- **INV-6**: The `error` package MUST NOT import from any other module
  (`db`, `service`, `rest-server`, `queue`, `queue-worker`). It depends only
  on the Kotlin standard library.

- **INV-7**: `FieldError` defined in this package is the canonical cross-module
  `FieldError`. The `rest-server` layer MUST NOT define its own parallel
  `FieldError` class; it MUST import `ed.unicoach.error.FieldError` directly.

---

## III. Behavioral Contracts

### `AppError` — [`AppError.kt`](./AppError.kt)

- **Purpose**: Root interface for all typed, domain-level errors in the
  application.
- **`rootCause: AppError?`**: Returns the upstream `AppError` that caused this
  error, enabling recursive cause-chain traversal. Returns `null` at the chain
  root.
- **Side Effects**: None. The interface carries no behavior.
- **Error Handling**: Not applicable — `AppError` is the error representation
  itself.
- **Idempotency**: Not applicable.

### `ExceptionWrapper` — [`AppError.kt`](./AppError.kt)

- **Purpose**: Wraps a JVM `Throwable` as a terminal `AppError`, bridging
  exception-based infrastructure failures into the typed error hierarchy.
- **`ExceptionWrapper(val exception: Throwable)`**: Stores the raw `Throwable`.
  `rootCause` is always `null`.
- **`ExceptionWrapper.from(e: Throwable): ExceptionWrapper`**: Factory method.
  Equivalent to calling the constructor directly; provided for call-site
  readability.
  - **Side Effects**: None.
  - **Error Handling**: Does not throw; accepts any `Throwable`.
  - **Idempotency**: Yes — pure construction, no state mutation.

### `FieldError` — [`FieldError.kt`](./FieldError.kt)

- **Purpose**: Represents a single structured validation failure tied to a named
  input field.
- **`FieldError(val field: String, val message: String)`**: Value type. Equality
  and `hashCode` are structural (data class).
- **Consumers**:
  - `ed.unicoach.util.Validator` / `ValidationErrors`: accumulates
    `List<FieldError>` during input validation in the `common` module.
  - `ed.unicoach.auth.AuthResult.ValidationFailure`: carries
    `List<FieldError>` up through the service layer.
  - `ed.unicoach.rest.models.ErrorResponse`: serializes
    `List<FieldError>?` in HTTP error responses.
- **Side Effects**: None.
- **Error Handling**: Not applicable.
- **Idempotency**: Not applicable.

---

## IV. Infrastructure & Environment

- **Module**: `common` Gradle module.
- **Package**: `ed.unicoach.error`.
- **Dependencies**: Kotlin standard library only. No external JARs, no
  environment variables, no HOCON configuration.
- **Transitive Dependents**: `db`, `service`, `rest-server`, `queue`,
  `queue-worker` all depend on `common` and therefore have access to this
  package. Any module that needs to model a typed error MUST implement
  `AppError`.

---

## V. History

No RFC lists files in `common/src/main/kotlin/ed/unicoach/error/` under its
**"Files Modified"** section. The types were introduced organically during RFC-08
implementation and refined across subsequent RFCs. The RFCs that directly shaped
the contracts documented here are:

- [x] [RFC-08: Auth Registration](../../../../../../../rfc/08-auth-registration.md) —
  Defined `FieldError` and the `AppError` interface with `rootCause`; mandated
  that `DatabaseFailure` variants carry an `AppError` root cause;
  established `FieldError`/`ErrorResponse` as global baseline constructs.
- [x] [RFC-13: Auth Me](../../../../../../../rfc/13-auth-me.md) —
  Established `ExceptionWrapper` as the concrete `AppError` implementation used
  in service-layer `DatabaseFailure` variants (`MeResult.DatabaseFailure`).
- [x] [RFC-14: Extract Database Module](../../../../../../../rfc/14-db-module.md) —
  Confirmed `common` provides `AppError` and `ExceptionWrapper` as shared
  infrastructure consumed by `db` module DAOs.
- [x] [RFC-22: Auth Logout](../../../../../../../rfc/22-auth-logout.md) —
  Further confirmed `ExceptionWrapper` as the canonical error carrier in
  `LogoutResult.DatabaseFailure`; explicitly lists `ExceptionWrapper` as a
  pre-existing dependency.
