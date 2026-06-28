# SPEC: `db/src/main/kotlin/ed/unicoach/db/models`

## I. Overview

The `models` package is the pure domain-type layer for the `db` Gradle module:
the canonical value types, aggregate row records, creation/update input records,
and read-time filter enums consumed by every DAO, service caller, and job
handler. It holds no I/O, no persistence logic, and no framework dependencies —
only Kotlin data types and their construction contracts.

The validation primitives (`ValidationResult`, `ValidationError`) and the
`EmailAddress` value type are defined in the `common` module
(`ed.unicoach.common.models`) and consumed by the factories here; their
contracts belong to `common`'s SPEC.

---

## II. Behavioral Contracts

### II-A. Validated string value types

Five `@JvmInline value class` wrappers gate construction through a `create()`
factory and have private constructors so the factory is the sole entry point.
Each is a pure, side-effect-free computation, idempotent, and never throws —
rejection is expressed as `ValidationResult.Invalid`.

- [`DisplayName.create(value)`](./DisplayName.kt),
  [`PersonName.create(value)`](./PersonName.kt),
  [`PasswordHash.create(value)`](./PasswordHash.kt) — trim, then accept any
  non-blank result; a blank-after-trim input returns
  `Invalid(ValidationError.Blank)`. `PasswordHash.create` wraps a pre-computed
  hash string and does not itself hash.
- [`ProviderSubject.create(value)`](./ProviderSubject.kt) — trims, rejects blank
  with `Invalid(Blank)`, and rejects input longer than 255 characters (measured
  after trim) with `Invalid(TooLong(maxLength = 255))` (matching the
  `user_auth_identities_subject_*` check constraints). Its constructor is
  private; `create()` is the sole construction path. It wraps a provider's
  stable subject identifier (Google's `sub` claim).
- [`ConvoName.create(value)`](./ConvoName.kt) — trims, rejects blank with
  `Invalid(Blank)`, and rejects input longer than 255 characters (measured after
  trim) with `Invalid(TooLong(maxLength = 255))`.

### II-B. `PartialDate` — variable-precision calendar value

[`PartialDate`](./PartialDate.kt) is a domain-agnostic, `java.time`-backed
sealed type with three variants — `YearOnly`, `YearAndMonth`, `FullDate` —
exposing `year: Year`, `month: Month?`, `day: Int?` with the unset components
null at the variant's precision. The variant set is downward-closed: there is no
day-without-month shape. It carries no domain meaning (birthdate, deadline,
graduation are imposed by callers).

- [`parse(iso)`](./PartialDate.kt) — validates in two stages. First it matches
  the input against `^\d{4}(-\d{2}(-\d{2})?)?$` (four-digit year, optional
  zero-padded two-digit month and day, no sign), rejecting unpadded or
  overlong/signed forms before any `java.time` call. Second, matching inputs are
  built via `Year`/`YearMonth.parse`/`LocalDate.parse`, which reject
  out-of-range months and impossible calendar days. **Error Handling**: every
  rejection returns `Invalid(InvalidFormat)` carrying the one canonical
  expected-form description; no exception escapes. **Idempotent**: yes —
  `parse(x.toIso())` round-trips at every precision.
- [`of(year, month?, day?)`](./PartialDate.kt) — the read-path inverse that
  rebuilds a `PartialDate` from decomposed components, selecting the variant by
  which components are present and validating via `Month.of`/`LocalDate.of`. A
  `day` with a null `month`, an out-of-range month, or an impossible day returns
  `Invalid(InvalidFormat)` with the same canonical description. Because callers
  feed `of()` components already satisfying persistence integrity, an `Invalid`
  here signals row corruption rather than user input. **Idempotent**: yes.
- `toIso()` emits zero-padded ISO at the stored precision only (`"YYYY"`,
  `"YYYY-MM"`, `"YYYY-MM-DD"`); its output is exactly the form `parse()`
  accepts.

### II-C. `TokenHash` — hashed credential wrapper

[`TokenHash`](./TokenHash.kt) wraps a `ByteArray` token hash with structural
equality.

- **Constructor** `TokenHash(value)` — requires a non-empty array; an empty
  array throws `IllegalArgumentException("TokenHash cannot be empty.")`.
- **`equals`/`hashCode`** — content-based (`ByteArray.contentEquals` /
  `contentHashCode`), so two hashes compare by bytes, not reference.
- **`fromRawToken(token)`** — pure computation: SHA-256 over the UTF-8 bytes of
  `token`, returned as a `TokenHash`. It is the shared hashing entry point for
  hashed-credential tables (sessions, verification tokens). `MessageDigest`
  acquisition succeeds on any conformant JVM; a missing SHA-256 algorithm
  surfaces as an unchecked `NoSuchAlgorithmException` (fatal misconfiguration).
  **Idempotent**: yes — same token yields the same hash.

### II-D. Authentication credentials and federated identity

A user's password credential lives directly on the entity as a nullable
`passwordHash: PasswordHash?` on `User`/`NewUser`/`UserVersion` — a user may
have no password (a Google-only account). Federated logins are modeled as
separate `AuthIdentity` rows rather than as a flag on the user. There is no
`AuthMethod` sealed type and no `SsoProviderId` value class.

- [`LoginMethod`](./LoginMethod.kt) is an enum recording how a session
  authenticated, persisted as `sessions.login_method`. Its variants are
  `PASSWORD("password")` and `GOOGLE("google")`, each carrying a `wire: String`.
  Its companion `fromWire(value: String): LoginMethod?` resolves a persisted
  wire value back to the enum, returning null on an unknown value.
- [`AuthProvider`](./AuthProvider.kt) is an enum naming a federated identity
  provider, persisted as `user_auth_identities.provider`. It currently has a
  single variant `GOOGLE("google")` carrying a `wire: String`, with a
  `fromWire(value: String): AuthProvider?` companion that returns null on an
  unknown value.
- [`ProviderSubject`](./ProviderSubject.kt) is the `@JvmInline value class`
  wrapping a provider's stable subject identifier (Google's `sub` claim) — the
  only key used to resolve a returning login. See II-A for its `create()`
  contract.

### II-E. Identity types

[`UserId`](./UserId.kt), [`StudentId`](./StudentId.kt),
[`SessionId`](./SessionId.kt), [`AuthIdentityId`](./AuthIdentityId.kt),
[`VerificationTokenId`](./VerificationTokenId.kt), [`ConvoId`](./ConvoId.kt),
and [`SystemPromptId`](./SystemPromptId.kt) wrap a `UUID`;
[`ConvoRequestId`](./ConvoRequestId.kt) and
[`ConvoResponseId`](./ConvoResponseId.kt) wrap a `Long` (the BIGINT identity
keys of `convo_requests`/`convo_responses`). [`ClaimId`](./ClaimId.kt) wraps a
`UUID`; [`ObservationId`](./ObservationId.kt) and
[`ExtractionRunId`](./ExtractionRunId.kt) wrap a `Long` (the BIGINT identity
keys of the append-only `observations`/`extraction_runs` logs). All implement
`Id` and derive `asString` from the wrapped value. None carry a factory or
validation — they are constructed directly. The `Long`-backed log-row ids are
structurally distinct from the `UUID`-backed ids.

[`CollegeId`](./CollegeId.kt) and `CollegeProgramId` also wrap a `UUID` and
implement `Id`, deriving `asString` from `value.toString()`. They are the
DB-generated surface ids of the `colleges`/`college_programs` rows; the federal
`UNITID` natural key lives as a plain `Int` field, not a wrapped id.
`CollegeProgramId` is defined alongside `CollegeProgram` in
[`CollegeProgram.kt`](./CollegeProgram.kt) rather than its own file, because
`college_programs` is bulk-upserted reference data with no standalone id-keyed
read path. `CollegeId` is now also the id type of `CollegeVersion` snapshot rows
(RFC 82), as versions are keyed on the same surface UUID as the parent `College`
row.

[`Id.asString`](./Entity.kt) is a derived, total, backing-agnostic string view
of an identity (e.g. `UUID.toString()`) for logging, serialization, and generic
code; it never throws and stores nothing.

### II-F. Capability interfaces

[Entity.kt](./Entity.kt) defines one-capability-each supertypes that each row
type composes by implementing exactly the capabilities it has. The load-bearing
signal is which capabilities a type _omits_.

- `Id` — identity marker exposing the derived `asString`.
- `Identifiable<ID : Id>` — exposes `id: ID`; every persisted row, including
  version-snapshot and append-only log rows, has a typed identity.
- `Created` — exposes `createdAt: Instant`; every row.
- `Updated` — exposes `updatedAt: Instant`; mutable rows only.
- `Versioned` — exposes `version: Int`, a plain OCC counter (there is no
  version-id wrapper type; per-row version is never a domain-typed value class).
- `SoftDeletable` — exposes `deletedAt: Instant?`; logical-delete rows only.

### II-G. Aggregate row records

Each row type implements exactly the capabilities matching its lifecycle. No
model in this package projects the `row_created_at`/`row_updated_at` audit-clock
columns — those columns and their triggers live in the database but are not
mapped into the domain (the same omission `Session` and `VerificationToken` make
against their tables' `row_created_at`).

- **Mutable entities** — [`User`](./User.kt) and [`Student`](./Student.kt) —
  implement `Created`, `Updated`, `Versioned`, and `SoftDeletable`: `version` is
  an OCC counter and a non-null `deletedAt` marks a soft delete. `User` carries
  a nullable `passwordHash: PasswordHash?` credential (null for a Google-only
  account; federated logins live in separate `AuthIdentity` rows), an
  `isAdmin: Boolean` privilege flag, and `emailVerifiedAt: Instant?` — the
  email-verification marker, non-null once the user has proven control of the
  address, null while unverified. `Student` carries a validated
  `expectedHighSchoolGraduationDate: PartialDate`.
- **Version-snapshot rows** — [`UserVersion`](./UserVersion.kt),
  [`StudentVersion`](./StudentVersion.kt), and
  [`CollegeVersion`](./CollegeVersion.kt) — implement `Created` and `Versioned`
  only. They are immutable history rows that mirror the live entity's domain
  fields as they stood at the captured version. `UserVersion` and
  `StudentVersion` additionally carry `updatedAt` and `deletedAt` as plain
  values (not implementing `Updated`/`SoftDeletable`). `CollegeVersion` also
  carries `updatedAt` as a plain value but omits `deletedAt` because `colleges`
  has no `deleted_at`; it mirrors the full curated column set of `College`
  (including all nullable Scorecard fields). `UserVersion` mirrors `User`,
  including the nullable `passwordHash: PasswordHash?` credential and
  `emailVerifiedAt: Instant?` (faithful history of the verification marker).
- [`Session`](./Session.kt) — `Created` and `Versioned` only: a versioned,
  immutable row carrying `expiresAt: Instant`, an optional `userId` (null for an
  anonymous/pre-auth session), a nullable `loginMethod: LoginMethod?` recording
  how the session authenticated (null when unset, e.g. an anonymous/pre-auth
  session), and request metadata. It holds the typed `SessionId` and version but
  no token value or raw hash.
- [`AuthIdentity`](./AuthIdentity.kt) — an immutable federated-identity row,
  `Identifiable` and `Created` only — recording the fact that a
  `(provider, subject)` pair belongs to a `userId`, established at `createdAt`.
  It carries a typed `AuthIdentityId`, an `AuthProvider`, a `ProviderSubject`,
  and `email: EmailAddress` / `emailVerified: Boolean`. The
  `email`/`emailVerified` fields are provenance-only — the claims the provider
  asserted when the row was created; they are never re-synced and never read on
  the login path (the login path resolves only on `(provider, subject)`).
- [`VerificationToken`](./VerificationToken.kt) — a single-use
  email-verification credential row, `Created` only (it is neither a versioned
  aggregate nor an append-only log). It carries `id: VerificationTokenId`,
  `userId: UserId`, `expiresAt: Instant`, and `consumedAt: Instant?` — a
  single-use marker that is null until the token is burned on verification. Like
  `Session`, the model holds no token value or hash; the raw token exists only
  in the email link and the hash only in the table.
- [`Convo`](./Convo.kt) — `Updated` and `SoftDeletable` but deliberately not
  `Versioned` (the `convos` table has no `version` column; renames are
  last-write-wins). Its `deletedAt: Instant?` is the soft-delete axis;
  `archivedAt: Instant?` is a separate, orthogonal, reversible user-facing shelf
  state (set/clear independently of soft delete). The two nullable timestamps
  are distinct columns kept 1:1 with the table.
- **Append-only log rows** — [`ConvoRequest`](./ConvoRequest.kt),
  [`ConvoResponse`](./ConvoResponse.kt),
  [`ConvoResponseRaw`](./ConvoResponseRaw.kt) — `Created` only; never updated or
  soft-deleted. `ConvoResponseRaw` keeps the verbatim provider `payload`
  separate from the normalized `ConvoResponse`.
- [`SystemPrompt`](./SystemPrompt.kt) — an immutable catalog row, `Identifiable`
  and `Created` only; rows are authored by migration and never mutated. Its
  `version: String` is a catalog version _label_, not an OCC counter, so it does
  not implement `Versioned`.
- **Versioned reference row** — [`College`](./College.kt) — implements
  `Identifiable + Created + Updated + Versioned` (RFC 82). It is a bulk-upserted
  projection of College Scorecard data whose `version` is trigger-managed by the
  ingest upsert (bumped only on a real content change) and whose history is
  recorded in `colleges_versions`. `College` mirrors the `colleges` columns
  (identity/location/size/selectivity/cost plus the three outcome signals
  `graduationRate`, `medianEarnings`, `pctPell`); its `unitId: Int` is the
  federal natural key as a plain `Int` (the upsert key), not a wrapped id.
  `SoftDeletable` is deliberately omitted — `colleges` has no `deleted_at`.
- **Reference row** — [`CollegeProgram`](./CollegeProgram.kt) — implements
  `Identifiable + Created + Updated` and deliberately omits `Versioned` and
  `SoftDeletable`: `college_programs` has no `version`/`deleted_at` (it remains
  a plain reference table, out of scope for RFC 82 versioning). `CollegeProgram`
  mirrors `college_programs` (`collegeId`, `cipCode`, `cipTitle`,
  `credentialLevel`); `credentialLevel` is a non-null `Int` (always present in
  the field-of-study source).

### II-H. Read projections and pairings

- [`ConvoWithActivity`](./ConvoWithActivity.kt) — a pure listing projection (not
  a row type, implements no capability) wrapping a `Convo` plus a derived
  `lastActivityAt: Instant?` = `MAX(convo_requests.created_at)` over all request
  rows (failed turns included, since a failed attempt is still activity); null
  for a conversation with no turns.
- [`ConvoTurn`](./ConvoTurn.kt) — the replay unit pairing a `ConvoRequest` with
  its `ConvoResponse?`. The response is nullable because request and response
  are written in separate transactions: a committed request can exist with no
  response row (provider in flight, or the response transaction never ran).
- [`CollegeMatch`](./CollegeMatch.kt) — a pure read projection of
  `CollegesDao.search` (not a persisted row, implements no capability). It
  carries the curated context columns (including the three outcome signals) plus
  `programTitles: List<String>` — the `cip_title`s of programs matched by the
  query's `cipPrefix`, empty when no program filter was applied. Of the three
  outcome signals only `graduationRate` is also a query filter
  (`minGraduationRate`); `medianEarnings` and `pctPell` are surfaced as context
  for the coach to reason over in prose, never thresholded on.

### II-I. Creation input records (`New*`)

Pure data carriers (no side effects) holding the construction arguments for an
insert; they carry no server-assigned identity or timestamp, and each is the
sole creation input for its aggregate. Fields are pre-validated value types and
DAOs do not re-validate them.

- [`NewUser`](./NewUser.kt) — every field mandatory except `displayName`
  (nullable), the nullable `passwordHash: PasswordHash?` credential (null for a
  Google-only account), and `isAdmin` (defaults `false`, so non-privileged
  creation sites need no change; an admin is minted only by explicit
  `isAdmin = true`). It carries no `emailVerifiedAt`: the verification marker is
  written only by the dedicated verification path, never at creation.
- [`NewStudent`](./NewStudent.kt) — carries a validated `PartialDate`.
- [`NewAuthIdentity`](./NewAuthIdentity.kt) — the creation input for an
  `AuthIdentity` insert, carrying the `userId`, `provider` (`AuthProvider`),
  `subject` (`ProviderSubject`), and the provenance `email`/`emailVerified`
  claims.
- [`NewSession`](./NewSession.kt) — carries a `tokenHash: TokenHash`, an
  optional `userId` (null for anonymous), a relative `expiration: Duration` (a
  TTL the DAO converts to an absolute `expires_at`), not an absolute timestamp,
  and a nullable `loginMethod: LoginMethod? = null` recording how the session
  authenticated.
- [`NewVerificationToken`](./NewVerificationToken.kt) — carries
  `userId: UserId`, a `tokenHash: TokenHash` (the SHA-256 hash, built via
  `TokenHash.fromRawToken`, the same construction sessions use), and an absolute
  `expiresAt: Instant`. The raw token is never carried here.
- [`NewConvo`](./NewConvo.kt), [`NewConvoRequest`](./NewConvoRequest.kt),
  [`NewConvoResponse`](./NewConvoResponse.kt) — the conversation creation
  inputs.
- [`NewSystemPrompt`](./NewSystemPrompt.kt) — the creation input for the
  `system_prompts` catalog, carrying the three immutable domain columns (`name`,
  `version`, `body`) as plain strings, mirroring the
  [`SystemPrompt`](./SystemPrompt.kt) row's `String` fields. Unlike the other
  validated `New*` inputs, it carries no pre-validated value types:
  canonicalization and bounds are enforced by the table's `CHECK`/`UNIQUE`
  constraints, not by a factory in this package. `id`, `createdAt`, and the
  `row_created_at` audit column are DB-defaulted and never client-supplied.
- [`NewCollege`](./NewCollege.kt) and
  [`NewCollegeProgram`](./NewCollegeProgram.kt) — the upsert inputs for the
  reference rows: no `id` (DB-generated) and no timestamps (DB-managed).
  `NewCollege` is keyed on `unitId`; `NewCollegeProgram` on
  `(collegeId, cipCode, credentialLevel)`. Every Scorecard-derived optional
  column is nullable so a blank source cell maps to `null`. They carry no
  validation — column bounds are DB-enforced by the
  `colleges`/`college_programs` `CHECK`/`UNIQUE` constraints.

### II-J. Update input records (`*Edit`)

Pure data carriers and the siblings of the `New*` inputs for the two mutable,
versioned, soft-deletable entities. Each carries the entity `id`, the expected
OCC `version` the caller read, and only the mutable business fields the generic
DAO `update` writes — never a server-managed/immutable column
(`createdAt`/`deletedAt`/`updatedAt`).

- [`UserEdit`](./UserEdit.kt) — carries `email`, `name`, `displayName?`,
  `isAdmin`. It omits the `password_hash` credential and the `emailVerifiedAt`
  marker, both of which mutate only through dedicated flows, so the generic
  update path cannot clobber them.
- [`StudentEdit`](./StudentEdit.kt) — carries only the validated
  `expectedHighSchoolGraduationDate`.

`Convo` has no edit-input record (its single-column rename is last-write-wins on
a non-versioned entity). The reference rows (`College`/`CollegeProgram`) have no
`*Edit` record either: they are bulk-upserted from their `New*` inputs, never
edited through a versioned DAO `update`.

### II-K. Read-time filter enums and inputs

- [`SoftDeleteScope`](./SoftDeleteScope.kt) — domain-agnostic companion to
  `SoftDeletable` with `ACTIVE` (`deleted_at IS NULL`), `DELETED`
  (`deleted_at IS NOT NULL`), and `ALL` (no filter). Reusable by any DAO over a
  soft-deletable entity.
- [`ArchiveScope`](./ArchiveScope.kt) — the sibling filter for `Convo`'s archive
  axis with `UNARCHIVED` (`archived_at IS NULL`), `ARCHIVED`
  (`archived_at IS NOT NULL`), and `ALL`. A separate, orthogonal axis from
  `SoftDeleteScope`.
- [`CollegeQuery`](./CollegeQuery.kt) — the typed filter input for
  `CollegesDao.search` (not a row type). Every field except `limit` is nullable
  (`null` = unconstrained axis); list fields (`states`, `locales`, `control`)
  are OR-sets. `limit: Int` is mandatory and is clamped to `1..25` by the
  `college` module's service boundary before reaching the DAO — the model itself
  enforces no bound.

### II-L. Coaching-memory aggregates (extraction)

The extraction pass distills finished coaching turns into durable memory: an
append-only record of what the student said, the coach's current revisable
beliefs, the link between the two, and a per-pass run ledger.

- [`Observation`](./Observation.kt) — a row of the append-only `observations`
  log: `Identifiable + Created` only, an immutable verbatim record of something
  a student said. It carries the `studentId`, the `convoId`, the
  `sourceRequestId: ConvoRequestId` it was extracted from, the verbatim `quote`,
  and two distinct instants: `createdAt` is the ingest time (when extraction
  recorded it) while `utteredAt` is the event time (the source turn's
  `created_at`).
- [`Claim`](./Claim.kt) — a row of the mutable `claims` entity:
  `Identifiable + Created + Updated`, the coach's current, revisable belief
  about a student. It is **not** `Versioned`; its lifecycle is captured by
  `status` plus the `supersededById`/`supersededAt`/`retractedAt` pointers
  rather than a versions table. It carries the `studentId`, the `statement`
  prose, the classifying enums (`origin`, `status`, `kind`, `subject`, `topic`,
  `visibility`), and a `confidence: Int` — a `0..1000` fixed-point value
  recomputed in code from the claim's support set, never assigned by the LLM.
- [`ClaimSupport`](./ClaimSupport.kt) — a row of the append-only `claim_support`
  link log: `Created` only, the immutable fact that an `Observation` was cited
  as support for a `Claim`. A pure link keyed on the composite
  `(claimId,
  observationId)`; it carries no surrogate id and implements no
  `Identifiable`.
- [`ExtractionRun`](./ExtractionRun.kt) — a row of the append-only
  `extraction_runs` log: `Identifiable + Created` only, one billed extraction
  LLM call over a conversation. It serves three roles: the conversation
  watermark (the highest `throughRequestId: ConvoRequestId` over `applied`
  rows), the provenance of the pass's writes (`systemPromptId`, `provider`,
  `modelResolved: String?`), and the per-pass token ledger (the four nullable
  token counts, recorded for every billed call including failures). It carries
  the `convoId`, `studentId`, the `outcome`, and the three write counts
  (`observationsWritten`, `claimsWritten`, `claimsSuperseded`). `provider` is a
  plain `String` (DB-constrained to `anthropic`/`log`), not a typed enum like
  `outcome`.

The classifying enums each persist as a lowercase `value` string matching a
table `CHECK` (project convention, not a native Postgres enum), and each exposes
a `fromValue(value: String): T?` companion that returns null on an unknown
string:

- [`ClaimOrigin`](./ClaimOrigin.kt) — whether a claim was `STUDENT_STATED` or
  `COACH_INFERRED`.
- [`ClaimStatus`](./ClaimStatus.kt) — lifecycle state: `ACTIVE`, `SUPERSEDED`,
  `RETRACTED`.
- [`ClaimKind`](./ClaimKind.kt) — the kind of belief: `GOAL`, `PREFERENCE`,
  `CONSTRAINT`, `FACT`, `CONCERN`.
- [`ClaimSubject`](./ClaimSubject.kt) — what the claim is about: `STUDENT`,
  `FAMILY`, `COLLEGE`, `APPLICATION`.
- [`ClaimTopic`](./ClaimTopic.kt) — the coaching topic: `ACADEMICS`,
  `ACTIVITIES`, `FINANCES`, `LOCATION`, `CAREER`, `TIMELINE`, `WELLBEING`.
- [`ClaimVisibility`](./ClaimVisibility.kt) — `STUDENT_VISIBLE` or `INTERNAL`,
  where `INTERNAL` means "not surfaced to the student unprompted"
  (coaching-process notes), not "hidden from the student".
- [`ExtractionOutcome`](./ExtractionOutcome.kt) — `APPLIED` (advanced the
  watermark and wrote memory) or `FAILED` (billed tokens but produced unusable
  output: watermark unchanged, write counts zero).

The extraction creation/update inputs (pure data carriers, siblings of the
`New*`/`*Edit` records described in II-I/II-J):

- [`NewObservation`](./NewObservation.kt) — the insert input for an
  `observations` row, omitting the DB-generated id and `createdAt`.
- [`NewClaim`](./NewClaim.kt) — the insert input for a fresh `claims` row,
  omitting the DB-generated id, timestamps, lifecycle pointers, and `status`/
  `confidence` entirely. The DB defaults `status` to `active` and `confidence`
  to `0`; a freshly-created claim's confidence is recomputed from its support
  set in the same write transaction.
- [`NewClaimSupport`](./NewClaimSupport.kt) — the insert input for a
  `claim_support` link, carrying the `(claimId, observationId)` pair and
  omitting the DB-generated `createdAt`.
- [`NewExtractionRun`](./NewExtractionRun.kt) — the insert input for an
  `extraction_runs` row, omitting the DB-generated id and `createdAt`. The three
  write counts default to `0` (a `failed` run records zero writes, enforced by a
  DB `CHECK`) and all four token fields default to null (populated when the
  provider reports usage).
- [`ClaimRevision`](./ClaimRevision.kt) — the update input for
  `ClaimsDao.revise` (not a `*Edit` record, as `Claim` is non-versioned and
  carries no OCC `version`): the next `status`, the recomputed `confidence`, and
  an optional `supersededById` (defaulting null). The DAO derives
  `superseded_at`/ `retracted_at` from the `status` so the DB
  lifecycle-consistency CHECKs hold — `superseded` requires a non-null
  `supersededById` and stamps `superseded_at`; `retracted` stamps `retracted_at`
  with a null `supersededById`; `active` clears both pointers.

---

## III. Infrastructure & Environment

- **Module**: `db` Gradle module (`db/build.gradle.kts`).
- **Package**: `ed.unicoach.db.models`.
- **Dependencies**: JDK standard-library types (`UUID`, `Instant`, `Duration`,
  `ByteArray`, `MessageDigest`, and the `java.time` calendar types `Year`,
  `Month`, `YearMonth`, `LocalDate`); the `kotlinx.serialization.json` value
  types `JsonElement`/`JsonObject` used purely as opaque structural values for
  JSONB columns; and the `ValidationResult`/`ValidationError` primitives plus
  `EmailAddress` from the internal `common` module.
- **JVM inline classes**: scalar wrappers are `@JvmInline value class` to avoid
  boxing; callers at the JVM boundary (e.g. JDBC mappers) unwrap via `.value`.
- **No persistence/serialization runtime**: the package imports no Ktor,
  Exposed, JDBC, or JSON encoder/decoder. The only third-party types are
  `JsonElement`/`JsonObject`, used as opaque values (no `@Serializable`, no
  `Json` codec); JSON parsing/encoding is the DAO layer's responsibility.

---

## IV. History

- [x] [RFC-07: Users DAO](../../../../../../../../rfc/07-users-dao.md) —
      Introduced `UserId`, `UserVersionId`, `PersonName`, `DisplayName`,
      `PasswordHash`, `SsoProviderId` (original location:
      `rest-server/.../db/models/`; moved by RFC-14). Also introduced
      `EmailAddress` and `ValidationResult`/`ValidationError`, later relocated
      to `common` by RFC-34.
- [x] [RFC-08: Auth Registration](../../../../../../../../rfc/08-auth-registration.md)
      — Introduced `AuthMethod` (`Password`/`SSO`/`Both`), `User`, `NewUser`.
- [x] [RFC-11: Sessions](../../../../../../../../rfc/11-sessions.md) —
      Introduced `Session` and `NewSession`.
- [x] [RFC-14: DB Module](../../../../../../../../rfc/14-db-module.md) — Moved
      all model files into `db/src/main/kotlin/ed/unicoach/db/models/` and
      introduced `Entity.kt`.
- [x] [RFC-21: Session Expiry Queue](../../../../../../../../rfc/21-session-expiry-queue.md)
      — Added `expiresAt: Instant` to `Session`; extracted
      `TokenHash.fromRawToken()` as a shared companion method.
- [x] [RFC-31: Student Profile](../../../../../../../../rfc/31-student-profile.md)
      — Introduced `PartialDate`, the `StudentId`/`StudentVersionId` value
      classes, and `Student`/`NewStudent`/`StudentVersion`.
- [x] [RFC-34: Transactional Email Service](../../../../../../../../rfc/34-transactional-email-service.md)
      — Relocated `EmailAddress` and `ValidationResult`/`ValidationError` to
      `common`; the remaining factories and `User`/`UserVersion`/`NewUser`
      import them from `common`.
- [x] [RFC-36: Entity Model Capability Taxonomy](../../../../../../../../rfc/36-entity-model-taxonomy.md)
      — Replaced the welded entity supertypes with one-capability-each
      interfaces (`Id`, `Identifiable`, `Created`, `Updated`, `Versioned`,
      `SoftDeletable`), collapsed per-row version to a plain `Int`, added the
      typed `SessionId`, and dropped the `rowCreatedAt`/`rowUpdatedAt` model
      properties.
- [x] [RFC-38: Convos DAO](../../../../../../../../rfc/38-convos-dao.md) —
      Introduced `Convo`, the append-only `ConvoRequest`/`ConvoResponse`/
      `ConvoResponseRaw`, the `ConvoTurn` pairing, the
      `NewConvo`/`NewConvoRequest`/`NewConvoResponse` inputs, the
      `ConvoId`/`SystemPromptId`/`ConvoRequestId`/`ConvoResponseId` id types,
      the `ConvoName` value type (adding the `TooLong` rejection), and
      `SoftDeleteScope`. Carved out `kotlinx.serialization.json` value types for
      JSONB columns.
- [x] [RFC-40: Validation Error Reporting](../../../../../../../../rfc/40-validation-error-reporting.md)
      — Renamed the blank-input rejection to `Blank`, enriched `InvalidFormat`
      with a single canonical expected-form description, and added `maxLength`
      to `ConvoName`'s `TooLong`.
- [x] [RFC-45: Coaching Service](../../../../../../../../rfc/45-coaching-service.md)
      — Added `archivedAt: Instant?` to `Convo`, `ArchiveScope`,
      `ConvoWithActivity`, and the `SystemPrompt` catalog row.
- [x] [RFC-60: Admin Website](../../../../../../../../rfc/60-admin-website.md) —
      Added `isAdmin: Boolean` to `User`/`UserVersion` and `isAdmin = false` to
      `NewUser`.
- [x] [RFC-62: DAO Capability Interfaces and Shared Query Scaffolding](../../../../../../../../rfc/62-dao-interfaces.md)
      — Added the `UserEdit` and `StudentEdit` update-input records.
- [x] [RFC-63: Admin System Prompts](../../../../../../../../rfc/63-admin-system-prompts.md)
      — Introduced `NewSystemPrompt`, the creation input for the
      `system_prompts` catalog (three plain-string domain columns;
      canonicalization and bounds DB-enforced rather than factory-validated).
      `SystemPrompt` and `SystemPromptId` (from RFC-38) remain a read-only
      catalog row and its id.
- [x] [RFC-64: Google SSO Login](../../../../../../../../rfc/64-google-sso-login.md)
      — Replaced the sealed `AuthMethod` model (and the `SsoProviderId` value
      class) with a credential-on-entity / identity-row model. Deleted
      `AuthMethod.kt` and `SsoProviderId.kt`. Added `LoginMethod`
      (`PASSWORD`/`GOOGLE` enum, persisted as `sessions.login_method`),
      `AuthProvider` (`GOOGLE` enum, persisted as
      `user_auth_identities.provider`), the `ProviderSubject` validated value
      class (Google's `sub` claim; trims, rejects blank and >255), the
      `AuthIdentityId` (`UUID`-backed) id value class, and the `AuthIdentity`
      immutable federated-identity row (`Identifiable` + `Created`) with its
      `NewAuthIdentity` creation input. Replaced `User`/`NewUser`'s
      `authMethod: AuthMethod` with a nullable `passwordHash: PasswordHash?`
      credential and added `passwordHash` to the `UserVersion` snapshot; added
      the nullable `loginMethod` to `Session` and `NewSession`. The
      `emailVerifiedAt` marker and the `VerificationToken` types from RFC-65 are
      retained.
- [x] [RFC-65: Email Verification](../../../../../../../../rfc/65-email-verification.md)
      — Added `emailVerifiedAt: Instant?` to `User` and `UserVersion`, and
      introduced the `VerificationTokenId`, `VerificationToken`, and
      `NewVerificationToken` types for the single-use email-verification
      credential (modeled on `Session`: hashed credential, no token value on the
      model, `created_at` mapped while `row_created_at` is ignored). `NewUser`
      and `UserEdit` were left unchanged so the marker stays out of the generic
      creation/update path.
- [x] [RFC-66: Extraction](../../../../../../../../rfc/66-extraction.md) — Added
      the coaching-memory models: the append-only `Observation` log row
      (`Identifiable + Created`) with its `ObservationId` (`Long`-backed) id and
      `NewObservation` input; the mutable, non-versioned `Claim` entity
      (`Identifiable + Created + Updated`, lifecycle via `status` +
      supersession/ retraction pointers, `confidence` a `0..1000` fixed-point
      recomputed in code) with its `ClaimId` (`UUID`-backed) id, `NewClaim`
      input, and `ClaimRevision` update input; the six claim classifying enums
      (`ClaimOrigin`, `ClaimStatus`, `ClaimKind`, `ClaimSubject`, `ClaimTopic`,
      `ClaimVisibility`); the append-only `ClaimSupport` link row (`Created`,
      composite-keyed, no surrogate id) with its `NewClaimSupport` input; and
      the append-only `ExtractionRun` log row (`Identifiable + Created`,
      watermark + provenance + token ledger) with its `ExtractionRunId`
      (`Long`-backed) id, `ExtractionOutcome` enum, and `NewExtractionRun`
      input. No change to any existing model type.
- [x] [RFC-67: College Knowledge](../../../../../../../../rfc/67-college-knowledge.md)
      — Added the College Scorecard reference models: the `CollegeId` /
      `CollegeProgramId` (`UUID`-backed) surface ids; the `College` and
      `CollegeProgram` reference rows (`Identifiable + Created + Updated`,
      deliberately not `Versioned`/`SoftDeletable`); the `NewCollege` /
      `NewCollegeProgram` upsert inputs (no id/timestamps, all Scorecard
      optionals nullable); the `CollegeQuery` typed filter (every field but the
      mandatory `limit` nullable; `limit` clamped `1..25` at the service); and
      the `CollegeMatch` search projection (curated context columns plus matched
      `programTitles`, only `graduationRate` filterable). No change to any
      existing model type.
- [x] [RFC-82: Versioned Colleges](../../../../../../../../rfc/82-versioned-colleges.md)
      — `College` now implements `Versioned` (added `version: Int`; the doc
      comment updated to describe the trigger-managed versioning and
      `colleges_versions` history table). Added `CollegeVersion`
      (`Identifiable<CollegeId> + Created + Versioned`): an immutable snapshot
      row mirroring the full curated column set of `College` (including all
      nullable Scorecard fields and `updatedAt` as a plain value, but no
      `deletedAt` because `colleges` has no `deleted_at`). No change to
      `CollegeProgram`, `NewCollege`, `NewCollegeProgram`, `CollegeQuery`, or
      `CollegeMatch`.
