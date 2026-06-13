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

- [`DisplayName.create()`](./DisplayName.kt) MUST trim whitespace. A
  blank-after-trim input MUST return `ValidationResult.Invalid(Blank)`.
- [`PersonName.create()`](./PersonName.kt) MUST trim whitespace. A
  blank-after-trim input MUST return `ValidationResult.Invalid(Blank)`.
- [`PasswordHash.create()`](./PasswordHash.kt) MUST trim whitespace. A
  blank-after-trim input MUST return `ValidationResult.Invalid(Blank)`.
- [`SsoProviderId.create()`](./SsoProviderId.kt) MUST trim whitespace. A
  blank-after-trim input MUST return `ValidationResult.Invalid(Blank)`.
- [`ConvoName.create()`](./ConvoName.kt) MUST trim whitespace. A
  blank-after-trim input MUST return `ValidationResult.Invalid(Blank)`. An input
  exceeding 255 characters after trim MUST return
  `ValidationResult.Invalid(TooLong(maxLength = 255))`. `ConvoName`'s
  constructor is private; `create()` is the sole construction path.
- All `@JvmInline value class` types (`UserId`, `StudentId`, `SessionId`,
  `DisplayName`, `PersonName`, `PasswordHash`, `SsoProviderId`, `ConvoId`,
  `ConvoRequestId`, `ConvoResponseId`, `SystemPromptId`, `ConvoName`) MUST
  expose their raw value through a public `val value` property.
- `UserId`, `StudentId`, and `SessionId` each wrap `UUID` and implement `Id`,
  deriving `asString` from `value.toString()`. None of these provide a factory
  method — they are constructed directly and carry no validation logic. Per-row
  versions are a plain `Int`, NOT a wrapped value class.
- `ConvoId` and `SystemPromptId` wrap `UUID`; `ConvoRequestId` and
  `ConvoResponseId` wrap `Long` (the BIGINT identity primary keys of
  `convo_requests`/`convo_responses`). This split is a structural guarantee: the
  two log-row ids MUST NOT be remodeled as `UUID`. None of these four carry a
  factory or validation logic.

### TokenHash

- [`TokenHash`](./TokenHash.kt) MUST enforce a non-empty `ByteArray` value at
  construction time; an empty array MUST throw `IllegalArgumentException`.
- `TokenHash.equals()` MUST use `ByteArray.contentEquals()`, not reference
  equality, to ensure structural token comparison.
- `TokenHash.hashCode()` MUST use `ByteArray.contentHashCode()`.
- `TokenHash.fromRawToken(token: String)` MUST compute a SHA-256 digest of the
  token encoded as UTF-8 bytes and return the resulting `TokenHash`. This is the
  only approved hashing entry point; callers MUST NOT re-implement SHA-256
  hashing inline.

### AuthMethod

- [`AuthMethod`](./AuthMethod.kt) is a sealed interface with exactly three
  variants: `Password`, `SSO`, and `Both`. Adding variants MUST require a
  coordinated DAO and migration change.
- A `Password` variant MUST carry a `PasswordHash`. An `SSO` variant MUST carry
  a `SsoProviderId`. A `Both` variant MUST carry both.

### PartialDate

- [`PartialDate`](./PartialDate.kt) is a domain-agnostic, `java.time`-backed
  sealed interface modeling a variable-precision calendar value with exactly
  three variants: `YearOnly` (year), `YearAndMonth` (year + month), and
  `FullDate` (year + month + day). It carries no domain semantics (birthdate,
  deadline, graduation) and MUST remain reusable.
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
  expressed as `ValidationResult.Invalid`. Every rejection from either factory
  MUST return `Invalid(InvalidFormat)` carrying a description of the expected
  forms.
- Every `InvalidFormat` emitted by `parse()` and `of()` MUST carry the one
  canonical expected-form description, defined exactly once on `PartialDate`. A
  new rejection site MUST NOT introduce ad-hoc, divergent expected-form text.

### Aggregate Records

Each row type implements exactly the capability interfaces matching its
lifecycle class. The load-bearing guarantees are the capabilities a type
DELIBERATELY OMITS and its input/snapshot contracts — never the mere presence of
an identity, timestamp, or field, which the data class declarations already
state.

- **Mutable entities** — `User` and `Student` — are the only types here that are
  both `Versioned` and `SoftDeletable`: their `version` is an OCC counter and
  their `deletedAt: Instant?` marks a soft delete when non-null. `Student`
  additionally holds a validated `expectedHighSchoolGraduationDate: PartialDate`
  (a `PartialDate`, never a raw date string). Stripping `Versioned` or
  `SoftDeletable` from either is a schema/lifecycle violation.
- **Version-snapshot rows** — `UserVersion` and `StudentVersion` — are
  immutable: `Created` and `Versioned` only, and MUST NOT implement `Updated` or
  `SoftDeletable` even though they carry `updatedAt: Instant` and
  `deletedAt: Instant?` as plain `val`s capturing the values at the instant the
  version was written. Each MUST mirror the domain fields of its live entity.
- `Session` is `Created` and `Versioned` only — a versioned, immutable row. It
  MUST carry `expiresAt: Instant` and MUST NOT store a token value or raw token
  hash; only the typed `SessionId` and `version` live on the model.
- `Convo` is `Updated` and `SoftDeletable` but MUST NOT be `Versioned`: OCC is
  deliberately disabled for this aggregate (the `convos` table has no `version`
  column), unlike `User`/`Student`. Its `deletedAt: Instant?` carries the usual
  soft-delete semantics.
- `Convo` carries `archivedAt: Instant?` as a plain `val` — an archive axis
  INDEPENDENT of and orthogonal to soft-delete: archive is a reversible
  user-facing shelf state (set/clear `archived_at`), NOT a `SoftDeletable`
  capability. The two nullable timestamps MUST stay distinct columns and MUST
  NOT be collapsed into one another. `archivedAt` keeps the model 1:1 with the
  `convos` table and MUST NOT be promoted to a typed wrapper.
- `ConvoRequest`, `ConvoResponse`, and `ConvoResponseRaw` are append-only log
  rows: each is `Created` only and MUST NEVER be `Updated` or `SoftDeletable`.
  `ConvoResponseRaw` keeps the verbatim provider `payload` separate from the
  normalized `ConvoResponse` and MUST NOT be folded into it.
- `SystemPrompt` is an immutable catalog row (`Identifiable` + `Created` only):
  rows are authored by migration and MUST NEVER be `Updated`, `Versioned`, or
  `SoftDeletable` — the catalog is append-only and rows are never mutated in
  place. Its `version: String` is a catalog version LABEL, NOT an OCC counter,
  and MUST NOT be modeled as the `Versioned` capability. It reuses the existing
  `SystemPromptId` value class.
- `ConvoWithActivity` is a pure read PROJECTION, not a persisted entity: it
  wraps a `Convo` plus a derived `lastActivityAt: Instant?`. It MUST NOT
  implement any capability interface and MUST NOT be treated as a row type.
  `lastActivityAt` is `MAX(convo_requests.created_at)` over ALL request rows —
  failed turns included, since a failed attempt is still activity — and is null
  for a conversation with no turns. It MUST NOT be narrowed to successful turns
  only.
- `ConvoTurn.response` MUST be nullable: a request and its response are written
  in separate transactions, so a committed request can exist with no response
  row (provider in flight, or the response transaction never ran).
- Per-row `version` is ALWAYS a plain `Int` OCC counter, NEVER a domain-typed
  wrapper value class.
- Input records carry NO server-assigned identity or timestamp and are each the
  SOLE creation input for their aggregate: `NewUser` (fields pre-validated, only
  `displayName` optional), `NewStudent` (validated `PartialDate`), `NewConvo`,
  `NewConvoRequest`, `NewConvoResponse`. `NewSession` additionally carries a
  RELATIVE `expiration: Duration` (a TTL the DAO converts to an absolute
  `expires_at`), not an absolute timestamp; a null `userId` marks an
  anonymous/pre-auth session.
- No model in this package carries `rowCreatedAt`/`rowUpdatedAt`. The
  `row_created_at`/`row_updated_at` Postgres columns and their triggers REMAIN
  in the database but are deliberately NOT projected into the domain models.
  Adding a row-timestamp property to any model here is a layering violation;
  dropping the DB columns/triggers is a separate schema concern this layer does
  NOT track.

### Capability Interfaces

The entity supertypes are one-capability-each interfaces in
[Entity.kt](./Entity.kt); a data class declares only the capabilities it
actually has. There MUST be no welded multi-capability supertype.

- `Id` — marker for identity types, exposing a derived `asString: String`
  backing-agnostic view (e.g. `UUID.toString()`). Implementors MUST retain their
  typed `value`; `asString` MUST be derived, never stored.
- `Identifiable<ID : Id>` — mandates `id: ID`; every persisted row, including
  append-only version rows, carries a typed identity bound to `Id`.
- `Created` — mandates `createdAt: Instant`; every row.
- `Updated` — mandates `updatedAt: Instant`; mutable rows only.
- `Versioned` — mandates `version: Int` (OCC counter). The version is a plain
  `Int`, NOT a domain-typed key; there MUST be no version-id wrapper value
  class.
- `SoftDeletable` — mandates `deletedAt: Instant?`; logical-delete rows only.
- `SoftDeleteScope` is a domain-agnostic read-time enum companion to
  `SoftDeletable` with exactly three variants: `ACTIVE` (rows with
  `deletedAt IS NULL`), `DELETED` (rows with `deletedAt IS NOT NULL`), and `ALL`
  (no `deletedAt` filter). It carries no domain semantics and MUST remain
  reusable by any DAO over a soft-deletable entity.
- `ArchiveScope` is the read-time filter for the archive axis — the sibling of
  `SoftDeleteScope`, selecting rows by `archived_at` — with exactly three
  variants: `UNARCHIVED` (`archived_at IS NULL`), `ARCHIVED`
  (`archived_at IS NOT NULL`), and `ALL` (no filter). It is a SEPARATE,
  orthogonal axis from `SoftDeleteScope`; the two MUST NOT be merged into one
  enum.
- There MUST be no row-timestamp capability — see the row-timestamp invariant
  under Aggregate Records.

### Validation Outcomes

- Every `create()` / `parse()` / `of()` factory in this package MUST express
  failure as a `ValidationResult.Invalid` (defined in
  `ed.unicoach.common.models`) and MUST NOT throw to signal a rejected value.
  This includes `ConvoName.create()`, which additionally emits
  `ValidationError.TooLong` for over-length input — the first factory in this
  package to use a rejection variant other than `BlankString`/`InvalidFormat`.

---

## III. Behavioral Contracts

### `DisplayName.create(value: String): ValidationResult<DisplayName>`

- **Side Effects**: None.
- **Behavior**: Trims input, validates non-blank.
- **Error Handling**: Returns `Invalid(Blank)` for blank input after trim.
- **Idempotent**: Yes.

### `PersonName.create(value: String): ValidationResult<PersonName>`

- **Side Effects**: None.
- **Behavior**: Trims input, validates non-blank.
- **Error Handling**: Returns `Invalid(Blank)` for blank input after trim.
- **Idempotent**: Yes.

### `PasswordHash.create(value: String): ValidationResult<PasswordHash>`

- **Side Effects**: None.
- **Behavior**: Trims input, validates non-blank. Does NOT hash the value —
  wraps a pre-computed hash string (caller is responsible for hashing before
  calling).
- **Error Handling**: Returns `Invalid(Blank)` for blank input after trim.
- **Idempotent**: Yes.

### `SsoProviderId.create(value: String): ValidationResult<SsoProviderId>`

- **Side Effects**: None.
- **Behavior**: Trims input, validates non-blank.
- **Error Handling**: Returns `Invalid(Blank)` for blank input after trim.
- **Idempotent**: Yes.

### `ConvoName.create(value: String): ValidationResult<ConvoName>`

- **Side Effects**: None.
- **Behavior**: Trims input, validates non-blank and length ≤ 255 characters
  (measured after trim). Constructor is private; this is the sole construction
  path.
- **Error Handling**: Returns `Invalid(BlankString)` for blank input after trim;
  returns `Invalid(TooLong)` when the trimmed value exceeds 255 characters.
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
  returns `Invalid(InvalidFormat)` carrying the canonical expected-form
  description. No exception escapes the method.
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
  `Invalid(InvalidFormat)` carrying the canonical expected-form description.
  Because callers feed `of()` only components that already satisfy the
  persistence layer's integrity guarantees, an `Invalid` from `of()` on a read
  path indicates row corruption, not user input.
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

### `Id.asString` (derived property)

- **Side Effects**: None (pure formatting).
- **Behavior**: Returns a backing-agnostic string view of the identity value
  (e.g. `value.toString()` for the UUID-backed
  `UserId`/`StudentId`/`SessionId`). Intended for logging, serialization, and
  generic code that must not depend on the concrete backing type.
- **Error Handling**: Total — no parsing, no failure mode, never throws.
- **Idempotent**: Yes.

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
- **Dependencies**: Types here use JDK standard library types (`UUID`,
  `Instant`, `Duration`, `ByteArray`, `MessageDigest`, and the `java.time`
  calendar types `Year`, `Month`, `YearMonth`, `LocalDate`), the
  `kotlinx.serialization.json` value types `JsonElement` and `JsonObject` (used
  as pure value types to model JSONB columns — see the import invariant in this
  section), plus the validation primitives (`ValidationResult`,
  `ValidationError`) and `EmailAddress` value type from the internal `common`
  module (`ed.unicoach.common.models`).
- **JVM Inline Classes**: `@JvmInline value class` is used for all scalar
  wrapper types to eliminate boxing overhead. Callers on the JVM boundary (e.g.,
  JDBC result-set mappers) MUST unwrap via `.value` when binding parameters.
- **No I/O or persistence framework imports**: This package MUST NOT import
  Ktor, Exposed, JDBC, or any persistence/serialization runtime. The ONLY
  permitted third-party types are `kotlinx.serialization.json.JsonElement` and
  `JsonObject`, used strictly as opaque structural value types (like `UUID` or
  `Instant`) to model JSONB columns. No `@Serializable` classes, no `Json`
  encoder/decoder, and no serialization behavior may be invoked in this package;
  parsing and encoding JSON is the DAO layer's responsibility. Any other
  framework import is an architectural boundary error.

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
- [x] [RFC-36: Entity Model Capability Taxonomy](../../../../../../../../rfc/36-entity-model-taxonomy.md)
      — Replaced the welded `BaseEntity`/`AdvancedEntity`/`BaseVersionEntity`
      supertypes with one-capability-each interfaces (`Id`,
      `Identifiable<ID : Id>`, `Created`, `Updated`, `Versioned`,
      `SoftDeletable`). Collapsed per-row version to a plain `Int` and deleted
      the `UserVersionId`/`StudentVersionId` value classes. Added the typed
      `SessionId` value class and made `Session.id` typed. Dropped the
      `rowCreatedAt`/`rowUpdatedAt` model properties (the DB columns and
      triggers remain).
- [x] [RFC-38: Convos DAO](../../../../../../../../rfc/38-convos-dao.md) —
      Introduced the `Convo` aggregate (`Updated` + `SoftDeletable`,
      deliberately NOT `Versioned`) and its append-only log records
      `ConvoRequest`, `ConvoResponse`, `ConvoResponseRaw`, the read-side
      `ConvoTurn` pairing, and the
      `NewConvo`/`NewConvoRequest`/`NewConvoResponse` input types. Added the
      `ConvoId`/`SystemPromptId` (`UUID`-backed) and
      `ConvoRequestId`/`ConvoResponseId` (`Long`/BIGINT-backed) id value
      classes, the `ConvoName` validated value type (adds the `TooLong`
      rejection), and the domain-agnostic `SoftDeleteScope` read enum. Carved
      out `kotlinx.serialization.json` pure value types (`JsonElement`,
      `JsonObject`) from the no-framework-imports invariant to model JSONB
      columns.
- [x] [RFC-40: Validation Error Reporting](../../../../../../../../rfc/40-validation-error-reporting.md)
      — Renamed the blank-input rejection to `Blank` at the four string-factory
      emit sites (`DisplayName`, `PersonName`, `PasswordHash`, `SsoProviderId`)
      and at `ConvoName`; enriched `InvalidFormat` with the expected-form
      description at `PartialDate`'s rejection sites via a single canonical
      constant, and `ConvoName`'s `TooLong` rejection now carries its
      `maxLength`.
- [x] [RFC-45: Coaching Service](../../../../../../../../rfc/45-coaching-service.md)
      — Added `archivedAt: Instant?` to `Convo` (reversible archive axis,
      orthogonal to soft-delete; keeps the model 1:1 with the `convos` table).
      Introduced `ArchiveScope` (read-time filter sibling of `SoftDeleteScope`),
      `ConvoWithActivity` (pure listing projection wrapping a `Convo` plus a
      derived `lastActivityAt` = `MAX(convo_requests.created_at)` over all
      turns, failed included), and the `SystemPrompt` catalog row model
      (append-only, `Created` only, reusing the existing `SystemPromptId`).
