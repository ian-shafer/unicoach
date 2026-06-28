# SPEC: `common/src/main/kotlin/ed/unicoach/error`

## I. Overview

This package is the **cross-cutting error abstraction layer** for the entire
`unicoach` JVM codebase. It defines the global exception hierarchy and marker
interfaces (traits) used to categorize and handle expected system and domain
errors within and across module boundaries. It also provides the `FieldError`
value type for structured per-field validation failures, and the
`Throwable.errorCategory()` extension function that derives a retryability
vocabulary string from those same traits.

---

## II. Behavioral Contracts

### `ExceptionTraits` — [`ExceptionTraits.kt`](./ExceptionTraits.kt)

- **Purpose**: Defines marker interfaces to categorize exceptions.
- **`interface TransientError`**: Marker for retryable errors. Maps to HTTP 503
  (Service Unavailable) at the routing layer.
- **`interface PermanentError`**: Marker for non-retryable errors. Maps to HTTP
  400 (Bad Request) or HTTP 422 (Unprocessable Entity) depending on the context.

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
  - `ed.unicoach.rest.models.ErrorResponse`: serializes `List<FieldError>?` in
    HTTP error responses.

### `ExceptionExtensions` — [`ExceptionExtensions.kt`](./ExceptionExtensions.kt)

- **Purpose**: Provides a single, shared classifier that maps the
  `TransientError`/`PermanentError` trait to a vocabulary string so log tags and
  structured error objects use the same labels across modules.
- **`fun Throwable.errorCategory(): String`**: Returns `"transient"` when the
  receiver implements `TransientError`, `"permanent"` when it implements
  `PermanentError`, or `"unknown"` when neither trait is present. The three-way
  result is the retryability vocabulary shared by the college loader (log
  tagging) and the college search tool (structured error object).

---

## III. Infrastructure & Environment

- **Module**: `common` Gradle module.
- **Package**: `ed.unicoach.error`.
- **Dependencies**: Kotlin standard library only.
- **Transitive Dependents**: All application modules depend on `common` and have
  access to this package.

---

## IV. History

- [x] [RFC-08: Auth Registration](../../../../../../../rfc/08-auth-registration.md)
      — Defined `FieldError`.
- [x] [RFC-24: Result Types Refactoring](../../../../../../../rfc/24-result-types.md)
      — Replaced the `AppError` hierarchy with `ExceptionTraits`
      (`TransientError` and `PermanentError`), and exclusively adopted
      `Result<T>` for error bubbling.
- [x] [RFC-78: College Scorecard Real-Data Hardening](../../../../../../../rfc/78-college-scorecard-real-data-hardening.md)
      — Added `ExceptionExtensions.kt`: the `Throwable.errorCategory()`
      extension deriving `"transient"` / `"permanent"` / `"unknown"` from the
      existing `TransientError`/`PermanentError` traits, shared by the college
      loader log tagging and the college search tool's structured error object.
