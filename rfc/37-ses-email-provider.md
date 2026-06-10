# 37 — Amazon SES Email Provider

## Executive Summary

This RFC implements the first transmitting `EmailProvider`: `SesEmailProvider`,
backed by Amazon SES (AWS SDK for Kotlin, `sesv2`). It slots in behind the
`EmailProvider` port defined by RFC 34 with no change to `EmailService`'s public
signature or control flow, the `email_sends` ledger, or `EmailSendsDao`. The
adapter maps the SES `SendEmail` outcome onto the existing `ProviderResult`
variants: a returned `messageId` → `Sent`; permanent SES errors (rejection,
unverified MAIL FROM, suspended/paused account, bad request) → `Rejected`;
throttling, quota, transport, and unrecognized errors → `TransientFailure`.

Idempotency and dedup are **out of scope**: RFC 34 assigns both the
provider-level idempotency key and the transactional outbox to the future queue
RFC, and this RFC leaves that allocation intact. The non-transactional
provider/DB dual-write hazard (sent-twice on retry) is therefore
accepted-and-open; it is specified once below (see "Idempotency and the
dual-write hazard").

Configuration gains a `provider` selector (`log` | `ses`) and an `email.ses`
block (region + optional static credentials), all `${?ENV}`-overridable.
`EmailProviderFactory` constructs the selected provider from config. The
`:email` module remains unwired; it is compiled and tested only, consistent with
RFC 34.

## Detailed Design

### Provider selection and module wiring

`EmailProviderFactory` is a new selector that maps `email.provider` to a
concrete `EmailProvider`. The module stays unwired: no `main()` calls the
factory and no production module depends on `:email`. The factory and SES
adapter are compiled and exercised by tests only, mirroring RFC 34's stance that
production construction is the queue RFC's responsibility.

There is **no production `AppConfig.load(...)` callsite to modify.** The only
loaders that reference `email.conf` are the `:email` test fixtures
(`EmailConfigTest`, `EmailServiceTest`, `EmailSendsDaoTest`), which already list
it. Wiring `email.conf` into a production config list is deferred with the
production call site, per RFC 34. This RFC therefore adds config _keys_ to
`email.conf` and the `EmailConfig` reader, not a new load site.

### Idempotency and the dual-write hazard

This RFC adds **no** idempotency or dedup machinery: no key derivation, no
ledger lookup, no schema change. `EmailService.send` retains RFC 34's control
flow (send, then record the terminal outcome); `EmailSendsDao` and `email_sends`
are untouched. RFC 34 already assigns the provider-level idempotency key and the
transactional outbox to the future queue RFC, and that allocation is unchanged
here.

The dual-write hazard RFC 34 documented therefore stands: `provider.send` (an
external RPC) and the `email_sends` insert are not atomic, so a
sent-but-unrecorded outcome becomes **sent-twice** on any retry. A content-hash
ledger dedup is rejected because it is keyed on content rather than the
per-triggering-event id the outbox needs, so the outbox would replace it rather
than build on it. SES exposes no native idempotency token, so even a
transmitting adapter cannot dedup at the provider; the closing mechanism is the
queue RFC's transactional outbox with a per-event key and single-flight
dispatch. The hazard is latent while the module stays unwired (no retrying
caller, no production call site).

### Data Models

No persistence or domain-model changes. `email_sends`, `NewEmailSend`,
`EmailSendStatus`, `OutboundEmail`, and `SentEmail` are unchanged from RFC 34,
and no migration is added. The only new structured type is the `SesConfig`
configuration reader (see Configuration below).

### API Contracts

`EmailService` and `EmailSendsDao` are unchanged from RFC 34. New surface is the
SES adapter, the provider selector, and the config readers.

#### Adapter — `SesEmailProvider`

The SES adapter implements `EmailProvider` over a narrow seam, so its mapping
logic is unit-testable without the AWS SDK's full client interface or a network.

```
fun interface SesSendOperation {
  suspend fun send(request: SendEmailRequest): SendEmailResponse
}

class SesEmailProvider(
  private val ses: SesSendOperation,
  private val resource: AutoCloseable,   // backing SesV2Client; closed by close()
) : EmailProvider, AutoCloseable {
  override val id: String                // constant "ses"; written to the ledger `provider` column
  override suspend fun send(email: OutboundEmail): ProviderResult
  override fun close()                   // closes the backing SES client
}
```

`send` builds a SES `SendEmailRequest` from the `OutboundEmail`
(`fromEmailAddress` ← `from.value`; `destination.toAddresses` ← `[to.value]`;
`content.simple` ← subject + plain-text body, each `Content` carrying an
explicit `charset = "UTF-8"` — SES defaults unset charsets to 7-bit ASCII, which
would mangle the non-ASCII subjects/bodies the value classes admit), invokes the
seam, and maps the outcome per the table below. `id` is the constant `ses` (the
wire identity, like RFC 34's `log`, names the delivery mechanism, not the
class). `SesEmailProvider` owns its `SesV2Client`'s lifecycle via
`AutoCloseable`; the port stays minimal (`id` + `send`), so a closer (future
wiring) checks `is AutoCloseable`. Closing the `SesV2Client` is sufficient: the
only credentials provider this RFC sets explicitly is
`StaticCredentialsProvider` (in-memory, no closeable resources), and the default
chain is owned and closed by the SDK.

The factory adapts the real `SesV2Client::sendEmail` to `SesSendOperation`;
tests supply a lambda. This avoids both a mocking framework (none is on the
classpath) and implementing the ~40-method `SesV2Client` interface.

#### SES outcome → `ProviderResult` mapping

| SES outcome                                                                                                                                    | `ProviderResult`           | Trait via `EmailService` |
| :--------------------------------------------------------------------------------------------------------------------------------------------- | :------------------------- | :----------------------- |
| Returns `SendEmailResponse` (has `messageId`)                                                                                                  | `Sent(messageId)`          | success                  |
| `MessageRejectedException`, `MailFromDomainNotVerifiedException`, `AccountSuspendedException`, `SendingPausedException`, `BadRequestException` | `Rejected(reason)`         | `PermanentError`         |
| `TooManyRequestsException`, `LimitExceededException`                                                                                           | `TransientFailure(reason)` | `TransientError`         |
| Any other throwable (transport/timeout, 5xx, unrecognized `SesV2Exception`)                                                                    | `TransientFailure(reason)` | `TransientError`         |

The catch-all maps to `TransientFailure`: for transactional email, retrying an
unknown error (the queue bounds attempts) is safer than silently dropping a
deliverable message. `reason` carries the SES exception message verbatim.

#### Selector — `EmailProviderFactory`

A selector mapping the config `provider` string to a concrete adapter.

```
object EmailProviderFactory {
  fun fromConfig(config: EmailConfig): Result<EmailProvider>
}
```

`fromConfig` returns `LogOnlyEmailProvider` for `provider == "log"`, constructs
a `SesV2Client` from `config.ses` (region; static credentials when both are
present, else the AWS default credential provider chain) and wraps it in
`SesEmailProvider` for `provider == "ses"`, and returns `Result.failure` for any
other value. The constructed `SesV2Client`'s lifetime transfers to the returned
`SesEmailProvider` (closed via its `close()`).

#### Configuration — `EmailConfig` (extended) and `SesConfig`

`email.conf` gains a provider selector and an `email.ses` block; all values are
`${?ENV}`-overridable.

```hocon
email {
  defaultFrom = "noreply@unicoach.app"
  defaultFrom = ${?EMAIL_DEFAULT_FROM}

  provider = "log"                       # "log" | "ses"
  provider = ${?EMAIL_PROVIDER}

  ses {
    region = "us-east-1"
    region = ${?EMAIL_SES_REGION}

    # Optional static credentials. When either is absent, the SES client uses the
    # AWS default credential provider chain (env vars, IAM instance/task role).
    accessKeyId     = ${?EMAIL_SES_ACCESS_KEY_ID}
    secretAccessKey = ${?EMAIL_SES_SECRET_ACCESS_KEY}
  }
}
```

```
class EmailConfig private constructor(
  val defaultFrom: String,
  val provider: String,
  val ses: SesConfig,
) {
  companion object { fun from(config: Config): Result<EmailConfig> }
}

class SesConfig private constructor(
  val region: String,
  val accessKeyId: String?,
  val secretAccessKey: String?,
) {
  companion object { fun from(config: Config): Result<SesConfig> }
}
```

`EmailConfig.from` reads `email.provider` and `email.defaultFrom` with fail-fast
`getString` (both have packaged defaults) and delegates `email.ses` to
`SesConfig.from`. `SesConfig.from` reads `email.ses.region` fail-fast (packaged
default) and the two credential keys via `hasPath` → nullable (absent when the
env var is unset). No address or region validation here, consistent with RFC
34's raw-string `defaultFrom`.

> **REQUIRES ARCHITECT DECISION — credential handling.** The task asked for
> "credential config." Embedding long-lived static AWS keys in config is an
> anti-pattern for an AWS-native deployment, where IAM instance/task roles are
> the intended mechanism. **Recommendation (designed above):** region is
> explicit config; credentials default to the AWS provider chain (role/env),
> with optional static `accessKeyId`/`secretAccessKey` override fields for
> local/dev only. Alternative (rejected): require static keys in config
> unconditionally.

### Error Handling / Edge Cases

- **SES permanent errors** → `ProviderResult.Rejected` → RFC 34's existing path:
  one `REJECTED` ledger row + `EmailRejectedException` (`PermanentError`).
- **SES transient/throttling/unknown errors** →
  `ProviderResult.TransientFailure` → RFC 34's existing path: no row,
  `EmailDeliveryException` (`TransientError`).
- **Unknown `email.provider` value**: `EmailProviderFactory.fromConfig` returns
  `Result.failure`; no provider is constructed.
- **Partial static credentials** (exactly one of `accessKeyId`/`secretAccessKey`
  present): the factory's static path requires **both**, so a half-configured
  pair falls through to the AWS default credential provider chain rather than
  failing. No half-set static credentials are passed to the SDK.
- **Invalid/blank region or unresolvable credentials**: not validated at config
  load (raw strings); surfaced by the SES SDK at first `send` and mapped to
  `TransientFailure` by the adapter's catch-all.
- **Dual-write hazard (residual, accepted-and-open)**: specified above under
  "Idempotency and the dual-write hazard" — the SES `send` and the `email_sends`
  insert are not atomic, so a sent-but-unrecorded outcome becomes sent-twice on
  retry. No dedup here; bounded later by the queue RFC.

### Dependencies

- **External (new):** AWS SDK for Kotlin SES v2 — `aws.sdk.kotlin:sesv2` — added
  to the version catalog and `email/build.gradle.kts` (`implementation`). It is
  coroutine-native (`suspend fun sendEmail`), matching the port's
  `suspend send`, and transitively provides the smithy-kotlin runtime and a
  default HTTP engine. No manual SigV4 (rejected: more code, hand-rolled
  signing).

  > **REQUIRES ARCHITECT DECISION — version pin.** Pin `aws-sdk-kotlin` to the
  > latest stable `aws.sdk.kotlin:sesv2` and confirm Gradle resolves it.
  > **Recommendation:** `1.6.27` (latest stable verified at review time);
  > re-check for a newer patch at implementation time before fixing the version.

- **Internal:** unchanged from RFC 34 — `:email` depends on `:common` and `:db`.
  No migration and no schema dependency is introduced. `kotlinx.coroutines.test`
  (already a test dep) covers `SesEmailProvider`'s `suspend` mapping tests; no
  new test dependency.

## Tests

### `SesEmailProvider` (`email`, fake `SesSendOperation`)

- `id == "ses"`.
- Seam returns `SendEmailResponse(messageId = "m-1")` → `ProviderResult.Sent`
  with `providerMessageId == "m-1"`.
- Seam throws `MessageRejectedException` → `Rejected`, `reason` carries the
  exception message.
- Seam throws `MailFromDomainNotVerifiedException` → `Rejected`.
- Seam throws `AccountSuspendedException` → `Rejected`.
- Seam throws `SendingPausedException` → `Rejected`.
- Seam throws `BadRequestException` → `Rejected`.
- Seam throws `TooManyRequestsException` → `TransientFailure`.
- Seam throws `LimitExceededException` → `TransientFailure`.
- Seam throws a generic `RuntimeException` (transport-like) → `TransientFailure`
  (catch-all).
- The `SendEmailRequest` built from a captured `OutboundEmail` carries
  `fromEmailAddress`, a single `toAddresses` entry, and the subject/body data
  matching the input value classes, each `Content` with `charset == "UTF-8"`.
- `close()` closes the injected `resource` (a recording fake `AutoCloseable`).

### `EmailProviderFactory` (`email`)

- `provider = "log"` → `Result.success`, instance `is LogOnlyEmailProvider`,
  `id == "log"`.
- `provider = "ses"` → `Result.success`, instance `is SesEmailProvider`,
  `id == "ses"`; the test closes the returned provider (releasing the real
  `SesV2Client`, constructed offline with the default region).
- `provider = "ses"` with exactly one static credential key set →
  `Result.success`, instance `is SesEmailProvider` (the static path requires
  both keys; one-key-only falls back to the default chain without failing). The
  test closes the returned provider.
- `provider = "smtp"` (unknown) → `Result.failure`.

### `EmailConfig` / `SesConfig` (`email`)

- `from` reads `provider` and `ses.region` from a parsed `Config`.
- `email.conf` on the classpath merges to defaults: `provider == "log"`,
  `ses.region == "us-east-1"`, credentials null.
- Credentials present in a parsed `Config` surface on `SesConfig`; absent →
  null.
- Existing `EmailConfigTest` cases updated:
  `from reads email defaultFrom
  verbatim` and
  `from does not validate the address` build their `Config` from a bare
  `parseString("email.defaultFrom = …")` with no `email.provider` or
  `email.ses.region`; both now add those keys so `SesConfig.from`'s fail-fast
  `getString("email.ses.region")` (and `EmailConfig.from`'s
  `getString("email.provider")`) succeed. (The classpath-merge case already
  resolves both from packaged `email.conf` defaults.)

### `EmailService` (`email`, DB-backed, fake `EmailProvider`)

- All RFC 34 cases continue to pass unchanged (no new control flow). The only
  edit is `EmailServiceTest`'s `config()` helper, updated to include `provider`
  and `ses.region` so the extended `EmailConfig.from` resolves.

## Implementation Plan

Each step is atomic and locally verifiable. Commands run inside the Nix dev
shell (`nix develop -c ...`). `bin/test` recreates and migrates the test
database before invoking Gradle. Confirm new tests are present and green in the
JUnit XML under `email/build/test-results/test/` — a bare `BUILD SUCCESSFUL` can
mask a no-tests-run state.

1. **Add the SES dependency.** Add an `aws-sdk-kotlin` version and an
   `aws-sesv2` library to `gradle/libs.versions.toml`; add
   `implementation(libs.aws.sesv2)` to `email/build.gradle.kts`.
   - Verify:
     `nix develop -c ./gradlew :email:dependencies --configuration runtimeClasspath`
     lists `aws.sdk.kotlin:sesv2`;
     `nix develop -c ./gradlew :email:compileKotlin`.
2. **Config**: extend `email.conf` (`provider`, `email.ses` block); add
   `SesConfig.kt`; extend `EmailConfig.kt`; update `EmailConfigTest`; update
   `EmailServiceTest`'s `config()` helper to include `provider` and
   `ses.region`.
   - Verify:
     `nix develop -c bin/test :email:test --tests "ed.unicoach.email.EmailConfigTest"`
     and `--tests "ed.unicoach.email.EmailServiceTest"`.
3. **SES adapter**: add `SesSendOperation.kt` and `SesEmailProvider.kt` (+
   `SesEmailProviderTest`).
   - Verify:
     `nix develop -c bin/test :email:test --tests "ed.unicoach.email.SesEmailProviderTest"`.
4. **Selector**: add `EmailProviderFactory.kt` (+ `EmailProviderFactoryTest`).
   - Verify:
     `nix develop -c bin/test :email:test --tests "ed.unicoach.email.EmailProviderFactoryTest"`;
     then `nix develop -c bin/test` (full regression).

## Files Modified

`SPEC.md` files are intentionally **not** listed and must not be touched; spec
synchronization is handled out-of-band by the `spec-sync-loop`.

New:

- `email/src/main/kotlin/ed/unicoach/email/SesConfig.kt`
- `email/src/main/kotlin/ed/unicoach/email/SesSendOperation.kt`
- `email/src/main/kotlin/ed/unicoach/email/SesEmailProvider.kt`
- `email/src/main/kotlin/ed/unicoach/email/EmailProviderFactory.kt`
- `email/src/test/kotlin/ed/unicoach/email/SesEmailProviderTest.kt`
- `email/src/test/kotlin/ed/unicoach/email/EmailProviderFactoryTest.kt`

Modified:

- `gradle/libs.versions.toml`
- `email/build.gradle.kts`
- `email/src/main/resources/email.conf`
- `email/src/main/kotlin/ed/unicoach/email/EmailConfig.kt`
- `email/src/test/kotlin/ed/unicoach/email/EmailConfigTest.kt`
- `email/src/test/kotlin/ed/unicoach/email/EmailServiceTest.kt`
