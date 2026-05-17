# SPEC: `common/src/main/kotlin/ed/unicoach/error`

## I. Overview

This package is the **cross-cutting error abstraction layer** for the entire
`unicoach` JVM codebase. It defines the global exception hierarchy and
marker interfaces (traits) used to categorize and handle expected system
and domain errors within and across module boundaries. It also provides the `FieldError`
value type for structured per-field validation failures.

---

## II. Invariants

- **INV-1**: The exception hierarchy MUST use marker interfaces (`TransientError`, `PermanentError`) to categorize exceptions, rather than a rigid tree structure.
- **INV-2**: `TransientError` MUST be applied to retryable errors (e.g., database locks, connection timeouts).
- **INV-3**: `PermanentError` MUST be applied to non-retryable errors (e.g., duplicate unique constraints, target version missing).
- **INV-4**: Domain exceptions MUST inherit from standard JVM `RuntimeException` and implement the relevant trait interfaces.
- **INV-5**: `FieldError` MUST remain a plain data class with exactly two
  non-nullable `String` fields: `field` (the logical field name) and `message`
  (a human-readable error description).
- **INV-6**: Exceptions in this package MUST NOT carry HTTP semantics (status codes, headers).
  HTTP mapping is exclusively the responsibility of the presentation layer.
- **INV-7**: The `error` package MUST NOT import from any other module.

---

## III. Behavioral Contracts

### `ExceptionTraits` — [`ExceptionTraits.kt`](./ExceptionTraits.kt)

- **Purpose**: Defines marker interfaces to categorize exceptions.
- **`interface TransientError`**: Marker for retryable errors. Maps to HTTP 503 (Service Unavailable) at the routing layer.
- **`interface PermanentError`**: Marker for non-retryable errors. Maps to HTTP 400 (Bad Request) or HTTP 422 (Unprocessable Entity) depending on the context.

### `FieldError` — [`FieldError.kt`](./FieldError.kt)

- **Purpose**: Represents a single structured validation failure tied to a named
  input field.
- **`FieldError(val field: String, val message: String)`**: Value type. Equality
  and `hashCode` are structural (data class).
- **Consumers**:
  - `ed.unicoach.util.Validator` / `ValidationErrors`: accumulates
    `List<FieldError>` during input validation in the `common` module.
  - `ed.unicoach.auth.RegisterOutcome.ValidationFailure`: carries
    `List<FieldError>` up through the service layer.
  - `ed.unicoach.rest.models.ErrorResponse`: serializes
    `List<FieldError>?` in HTTP error responses.

---

## IV. Infrastructure & Environment

- **Module**: `common` Gradle module.
- **Package**: `ed.unicoach.error`.
- **Dependencies**: Kotlin standard library only.
- **Transitive Dependents**: All application modules depend on `common` and
  have access to this package.

---

## V. History

- [x] [RFC-08: Auth Registration](../../../../../../../rfc/08-auth-registration.md) —
  Defined `FieldError`.
- [x] [RFC-24: Result Types Refactoring](../../../../../../../rfc/24-result-types.md) —
  Replaced the `AppError` hierarchy with `ExceptionTraits` (`TransientError` and `PermanentError`), and exclusively adopted `Result<T>` for error bubbling.
