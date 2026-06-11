# SPEC: `email/src/main/kotlin/ed/unicoach/email`

## I. Overview

The `email` package is the transactional email domain layer. It exposes a single
non-blocking operation — deliver one email through a pluggable provider port and
record every terminal outcome in an append-only ledger. It owns the domain value
types (`EmailSubject`, `EmailBody`, `OutboundEmail`, `SentEmail`), the
`EmailProvider` port and its `ProviderResult` outcomes, two adapters — the
record-only `LogOnlyEmailProvider` and the transmitting `SesEmailProvider`
(Amazon SES) behind its `SesSendOperation` seam — the `EmailProviderFactory`
that selects between them by configuration, the `EmailService` orchestrator, the
configuration surface (`EmailConfig`/`SesConfig`), and the typed failure
exceptions. The ledger is reached only through the `dao/` collaborator; no SQL
or transaction management lives in this package.

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
  (`providerMessageId` = the outcome's provider message id (nullable), error
  message null) and return success carrying the ledger row id and the provider
  message id.
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
- `EmailSubject.create` MUST reject blank input as `Invalid(Blank)` and input
  exceeding `MAX_SUBJECT_LENGTH` (255) as `Invalid(TooLong)` carrying the
  violated maximum length; otherwise `Valid`.
- `EmailBody.create` MUST reject blank input as `Invalid(Blank)` and input
  exceeding `MAX_BODY_LENGTH` (65536 characters) as `Invalid(TooLong)` carrying
  the violated maximum length; otherwise `Valid`. The bound is a character
  count, NOT a byte count.
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

### SES adapter (`SesEmailProvider`)

- `SesEmailProvider.id` MUST be the constant `"ses"` — the delivery mechanism's
  wire identity, not the class name — written verbatim to the ledger.
- `SesEmailProvider.send` MUST transmit through Amazon SES (`sesv2`) and map
  every `SendEmail` outcome onto a `ProviderResult`:
  - a returned message id → `Sent(messageId)`;
  - permanent SES errors (message rejected, unverified MAIL FROM domain,
    suspended account, sending paused, bad request) → `Rejected(reason)`;
  - throttling/quota errors (too-many-requests, limit-exceeded), transport
    failures, and any unrecognized error → `TransientFailure(reason)`.
- An error the adapter does not recognize MUST map to `TransientFailure`, NEVER
  `Rejected`: a bounded retry is safer than silently dropping a deliverable
  message. Each `reason` MUST carry the SES exception message verbatim, falling
  back to the exception class name when the message is absent.
- `SesEmailProvider` MUST pin the UTF-8 charset on both subject and body
  content; it MUST NOT rely on the SES default (7-bit ASCII), which would mangle
  the non-ASCII text the value types admit.
- `SesEmailProvider` MUST own its backing `SesV2Client` and release it via
  `AutoCloseable.close()`.
- `SesSendOperation` MUST be the sole seam between `SesEmailProvider` and
  `SesV2Client.sendEmail` — a `fun interface` so the mapping logic is
  unit-testable without the real client, a mocking framework, or a network.

### Provider selection (`EmailProviderFactory`)

- `EmailProviderFactory.fromConfig` MUST map `EmailConfig.provider` to exactly
  one adapter: `"log"` → `LogOnlyEmailProvider`, `"ses"` → `SesEmailProvider`.
  Any other value MUST yield `Result.failure`, NEVER a silent fallback default.
- The SES client MUST be built with the configured `region` and MUST use static
  credentials only when BOTH `accessKeyId` and `secretAccessKey` are present;
  with either absent it MUST fall back to the AWS default credential provider
  chain (env vars, IAM instance/task role).
- The constructed `SesV2Client`'s lifetime MUST transfer to the returned
  `SesEmailProvider`, which owns closing it.

### Outcome / error modeling

- Permanent, expected domain rejections MUST be modeled as `Rejected`/`Sent`
  outcomes and the `PermanentError`-tagged exceptions; retriable failures MUST
  be modeled as `TransientFailure` and the `TransientError`-tagged exception.
  The three exceptions ([EmailExceptions.kt](./EmailExceptions.kt)) MUST carry
  the marker traits exactly as:
  - `EmailConfigException : PermanentError` (invalid configured sender)
  - `EmailRejectedException : PermanentError` (provider permanent rejection)
  - `EmailDeliveryException : TransientError` (provider transient failure)
- The transient/permanent marker trait is the queue-readiness contract: a future
  retrying caller branches on it. Failures returned by `send` MUST preserve a
  cause that carries the correct marker trait (either an `email` exception
  above, or the underlying ledger-write failure's own trait, unaltered).

### Configuration

- `EmailConfig` MUST be constructed only via `from(config)`, which reads
  `email.defaultFrom` and `email.provider` verbatim (both have packaged
  defaults) and delegates the `email.ses` block to `SesConfig.from`. It MUST NOT
  validate the sender address — a malformed `defaultFrom` still yields a
  successful `EmailConfig`; address validation is `EmailService`'s
  responsibility at first send.
- `EmailConfig` MUST carry the `provider` selector string consumed by
  `EmailProviderFactory`; the chosen adapter is resolved there, not in
  `EmailConfig`.
- `SesConfig.from` MUST read `email.ses.region` fail-fast (packaged default) and
  the two credential keys via presence check → nullable (each absent when its
  env-var override is unset). It MUST NOT validate region or credentials; the
  SES SDK surfaces those at first send, where the adapter's catch-all maps them
  to `TransientFailure`.

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

### `SesEmailProvider.send(email: OutboundEmail): ProviderResult` / `close()`

See [SesEmailProvider.kt](./SesEmailProvider.kt),
[SesSendOperation.kt](./SesSendOperation.kt).

- **Side Effects**: `send` transmits one email through Amazon SES (`sesv2`) via
  the injected `SesSendOperation`. `close` releases the backing `SesV2Client`.
- **Behavior**: `id == "ses"`. Builds a `SendEmailRequest`
  (from/to/subject/body) with UTF-8 pinned on subject and body, then maps the
  outcome: message id → `Sent`; permanent SES errors → `Rejected`;
  throttling/quota, transport, and unrecognized errors → `TransientFailure`.
- **Error Handling**: SES outcomes are returned as `ProviderResult` values,
  never thrown. Each `Rejected`/`TransientFailure` `reason` is the SES exception
  message verbatim, falling back to the exception class name when the message is
  absent. An unrecognized error resolves to `TransientFailure`, not `Rejected`.
- **Idempotency**: No. Each `send` is one SES transmission; a retry
  re-transmits.

### `SesSendOperation.send(request: SendEmailRequest): SendEmailResponse`

See [SesSendOperation.kt](./SesSendOperation.kt).

- **Side Effects**: Implementation-defined. The factory's adapter calls
  `SesV2Client.sendEmail`; tests supply a lambda.
- **Behavior**: A `fun interface` seam isolating `SesEmailProvider`'s mapping
  logic from the concrete `SesV2Client`.
- **Error Handling**: Propagates SES SDK exceptions to the caller, which maps
  them.
- **Idempotency**: Adapter-dependent (unspecified at this seam).

### `EmailProviderFactory.fromConfig(config: EmailConfig): Result<EmailProvider>`

See [EmailProviderFactory.kt](./EmailProviderFactory.kt).

- **Side Effects**: For `provider == "ses"`, constructs a `SesV2Client`
  (region + optional static credentials) whose lifetime transfers to the
  returned `SesEmailProvider`. For `"log"`, constructs a `LogOnlyEmailProvider`.
- **Behavior**: Selects the adapter by `config.provider`. SES uses static
  credentials only when both `accessKeyId` and `secretAccessKey` are present;
  else the AWS default credential provider chain.
- **Error Handling**: Returns `Result.failure(IllegalArgumentException)` for any
  unknown `provider` value — no fallback default.
- **Idempotency**: Each call yields a new provider instance (and, for SES, a new
  client the caller must close).

### `EmailConfig.from(config: Config): Result<EmailConfig>`

See [EmailConfig.kt](./EmailConfig.kt), [SesConfig.kt](./SesConfig.kt).

- **Side Effects**: None.
- **Behavior**: Reads `email.defaultFrom` and `email.provider` from the supplied
  `Config` and delegates `email.ses` to `SesConfig.from`. `SesConfig.from` reads
  `email.ses.region` and the optional `accessKeyId`/`secretAccessKey`. Performs
  no address, region, or credential validation.
- **Error Handling**: Returns `Result.failure` if a required key (`defaultFrom`,
  `provider`, `ses.region`) is absent or unreadable, or if `SesConfig.from`
  fails (wrapping the config library's exception). A present but malformed
  address value still yields `Result.success`.
- **Idempotent**: Yes.

### `EmailSubject.create` / `EmailBody.create`

See [EmailSubject.kt](./EmailSubject.kt), [EmailBody.kt](./EmailBody.kt).

- **Side Effects**: None.
- **Behavior**: Validate non-blank and within the maximum character length, then
  wrap. `EmailSubject` bound is `MAX_SUBJECT_LENGTH` (255); `EmailBody` bound is
  `MAX_BODY_LENGTH` (65536 characters).
- **Error Handling**: `Invalid(Blank)` for blank; `Invalid(TooLong)` carrying
  the violated maximum length for over-length; never throws.
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
- **Configuration**: `email/src/main/resources/email.conf` defines, each with a
  packaged default and an optional `${?ENV}` override applied at load time:
  - `email.defaultFrom` (`"noreply@unicoach.app"`, override
    `EMAIL_DEFAULT_FROM`);
  - `email.provider` (`"log"`; values `"log" | "ses"`, override
    `EMAIL_PROVIDER`);
  - `email.ses.region` (`"us-east-1"`, override `EMAIL_SES_REGION`);
  - `email.ses.accessKeyId` / `email.ses.secretAccessKey` — no packaged default,
    set only via `EMAIL_SES_ACCESS_KEY_ID` / `EMAIL_SES_SECRET_ACCESS_KEY`; each
    key is present only when its own env var is set. Static credentials are used
    only when both are present, else the AWS default credential chain.

  `email.conf` MUST be on the `AppConfig.load(...)` resource list at any
  construction site; DB-backed tests load `common.conf`, `db.conf`,
  `email.conf`.
- **Ledger dependency**: the `email_sends` table (migration `0008` in
  `db/schema/`) MUST exist for `EmailService`/the DAO to record outcomes.
- **External dependencies**: `kotlinx.coroutines.core`, `slf4j.api`, `aws.sesv2`
  (AWS SDK for Kotlin, `sesv2` — the SES transport), and
  `compileOnly(postgresql)`.

---

## V. History

- [x] [RFC-34: Transactional Email Service](../../../../../../../rfc/34-transactional-email-service.md)
      — Introduced the `email` module: `EmailService` (config-resolved memoized
      sender, transmit-then-record ordering, terminal-only ledger writes), the
      `EmailProvider` port and `ProviderResult` outcomes, the
      `LogOnlyEmailProvider` adapter (`id = "log"`), value types
      `EmailSubject`/`EmailBody`/`OutboundEmail`/`SentEmail`,
      [`EmailSendStatus`](./EmailSendStatus.kt), `EmailConfig`, and the
      `PermanentError`/`TransientError`-tagged
      exceptions. Consumes `EmailAddress`/`ValidationResult` relocated from `db`
      to `common`.
- [x] [RFC-37: Amazon SES Email Provider](../../../../../../../rfc/37-ses-email-provider.md)
      — Added the first transmitting adapter `SesEmailProvider` (`id = "ses"`,
      sesv2-backed, UTF-8-pinned, `AutoCloseable`) behind the `SesSendOperation`
      seam, mapping SES outcomes onto `Sent`/`Rejected`/`TransientFailure`; the
      `EmailProviderFactory` (`"log" | "ses"` selection, static-vs-default
      credentials); and `SesConfig` plus the `email.provider`/`email.ses`
      configuration surface. Left idempotency, dedup, and production wiring
      deferred to the future queue RFC (consistent with RFC 34).
- [x] [RFC-40: Validation Error Reporting](../../../../../../../rfc/40-validation-error-reporting.md)
      — Renamed the blank rejection to `Blank`; `TooLong` rejections now carry
      the violated `maxLength`.
