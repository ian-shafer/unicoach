# SPEC: `db/src/main/kotlin/ed/unicoach/db/models`

## I. Overview

The `models` package is the domain type layer for the `db` Gradle module. It
defines the canonical value types and aggregate records used by all DAOs,
service-layer callers, and job handlers. No I/O, no persistence logic, and no
framework dependencies live here — only pure Kotlin data types and their
construction contracts.

Validation primitives (`ValidationResult`, `ValidationError`) and the
`EmailAddress` value type are NOT defined here; they live in the `common` module
(`ed.unicoach.common.models`) and are consumed by the factory methods below.
Their contracts are owned by `common`'s SPEC.

---

## II. Invariants

### Value Types (Inline Classes)

- `DisplayName.create()` MUST trim whitespace. A blank-after-trim input MUST
  return `ValidationResult.Invalid(BlankString)`.
- `PersonName.create()` MUST trim whitespace. A blank-after-trim input MUST
  return `ValidationResult.Invalid(BlankString)`.
- `PasswordHash.create()` MUST trim whitespace. A blank-after-trim input MUST
  return `ValidationResult.Invalid(BlankString)`.
- `SsoProviderId.create()` MUST trim whitespace. A blank-after-trim input MUST
  return `ValidationResult.Invalid(BlankString)`.
- All `@JvmInline value class` types (`UserId`, `UserVersionId`, `StudentId`,
  `StudentVersionId`, `DisplayName`, `PersonName`, `PasswordHash`,
  `SsoProviderId`) MUST expose their raw value through a public `val value`
  property.
- `UserId` and `StudentId` wrap `UUID`; `UserVersionId` and `StudentVersionId`
  wrap `Int`. None of these provide a factory method — they are constructed
  directly and carry no validation logic.

### TokenHash

- `TokenHash` MUST enforce a non-empty `ByteArray` value at construction time;
  an empty array MUST throw `IllegalArgumentException`.
- `TokenHash.equals()` MUST use `ByteArray.contentEquals()`, not reference
  equality, to ensure structural token comparison.
- `TokenHash.hashCode()` MUST use `ByteArray.contentHashCode()`.
- `TokenHash.fromRawToken(token: String)` MUST compute a SHA-256 digest of the
  token encoded as UTF-8 bytes and return the resulting `TokenHash`. This is the
  only approved hashing entry point; callers MUST NOT re-implement SHA-256
  hashing inline.

### AuthMethod

- `AuthMethod` is a sealed interface with exactly three variants: `Password`,
  `SSO`, and `Both`. Adding variants MUST require a coordinated DAO and
  migration change.
- A `Password` variant MUST carry a `PasswordHash`. An `SSO` variant MUST carry
  a `SsoProviderId`. A `Both` variant MUST carry both.

### PartialDate

- `PartialDate` is a domain-agnostic, `java.time`-backed sealed interface
  modeling a variable-precision calendar value with exactly three variants:
  `YearOnly` (year), `YearAndMonth` (year + month), and `FullDate` (year +
  month + day). It carries no domain semantics (birthdate, deadline, graduation)
  and MUST remain reusable.
- Every variant MUST expose `year: Year`, `month: Month?`, and `day: Int?`, with
  the unset components null at the variant's precision (`YearOnly` has null
  `month`/`day`; `YearAndMonth` has null `day`).
- Precision MUST be downward-closed: a value MUST NOT carry a day without a
  month. This is enforced structurally by the variant set (there is no
  day-without-month variant) and by `of()`, which rejects
  `day != null && month == null`.
- `toIso()` MUST emit zero-padded ISO at the stored precision only: `"YYYY"`,
  `"YYYY-MM"`, or `"YYYY-MM-DD"` (year four digits, month/day two digits each).
- The accepted wire form and `toIso()` output MUST be identical: `parse()` and
  `toIso()` are symmetric round-trips at every precision.
- `PartialDate` MUST be constructed only via the `parse()` / `of()` factory
  methods or its variant constructors; neither factory throws — all failures are
  expressed as `ValidationResult.Invalid`. Both factories use
  `ValidationError.InvalidFormat` for every rejection; no new `ValidationError`
  variant is introduced.

### Aggregate Records

- `NewUser` MUST carry a validated `EmailAddress`, `PersonName`, optional
  `DisplayName`, and an `AuthMethod`. It is the sole input type for user
  creation.
- `NewSession` MUST carry a `TokenHash`, a relative `Duration` expiration, and
  optional `userId`, `userAgent`, `initialIp`, and `metadata` fields. Sessions
  with `userId == null` represent anonymous/pre-auth sessions.
- `User` MUST implement both `BaseEntity<UserId, UserVersionId>` and
  `AdvancedEntity`. It MUST carry `deletedAt: Instant?`; a non-null value
  indicates a soft-deleted user.
- `UserVersion` MUST implement `BaseVersionEntity<UserId, UserVersionId>`. It
  MUST carry six timestamps: `createdAt`, `updatedAt` (via `BaseVersionEntity`),
  and `rowCreatedAt`, `rowUpdatedAt` as explicit `val` fields (NOT via
  `AdvancedEntity` — `UserVersion` does NOT implement `AdvancedEntity`). It MUST
  also carry `deletedAt: Instant?`.
- `Student` MUST implement both `BaseEntity<StudentId, StudentVersionId>` and
  `AdvancedEntity`. It MUST carry `userId: UserId`, an
  `expectedHighSchoolGraduationDate:
  PartialDate`, and `deletedAt: Instant?`
  (a non-null value indicates a soft-deleted student).
- `StudentVersion` MUST implement
  `BaseVersionEntity<StudentId, StudentVersionId>`. It MUST carry the same
  domain fields as `Student` (`userId`, `expectedHighSchoolGraduationDate`,
  `deletedAt`) plus `rowCreatedAt`/`rowUpdatedAt` as explicit `val` fields (NOT
  via `AdvancedEntity` — `StudentVersion` does NOT implement `AdvancedEntity`).
- `NewStudent` MUST carry a `userId: UserId` and a validated
  `expectedHighSchoolGraduationDate: PartialDate`. It is the sole input type for
  student creation.
- `Session` MUST include `expiresAt: Instant` as a mandatory field. It MUST NOT
  include a token value or raw token hash — only the opaque UUID session `id`
  and `version` are stored on the model.

### Entity Interfaces

- `BaseEntity<ID, V>` mandates `id: ID`, `versionId: V`, `createdAt`, and
  `updatedAt` on every implementor. Both `ID` and `V` are bounded `: Any`
  (non-null) type parameters; the entity's identity and version-key types MUST
  be carried explicitly rather than hardcoded, so each implementor pairs its own
  id type with its own version-id type (e.g. `UserId`/`UserVersionId`,
  `StudentId`/`StudentVersionId`). `User` and `Student` are the current
  implementors.
- `AdvancedEntity` provides row-level audit timestamps (`rowCreatedAt`,
  `rowUpdatedAt`) distinct from domain-level `createdAt`/`updatedAt`. Types
  implementing `AdvancedEntity` MUST carry all four timestamps.
- `BaseVersionEntity<ID, V>` extends `BaseEntity<ID, V>` and is used exclusively
  for version-history records.

### Validation Outcomes

- Every `create()` / `parse()` / `of()` factory in this package MUST express
  failure as a `ValidationResult.Invalid` (defined in
  `ed.unicoach.common.models`) and MUST NOT throw to signal a rejected value.

---

## III. Behavioral Contracts

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
- **Behavior**: Trims input, validates non-blank. Does NOT hash the value —
  wraps a pre-computed hash string (caller is responsible for hashing before
  calling).
- **Error Handling**: Returns `Invalid(BlankString)` for blank input after trim.
- **Idempotent**: Yes.

### `SsoProviderId.create(value: String): ValidationResult<SsoProviderId>`

- **Side Effects**: None.
- **Behavior**: Trims input, validates non-blank.
- **Error Handling**: Returns `Invalid(BlankString)` for blank input after trim.
- **Idempotent**: Yes.

### `PartialDate.parse(iso: String): ValidationResult<PartialDate>`

- **Side Effects**: None.
- **Behavior**: Validates in two ordered stages. **First**, the input MUST match
  the regex `^\d{4}(-\d{2}(-\d{2})?)?$` (year exactly four digits, optional
  two-digit month, optional two-digit day, no leading sign). Inputs that fail
  the regex — unpadded components (`"2028-6"`, `"2028-6-5"`), signed or overlong
  years (`"+2028"`, `"20281"`) — MUST be rejected before any `java.time` call.
  **Second**, matching inputs are constructed via `java.time` (`Year` /
  `YearMonth.parse` / `LocalDate.parse`), which rejects out-of-range months and
  impossible calendar days.
- **Error Handling**: Every rejection — failing regex, out-of-range month,
  impossible calendar day (`DateTimeParseException`/`DateTimeException`) —
  returns `Invalid(InvalidFormat)`. No exception escapes the method.
- **Idempotent**: Yes. The accepted form equals `toIso()` output, so
  `parse(x.toIso())` round-trips at every precision.

### `PartialDate.of(year: Int, month: Int?, day: Int?): ValidationResult<PartialDate>`

- **Side Effects**: None.
- **Behavior**: Reconstructs a `PartialDate` from decomposed
  `(year, month?, day?)` components — the read-path inverse of the stored-column
  decomposition. A `day` with a null `month` MUST be rejected. Otherwise selects
  the variant by which components are present and validates the month/day via
  `java.time` (`Month.of` / `LocalDate.of`).
- **Error Handling**: `day != null && month == null`, an out-of-range month, or
  an impossible calendar day (`DateTimeException`) returns
  `Invalid(InvalidFormat)`. Because callers feed `of()` only components that
  already satisfy the persistence layer's integrity guarantees, an `Invalid`
  from `of()` on a read path indicates row corruption, not user input.
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
- **Error Handling**: `MessageDigest.getInstance("SHA-256")` is guaranteed to
  succeed on any conformant JVM; no checked exceptions are possible. If the JVM
  lacks SHA-256 support, an unchecked `NoSuchAlgorithmException` propagates —
  this is a fatal misconfiguration, not a recoverable error.
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
  The DAO layer MUST NOT re-validate fields already validated by factory
  methods.

---

## IV. Infrastructure & Environment

- **Module**: `db` Gradle module (`db/build.gradle.kts`).
- **Package**: `ed.unicoach.db.models`.
- **Dependencies**: No external runtime dependencies. Types here use JDK
  standard library types (`UUID`, `Instant`, `Duration`, `ByteArray`,
  `MessageDigest`, and the `java.time` calendar types `Year`, `Month`,
  `YearMonth`, `LocalDate`) plus the validation primitives (`ValidationResult`,
  `ValidationError`) and `EmailAddress` value type from the internal `common`
  module (`ed.unicoach.common.models`).
- **JVM Inline Classes**: `@JvmInline value class` is used for all scalar
  wrapper types to eliminate boxing overhead. Callers on the JVM boundary (e.g.,
  JDBC result-set mappers) MUST unwrap via `.value` when binding parameters.
- **No framework imports**: This package MUST NOT import Ktor, Exposed, kotlinx,
  or any persistence library. Violations are an architectural boundary error.

---

## V. History

- [x] [RFC-07: Users DAO](../../../../../../../../rfc/07-users-dao.md) —
      Introduced `UserId`, `UserVersionId`, `PersonName`, `DisplayName`,
      `PasswordHash`, `SsoProviderId` (original location:
      `rest-server/src/main/kotlin/ed/unicoach/db/models/`; moved to current
      path by RFC-14). Also introduced `EmailAddress` and
      `ValidationResult`/`ValidationError`, which RFC-34 later relocated to
      `common`.
- [x] [RFC-08: Auth Registration](../../../../../../../../rfc/08-auth-registration.md)
      — Introduced `AuthMethod` (sealed interface with `Password`, `SSO`,
      `Both`), `User`, `NewUser`.
- [x] [RFC-11: Sessions](../../../../../../../../rfc/11-sessions.md) —
      Introduced `Session` and `NewSession` models.
- [x] [RFC-14: DB Module](../../../../../../../../rfc/14-db-module.md) — Moved
      all model files from `service/` to
      `db/src/main/kotlin/ed/unicoach/db/models/`. Introduced `Entity.kt`
      (`BaseEntity`, `AdvancedEntity`, `BaseVersionEntity`). Confirmed current
      package location.
- [x] [RFC-21: Session Expiry Queue](../../../../../../../../rfc/21-session-expiry-queue.md)
      — Added `expiresAt: Instant` to `Session`. Extracted
      `TokenHash.fromRawToken()` as a shared companion method (previously a
      private function in `AuthRoutes.kt`).
- [x] [RFC-31: Student Profile](../../../../../../../../rfc/31-student-profile.md)
      — Introduced `PartialDate` (variable-precision `java.time`-backed sealed
      type with two-stage zero-padded-ISO parsing),
      `StudentId`/`StudentVersionId` value classes, and
      `Student`/`NewStudent`/`StudentVersion` aggregates. Generalized
      `BaseEntity`/`BaseVersionEntity` to carry the version-key type as a second
      parameter (`BaseEntity<ID, V>`); updated `User` and `UserVersion` to
      `BaseEntity<UserId, UserVersionId>` /
      `BaseVersionEntity<UserId, UserVersionId>`.
- [x] [RFC-34: Transactional Email Service](../../../../../../../../rfc/34-transactional-email-service.md)
      — Removed `EmailAddress.kt` and `ValidationResult.kt` (the
      `ValidationResult`/`ValidationError` primitives) from this package,
      relocating them to `common` (`ed.unicoach.common.models`). The remaining
      factories (`DisplayName`, `PersonName`, `PasswordHash`, `SsoProviderId`,
      `PartialDate`) and `User`/`UserVersion`/`NewUser` now import these symbols
      from `common`.
