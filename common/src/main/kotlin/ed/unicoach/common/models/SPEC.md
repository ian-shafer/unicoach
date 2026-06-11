# SPEC: `common/src/main/kotlin/ed/unicoach/common/models`

## I. Overview

The `models` package of the `common` module holds dependency-free, reusable
value types: `EmailAddress`, a validated email-address wrapper, and
`ValidationResult` / `ValidationError`, a generic type for modeling the outcome
of a fallible construction. These are foundation primitives at the bottom of the
dependency graph — they depend on nothing but the Kotlin standard library, so
any module may use them without taking a dependency on any other module.

---

## II. Invariants

### [`ValidationResult`](./ValidationResult.kt) / `ValidationError`

- `ValidationResult` MUST be a sealed (closed) hierarchy: success and failure
  are the only outcomes, and no third variant may be added. `Invalid` MUST be a
  `ValidationResult<Nothing>`, so a single failure value is assignable to a
  `ValidationResult<T>` of any `T`.
- `ValidationResult` MUST model the **expected** outcome of a validating
  construction — success versus a typed, named failure — as ordinary data. It is
  NOT exception machinery: a recoverable rejection MUST be returned as
  `Invalid`, never thrown.
- `ValidationError` MUST be a sealed interface whose variants are the closed,
  named set of validation-failure reasons (currently `Blank`,
  `InvalidFormat`, `TooLong`). A failure MUST be carried as a distinct variant,
  not as a free-form string or error code, so callers can branch exhaustively on
  the reason.
- A validation failure MUST carry the requirement it enforces — the expected
  format for `InvalidFormat`, the limit for `TooLong` — sufficient for a caller
  to render a precise message without access to the rejected input. This
  metadata is the schema requirement, not user data: it is non-sensitive and
  safe to log.
- Requirement metadata MUST be per-variant; the `ValidationError` base MUST NOT
  carry shared metadata. `Blank` deliberately carries nothing — adding a payload
  to it, or hoisting fields onto the base, would undo this decision.
- `ValidationError` MUST NOT carry the raw offending value. The rejected input
  is the least-trusted data in the system; capturing it is the consumer's
  responsibility, never this type's.

### [`EmailAddress`](./EmailAddress.kt)

- `EmailAddress` MUST be a `@JvmInline value class` wrapping a single
  `value: String`, with value semantics and no identity beyond that string.
- `EmailAddress` MUST be immutable: once constructed, its `value` MUST NOT
  change.
- `EmailAddress` MUST be constructed only via `EmailAddress.create()`. The
  primary constructor is private, so no instance can exist that did not pass
  validation — an `EmailAddress` value is a proof that its string is valid.
- `EmailAddress.create()` MUST normalize before validating: it MUST trim
  surrounding whitespace and lowercase the input, and the stored `value` MUST be
  that normalized string.
- A valid address MUST be non-blank after trimming and MUST contain a single
  required interior `@` — at least one character on each side. `create()` MUST
  reject, in order: blank-after-trim as `Invalid(Blank)`, then absence of
  an interior `@` (no `@`, or `@` at the first or last index) as
  `Invalid(InvalidFormat)`; otherwise `Valid(EmailAddress)`. Validation is
  intentionally permissive — it is interior-`@` presence, not the full RFC 5322
  grammar.
- `create()` MUST NOT throw on invalid input and MUST NOT use a sentinel (null,
  empty string) to signal failure. Every rejection MUST be expressed as a
  `ValidationResult.Invalid`.

---

## III. Behavioral Contracts

### `EmailAddress.create(value: String): ValidationResult<EmailAddress>`

- **Side Effects**: None. Pure function of its argument.
- **Behavior**: Trims and lowercases the input, then validates. On success
  returns `Valid(EmailAddress)` wrapping the normalized string. The
  normalization is observable: the stored `value` is trimmed and lowercased, not
  the raw input.
- **Error Handling**: Returns `Invalid(Blank)` when the input is blank
  after trimming; returns `Invalid(InvalidFormat(...))` when the normalized
  value lacks an interior `@`, with the variant carrying the expected address
  shape. No exception is thrown for invalid input. `TooLong` is a defined
  `ValidationError` variant but is NOT produced by this factory.
- **Idempotent**: Yes. Calling with the same input always yields an equal
  result; the normalized output is itself a fixed point (`create(x.value)`
  re-validates to the same address).

### `ValidationResult<T>` (sealed interface, data variants)

- **Side Effects**: None. A pure, immutable data carrier.
- **Contract**: `Valid` and `Invalid` are `data` types with structural equality.
  Consumers MUST branch on the variant (`is Valid` / `is Invalid`) to extract
  the value or the typed error; an `Invalid` carries the full `ValidationError`
  reason without loss.
- **Idempotent**: N/A (no operation; immutable value).

---

## IV. Infrastructure & Environment

- **Module**: `common` Gradle module — the bottom of the module dependency
  graph; it depends on no other module in this codebase.
- **Package**: `ed.unicoach.common.models`.
- **Dependencies**: Kotlin standard library only (`String` and its
  trim/lowercase/indexing operations). No `java.*`, no third-party, no framework
  imports. This package MUST NOT import any persistence, transport, or other
  application module; doing so would invert the dependency graph and is an
  architectural boundary error.
- **JVM boundary**: Callers crossing a JVM/reflection boundary MUST unwrap
  `EmailAddress` via `.value`.

---

## V. History

- [x] [RFC-07: Users DAO](../../../../../../../../rfc/07-users-dao.md) —
      Introduced `EmailAddress`, `ValidationResult`, and `ValidationError` (with
      the `BlankString`, `InvalidFormat`, `TooLong` variants). Original
      location: `rest-server/src/main/kotlin/ed/unicoach/db/models/`.
- [x] [RFC-14: DB Module](../../../../../../../../rfc/14-db-module.md) —
      Relocated both files into the `db` module at
      `db/src/main/kotlin/ed/unicoach/db/models/` (no behavior change).
- [x] [RFC-34: Transactional Email Service](../../../../../../../../rfc/34-transactional-email-service.md)
      — Relocated both files into the `common` module at the current path so
      multiple modules can reuse them without depending on each other. Added
      interior-`@` presence validation to `EmailAddress.create()` (previously
      rejected only blank input), yielding `Invalid(InvalidFormat)` when the
      normalized value has no interior `@`.
- [x] [RFC-40: Richer Validation-Error Reporting](../../../../../../../../rfc/40-validation-error-reporting.md)
      — Renamed `BlankString` to `Blank` and enriched `InvalidFormat` and
      `TooLong` with the requirement they enforce (expected format and maximum
      length, respectively).
