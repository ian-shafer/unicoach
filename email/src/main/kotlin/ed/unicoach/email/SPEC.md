# SPEC: `email/src/main/kotlin/ed/unicoach/email`

## I. Overview

The `email` package is the transactional email domain layer. It exposes a single
non-blocking operation — deliver one email through a pluggable provider port and
record every terminal outcome in an append-only ledger. It owns the domain value
types (`EmailSubject`, `EmailBody`, `OutboundEmail`, `SentEmail`), the
`EmailProvider` port and its `ProviderResult` outcomes, the default
`LogOnlyEmailProvider` adapter, the `EmailService` orchestrator, the
configuration surface (`EmailConfig`), and the typed failure exceptions. The
ledger is reached only through the `dao/` collaborator; no SQL or transaction
management lives in this package.

---

## II. Invariants

### Send orchestration (`EmailService`)

- `send` MUST resolve the sender address from configuration
  (`EmailConfig.defaultFrom`), NOT from a caller-supplied parameter. The caller
  supplies only `to`, `subject`, and `body`.
- The resolved sender MUST be validated exactly once per `EmailService` instance
  and memoized for the instance's lifetime; a `defaultFrom` change MUST require
  reconstructing the service. The memoization MUST be safe for concurrent
  `suspend` callers.
- If the configured sender fails validation, `send` MUST return a permanent
  failure **before** invoking the provider, and MUST write no ledger row.
- A `Sent` provider outcome MUST write exactly one ledger row with status `SENT`
  (`providerMessageId` set, error message null) and return success carrying the
  ledger row id and the provider message id.
- A `Rejected` provider outcome MUST write exactly one ledger row with status
  `REJECTED` (error message = the provider's reason, `providerMessageId` null)
  and return a permanent failure.
- A `TransientFailure` provider outcome MUST write **no** ledger row and return
  a transient failure. Transient outcomes are NEVER recorded in this ledger.
- Every ledger row's provider identity MUST be sourced from `provider.id`, NEVER
  a hardcoded provider literal.
- When the ledger write fails, `send` MUST return the underlying write failure
  unaltered, in preference to any provider-derived outcome (including a
  `Rejected` reason). The write failure's cause MUST NOT be remapped to an
  `email`-domain exception.
- `send` MUST NOT validate `to`/`subject`/`body`: these arrive as already-valid
  domain types and are passed through unchanged into the `OutboundEmail`.

### Dual-write hazard (provider RPC vs. ledger insert)

- The provider transmission and the ledger insert are NOT atomic: no transaction
  spans them and the provider effect cannot be rolled back. The ordering MUST be
  **transmit, then record**, making the only unrecoverable case
  _sent-but-unrecorded_. The module accepts at-least-once semantics on retry; it
  MUST NOT attempt to make the two writes transactional.

### Value types

- `EmailSubject` and `EmailBody` MUST be constructed only via their `create`
  factory; the private constructor MUST NOT be reachable from outside the class.
- `EmailSubject.create` MUST reject blank input as `Invalid(BlankString)` and
  input exceeding `MAX_SUBJECT_LENGTH` (255) as `Invalid(TooLong)`; otherwise
  `Valid`.
- `EmailBody.create` MUST reject blank input as `Invalid(BlankString)` and input
  exceeding `MAX_BODY_LENGTH` (65536 characters) as `Invalid(TooLong)`;
  otherwise `Valid`. The bound is a character count, NOT a byte count.
- `create` factories MUST NEVER throw; all rejections MUST be expressed as
  `ValidationResult.Invalid`.
- `OutboundEmail` MUST carry only already-validated domain types (`EmailAddress`
  for `from`/`to`, `EmailSubject`, `EmailBody`) — it has no factory and performs
  no validation.
- `SentEmail` MUST be an immutable record of the ledger row id and the nullable
  provider message id; it carries no provider, recipient, or status fields.

### Provider port

- `EmailProvider` MUST expose a stable `id: String` (the wire identity written
  to the ledger) and a single `suspend fun send(email): ProviderResult`.
- `ProviderResult` MUST model exactly three terminal outcomes: `Sent` (accepted,
  carries nullable `providerMessageId`), `Rejected` (permanent, carries
  `reason`), `TransientFailure` (retriable, carries `reason`). The outcome set
  is closed (sealed).
- `LogOnlyEmailProvider.id` MUST be the constant `"log"`. Its `send` MUST always
  return `Sent` with a non-null synthetic `providerMessageId` and MUST transmit
  nothing.

### Outcome / error modeling

- Permanent, expected domain rejections MUST be modeled as `Rejected`/`Sent`
  outcomes and the `PermanentError`-tagged exceptions; retriable failures MUST
  be modeled as `TransientFailure` and the `TransientError`-tagged exception.
  The three exceptions MUST carry the marker traits exactly as:
  - `EmailConfigException : PermanentError` (invalid configured sender)
  - `EmailRejectedException : PermanentError` (provider permanent rejection)
  - `EmailDeliveryException : TransientError` (provider transient failure)
- The transient/permanent marker trait is the queue-readiness contract: a future
  retrying caller branches on it. Failures returned by `send` MUST preserve a
  cause that carries the correct marker trait (either an `email` exception
  above, or the underlying ledger-write failure's own trait, unaltered).

### Configuration

- `EmailConfig` MUST be constructed only via `from(config)`, which reads
  `email.defaultFrom` verbatim. It MUST NOT validate the address — a malformed
  `defaultFrom` still yields a successful `EmailConfig`; address validation is
  `EmailService`'s responsibility at first send.
- `EmailConfig` MUST expose no provider-selector field; the single adapter is
  chosen in wiring code.

---

## III. Behavioral Contracts

### `EmailService.send(to: EmailAddress, subject: EmailSubject, body: EmailBody): Result<SentEmail>`

See [EmailService.kt](./EmailService.kt).

- **Side Effects**: Invokes the injected `EmailProvider` (external transmission,
  for a real adapter) and conditionally writes one row to the `email_sends`
  ledger through the `dao/` collaborator. Sender resolution is memoized on first
  call (lazy initialization).
- **Execution Context**: Non-blocking `suspend`. Blocking ledger I/O is
  performed inside the collaborator's connection scope
  (`Database.withConnection`); the module does not pin work to the caller's
  context and manages no dispatcher of its own.
- **Control Flow**:
  1. Resolved sender `Invalid` → `Result.failure(EmailConfigException)`;
     provider not called; no row.
  2. Provider `Sent` → insert `SENT` row →
     `Result.success(SentEmail(id, providerMessageId))`.
  3. Provider `Rejected` → insert `REJECTED` row →
     `Result.failure(EmailRejectedException(reason))`.
  4. Provider `TransientFailure` → no row →
     `Result.failure(EmailDeliveryException(reason))`.
- **Error Handling**: Contractual failures are `EmailConfigException`,
  `EmailRejectedException`, `EmailDeliveryException` (each carrying its marker
  trait). A ledger-write failure short-circuits all of the above and is returned
  with its cause unaltered — this module neither swallows it nor remaps it to an
  `email` exception, so whatever marker trait the write failure already carries
  reaches the caller intact.
- **Idempotency**: No. Each call is a distinct send + append. On a
  _sent-but-unrecorded_ failure a retry produces a duplicate transmission
  (at-least-once); deduplication is a deferred concern, not provided here.

### `EmailProvider.send(email: OutboundEmail): ProviderResult`

See [EmailProvider.kt](./EmailProvider.kt),
[ProviderResult.kt](./ProviderResult.kt).

- **Side Effects**: Implementation-defined transmission. The port itself
  prescribes none beyond returning a terminal `ProviderResult`.
- **Error Handling**: Expected outcomes are returned as `ProviderResult` values
  (`Sent`/`Rejected`/`TransientFailure`), NOT thrown. `id` is written verbatim
  to the ledger `provider` column.
- **Idempotency**: Port-level: unspecified (adapter-dependent).

### `LogOnlyEmailProvider`

See [LogOnlyEmailProvider.kt](./LogOnlyEmailProvider.kt).

- **Side Effects**: Emits a log line; transmits nothing.
- **Behavior**: `id == "log"`. `send` always returns `Sent` with a freshly
  generated non-null `providerMessageId`.
- **Error Handling**: Never returns `Rejected` or `TransientFailure`.
- **Idempotency**: Each call yields a new `providerMessageId`; no external
  effect to repeat.

### `EmailConfig.from(config: Config): Result<EmailConfig>`

See [EmailConfig.kt](./EmailConfig.kt).

- **Side Effects**: None.
- **Behavior**: Reads `email.defaultFrom` from the supplied `Config`. Performs
  no address validation.
- **Error Handling**: Returns `Result.failure` if the `email.defaultFrom` key is
  absent or unreadable (wrapping the config library's exception). A present but
  malformed address value still yields `Result.success`.
- **Idempotent**: Yes.

### `EmailSubject.create` / `EmailBody.create`

See [EmailSubject.kt](./EmailSubject.kt), [EmailBody.kt](./EmailBody.kt).

- **Side Effects**: None.
- **Behavior**: Validate non-blank and within the maximum character length, then
  wrap. `EmailSubject` bound is `MAX_SUBJECT_LENGTH` (255); `EmailBody` bound is
  `MAX_BODY_LENGTH` (65536 characters).
- **Error Handling**: `Invalid(BlankString)` for blank; `Invalid(TooLong)` for
  over-length; never throws.
- **Idempotent**: Yes.

### Collaborators (referenced, not owned here)

- **`dao/` (`EmailSendsDao`, `NewEmailSend`)**: the append-only `email_sends`
  ledger port. `EmailService` maps an `OutboundEmail` + outcome to a
  `NewEmailSend` and inserts it within `Database.withConnection`, which owns the
  transaction. The DAO's own contract (terminal-status `CHECK`, append-only
  triggers, SQLSTATE-based transient/permanent failure classification) is
  documented in the `dao/` SPEC.
- **`ed.unicoach.common.models.EmailAddress`**: consumed domain type for
  `from`/`to`; constructed via its own validating factory in `common`. This
  module relies on it being already-valid at the `send` boundary and does not
  redefine it.
- **`ed.unicoach.error.{PermanentError, TransientError}`**: consumed marker
  traits from `common`, used to tag this module's exceptions for
  queue-readiness.

---

## IV. Infrastructure & Environment

- **Module**: `email` Gradle leaf module (`email/build.gradle.kts`). Plugins:
  `kotlin.jvm`, `ktlint` only (no serialization plugin). Depends on `common` and
  `db`. The module is currently **unwired**: no other module depends on it and
  no `main()` constructs `EmailService`; it is compiled and tested only.
- **Package**: `ed.unicoach.email`.
- **Configuration**: `email/src/main/resources/email.conf` defines
  `email.defaultFrom` with a packaged default of `"noreply@unicoach.app"`,
  overridable at load time via the `EMAIL_DEFAULT_FROM` environment variable
  (HOCON `${?EMAIL_DEFAULT_FROM}` optional substitution). `email.conf` MUST be
  on the `AppConfig.load(...)` resource list at any construction site; DB-backed
  tests load `common.conf`, `db.conf`, `email.conf`.
- **Ledger dependency**: the `email_sends` table (migration `0008` in
  `db/schema/`) MUST exist for `EmailService`/the DAO to record outcomes.
- **External dependencies**: `kotlinx.coroutines.core`, `slf4j.api`,
  `compileOnly(postgresql)`. No SMTP or HTTP client (deferred with the real
  adapter).

---

## V. History

- [x] [RFC-34: Transactional Email Service](../../../../../../../rfc/34-transactional-email-service.md)
      — Introduced the `email` module: `EmailService` (config-resolved memoized
      sender, transmit-then-record ordering, terminal-only ledger writes), the
      `EmailProvider` port and `ProviderResult` outcomes, the
      `LogOnlyEmailProvider` adapter (`id = "log"`), value types
      `EmailSubject`/`EmailBody`/`OutboundEmail`/`SentEmail`, `EmailSendStatus`,
      `EmailConfig`, and the `PermanentError`/`TransientError`-tagged
      exceptions. Consumes `EmailAddress`/`ValidationResult` relocated from `db`
      to `common`.
