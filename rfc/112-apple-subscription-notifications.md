# RFC 112: App Store Server Notifications V2 webhook

## Executive Summary

RFC 110 landed the verify path and left one gap: a renewal reaches the server
only when the app re-posts `/verify`. Until it does, the row's window has
elapsed, `findCurrent` returns null, and a paying subscriber meters as an
exhausted free-tier student. This RFC is the second slice of the
`subscriptions-apple` node of `features/paid-subscriptions.md` and closes that
gap.

An Apple-authenticated endpoint,
`POST /api/v1/subscriptions/apple-notifications`, receives App Store Server
Notifications V2. It carries no session cookie: the notification's own JWS
signature is the authentication, verified for real — the `x5c` certificate chain
is validated by PKIX to a pinned Apple Root CA – G3, the leaf must carry Apple's
notification marker OID, and ES256 is pinned by the verifying algorithm. The
outer payload and its nested `signedTransactionInfo` both go through that
verifier; nothing inbound reaches decode-only `AppleJws`.

The notification is a **trigger, not a state carrier**. The endpoint enqueues
one `REFRESH_SUBSCRIPTION` job holding the `originalTransactionId`; the worker
looks up the owning row, re-fetches authoritative state from the App Store
Server API, and upserts through the same path `/verify` uses. That leaves one
Apple→row mapping in the codebase, makes duplicate and out-of-order deliveries
no-ops, and needs no code change when Apple adds a notification type.

The webhook refreshes but never binds: a notification for an unknown
`originalTransactionId` creates nothing. Backend only. No migration, no new
table, no new configuration key.

## Detailed Design

### Verified starting state (code, not RFCs)

- `AppleJws.payload(jws)` decodes without verifying and carries a class comment
  reserving the verifying sibling to this RFC. Its two callers are
  `AppStoreServerApi` (TLS-trusted response bodies) and
  `SubscriptionService.extractTransactionId` (an untrusted lookup key).
- `AppStoreServerApi.subscriptionStatus(transactionId)` returns
  `Found(AppleSubscription)` / `NotFound` / `Unavailable(reason)`, with a
  `Result.failure` reserved for an unparseable 200 body, an unknown status
  integer, or a GRACE entry lacking `gracePeriodExpiresDate`. Apple's endpoint
  accepts an `originalTransactionId` in the same path position, so no client
  change is needed to look one up.
- `SubscriptionsDao.findByOriginalTransactionId` exists with `upsert` and
  `findCurrent`; `upsert` refuses a rebind (`OwnedByOtherStudent`) and skips a
  no-op refresh (`Unchanged`). `SubscriptionStatus` spans
  `active`/`expired`/`billing_retry`/`grace`/`revoked`.
- `SubscriptionService.verify` orders validation → Apple lookup → plan check →
  upsert, and holds no database connection across the Apple call.
- `AppStoreConfig` exposes `environment` and `bundleId`; `AppStoreCredentials`
  is null when unconfigured. `AppStoreServerApiFactory.fromConfig` returns
  `Wiring(api, client)` and hands the client's lifetime to the caller.
- `AppStoreTestFixtures` signs fixture JWSes with a generated P-256 key via
  java-jwt; nothing in the tree generates X.509 certificates.
- `configureClientKeyGate` rejects any request without `X-Unicoach-Client-Key`
  once `UNICOACH_CLIENT_KEYS` is set, exempting only
  `clientKeyGate.allowlistPaths` (today `["/healthz"]`).
  `configureEmailVerificationGate` passes a request with no resolvable caller.
  `SessionExpiryPlugin` returns immediately when the session cookie is absent.
- `rest-server.conf`'s `requestSize.maxSize` is 8 KiB with per-route overrides
  (`/api/v1/subscriptions/verify` = 24 KiB).
- `JobsDao` caps `payload` at 64 KiB (`jobs_payload_size_check`) and the worker
  prunes completed jobs by retention — the `jobs` table is not an audit trail.
  `subscriptions_versions` is.
- `openapi.yaml` declares no root-level `security`; each operation states its
  own. Migrations end at `0042`.
- `:service` already depends on `:appstore` and `:queue`; `:rest-server` already
  depends on `:appstore` and its testFixtures. `:queue-worker` does not load
  `appstore.conf` and builds no App Store client.

### Verification model — Apple's signature is the authentication

The endpoint is reachable by Apple and by anyone else who can address it, so its
entire authentication is the notification's JWS: a payload whose `x5c` chain
validates to Apple's pinned root, whose leaf is marked as an Apple notification
signer, and whose ES256 signature verifies under that leaf's key. Nothing else
about the request is trusted — not its source address, not a shared secret, not
a header.

This is the exact trust argument RFC 110 could not make and deliberately
deferred: the verify path fetches bytes _from_ Apple over TLS, so the transport
authenticates them; here the bytes arrive _at_ us, so only the signature can.
Two rules follow, and the tests pin both:

1. Every inbound Apple-signed JWS goes through `AppleJwsVerifier`, including the
   nested `data.signedTransactionInfo`. The outer signature does cover the
   nested string, so decoding it would be defensible — verifying it instead
   keeps the rule absolute and grep-checkable, at the cost of one more chain
   validation per notification.
2. `AppleJws` stays reachable only from TLS-fetched App Store Server API
   responses and from the client-posted lookup key.

### `:appstore` — the x5c-verifying sibling

#### Pinned trust anchors

`appstore/src/main/resources/apple-root-ca-g3.pem` is Apple Root CA – G3,
downloaded from `https://www.apple.com/certificateauthority/AppleRootCA-G3.cer`
and converted to PEM. Pinning one root, rather than trusting the JVM's default
store, is the point: a chain that validates against any other CA is not Apple's.

```kotlin
// appstore/src/main/kotlin/ed/unicoach/appstore/AppleRootCertificates.kt
/**
 * The pinned Apple trust anchors, parsed from the bundled PEM at first use.
 * Failure on: a missing or unparseable resource — a broken anchor set is a
 * broken build, not a runtime condition.
 */
object AppleRootCertificates {
  fun bundled(): Result<Set<X509Certificate>>
}
```

#### `AppleJwsVerifier`

```kotlin
// appstore/src/main/kotlin/ed/unicoach/appstore/AppleJwsVerifier.kt
/**
 * The x5c-chain-VERIFYING sibling of decode-only [AppleJws]: proves a JWS was
 * signed by Apple before its payload is read. Every inbound Apple-signed
 * payload goes through here.
 */
class AppleJwsVerifier(
  private val trustAnchors: Set<X509Certificate>,
  private val clock: Clock = Clock.systemUTC(),
) {
  /**
   * Failure — one taxonomy, all operator-facing and all equally unauthenticated
   * — on: absent or empty `x5c`; an undecodable certificate; a chain that PKIX
   * cannot validate to a [trustAnchors] member at the clock's instant; a leaf
   * without Apple's notification marker OID; a signature that does not verify
   * under the leaf's key (which includes any `alg` other than ES256); a payload
   * that is not a JSON object. Each failure message names which check failed.
   */
  fun verified(jws: String): Result<JsonObject>
}
```

Mechanics, all JDK or java-jwt primitives — nothing cryptographic is written
here:

- `CertificateFactory.getInstance("X.509")` parses each base64 `x5c` entry (leaf
  first, per RFC 7515) into an `X509Certificate`. Apple sends three entries —
  leaf, WWDR intermediate, and Apple Root CA – G3 — and RFC 5280 §6.1 validates
  a path that excludes the trust anchor, so a trailing entry equal to a
  [trustAnchors] member is dropped before the `CertPath` is built. Dropping it
  concedes nothing: trust comes from the bundled anchor, so a trailing
  certificate an attacker appends is not a member, stays in the path, and fails
  PKIX.
- `CertPathValidator.getInstance("PKIX")` validates the `CertPath` against
  `PKIXParameters(trustAnchors.map { TrustAnchor(it, null) })` with
  `date = clock.instant()`. `isRevocationEnabled = false`: an inbound Apple
  request must not block on an outbound OCSP fetch to Apple, and the pinned root
  plus the marker OID carry the trust. The accepted exposure is a leaf revoked
  but not yet expired.
- The leaf must carry the extension OID `1.2.840.113635.100.6.11.1`
  (`X509Certificate.getExtensionValue`). Without it, any certificate Apple
  issues for any purpose could sign a forged notification, because they all
  chain to the same root. This check, not the chain, is what makes the leaf a
  _notification signer_.
- `JWT.require(Algorithm.ECDSA256(leafPublicKey as ECPublicKey, null))` verifies
  the signature. Passing the algorithm rather than reading the header's `alg` is
  what defeats the `alg: none` and `alg: HS256` confusion attacks; a non-EC leaf
  key is rejected here.
- The payload segment is then decoded and parsed exactly as `AppleJws` does.

#### `AppleNotification` + `AppleNotificationVerifier`

```kotlin
// appstore/src/main/kotlin/ed/unicoach/appstore/AppleNotification.kt
/** One verified App Store Server Notification V2, reduced to what we act on and log. */
class AppleNotification(
  val notificationUuid: String,
  // Carried verbatim, never matched against a known set: an unrecognised type
  // still refreshes state correctly, so recognising types is not this design's job.
  val notificationType: String,
  val subtype: String?,
  val environment: AppStoreEnvironment,
  val bundleId: String,
  /**
   * Null when the notification names no transaction — Apple's TEST
   * notification carries a `data` block with no `signedTransactionInfo`, and
   * the summary-bearing types (`RENEWAL_EXTENSION` with subtype `SUMMARY`)
   * carry a `summary` block and no `data` at all. Nothing to refresh.
   */
  val originalTransactionId: String?,
)

/** Outcome of reading one inbound `signedPayload`. */
sealed interface AppleNotificationOutcome {
  data class Verified(val notification: AppleNotification) : AppleNotificationOutcome

  /**
   * Nothing here is proof the bytes came from Apple: an oversized body, a
   * malformed JWS, or any [AppleJwsVerifier] failure on the outer payload or
   * the nested transaction. [reason] is the operator-facing detail; the client
   * is told only that it is unauthenticated.
   */
  data class Untrusted(val reason: String) : AppleNotificationOutcome

  /** Apple-signed, but for another bundle or another App Store environment than this box serves. */
  data class ForeignTarget(val reason: String) : AppleNotificationOutcome
}
```

```kotlin
// appstore/src/main/kotlin/ed/unicoach/appstore/AppleNotificationVerifier.kt
class AppleNotificationVerifier(
  private val verifier: AppleJwsVerifier,
  private val config: AppStoreConfig,
) {
  /**
   * Failure (bug-grade, distinct from [AppleNotificationOutcome.Untrusted]) on:
   * an Apple-signed payload missing `notificationUUID` or `notificationType`;
   * one carrying neither a `data` nor a `summary` object; a target block
   * carrying no `bundleId` or no `environment`; an unknown environment string;
   * or a verified transaction carrying no `originalTransactionId`. A new Apple
   * payload shape must be looked at, not guessed at — the same posture
   * [AppStoreServerApi] takes toward an unknown status integer.
   */
  fun read(signedPayload: String): Result<AppleNotificationOutcome>

  companion object {
    /**
     * A V2 notification nests up to three JWSes, each carrying its own x5c
     * chain; 32 KiB bounds hostile input with room to spare. A JWS is
     * base64url — ASCII throughout — so a `length` comparison against this
     * size is exact, matching [SubscriptionService.MAX_JWS]'s reasoning.
     */
    val MAX_JWS: DataSize = DataSize.ofKibibytes(32)
  }
}
```

Order, and why: bound the string → verify the outer JWS → read the target block
(`data` when present, else `summary`; a V2 payload carries exactly one, and the
summary-bearing types carry no `data`) and hold its `bundleId` and `environment`
against `config` → verify the nested `data.signedTransactionInfo`, when there is
one, and read its `originalTransactionId`. The target check precedes the nested
verification so a notification for another bundle costs one chain validation,
not two.

`data.status` and `data.signedRenewalInfo` are deliberately unread: the
notification is a trigger, and the state comes from Apple's API.

### Queue — `REFRESH_SUBSCRIPTION`

```kotlin
// queue/src/main/kotlin/ed/unicoach/queue/JobType.kt
REFRESH_SUBSCRIPTION("REFRESH_SUBSCRIPTION"),
```

Named for the work, not the trigger, like every sibling (`SEND_EMAIL`,
`EXTRACT_CONVERSATION`).

```kotlin
// queue/src/main/kotlin/ed/unicoach/queue/SubscriptionRefreshPayload.kt
/**
 * Payload of a [JobType.REFRESH_SUBSCRIPTION] job: the Apple subscription to
 * re-read. [originalTransactionId] is the only field the handler acts on; the
 * notification identifiers ride along as log context so a dead-lettered job can
 * be traced back to the delivery that produced it, and never select behaviour.
 */
@Serializable
data class SubscriptionRefreshPayload(
  val originalTransactionId: String,
  val notificationUuid: String,
  val notificationType: String,
  val subtype: String? = null,
)
```

The raw `signedPayload` is not carried: re-verifying in the worker would make a
job's success depend on certificate validity at execution time rather than at
receipt.

### `:service` — `SubscriptionService.refresh` + the handler

```kotlin
// service/src/main/kotlin/ed/unicoach/subscriptions/RefreshResult.kt
sealed interface RefreshResult {
  data class Refreshed(val subscription: Subscription) : RefreshResult

  /** No local row owns this transaction: nothing to refresh, and no student to bind one to. */
  data object NotBound : RefreshResult

  data object UnknownTransaction : RefreshResult

  data class UnknownProduct(val productId: String) : RefreshResult

  data class AppStoreUnavailable(val reason: String) : RefreshResult
}
```

```kotlin
// service/src/main/kotlin/ed/unicoach/subscriptions/SubscriptionService.kt
class SubscriptionService(/* unchanged */) {
  suspend fun verify(studentId: StudentId, signedTransaction: String): Result<VerifyResult>

  /**
   * The webhook's flow: read the owning row, re-fetch authoritative state from
   * Apple, and upsert it under the student that row already names. Refreshes
   * but never binds — a subscription no student has verified is
   * [RefreshResult.NotBound], because the notification carries no student
   * identity and inventing one would let an Apple-authenticated endpoint mint
   * an entitlement.
   */
  suspend fun refresh(originalTransactionId: String): Result<RefreshResult>
}
```

`verify` and `refresh` differ only in where the student comes from — the session
for one, the existing row for the other — so the shared Apple lookup → plan
check → upsert tail is one private helper, keeping a single Apple→row mapping in
the codebase.

`refresh` order: `findByOriginalTransactionId` (null → `NotBound`, with no Apple
call — an unbound notification costs nothing) → `subscriptionStatus` → identity
check → `plans.periodBudget` (null → `UnknownProduct`, nothing written,
mirroring `verify`: no `subscriptions` row is ever written for a product the box
cannot budget) → `upsert` under the row's `studentId`. The identity check is
load-bearing: Apple's _Get All Subscription Statuses_ answers for every
auto-renewable subscription the customer holds, and `AppStoreServerApi` reduces
that to the entry with the greatest `expiresDate`, so the returned
`originalTransactionId` is not guaranteed to be the one asked for; a mismatch is
`RefreshResult.UnknownTransaction`, logged with both ids, and nothing is
written. The read and the write are separate transactions with the Apple call
between them, so no database connection is held across network I/O; ownership
cannot drift because the write is keyed by the same transaction the read matched
and a rebind is refused permanently. `upsert`'s `OwnedByOtherStudent` arm is
therefore unreachable and is surfaced as a `Result.failure` naming both students
rather than silently folded away.

```kotlin
// service/src/main/kotlin/ed/unicoach/subscriptions/SubscriptionRefreshHandler.kt
/**
 * The [JobHandler] for [JobType.REFRESH_SUBSCRIPTION]. `executionTimeout` is
 * strictly less than `lockDuration` so a slow Apple call cannot outlive its
 * queue lock; the work is one network call and one write, so distinct jobs run
 * in parallel — the [ed.unicoach.email.EmailSendHandler] shape.
 */
class SubscriptionRefreshHandler(
  private val subscriptionService: SubscriptionService,
) : JobHandler {
  override val jobType = JobType.REFRESH_SUBSCRIPTION
  override val config = JobTypeConfig(
    concurrency = 4, maxAttempts = 5, lockDuration = 2.minutes, executionTimeout = 30.seconds,
  )
  override suspend fun execute(payload: JsonObject): JobResult
}
```

| `RefreshResult`          | `JobResult`                                                |
| :----------------------- | :--------------------------------------------------------- |
| `Refreshed`              | `Success`                                                  |
| `NotBound`               | `Success` (INFO: no student has verified this yet)         |
| `UnknownProduct`         | `PermanentFailure`; ERROR names the product (config drift) |
| `UnknownTransaction`     | `RetriableFailure` (Apple sent it; its API should know it) |
| `AppStoreUnavailable`    | `RetriableFailure`                                         |
| undeserializable payload | `PermanentFailure` (poison message, logged raw)            |
| `Result.failure`         | rethrown, so the worker's default classifies it            |

### REST surface

#### `POST /api/v1/subscriptions/apple-notifications`

Grouped under `subscriptions` because that is the state it maintains, and named
for the sender because Apple is the only caller.

```kotlin
// rest-server/.../models/AppleNotificationRequest.kt
data class AppleNotificationRequest(val signedPayload: String)
```

`AppleNotificationRouteHandler(appleNotificationVerifier, queueService)` — a new
file, and, like `AuthRouteHandler`'s unauthenticated arms, no `CallerResolution`
delegation: there is no session to resolve. `post` plus
`rejectUnsupportedMethods(HttpMethod.Post)`, registered in `configureRouting`.

| Outcome                                  | Response                                       |
| :--------------------------------------- | :--------------------------------------------- |
| `Verified`, `originalTransactionId` set  | enqueue, then 200 (empty body)                 |
| `Verified`, `originalTransactionId` null | 200; INFO logs type and uuid; nothing enqueued |
| `Untrusted`                              | 401 `unauthorized`; WARN logs the reason       |
| `ForeignTarget`                          | 400 `validation_failed`; ERROR logs the reason |
| `EnqueueResult.DatabaseFailure`          | 500 `internal_error`, so Apple retries         |

The 200 carries an empty body because Apple reads only the status code; every
other status carries the standard `ErrorResponse` envelope, as every other route
does, and `api-specs/openapi.yaml` `$ref`s it for 400/401/413/500. 401 rather
than 400 for a failed signature: it is an authentication failure, and it is the
one outcome an attacker can provoke at will, so its `message` is a fixed string
and the reason stays in the log.

`ASYNC_WORK.md`'s required-enqueue rule holds with nothing to spell out: this
request has no other database work, so the enqueue **is** the request's
transaction, and the request fails if it fails.

#### Gates the endpoint must clear

- `clientKeyGate.allowlistPaths` gains
  `"/api/v1/subscriptions/apple-notifications"`. Apple sends no
  `X-Unicoach-Client-Key`, so without this the endpoint answers 403 in every
  environment where `UNICOACH_CLIENT_KEYS` is set — a failure invisible in dev,
  where the gate is inert.
- `requestSize.routeOverrides` gains the path at `48 KiB`: the 8 KiB default
  cannot hold three nested JWSes with their certificate chains, and the cap sits
  above `AppleNotificationVerifier.MAX_JWS` (32 KiB) plus JSON envelope, so an
  oversized body is refused by the service's own bound where the reason is
  legible, not by a bare 413.
- The email-verification gate and `SessionExpiryPlugin` both no-op on a request
  with no session cookie; neither needs a change.

### Wiring

`rest-server/Application.kt` hoists `AppStoreConfig.from(config).getOrThrow()`
into a local — today it is an inline argument to
`AppStoreServerApiFactory.fromConfig` — so `bundleId` and `environment` are
reachable, and builds
`AppleNotificationVerifier(AppleJwsVerifier(AppleRootCertificates.bundled().getOrThrow()), appStoreConfig)`.
`appModule` gains one parameter; every test call site ripples mechanically, and
`TestAppStore.kt` grows the one builder the suites share.

`queue-worker/Application.kt` gains the App Store client it does not have today:
`appstore.conf` in the `AppConfig.load` list, `AppStoreConfig`,
`AppStoreServerApiFactory.fromConfig`, and a `SubscriptionService` over them
plus the `SubscriptionPlans` the process already builds for `BudgetService` —
one instance, so the refresh path and the gate read the same plan table.
`SubscriptionRefreshHandler` is registered **unconditionally** — beside
`EmailSendHandler`, and outside the enabled-pass gate, because subscriptions are
not an optional coaching feature. The client closes in the existing `finally`
beside `emailProvider`.

### Retiring the renewal-staleness gap

`findCurrent` and `windowedCost` are untouched. Rollover remains what RFC 110
made it — the window read _is_ the reset, with no reset event — and the only
change is what advances the window: the webhook, rather than the app's next
`/verify`. An exhausted subscriber becomes entitled again on the turn after the
job runs.

`/verify` keeps its job. It is still the only path that binds a subscription to
a student, still the purchase-time call, and still an idempotent refresh. It
stops being the _renewal_ path. Five in-tree comments and one openapi
description say otherwise today and are corrected in place:
`SubscriptionService`'s class comment; `AppleJws`'s reservation of the verifying
sibling (now landed); both `SubscriptionsDao` sites — the object's class comment
("the Notifications-V2 webhook later") and `findByOriginalTransactionId`'s "the
webhook RFC's entry point"; `SubscriptionRoutingTest`'s "until the webhook RFC";
and the `verifySubscription` description in `openapi.yaml`.

### API Contracts

All specified above: `AppleRootCertificates`, `AppleJwsVerifier`,
`AppleNotification`, `AppleNotificationOutcome` (`Verified` / `Untrusted` /
`ForeignTarget`), `AppleNotificationVerifier` (+ `MAX_JWS`);
`JobType.REFRESH_SUBSCRIPTION` + `SubscriptionRefreshPayload`; `RefreshResult`,
`SubscriptionService.refresh`, `SubscriptionRefreshHandler`;
`AppleNotificationRequest`, `AppleNotificationRouteHandler`,
`POST /api/v1/subscriptions/apple-notifications`. Wiring ripples:
`configureRouting` (+ the handler), `appModule` (+ `appleNotificationVerifier`),
`startServer` (hoisted `AppStoreConfig`, verifier construction), `queue-worker`
`main()` (App Store client + handler). Config: `clientKeyGate.allowlistPaths`
and `requestSize.routeOverrides` in `rest-server.conf`. `api-specs/openapi.yaml`
gains the path and `AppleNotificationRequest`; the operation declares no
`security` block, matching the file's per-operation convention.

No migration, no new `ErrorCode` (`UNAUTHORIZED`, `VALIDATION_FAILED` and
`INTERNAL_ERROR` all exist), no new environment variable or SSM key.

### Error Handling / Edge Cases

- **The marker OID is the one thing synthetic tests cannot validate.** Every
  test here signs with a locally minted chain, so `1.2.840.113635.100.6.11.1` is
  asserted only against certificates this repo generated. The Paid Apps
  agreement is not yet active, so no real Apple notification exists to check it
  against. **Operational gate: one real sandbox notification must verify
  end-to-end before the production endpoint URL is entered in App Store
  Connect.** A wrong OID fails closed — every notification 401s and the row goes
  stale, exactly as today — so the failure is loud and non-destructive, but it
  is a failure.
- **Environment mismatch.** A `Sandbox` notification reaching a
  `production`-configured box is `ForeignTarget` → 400, never applied. App Store
  Connect holds separate Production and Sandbox notification URLs, so in normal
  operation the two never cross; a crossing is misconfiguration or an attack,
  and both deserve refusal. There is no sandbox deployment today, so sandbox
  notifications simply go undelivered until one exists — the same posture RFC
  110 took toward the production→sandbox fallback.
- **A notification for a subscription nobody verified.** `NotBound`. The
  student's next `/verify` binds the row and refreshes it in the same call, so
  nothing is lost. This is the normal state for a purchase whose app crashed
  before posting.
- **Duplicate delivery.** Apple retries the same `notificationUUID` on any
  non-2xx and may deliver twice regardless. Both jobs re-fetch the same state
  and the second `upsert` is `Unchanged` — no version row, no `updated_at` bump.
  Idempotency is a property of the design, not a dedup table.
- **Out-of-order delivery.** A late `DID_RENEW` arriving after `EXPIRED` cannot
  write stale state, because neither job reads the notification's state.
- **A notification type Apple adds later.** Enqueued and refreshed like any
  other; `notificationType` is logged, never matched.
- **Apple unreachable, or credentials unconfigured in the worker.** Both are
  `Unavailable` → `RetriableFailure` → dead-letter after five attempts. In dev
  and test no notifications arrive, so the unconfigured worker never runs one.
- **Lost notification.** Apple retries for roughly three days; past that the row
  stays stale until the app's next `/verify`, which the iOS node performs on
  launch and foreground. A reconciliation sweep is deliberately not built.
- **Fuzzing.** The path is fuzzed. A random `signedPayload` fails at the size or
  JWS-structure check → 401, already inside both schemathesis accepted sets; no
  `schemathesis.toml` change. The client-key allowlist entry means the fuzzer
  reaches the handler rather than the gate.
- **Body larger than 48 KiB.** Refused by `configureRequestSizeLimit` as 413
  before any verification work; between 32 and 48 KiB it reaches the service and
  is refused as `Untrusted` with a legible reason.
- **A verified payload in a shape Apple has never sent.** `Result.failure` → 500
  → Apple retries, and the operator sees it. Preferred over guessing: the same
  call `AppStoreServerApi` makes for an unknown status integer.
- **Rollback.** Nothing this RFC lands is irreversible in the database — no
  migration, no new table, and every `subscriptions` write is one the `/verify`
  path could equally have made. Backing out is therefore two ordered steps:
  clear the notification URL in App Store Connect first, so Apple stops posting,
  then revert the release. Reverting first leaves Apple retrying against a route
  that answers 403 (the client-key gate, once the allowlist entry is gone) for
  three days. Rows already refreshed stay correct and the tree returns to RFC
  110's behaviour: renewals reach the server on the app's next `/verify`.

### Dependencies

One new third-party dependency, test-only: `org.bouncycastle:bcpkix-jdk18on`
(with `bcprov-jdk18on`) wired solely as `testFixturesImplementation` of
`:appstore`, because nothing in the JDK's public API generates X.509
certificates and every negative case here needs a synthetic chain — a foreign
root, a leaf without the marker OID, an expired leaf, a truncated chain. It
never reaches a main or runtime classpath. Production verification uses only
`java.security.cert` and the already-present `java-jwt`.

New internal edges: none. `:service` already depends on `:appstore` and
`:queue`; `:rest-server` already depends on `:appstore` and its testFixtures;
`:queue-worker` reaches `:appstore` through `:service`'s existing edge and
declares its own for the composition root. `cron`, `admin-web`, `public-web`,
iOS: untouched.

## Tests

All suites run through `bin/test` (real Postgres); Apple's API is always the
scripted transport and every certificate is locally minted — no test reaches the
network.

### `appstore` — fixtures

`AppStoreTestFixtures` gains a BouncyCastle chain builder: a self-signed test
root, an intermediate, and a leaf carrying the marker OID, each parameterisable
(alternate root, OID omitted, validity in the past, chain truncated), plus
`signWithChain(payload, chain)` producing a JWS with the `x5c` header and
`signedNotification(...)` assembling a V2 payload around a nested signed
transaction.

### `appstore` — `AppleRootCertificatesTest` (new)

- **bundled anchor parses** — exactly one `X509Certificate`.
- **it is the Apple root** — subject DN contains `CN=Apple Root CA - G3`, and
  issuer equals subject.
- **it is self-signed** — `verify(cert.publicKey)` succeeds, proving the
  committed bytes are the genuine self-signed root rather than any certificate
  that happens to bear the name.
- **it is not expired** — `checkValidity()` passes.

### `appstore` — `AppleJwsVerifierTest` (new)

- **valid chain verifies** — payload returned as the signed `JsonObject`.
- **foreign root refused** — a structurally identical chain from a second test
  root fails; the message names the chain.
- **leaf without the marker OID refused** — the check that stops any other
  Apple-issued certificate from signing notifications.
- **expired leaf refused** — leaf `notAfter` in the past against the injected
  clock.
- **Apple's three-entry chain verifies** — `x5c` = leaf, intermediate, and the
  trust anchor itself, the shape Apple actually sends; pins that the anchor is
  stripped from the `CertPath` rather than validated as a path element. The
  fixture chain builder emits this shape by default.
- **two-entry chain verifies** — `x5c` = leaf and intermediate, the anchor
  omitted; both wire shapes are accepted.
- **truncated chain refused** — leaf only, intermediate omitted.
- **foreign trailing certificate refused** — a fourth entry that is not a
  [trustAnchors] member is kept in the path and fails PKIX.
- **absent and empty `x5c` refused.**
- **`alg: none` refused** and **`alg: HS256` refused** — the algorithm-confusion
  pair, signed so that only a header-trusting verifier would accept them.
- **tampered payload refused** — valid chain, payload segment mutated after
  signing.
- **non-JSON-object payload refused.**
- **each failure names its check** — messages are asserted, since they are the
  operator's only view of a 401.

### `appstore` — `AppleNotificationVerifierTest` (new)

- **verified notification** — `Verified` carrying uuid, type, subtype,
  environment, bundleId, and the `originalTransactionId` decoded from the nested
  transaction.
- **nested transaction on a foreign chain → `Untrusted`** — the test that pins
  rule 1: the nested JWS is verified, never merely decoded.
- **`data` without `signedTransactionInfo` → `Verified` with a null
  `originalTransactionId`** — Apple's TEST notification; nothing to refresh.
- **`summary` instead of `data` → `Verified` with a null
  `originalTransactionId`** — the `RENEWAL_EXTENSION` summary shape; `bundleId`
  and `environment` are read from `summary` and still held against `config`.
- **`summary` carrying a foreign bundleId → `ForeignTarget`** — the target check
  reads whichever block is present, not `data` alone.
- **neither `data` nor `summary` → `Result.failure`** — a new shape, not a
  refusal.
- **bundleId mismatch → `ForeignTarget`.**
- **environment mismatch → `ForeignTarget`** — a `Sandbox` payload against a
  `PRODUCTION` config.
- **blank, non-JWS, and over-`MAX_JWS` input → `Untrusted`** with no chain work.
- **unknown `notificationType` → `Verified`**, carried verbatim.
- **missing `notificationUUID` → `Result.failure`** — a new shape, not a
  refusal.

### `queue` — `JobTypeTest` (extend)

- **`REFRESH_SUBSCRIPTION` round-trips** through `fromValue`, matching the
  `SEND_EMAIL` case.

### `service` — `SubscriptionServiceTest` (extend, DB-backed)

- **refresh with no local row** — `NotBound`, **zero transport calls**, no row
  created.
- **refresh applies changed state** — same id, new window and status; `version`
  bumped, `student_id` unchanged though `refresh` was passed no student.
- **refresh of identical state** — `Refreshed`, `version` unmoved, no new
  `subscriptions_versions` row.
- **refresh records non-entitling statuses** — Apple `expired` and `revoked`
  each land in the row.
- **unknown product** — `UnknownProduct`, row untouched.
- **Apple 404** — `UnknownTransaction`, row untouched.
- **transport failure** — `AppStoreUnavailable` carrying the reason, row
  untouched.
- **refresh when Apple answers for a different transaction** — the scripted
  transport returns a subscription whose `originalTransactionId` is not the
  requested one; `UnknownTransaction`, and no row — neither the requested one
  nor the returned one — is written.

### `service` — `SubscriptionRefreshHandlerTest` (new)

- **undeserializable payload** — `PermanentFailure`, nothing called.
- **each `RefreshResult` maps to its `JobResult`** — the six rows of the table
  above.
- **`executionTimeout < lockDuration`** — the queue-lock invariant, asserted on
  the declared config.

### `rest-server` — `AppleNotificationRoutingTest` (new)

- **accepted notification** — 200 **with no session cookie**, and exactly one
  `REFRESH_SUBSCRIPTION` job whose payload carries the expected
  `originalTransactionId`, uuid, type and subtype.
- **foreign-root notification** — 401 and **zero jobs**.
- **garbage `signedPayload`** — 401 and zero jobs.
- **bundleId mismatch** and **environment mismatch** — 400 and zero jobs each.
- **TEST notification** — 200 and zero jobs.
- **`GET`** — 405 with `Allow: POST`.
- **oversized body** — 413 from the route override.
- **client-key gate** — with `UNICOACH_CLIENT_KEYS` configured, this path
  answers without the header while a sibling path answers 403; without this the
  endpoint is unreachable in production and no other test would notice.
- **enqueue failure** — a `JobsDao` whose `insert` answers
  `JobInsertResult.DatabaseFailure`; 500, so Apple retries, and no job row
  exists.
- **verified payload in an unseen shape** — an Apple-signed payload with no
  `notificationUUID`; 500 and zero jobs, the `Result.failure` arm reaching the
  wire.
- **staleness-gap e2e** — a subscribed student with period spend seeded to
  exhaustion in window W1 is refused a turn with 402; a `DID_RENEW` notification
  is POSTed (the scripted Apple transport answers with window W2);
  `SubscriptionRefreshHandler` executes the payload of the job the route
  actually enqueued; the next turn is admitted and
  `GET /api/v1/students/me/coaching-usage` reports `usedPercent = 0` with the
  new `resetsAt`. `/verify` is never called. This is the RFC's reason for
  existing, so it is asserted end to end; the polling loop between enqueue and
  execute is `QueueWorkerTest`'s subject, not this one's.

### `rest-server` — contract couplings (new, mirroring the verify pair)

- **`OpenApiAppleNotificationLimitTest`** — the published
  `AppleNotificationRequest.signedPayload.maxLength` equals
  `AppleNotificationVerifier.MAX_JWS.bytes`.
- **`RequestSizeAppleNotificationLimitTest`** — the configured route override
  exceeds `MAX_JWS`, so the service's legible refusal is reachable rather than
  shadowed by a bare 413.

### Contract

`api-specs/openapi.yaml` staged → `bin/test-fuzz` runs from the pre-commit hook.
The documented 400/401/500 are in both of `schemathesis.toml`'s accepted sets;
413 is in `negative_data_rejection` only, and is unreachable by the fuzzer
because the published `signedPayload.maxLength` (32 KiB) keeps every generated
body under the 48 KiB route override. No `schemathesis.toml` change.

## Implementation Plan

1. **`:appstore`: the verifier.** Add `bouncycastle` to
   `gradle/libs.versions.toml` (`bcpkix-jdk18on`, `bcprov-jdk18on`) and wire it
   as `testFixturesImplementation` in `appstore/build.gradle.kts`. Download
   `https://www.apple.com/certificateauthority/AppleRootCA-G3.cer`, convert
   (`openssl x509 -inform DER -in AppleRootCA-G3.cer -out apple-root-ca-g3.pem`)
   and commit it to `appstore/src/main/resources/`. Add `AppleRootCertificates`,
   `AppleJwsVerifier`, the fixture chain builder, and
   `AppleRootCertificatesTest` + `AppleJwsVerifierTest`.
   - Verify: `nix develop -c bin/test appstore`.
2. **`:appstore`: the notification.** Add `AppleNotification`,
   `AppleNotificationOutcome`, `AppleNotificationVerifier`, the fixture
   `signedNotification` builder, and `AppleNotificationVerifierTest`.
   - Verify: `nix develop -c bin/test appstore`.
3. **`:queue`: job type and payload.** Add `JobType.REFRESH_SUBSCRIPTION` and
   `SubscriptionRefreshPayload`; extend `JobTypeTest`.
   - Verify: `nix develop -c bin/test queue`.
4. **`:service`: refresh and handler.** Add `RefreshResult`; extract `verify`'s
   Apple-lookup → plan-check → upsert tail into a private helper and add
   `refresh` on top of it; add `SubscriptionRefreshHandler`; extend
   `SubscriptionServiceTest` and add `SubscriptionRefreshHandlerTest`.
   - Verify: `nix develop -c bin/test service --tests "*Subscription*"`.
5. **`:queue-worker`: wiring.** Add `appstore.conf` to the `AppConfig.load`
   list, hoist the existing `SubscriptionPlans.from(config).getOrThrow()` into a
   local shared with `BudgetService`, build `AppStoreConfig` +
   `AppStoreServerApiFactory.fromConfig` + `SubscriptionService`, register
   `SubscriptionRefreshHandler` unconditionally, close the client in the
   existing `finally`.
   - Verify: `nix develop -c ./gradlew :queue-worker:compileKotlin`.
6. **`:rest-server`: endpoint, gates, wiring.** Add `AppleNotificationRequest`
   and `AppleNotificationRouteHandler`; register in `Routing.kt`; hoist
   `AppStoreConfig` in `Application.kt`, build the verifier, widen `appModule`
   and every call site plus `TestAppStore.kt`; add the
   `clientKeyGate.allowlistPaths` entry and the `requestSize.routeOverrides`
   entry to `rest-server.conf`; add `AppleNotificationRoutingTest` and the two
   contract-coupling suites.
   - Verify:
     `nix develop -c bin/test rest-server --tests "*AppleNotification*"`.
7. **Contract.** `api-specs/openapi.yaml`: the new path with no `security`
   block, `AppleNotificationRequest`, and the five documented statuses.
   - Verify: `nix develop -c bin/test-fuzz`.
8. **Retire the staleness-gap prose.** Correct the five comments and the
   `verifySubscription` description named under _Retiring the renewal-staleness
   gap_.
   - Verify:
     `grep -rniE "webhook (rfc|slice|later|exists)|until the [^.]*webhook" appstore db service rest-server api-specs`
     returns nothing; `nix develop -c bin/format -c`.
9. **Brief.** `features/paid-subscriptions.md`: mark the webhook row implemented
   with rfc 112 in the living index, and update the `paywall-ios` kickoff prompt
   — the renewal-refresh gap it describes is closed, so re-verifying on
   launch/foreground is a binding-and-restore concern, not a renewal one.
   - Verify: `grep -n "112" features/paid-subscriptions.md`;
     `nix develop -c bin/format -c`.
10. **Full gate.** Whole suite plus formatter.
    - Verify: `nix develop -c bin/test`; `nix develop -c bin/format -c`.

## Files Modified

Expected scope, non-exhaustive (per convention).

**`appstore`**

- `appstore/build.gradle.kts` — BouncyCastle as `testFixturesImplementation`.
- `appstore/src/main/resources/apple-root-ca-g3.pem` — new.
- `appstore/src/main/kotlin/ed/unicoach/appstore/AppleRootCertificates.kt` —
  new.
- `appstore/src/main/kotlin/ed/unicoach/appstore/AppleJwsVerifier.kt` — new.
- `appstore/src/main/kotlin/ed/unicoach/appstore/AppleNotification.kt` — new.
- `appstore/src/main/kotlin/ed/unicoach/appstore/AppleNotificationVerifier.kt` —
  new.
- `appstore/src/main/kotlin/ed/unicoach/appstore/AppleJws.kt` — the reserved
  sibling has landed; comment corrected.
- `appstore/src/testFixtures/kotlin/ed/unicoach/appstore/AppStoreTestFixtures.kt`
  — chain builder and `signedNotification`.
- `appstore/src/test/kotlin/ed/unicoach/appstore/` —
  `AppleRootCertificatesTest`, `AppleJwsVerifierTest`,
  `AppleNotificationVerifierTest`, new.

**`queue`**

- `queue/src/main/kotlin/ed/unicoach/queue/JobType.kt` — one entry.
- `queue/src/main/kotlin/ed/unicoach/queue/SubscriptionRefreshPayload.kt` — new.
- `queue/src/test/kotlin/ed/unicoach/queue/JobTypeTest.kt` — extended.

**`service`**

- `service/src/main/kotlin/ed/unicoach/subscriptions/RefreshResult.kt` — new.
- `service/src/main/kotlin/ed/unicoach/subscriptions/SubscriptionService.kt` —
  `refresh` plus the shared tail.
- `service/src/main/kotlin/ed/unicoach/subscriptions/SubscriptionRefreshHandler.kt`
  — new.
- `service/src/test/kotlin/ed/unicoach/subscriptions/SubscriptionServiceTest.kt`
  — extended.
- `service/src/test/kotlin/ed/unicoach/subscriptions/SubscriptionRefreshHandlerTest.kt`
  — new.

**`rest-server`**

- `rest-server/src/main/kotlin/ed/unicoach/rest/models/AppleNotificationRequest.kt`
  — new.
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AppleNotificationRoutes.kt`
  — new.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt` — register.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — hoisted
  `AppStoreConfig`, verifier construction, `appModule` widening.
- `rest-server/src/main/resources/rest-server.conf` — client-key allowlist and
  request-size override.
- `rest-server/src/test/kotlin/ed/unicoach/rest/TestAppStore.kt` — the shared
  verifier builder.
- `rest-server/src/test/kotlin/ed/unicoach/rest/AppleNotificationRoutingTest.kt`
  — new;
  `rest-server/src/test/kotlin/ed/unicoach/rest/models/OpenApiAppleNotificationLimitTest.kt`
  and
  `rest-server/src/test/kotlin/ed/unicoach/rest/config/RequestSizeAppleNotificationLimitTest.kt`
  — new; the other `appModule`-booting suites gain the new argument
  mechanically.
- `rest-server/src/test/kotlin/ed/unicoach/rest/SubscriptionRoutingTest.kt` —
  comment only; the staleness gap it describes is closed.

**`queue-worker`**

- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` — App Store
  client, `SubscriptionService`, handler registration.

**`db`**

- `db/src/main/kotlin/ed/unicoach/db/dao/SubscriptionsDao.kt` — comment only;
  the webhook RFC named in `findByOriginalTransactionId` has landed.

**Build**

- `gradle/libs.versions.toml` — BouncyCastle.

**`api-specs`**

- `api-specs/openapi.yaml` — new path and request schema; corrected
  `verifySubscription` description.

**Feature brief**

- `features/paid-subscriptions.md` — living index and the `paywall-ios` kickoff
  prompt.
