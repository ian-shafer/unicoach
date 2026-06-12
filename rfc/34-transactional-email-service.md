# 34 — Transactional Email Service

## Executive Summary

This RFC introduces a generic email-sending capability: a non-blocking
`suspend fun EmailService.send(...)` that delivers one transactional email
through a provider port and records every successfully-terminated send in an
append-only database ledger. It is the foundational infrastructure on which
later product flows (verification, password reset) and queue-driven delivery
will build.

Scope is deliberately narrow. There is **no** queue integration and **no**
concrete email type wired into any product flow. `send` is a non-blocking
`suspend` function that awaits the terminal send outcome (it is not
fire-and-forget; asynchronous, durable dispatch is the deferred queue's
responsibility). It has no production call site in this RFC; it is exercised
only by tests. It is, however, designed to be queue-ready: failures are returned
as `Result.failure` carrying the existing `TransientError`/`PermanentError`
marker traits, so a future queue handler can branch transient (retry) versus
permanent (dead-letter) with no change to `send`.

Delivery is abstracted behind an `EmailProvider` port. The only adapter in this
RFC is `LogOnlyEmailProvider`, which records-but-does-not-transmit; the real
adapter (SES or SendGrid) is deferred to its own RFC and slots in behind the
port.

Persistence is a new append-only log table, `email_sends`, governed by
`postgres-log-table-design`. It records **terminal outcomes only**: `SENT` and
permanent `REJECTED`. Transient failures are never logged here — they are the
queue's domain (`job_attempts`), and logging them would create two sources of
truth for one logical message. Each row is written once with its terminal
status; `UPDATE`/`DELETE` are blocked by trigger.

The RFC also relocates the shared validation primitives `EmailAddress`,
`ValidationResult`, and `ValidationError` from the `db` module to `common`,
where the new `email` module (and any future module) can reuse them, and adds
`@`-presence validation to `EmailAddress`. New code lives in a dedicated `email`
module.

## Detailed Design

### Module placement

A new Gradle leaf module, `email`, holds the domain service, the port, the
adapter, the value types, and the DAO. It depends on `common` and `db` and
mirrors the precedent that `queue` is its own module with its own DAO
(`JobsDao`). The `net` module is job-handler glue, not a general adapter home,
so it is not used.

The module is **unwired**: no other module declares a dependency on `email` in
this RFC, and no `main()` constructs `EmailService`. Production wiring
(`implementation(project(":email"))` in `queue-worker`/`net`, plus a call site)
is deferred to the queue RFC. Gradle still compiles and tests the module because
it is listed in `settings.gradle.kts`.

The migration SQL is the exception: it lives in `db/schema/`, the single
directory `bin/db-migrate` reads.

### Shared validation primitives: relocation `db` → `common`

`EmailAddress`, `ValidationResult`, and `ValidationError` currently live in
`db/src/main/kotlin/ed/unicoach/db/models/`. `EmailAddress.create` returns
`ValidationResult<EmailAddress>` and constructs `ValidationError`, so all three
must move together. They are generic validation primitives with no `db`-specific
dependency, and `db` already depends on `common`, so the relocation is upward
and legal.

Target package: `ed.unicoach.common.models`.

The move is purely compile-time-validated: none of the three is `@Serializable`,
none is persisted, and nothing references them by reflection or serial name, so
the only failure mode is a broken import, which fails the build. The blast
radius is the sibling value types and the DAOs/services/tests that reference the
moved types (see Files Modified). Two distinct edits are required: the
`db/models` siblings currently resolve the moved symbols by **same-package
visibility** and so must have **new** `import ed.unicoach.common.models.{...}`
lines **added**; the cross-package referencers (DAOs, services, tests) have
their existing `import ed.unicoach.db.models.{...}` lines **rewritten** to
`ed.unicoach.common.models.{...}`. No behavior change.

`EmailAddress.create` gains `@`-presence validation. Current behavior rejects
only blank input. New behavior, on the trimmed-and-lowercased value:

| Input condition                       | Result                   |
| :------------------------------------ | :----------------------- |
| blank                                 | `Invalid(BlankString)`   |
| no `@`, or `@` at first or last index | `Invalid(InvalidFormat)` |
| otherwise                             | `Valid(EmailAddress)`    |

This guarantees at least one character on each side of a single required `@`. It
is intentionally permissive (no full RFC 5322 grammar); the durable invariant is
"non-blank, lowercased, trimmed, contains an interior `@`".

### Data Models

#### Database — `email_sends`

New migration `db/schema/0008.create-email-sends.sql`. `email_sends` is an
append-only log (`postgres-log-table-design`): one immutable row per terminal
send outcome, written once. The two log guard functions `prevent_log_update()`
and `prevent_log_delete()` are created in migration `0006` and, because
migrations apply in lexical order on every database, are present before `0008`
runs; `0008` references them rather than redefining them.

| Column                | Type          | Constraints                                                         | Meaning                                      |
| :-------------------- | :------------ | :------------------------------------------------------------------ | :------------------------------------------- |
| `id`                  | `UUID`        | `PRIMARY KEY DEFAULT uuidv7()`                                      | Identity; time-ordered                       |
| `created_at`          | `TIMESTAMPTZ` | `NOT NULL DEFAULT NOW()`                                            | Logical send time                            |
| `row_created_at`      | `TIMESTAMPTZ` | `NOT NULL DEFAULT NOW()`                                            | Physical insert time                         |
| `recipient_email`     | `TEXT`        | `NOT NULL`                                                          | Destination (`EmailAddress.value`)           |
| `sender_email`        | `TEXT`        | `NOT NULL`                                                          | Configured sender (`EmailAddress.value`)     |
| `subject`             | `TEXT`        | `NOT NULL`                                                          | Subject line                                 |
| `body`                | `TEXT`        | `NOT NULL`                                                          | Plain-text body                              |
| `status`              | `TEXT`        | `NOT NULL CHECK (status IN ('SENT','REJECTED'))`                    | Terminal outcome                             |
| `provider`            | `TEXT`        | `NOT NULL` (intentionally unconstrained; adapter set is open-ended) | Adapter identity, e.g. `log` (`provider.id`) |
| `provider_message_id` | `TEXT`        | nullable                                                            | Provider's id; set when `SENT`               |
| `error_message`       | `TEXT`        | nullable                                                            | Rejection reason; set when `REJECTED`        |

Triggers:

- `BEFORE UPDATE ... EXECUTE PROCEDURE prevent_log_update()`
- `BEFORE DELETE ... EXECUTE PROCEDURE prevent_log_delete()`

Naming note: both address columns carry the `_email` suffix, and this is not
no-smurf-naming. A `TEXT` column has no `EmailAddress` type, so the suffix is
the only carrier of the fact that the column holds an email address rather than
a user-id reference or a display name; stripping it to `recipient`/`sender`
would discard semantics the medium cannot otherwise express. (Contrast the
Kotlin layer, where `EmailAddress` conveys this and the suffix _would_ be
redundant — see `NewEmailSend`, whose fields are `recipient`/`sender`.)

#### Kotlin domain types (`email` module)

Value types (`@JvmInline value class`, private constructor, `create` factory
returning `ValidationResult`), bounds named as constants:

```
EmailSubject(value: String)      // MAX_SUBJECT_LENGTH = 255
  create: blank -> Invalid(BlankString); length > 255 -> Invalid(TooLong); else Valid

EmailBody(value: String)         // MAX_BODY_LENGTH = 65536  (character bound chosen to approximate the queue's 64 KiB payload cap; not an exact byte equivalence — multi-byte UTF-8 may exceed 64 KiB on the wire)
  create: blank -> Invalid(BlankString); length > 65536 -> Invalid(TooLong); else Valid
```

Address values reuse the relocated `ed.unicoach.common.models.EmailAddress`.

```
data class OutboundEmail(
  val from: EmailAddress,
  val to: EmailAddress,
  val subject: EmailSubject,
  val body: EmailBody,
)

data class SentEmail(
  val id: UUID,
  val providerMessageId: String?,
)
```

DAO input model:

```
data class NewEmailSend(
  val recipient: EmailAddress,         // -> recipient_email column
  val sender: EmailAddress,            // -> sender_email column
  val subject: EmailSubject,
  val body: EmailBody,
  val status: EmailSendStatus,         // SENT | REJECTED
  val provider: String,
  val providerMessageId: String?,
  val errorMessage: String?,
)

enum class EmailSendStatus { SENT, REJECTED }
```

### API Contracts

#### Port — `EmailProvider`

```
interface EmailProvider {
  val id: String                                         // provider identity; written verbatim to the ledger `provider` column
  suspend fun send(email: OutboundEmail): ProviderResult
}

sealed interface ProviderResult {
  data class Sent(val providerMessageId: String?) : ProviderResult       // accepted/delivered
  data class Rejected(val reason: String) : ProviderResult               // permanent; no retry helps
  data class TransientFailure(val reason: String) : ProviderResult       // retriable
}
```

#### Adapter — `LogOnlyEmailProvider`

```
class LogOnlyEmailProvider : EmailProvider {
  override val id: String                                                // constant "log"
  override suspend fun send(email: OutboundEmail): ProviderResult        // always Sent; transmits nothing
}
```

`id` is the constant string `log`; `EmailService` writes it to the ledger
`provider` column. (The wire identity `log` is deliberately shorter than the
Kotlin class name `LogOnlyEmailProvider`: the column records the delivery
mechanism, not the class.) `send` returns `Sent` with a non-null synthetic
`providerMessageId`.

#### Domain service — `EmailService`

```
class EmailService(
  private val database: Database,
  private val provider: EmailProvider,
  private val config: EmailConfig,
) {
  suspend fun send(
    to: EmailAddress,
    subject: EmailSubject,
    body: EmailBody,
  ): Result<SentEmail>
}
```

`from` is not a parameter; it is resolved from configuration, validated once on
first use and memoized:

```
private val resolvedFrom: ValidationResult<EmailAddress> by lazy { EmailAddress.create(config.defaultFrom) }
```

Memoization is for the `EmailService` instance's lifetime (config is load-once);
a `defaultFrom` change requires reconstructing the service. `by lazy` is
thread-safe by default, which suffices for concurrent `suspend` callers.

Control flow (declarative):

1. If `resolvedFrom` is `Invalid` → `Result.failure(EmailConfigException)`
   (permanent); provider not called; no row.
2. Build `OutboundEmail(from = resolvedFrom.value, to, subject, body)`.
3. `provider.send(outbound)`. Each terminal-outcome row sets its ledger
   `provider` column from `provider.id`, so the service records provider
   identity without naming any provider literal:
   - `Sent` → insert `email_sends` row `status = SENT`, `provider_message_id`
     set, `error_message = null`; return
     `Result.success(SentEmail(id, providerMessageId))`.
   - `Rejected` → insert row `status = REJECTED`, `error_message = reason`,
     `provider_message_id = null`; return
     `Result.failure(EmailRejectedException(reason))` (permanent).
   - `TransientFailure` → no row; return
     `Result.failure(EmailDeliveryException(reason))` (transient).

#### DAO — `EmailSendsDao`

```
object EmailSendsDao {
  fun insert(session: SqlSession, newSend: NewEmailSend): Result<SentEmail>
}
```

`session` is `ed.unicoach.db.dao.SqlSession`; `Database.withConnection { ... }`
supplies it and owns the transaction. On database failure the DAO returns
`Result.failure` via the existing `ed.unicoach.db.dao.mapDatabaseError`, which
classifies the cause by SQLSTATE: connection/serialization/deadlock/resource
states (`08*`, `40001`, `40P01`, `53*`, `57P*`) map to a `TransientError`; every
other failure — including `CHECK`/`NOT NULL` constraint violations — maps to a
`PermanentError`. The DAO does not assert a single fixed trait.

#### Configuration — `EmailConfig`

`email/src/main/resources/email.conf`:

```hocon
email {
  defaultFrom = "noreply@unicoach.app"
  defaultFrom = ${?EMAIL_DEFAULT_FROM}
}
```

```
class EmailConfig private constructor(val defaultFrom: String) {
  companion object {
    fun from(config: Config): Result<EmailConfig>      // reads email.defaultFrom verbatim; no address validation here
  }
}
```

`from` performs no address validation — `defaultFrom` is carried as a raw string
and validated at first send (see `EmailService.resolvedFrom`). No `provider`
selector field exists yet; the single adapter is chosen in wiring code.
`email.conf` must be added to the `AppConfig.load(...)` resource list at the
eventual construction site (deferred); the DB-backed tests load `common.conf`,
`db.conf`, and `email.conf`.

### Error Handling / Edge Cases

- **Misconfigured sender** (`email.defaultFrom` fails `EmailAddress.create`):
  `EmailService.send` returns
  `Result.failure(EmailConfigException : PermanentError)` before calling the
  provider; nothing is transmitted and no row is written. Permanent because
  retrying an unchanged config cannot succeed.
- **Permanent rejection** (`ProviderResult.Rejected`): a `REJECTED` ledger row
  is written and `Result.failure(EmailRejectedException : PermanentError)`
  returned.
- **Transient failure** (`ProviderResult.TransientFailure`): no ledger row;
  `Result.failure(EmailDeliveryException : TransientError)`. The
  transient/permanent trait is the queue-readiness contract — a future handler
  maps it to `RetriableFailure`/`PermanentFailure`.
- **Non-transactional provider/DB boundary (dual-write).** `provider.send` (an
  external RPC) and the `email_sends` insert (a DB transaction) are not atomic:
  no shared transaction spans them, the provider effect cannot be rolled back,
  and a DB connection must never be held open across the RPC. The chosen
  ordering — send, then record — makes the unrecoverable case
  **sent-but-unrecorded**, which on any retry becomes **sent-twice**. A
  transient RPC failure that actually delivered is indistinguishable from one
  that did not, so retry is inherently at-least-once. This RFC accepts the gap:
  the only adapter is non-transmitting (`LogOnlyEmailProvider`) and there is no
  retrying caller, so the hazard is latent. The deferred queue RFC **must**
  bound it with (a) a provider-level **idempotency key** so a replayed send is
  deduplicated by the provider, and (b) the **transactional outbox** pattern —
  enqueuing the send in the same transaction as its triggering operation — so
  the database remains the single source of truth.
- **Ledger insert fails after a `Rejected` outcome**: the database failure is
  returned in preference to the rejection. Its trait is whatever
  `mapDatabaseError` assigns (transient ⇒ a future queue retries; permanent ⇒ it
  dead-letters). On a transient-driven retry the provider re-rejects without
  transmitting — harmless (no email is sent), accepted to keep the path simple
  (see the dual-write boundary above).
- **Ledger insert fails after a `Sent` outcome**: the provider has already
  transmitted but no durable record exists; `send` returns the database failure.
  Its trait is `mapDatabaseError`'s classification. If transient, a future queue
  retries and re-sends (the **sent-twice** case of the dual-write boundary
  above); if permanent, it dead-letters and the send is recorded nowhere. With
  inline delivery, a non-transmitting adapter, and no queue, both gaps are
  accepted here; the queue RFC bounds the resend hazard via idempotency key +
  outbox.
- **Exception types** (new, in `email` module), each tagged with the existing
  marker trait from the `common` module's `ed.unicoach.error` package
  (`ed.unicoach.error.TransientError` / `ed.unicoach.error.PermanentError`):
  - `EmailConfigException : PermanentError`
  - `EmailRejectedException : PermanentError`
  - `EmailDeliveryException : TransientError`
- **Input invariants** (`to`, `subject`, `body`) are enforced at the type
  boundary: callers can only construct `EmailAddress`/`EmailSubject`/`EmailBody`
  through validating factories, so `send` receives already-valid values.

### Dependencies

- **Internal:** `email` depends on `common` (validation primitives in
  `ed.unicoach.common.models`, config in `ed.unicoach.common.config`, error
  marker traits in `ed.unicoach.error`, coroutines) and `db` (`Database`,
  `SqlSession`, `mapDatabaseError`). Migration `0008` depends on the
  `prevent_log_update`/`prevent_log_delete` functions from migration `0006` and
  on `uuidv7()`.
- **External:** none new. `email/build.gradle.kts` declares the exact set the
  module needs (structurally similar to `queue`/`net`, but not an exact copy of
  either — `queue` omits a `postgresql` test dep, `net` omits
  `coroutines.test`). Plugins: `kotlin.jvm`, `ktlint` only (the
  `kotlin.serialization` plugin is **not** added — no JSON payloads until the
  queue RFC). Main scope: `implementation(project(":common"))`,
  `implementation(project(":db"))`, `kotlinx.coroutines.core`, `slf4j.api`,
  `compileOnly(postgresql)`. Test scope: `kotlin.test.junit5`,
  `kotlinx.coroutines.test`, `postgresql`, `hikaricp` (the latter two are
  required because `EmailSendsDao`/`EmailService` tests are DB-backed). No SMTP
  or HTTP client dependency (deferred with the real adapter).

## Tests

### Validation primitives (`common`)

- `EmailAddressTest`:
  - blank input → `Invalid(BlankString)`.
  - `"noat"` (no `@`) → `Invalid(InvalidFormat)`.
  - `"@x"` (`@` at start) → `Invalid(InvalidFormat)`.
  - `"x@"` (`@` at end) → `Invalid(InvalidFormat)`.
  - `"  A@B.io "` → `Valid`, `value == "a@b.io"` (trimmed, lowercased).
- Regression (import-only relocation, no new assertions): existing
  `UsersDaoTest`, `StudentsDaoTest`, `PartialDateTest`, `AuthServiceTest`,
  `StudentServiceTest` compile and pass against `ed.unicoach.common.models`.

### Value types (`email`)

- `EmailSubjectTest`: blank → `BlankString`; 256 chars → `TooLong`; 255 chars →
  `Valid`.
- `EmailBodyTest`: blank → `BlankString`; 65537 chars → `TooLong`; 65536 chars →
  `Valid`.

### `EmailConfig` (`email`)

- `from()` reads `email.defaultFrom` from a `Config` built via
  `ConfigFactory.parseString("email.defaultFrom = \"x@y.io\"")` and surfaces
  `"x@y.io"`. (The `${?EMAIL_DEFAULT_FROM}` substitution is HOCON's
  load-time-from-process-environment behavior, not this module's logic, and is
  explicitly out of scope for a unit test.)
- `AppConfig.load("common.conf", "db.conf", "email.conf")` succeeds and `from()`
  on the merged `Config` yields `defaultFrom == "noreply@unicoach.app"` (the
  packaged default), proving `email.conf` is on the classpath and merges.
- `from()` does not validate the address (a malformed `defaultFrom` still yields
  a successful `EmailConfig`).

### `LogOnlyEmailProvider` (`email`)

- `id == "log"`.
- `send()` returns `ProviderResult.Sent` with a non-null `providerMessageId`.

### `EmailSendsDao` (`email`, DB-backed)

- Insert `SENT` row (`provider_message_id` set, `error_message` null) →
  readable; all fields intact.
- Insert `REJECTED` row (`error_message` set, `provider_message_id` null) →
  readable.
- `CHECK (status IN ('SENT','REJECTED'))` rejects an out-of-domain status.
- `UPDATE` on any row raises (`prevent_log_update`).
- `DELETE` on any row raises (`prevent_log_delete`).
- Omitting a `NOT NULL` column is rejected.
- A constraint-violation insert (out-of-domain `status`) returns
  `Result.failure` whose cause `is DaoException` and `is PermanentError` —
  pinning that `mapDatabaseError` classifies a `CHECK` violation as permanent
  (the queue-readiness contract the Error Handling section depends on).

### `EmailService` (`email`, DB-backed, fake `EmailProvider`)

- Provider `Sent` → exactly one `SENT` ledger row whose `provider` column equals
  the fake provider's `id`; returns `Result.success(SentEmail)` carrying `id`
  and `providerMessageId`. The fake exposes a distinctive `id` (e.g. `"fake"`,
  not `"log"`), pinning that the column is sourced from `provider.id` rather
  than a hardcoded literal.
- Provider `Rejected` → exactly one `REJECTED` ledger row with
  `error_message == reason` and `provider` equal to the fake's `id`; returns
  `Result.failure`, cause `is PermanentError`.
- Provider `TransientFailure` → no ledger row; returns `Result.failure`, cause
  `is TransientError`.
- Invalid `email.defaultFrom` → `Result.failure` (cause `is PermanentError`),
  provider never invoked, no ledger row.
- Ledger insert fails after a provider `Sent` (forced DB failure, e.g. a
  closed/poisoned `Database`) → `send` returns `Result.failure` carrying the DAO
  cause unaltered (the failure is propagated, not swallowed), per the dual-write
  Error Handling contract.
- Fake provider captures the `OutboundEmail` it received: `from` equals the
  configured `defaultFrom`; `to`/`subject`/`body` pass through unchanged.

## Implementation Plan

Each step is atomic and locally verifiable. All commands run inside the Nix dev
shell (`nix develop -c ...`). `bin/test` recreates and migrates the test
database before invoking Gradle.

1. **Relocate validation primitives.** Move `EmailAddress.kt` and
   `ValidationResult.kt` (holding `ValidationResult` + `ValidationError`) from
   `db/src/main/kotlin/ed/unicoach/db/models/` to
   `common/src/main/kotlin/ed/unicoach/common/models/`, repackaging to
   `ed.unicoach.common.models`. For every file in `db` and `service` (main and
   test) listed in Files Modified, **add or rewrite** the relevant import:
   same-package `db/models` siblings gain new
   `import ed.unicoach.common.models.{EmailAddress,ValidationResult,ValidationError}`
   lines; cross-package referencers rewrite their existing
   `ed.unicoach.db.models.*` import path. No behavior change.
   - Verify: `nix develop -c bin/test` (full suite green; confirm via JUnit XML,
     not only console).
2. **Add `@` validation** to `EmailAddress.create`; add
   `common/src/test/.../EmailAddressTest.kt`.
   - Verify: `nix develop -c bin/test :common:test`.
3. **Migration `0008.create-email-sends.sql`**: table, `CHECK (status ...)`, and
   `BEFORE UPDATE/DELETE` triggers referencing
   `prevent_log_update`/`prevent_log_delete`.
   - Verify: `nix develop -c bin/db-migrate`; then
     `nix develop -c psql "$DATABASE_URL" -c '\d email_sends'` shows columns,
     check, and both triggers.
4. **Register the `email` module**: add `include("email")` to
   `settings.gradle.kts`; create `email/build.gradle.kts` per the plugin and
   dependency set enumerated in §Detailed Design → Dependencies; create
   `email/src/main/resources/email.conf`.
   - Verify: `nix develop -c ./gradlew :email:compileKotlin`.
5. **Value types** `EmailSubject`, `EmailBody` (+ `EmailSubjectTest`,
   `EmailBodyTest`).
   - Verify: `nix develop -c bin/test :email:test`; confirm `EmailSubjectTest`
     and `EmailBodyTest` are present and green in the JUnit XML under
     `email/build/test-results/test/` (a bare `BUILD SUCCESSFUL` can mask a
     no-tests-run state).
6. **`EmailConfig`** (+ `EmailConfigTest`).
   - Verify: `nix develop -c bin/test :email:test`; confirm `EmailConfigTest` is
     present and green in the JUnit XML.
7. **Domain/port types**: `OutboundEmail`, `SentEmail`, `ProviderResult`,
   `EmailProvider`, `EmailSendStatus`, `EmailExceptions`
   (`EmailConfigException`, `EmailRejectedException`, `EmailDeliveryException`).
   - Verify: `nix develop -c ./gradlew :email:compileKotlin`.
8. **`LogOnlyEmailProvider`** (+ `LogOnlyEmailProviderTest`).
   - Verify: `nix develop -c bin/test :email:test`; confirm
     `LogOnlyEmailProviderTest` is present and green in the JUnit XML.
9. **`EmailSendsDao` + `NewEmailSend`** (+ DB-backed `EmailSendsDaoTest`
   including `CHECK`, guard-trigger, and constraint-violation-trait assertions).
   - Verify: `nix develop -c bin/test :email:test`; confirm `EmailSendsDaoTest`
     is present and green in the JUnit XML.
10. **`EmailService`** (+ DB-backed `EmailServiceTest` with a fake
    `EmailProvider`).
    - Verify: `nix develop -c bin/test :email:test` (confirm `EmailServiceTest`
      is present and green in the JUnit XML), then `nix develop -c bin/test`
      (full regression).

## Files Modified

`SPEC.md` files (e.g. `db/schema/SPEC.md`,
`db/src/main/kotlin/ed/unicoach/db/models/SPEC.md`) are intentionally **not**
listed and must not be touched during implementation; spec synchronization is
handled out-of-band by the `spec-sync-loop`.

New:

- `db/schema/0008.create-email-sends.sql`
- `common/src/main/kotlin/ed/unicoach/common/models/EmailAddress.kt` (moved from
  `db`)
- `common/src/main/kotlin/ed/unicoach/common/models/ValidationResult.kt` (moved
  from `db`; holds `ValidationResult` + `ValidationError`)
- `common/src/test/kotlin/ed/unicoach/common/models/EmailAddressTest.kt`
- `email/build.gradle.kts`
- `email/src/main/resources/email.conf`
- `email/src/main/kotlin/ed/unicoach/email/EmailConfig.kt`
- `email/src/main/kotlin/ed/unicoach/email/EmailSubject.kt`
- `email/src/main/kotlin/ed/unicoach/email/EmailBody.kt`
- `email/src/main/kotlin/ed/unicoach/email/OutboundEmail.kt`
- `email/src/main/kotlin/ed/unicoach/email/SentEmail.kt`
- `email/src/main/kotlin/ed/unicoach/email/ProviderResult.kt`
- `email/src/main/kotlin/ed/unicoach/email/EmailProvider.kt`
- `email/src/main/kotlin/ed/unicoach/email/EmailSendStatus.kt`
- `email/src/main/kotlin/ed/unicoach/email/EmailExceptions.kt`
- `email/src/main/kotlin/ed/unicoach/email/LogOnlyEmailProvider.kt`
- `email/src/main/kotlin/ed/unicoach/email/EmailService.kt`
- `email/src/main/kotlin/ed/unicoach/email/dao/EmailSendsDao.kt`
- `email/src/main/kotlin/ed/unicoach/email/dao/NewEmailSend.kt`
- `email/src/test/kotlin/ed/unicoach/email/EmailSubjectTest.kt`
- `email/src/test/kotlin/ed/unicoach/email/EmailBodyTest.kt`
- `email/src/test/kotlin/ed/unicoach/email/EmailConfigTest.kt`
- `email/src/test/kotlin/ed/unicoach/email/LogOnlyEmailProviderTest.kt`
- `email/src/test/kotlin/ed/unicoach/email/EmailServiceTest.kt`
- `email/src/test/kotlin/ed/unicoach/email/dao/EmailSendsDaoTest.kt`

Deleted:

- `db/src/main/kotlin/ed/unicoach/db/models/EmailAddress.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/ValidationResult.kt`

Modified (import/package rewrites for the relocated types):

- `db/src/main/kotlin/ed/unicoach/db/models/DisplayName.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/PartialDate.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/PasswordHash.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/PersonName.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/SsoProviderId.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewUser.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/User.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/UserVersion.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/StudentsDao.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/StudentsDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/models/PartialDateTest.kt`
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt`
- `service/src/main/kotlin/ed/unicoach/auth/LoginResult.kt`
- `service/src/main/kotlin/ed/unicoach/auth/RegistrationValidator.kt`
- `service/src/main/kotlin/ed/unicoach/student/StudentService.kt`
- `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/student/StudentServiceTest.kt`
- `settings.gradle.kts`
