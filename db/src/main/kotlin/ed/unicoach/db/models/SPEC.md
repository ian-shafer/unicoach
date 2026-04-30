# SPEC: `db/src/main/kotlin/ed/unicoach/db/models`

## I. Overview

The `models` package is the domain type layer for the `db` Gradle module. It defines
the canonical value types, aggregate records, and validation primitives used by all
DAOs, service-layer callers, and job handlers. No I/O, no persistence logic, and no
framework dependencies live here — only pure Kotlin data types and their construction
contracts.

---

## II. Invariants

### Value Types (Inline Classes)

- `EmailAddress` MUST be constructed only via `EmailAddress.create()`; direct
  instantiation of the private constructor MUST NOT be allowed from outside the class.
- `EmailAddress.create()` MUST trim whitespace and lowercase the input before storing
  it. A blank-after-trim input MUST return `ValidationResult.Invalid(BlankString)`.
- `DisplayName.create()` MUST trim whitespace. A blank-after-trim input MUST return
  `ValidationResult.Invalid(BlankString)`.
- `PersonName.create()` MUST trim whitespace. A blank-after-trim input MUST return
  `ValidationResult.Invalid(BlankString)`.
- `PasswordHash.create()` MUST trim whitespace. A blank-after-trim input MUST return
  `ValidationResult.Invalid(BlankString)`.
- `SsoProviderId.create()` MUST trim whitespace. A blank-after-trim input MUST return
  `ValidationResult.Invalid(BlankString)`.
- All `@JvmInline value class` types (`UserId`, `UserVersionId`, `EmailAddress`,
  `DisplayName`, `PersonName`, `PasswordHash`, `SsoProviderId`) MUST expose their
  raw value through a public `val value` property.
- `UserId` wraps `UUID`; `UserVersionId` wraps `Int`. Neither provides a factory
  method — they are constructed directly and carry no validation logic.

### TokenHash

- `TokenHash` MUST enforce a non-empty `ByteArray` value at construction time; an
  empty array MUST throw `IllegalArgumentException`.
- `TokenHash.equals()` MUST use `ByteArray.contentEquals()`, not reference equality,
  to ensure structural token comparison.
- `TokenHash.hashCode()` MUST use `ByteArray.contentHashCode()`.
- `TokenHash.fromRawToken(token: String)` MUST compute a SHA-256 digest of the
  token encoded as UTF-8 bytes and return the resulting `TokenHash`. This is the
  only approved hashing entry point; callers MUST NOT re-implement SHA-256 hashing
  inline.

### AuthMethod

- `AuthMethod` is a sealed interface with exactly three variants: `Password`,
  `SSO`, and `Both`. Adding variants MUST require a coordinated DAO and migration
  change.
- A `Password` variant MUST carry a `PasswordHash`. An `SSO` variant MUST carry a
  `SsoProviderId`. A `Both` variant MUST carry both.

### Aggregate Records

- `NewUser` MUST carry a validated `EmailAddress`, `PersonName`, optional
  `DisplayName`, and an `AuthMethod`. It is the sole input type for user creation.
- `NewSession` MUST carry a `TokenHash`, a relative `Duration` expiration, and
  optional `userId`, `userAgent`, `initialIp`, and `metadata` fields. Sessions with
  `userId == null` represent anonymous/pre-auth sessions.
- `User` MUST implement both `BaseEntity<UserId>` and `AdvancedEntity`. It MUST
  carry `deletedAt: Instant?`; a non-null value indicates a soft-deleted user.
- `UserVersion` MUST implement `BaseVersionEntity<UserId>`. It MUST carry six
  timestamps: `createdAt`, `updatedAt` (via `BaseVersionEntity`), and `rowCreatedAt`,
  `rowUpdatedAt` as explicit `val` fields (NOT via `AdvancedEntity` — `UserVersion`
  does NOT implement `AdvancedEntity`). It MUST also carry `deletedAt: Instant?`.
- `Session` MUST include `expiresAt: Instant` as a mandatory field. It MUST NOT
  include a token value or raw token hash — only the opaque UUID session `id` and
  `version` are stored on the model.

### Entity Interfaces

- Every entity type returned by a DAO MUST implement `BaseEntity<ID>`, which
  mandates `id`, `versionId`, `createdAt`, and `updatedAt`.
- `AdvancedEntity` provides row-level audit timestamps (`rowCreatedAt`,
  `rowUpdatedAt`) distinct from domain-level `createdAt`/`updatedAt`. Types
  implementing `AdvancedEntity` MUST carry all four timestamps.
- `BaseVersionEntity<ID>` extends `BaseEntity<ID>` and is used exclusively for
  version-history records.

### ValidationResult

- `ValidationResult<T>` is a sealed interface with exactly two variants: `Valid<T>`
  (carries the constructed value) and `Invalid` (carries a `ValidationError`).
- `ValidationError` is a sealed interface with three concrete variants:
  `BlankString`, `InvalidFormat`, `TooLong`. No runtime exceptions are thrown by
  factory methods; all failures MUST be expressed as `Invalid`.

---

## III. Behavioral Contracts

### `EmailAddress.create(value: String): ValidationResult<EmailAddress>`

- **Side Effects**: None.
- **Behavior**: Trims input, lowercases it, then validates non-blank.
- **Error Handling**: Returns `Invalid(BlankString)` for blank input after trim.
  MUST NOT perform format validation — `Invalid(InvalidFormat)` and
  `Invalid(TooLong)` `ValidationError` variants are defined in `ValidationResult.kt`
  but are NOT used by any current factory method; format enforcement is the
  caller's responsibility.
- **Idempotent**: Yes — calling twice with identical input returns identical result.

### `DisplayName.create(value: String): ValidationResult<DisplayName>`

- **Side Effects**: None.
- **Behavior**: Trims input, validates non-blank.
- **Error Handling**: Returns `Invalid(BlankString)` for blank input after trim.
- **Idempotent**: Yes.

### `PersonName.create(value: String): ValidationResult<PersonName>`

- **Side Effects**: None.
- **Behavior**: Trims input, validates non-blank.
- **Error Handling**: Returns `Invalid(BlankString)` for blank input after trim.
- **Idempotent**: Yes.

### `PasswordHash.create(value: String): ValidationResult<PasswordHash>`

- **Side Effects**: None.
- **Behavior**: Trims input, validates non-blank. Does NOT hash the value — wraps
  a pre-computed hash string (caller is responsible for hashing before calling).
- **Error Handling**: Returns `Invalid(BlankString)` for blank input after trim.
- **Idempotent**: Yes.

### `SsoProviderId.create(value: String): ValidationResult<SsoProviderId>`

- **Side Effects**: None.
- **Behavior**: Trims input, validates non-blank.
- **Error Handling**: Returns `Invalid(BlankString)` for blank input after trim.
- **Idempotent**: Yes.

### `TokenHash(value: ByteArray)` constructor

- **Side Effects**: None.
- **Behavior**: Validates `value.isNotEmpty()` via `require()`; throws
  `IllegalArgumentException` with message `"TokenHash cannot be empty."` on
  violation.
- **Idempotent**: Yes.

### `TokenHash.fromRawToken(token: String): TokenHash`

- **Side Effects**: None (pure computation).
- **Behavior**: Computes SHA-256 over `token.toByteArray(Charsets.UTF_8)` using
  `java.security.MessageDigest`. Returns the resulting `TokenHash`.
- **Error Handling**: `MessageDigest.getInstance("SHA-256")` is guaranteed to succeed
  on any conformant JVM; no checked exceptions are possible. If the JVM lacks SHA-256
  support, an unchecked `NoSuchAlgorithmException` propagates — this is a fatal
  misconfiguration, not a recoverable error.
- **Idempotent**: Yes — same token always produces the same hash.

### `NewSession` (data class)

- **Side Effects**: None (pure data carrier).
- **Contract**: Carries the construction arguments for a session insert. The
  `expiration: Duration` field is relative (time-to-live), not an absolute
  timestamp. The DAO layer is responsible for converting it to an absolute
  `expires_at` timestamp at insertion time.

### `NewUser` (data class)

- **Side Effects**: None (pure data carrier).
- **Contract**: All fields except `displayName` are mandatory and pre-validated.
  The DAO layer MUST NOT re-validate fields already validated by factory methods.

---

## IV. Infrastructure & Environment

- **Module**: `db` Gradle module (`db/build.gradle.kts`).
- **Package**: `ed.unicoach.db.models`.
- **Dependencies**: No external runtime dependencies. All types in this package use
  only JDK standard library types (`UUID`, `Instant`, `Duration`, `ByteArray`,
  `MessageDigest`).
- **JVM Inline Classes**: `@JvmInline value class` is used for all scalar wrapper
  types to eliminate boxing overhead. Callers on the JVM boundary (e.g., JDBC
  result-set mappers) MUST unwrap via `.value` when binding parameters.
- **No framework imports**: This package MUST NOT import Ktor, Exposed, kotlinx, or
  any persistence library. Violations are an architectural boundary error.

---

## V. History

- [x] [RFC-07: Users DAO](../../../../../../../../rfc/07-users-dao.md) — Introduced `UserId`, `UserVersionId`, `EmailAddress`, `PersonName`, `DisplayName`, `PasswordHash`, `SsoProviderId`, `ValidationResult` (original location: `rest-server/src/main/kotlin/ed/unicoach/db/models/`; moved to current path by RFC-14).
- [x] [RFC-08: Auth Registration](../../../../../../../../rfc/08-auth-registration.md) — Introduced `AuthMethod` (sealed interface with `Password`, `SSO`, `Both`), `User`, `NewUser`.
- [x] [RFC-11: Sessions](../../../../../../../../rfc/11-sessions.md) — Introduced `Session` and `NewSession` models.
- [x] [RFC-14: DB Module](../../../../../../../../rfc/14-db-module.md) — Moved all model files from `service/` to `db/src/main/kotlin/ed/unicoach/db/models/`. Introduced `Entity.kt` (`BaseEntity`, `AdvancedEntity`, `BaseVersionEntity`). Confirmed current package location.
- [x] [RFC-21: Session Expiry Queue](../../../../../../../../rfc/21-session-expiry-queue.md) — Added `expiresAt: Instant` to `Session`. Extracted `TokenHash.fromRawToken()` as a shared companion method (previously a private function in `AuthRoutes.kt`).
