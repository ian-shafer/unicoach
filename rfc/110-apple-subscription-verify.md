# RFC 110: Apple subscription verify path

## Executive Summary

RFC 109 landed the budget gate free-tier-only: every student hard-blocks at the
lifetime allowance with no purchase path. This RFC is the verify-path slice of
the `subscriptions-apple` node of `features/paid-subscriptions.md` — the node
splits into this RFC and a later Notifications-V2 webhook RFC.

Three additions. A versioned `subscriptions` table + DAO: the server-side state
of record, keyed to the student via Apple's `originalTransactionId`, holding
`product_id`, `status`, and the current period window. A
`POST /api/v1/subscriptions/verify` endpoint: the app posts the StoreKit 2
signed transaction (JWS); the backend extracts its transaction id, fetches the
authoritative subscription state from the App Store Server API (a new
`:appstore` module — ES256-signed API JWT, thin Ktor transport seam per RFC
107), and upserts the row. The subscribed entitlement branch: `BudgetService`
reads the current subscription and meters `StudentLlmCostDao.windowedCost` over
`[period_start, period_end)` against a per-plan budget of `y × price`
(`SubscriptionPlans` config; one plan, `coach.uni.UnicoachiOS.monthly10` at
`$9.99`, `y = 0.5`). The four RFC 109 call gates are untouched — they consume
the same `verdict`.

Until the webhook lands, renewals reach the server only by the app re-posting to
`/verify`; the gate itself never calls Apple. The Paid Apps agreement is
awaiting Apple activation, so all tests fake at the transport seam; real sandbox
verification is deferred. Backend only — no iOS UI, no webhook.

## Detailed Design

### Verified starting state (code, not RFCs)

- `StudentLlmCostDao.windowedCost(session, studentId, periodStart, periodEnd)`
  exists with **zero callers** — the subscription meter landed ahead of this
  consumer. It `require`s `periodStart < periodEnd` and sums
  `student_llm_cost.cost_nanodollars` over `created_at ∈ [start, end)` with the
  same `uncostedCalls`/`estimatedCalls` counters as `lifetimeCost`.
- `BudgetService(database, config: BudgetConfig)` owns the gate: `verdict` wraps
  `entitlement(session, studentId)`, which today reads only `lifetimeCost` and
  builds `Entitlement(spent, allowance)`. The four call-site gates and the usage
  route consume `verdict`/`entitlement` and never re-derive `exhausted` — so the
  subscribed branch lands entirely inside `BudgetService.entitlement`, and no
  gate changes.
- `Entitlement(spent, allowance)` derives `exhausted` (`>=`) and `usedPercent`
  (floored, capped, `Math.multiplyExact`). It has no notion of which meter it
  reports or when it resets.
- Provider modules follow one shape: `:chat` (Anthropic) and `:email` (SES) each
  own their conf resource, a typed config reader, a `fun interface` transport
  seam at the narrowest HTTP boundary, and RFC 107 `java-test-fixtures` with a
  scripted transport fake. `auth0:java-jwt` is already in the version catalog
  (Google SSO), and `Algorithm.ECDSA256` covers ES256 — no new crypto
  dependency.
- Config placement (RFC 95 / `CONFIGURATION.md`): env-selecting toggles are
  required substitutions committed per dotenv role (`CHAT_PROVIDER`,
  `GOOGLE_AUTH_PROVIDER`; the test harness layers `.env.dev` under `.env.test`,
  so a `.env.dev` value covers dev/test/fuzz); secrets are out-of-band SSM
  SecureStrings (`infra/ssm.tf` `ssm_out_of_band_secrets`, placeholder +
  `ignore_changes`); JVM-only tunables live in HOCON with at most a `${?VAR}`
  override. `bin/gen-deployed-env`'s `DEPLOY_VAR_NAMES` enumerates every
  committed cloud key; `render-env.sh` produces flat `KEY=VALUE` lines, so a
  multiline PEM cannot ride through SSM unencoded.
- Entity tables follow `fit_suggestions` (0031): uuidv7 PK, four-timestamp
  split, TEXT + named CHECK enums, shared guard triggers
  (`prevent_physical_delete`, `prevent_immutable_updates`,
  `prevent_physical_timestamp_update`, `update_timestamp`), partial indexes for
  the hot read. Migrations end at `0041`; this RFC adds `0042`.
- Routes: student-scoped handlers delegate to
  `CallerResolution`/`SessionCallerResolution` (401/409 preambles),
  `rejectUnsupportedMethods`, registration in `configureRouting`, service
  assembly in `appModule` (tests inject provider-bearing objects — `LlmCallLog`
  — through its parameters). The email-verification gate covers every
  non-`/api/v1/auth` path automatically. Jackson serializes `Instant` as
  ISO-8601 (`WRITE_DATES_AS_TIMESTAMPS` disabled). Validation failures respond
  400 `validation_failed` with `ed.unicoach.error.FieldError`s.
- iOS bundle id is `coach.uni.UnicoachiOS` (`project.pbxproj`), matching the
  proposed product id prefix.

### Verification model — one Apple read, TLS-trusted; a DB-only gate

The client-posted JWS is never trusted and never signature-verified: it is used
only to extract a `transactionId` lookup key. The authoritative state is fetched
from Apple over TLS (Get All Subscription Statuses,
`GET /inApps/v1/subscriptions/{transactionId}`), so the response payloads are
trusted without x5c chain verification — the bytes came from Apple's own host.
This trust argument holds **only** for the verify path; the webhook RFC receives
Apple-signed payloads on our endpoint and MUST add real JWS verification (see
Seams).

The Apple call runs inline on the request coroutine — the documented
`ASYNC_WORK.md` exception: the response's entire purpose is the verification
result, so the result is required for the response to be correct. There is no
enqueue in this RFC.

The budget gate never calls Apple. `BudgetService` reads only the local
`subscriptions` row and the cost ledger — gate latency and availability stay
DB-bound. The cost: a renewal is invisible until the row is refreshed. Until the
webhook RFC lands, refresh happens by the app re-posting `/verify` (the endpoint
is an idempotent upsert); a lapsed window simply falls back to the free branch
(already exhausted → 402), which the paywall surfaces as "re-verify or
resubscribe".

### `subscriptions` table — migration `0042.create-subscriptions.sql`

The state of record for one Apple auto-renewable subscription, keyed by Apple's
`originalTransactionId` (stable across renewals) and bound to the student who
verified it.

The entity is **versioned** (the `users`/`colleges` mechanism: shared
`enforce_versioning()` plus a per-table history writer): the gate blocks on what
this row said at the time, so an entitlement dispute ("I paid and was 402'd") is
answered from the row's timeline — Apple's records can replay Apple's belief,
only versions preserve ours. Every mutation source funnels through
`SubscriptionsDao.upsert` (verify now, the webhook RFC later), so the one
trigger captures both. The `version` bump is DAO-supplied in the conflict arm
(`version = subscriptions.version + 1`), never caller-supplied — versions are an
audit trail here, not caller-facing concurrency control.

#### Entity Configuration

| Setting        | Selection | Implementation Requirement                                                                         |
| :------------- | :-------- | :------------------------------------------------------------------------------------------------- |
| **ID Type**    | `UUIDv7`  | `id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7()`                                                    |
| **Mutability** | Mutable   | `updated_at` via shared `update_timestamp` trigger                                                 |
| **Timestamps** | Advanced  | four-timestamp split (`created_at`/`row_created_at`/`updated_at`/`row_updated_at`)                 |
| **Versioning** | Enabled   | `version` + `subscriptions_versions`, shared `enforce_versioning()` + `log_subscription_version()` |
| **Deletions**  | None      | no `deleted_at`; lifecycle is `status` (`expired`/`revoked` rows remain), delete trigger           |

```sql
CREATE TABLE subscriptions (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  version INTEGER NOT NULL DEFAULT 1,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

  -- Apple's stable per-subscription identity; renewals keep it, so it is the
  -- upsert key. One Apple subscription maps to at most one student, ever.
  original_transaction_id TEXT NOT NULL,
  product_id              TEXT NOT NULL,

  -- Apple's status enum, snake_cased: 1=active, 2=expired, 3=billing_retry,
  -- 4=grace, 5=revoked. Entitling statuses are active + grace.
  status TEXT NOT NULL,

  -- The current entitlement window: the latest transaction's purchaseDate →
  -- expiresDate (grace: gracePeriodExpiresDate). Also the subscription meter's
  -- windowedCost bounds and the usage bar's reset date.
  period_start TIMESTAMPTZ NOT NULL,
  period_end   TIMESTAMPTZ NOT NULL,

  CONSTRAINT subscriptions_original_transaction_id_unique UNIQUE (original_transaction_id),
  CONSTRAINT subscriptions_original_transaction_id_length_check CHECK (length(original_transaction_id) <= 255),
  CONSTRAINT subscriptions_original_transaction_id_not_empty_check CHECK (length(original_transaction_id) > 0),
  CONSTRAINT subscriptions_product_id_length_check CHECK (length(product_id) <= 255),
  CONSTRAINT subscriptions_product_id_not_empty_check CHECK (length(product_id) > 0),
  CONSTRAINT subscriptions_status_check CHECK (status IN ('active','expired','billing_retry','grace','revoked')),
  -- Also guards StudentLlmCostDao.windowedCost's periodStart < periodEnd require.
  CONSTRAINT subscriptions_period_check CHECK (period_start < period_end)
);

-- The gate's read: the student's current entitling subscription.
CREATE INDEX subscriptions_student_current_idx
  ON subscriptions (student_id, period_end) WHERE status IN ('active','grace');
-- Admin/debug listing per student.
CREATE INDEX subscriptions_student_idx ON subscriptions (student_id, created_at);

-- History: every committed state the row has taken (the insert and each real
-- update), mirroring users_versions/colleges_versions — unique
-- original_transaction_id relaxed, (id, version) is the PK and the only index.
CREATE TABLE subscriptions_versions (
  id      UUID    NOT NULL REFERENCES subscriptions(id) ON DELETE RESTRICT,
  version INTEGER NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL,
  row_created_at TIMESTAMPTZ NOT NULL,
  updated_at     TIMESTAMPTZ NOT NULL,
  row_updated_at TIMESTAMPTZ NOT NULL,
  student_id UUID NOT NULL,
  original_transaction_id TEXT NOT NULL,
  product_id              TEXT NOT NULL,
  status TEXT NOT NULL,
  period_start TIMESTAMPTZ NOT NULL,
  period_end   TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (id, version)
);
```

Triggers: the `fit_suggestions` guard set (`prevent_physical_delete`,
`prevent_immutable_updates`, `prevent_physical_timestamp_update`,
`update_timestamp`) plus the versioning pair — the shared `enforce_versioning()`
(0000: insert must be 1, update must be `old + 1`) and a new per-table
`log_subscription_version()` `AFTER INSERT OR UPDATE` history writer, modeled
verbatim on `log_user_version()`. No `UNIQUE(student_id)`: a student who lapses
and later resubscribes under a different Apple ID gets a second row (new
`originalTransactionId`); `findCurrent` disambiguates. `student_id` rebinding is
refused at the DAO (below), not by trigger.

### `Subscription` model + `SubscriptionsDao` (`:db`)

```kotlin
// db/src/main/kotlin/ed/unicoach/db/models/Subscription.kt
@JvmInline value class SubscriptionId(val value: UUID)

/** Wire and DB share the lowercase string; `value` is that single spelling. */
enum class SubscriptionStatus(val value: String) {
  ACTIVE("active"), EXPIRED("expired"), BILLING_RETRY("billing_retry"),
  GRACE("grace"), REVOKED("revoked");
  companion object { fun from(value: String): SubscriptionStatus }
}

class Subscription(
  val id: SubscriptionId,
  val version: Int,
  val studentId: StudentId,
  val originalTransactionId: String,
  val productId: String,
  val status: SubscriptionStatus,
  val periodStart: Instant,
  val periodEnd: Instant,
  val createdAt: Instant,
  val updatedAt: Instant,
)
```

```kotlin
// db/src/main/kotlin/ed/unicoach/db/dao/SubscriptionsDao.kt
object SubscriptionsDao {
  /**
   * Insert-or-refresh keyed on original_transaction_id. The conflict arm sets
   * `version = subscriptions.version + 1` (satisfying enforce_versioning) and
   * updates product_id/status/period only when the existing row's student_id
   * matches AND the incoming state actually differs
   * (`... DO UPDATE ... WHERE subscriptions.student_id = EXCLUDED.student_id
   * AND (subscriptions.product_id, subscriptions.status,
   * subscriptions.period_start, subscriptions.period_end) IS DISTINCT FROM
   * (EXCLUDED....) RETURNING *`). The state-distinct guard keeps an
   * app-launch re-verify from minting a version row (and bumping updated_at)
   * when Apple's state is unchanged. Zero rows returned is therefore
   * ambiguous, so the DAO classifies with a follow-up SELECT by
   * original_transaction_id in the same transaction: same student → Unchanged,
   * different student → OwnedByOtherStudent. Never a silent no-op or a rebind.
   */
  fun upsert(
    session: SqlSession,
    studentId: StudentId,
    originalTransactionId: String,
    productId: String,
    status: SubscriptionStatus,
    periodStart: Instant,
    periodEnd: Instant,
  ): Result<SubscriptionUpsert>

  /**
   * The gate's read: the entitling row covering the DB clock's now —
   * `status IN ('active','grace') AND period_start <= NOW() AND NOW() < period_end`,
   * latest period_end on the rare tie. Null when none.
   */
  fun findCurrent(session: SqlSession, studentId: StudentId): Result<Subscription?>

  /** Lookup by Apple's key; the webhook RFC's entry point, and test support. */
  fun findByOriginalTransactionId(session: SqlSession, originalTransactionId: String): Result<Subscription?>
}

sealed interface SubscriptionUpsert {
  data class Applied(val subscription: Subscription) : SubscriptionUpsert
  /** The state-distinct guard skipped a no-op refresh; carries the untouched row. */
  data class Unchanged(val subscription: Subscription) : SubscriptionUpsert
  data object OwnedByOtherStudent : SubscriptionUpsert
}
```

`findCurrent` compares against the DB clock (`NOW()`), consistent with the
DB-clock-only posture of RFC 97 and with the in-transaction gate read it serves.

### `:appstore` module — App Store Server API client

A new top-level `:appstore` module (architect decision), the third instance of
the established provider-module shape (`:chat`/`:email`): own conf resource,
typed config, `fun interface` transport seam, testFixtures with a scripted
transport. Folding the client into `:service` would break the
one-provider-one-module symmetry and give `:service` a Ktor client dependency;
the webhook RFC also consumes this module (JWS decoding, status mapping).

New Gradle module `appstore` (`settings.gradle.kts` +
`appstore/build.gradle.kts`): deps `ktor-client-core`, `ktor-client-cio`,
`kotlinx-serialization-json`, `kotlinx-coroutines-core`, `java-jwt`,
`typesafe-config`, `slf4j-api`; `java-test-fixtures` plugin. `:service` gains
`implementation(project(":appstore"))`.

#### Config — `appstore.conf` + `AppStoreConfig`

```hocon
appStore {
  # "sandbox" | "production". Required substitution (no default): a forgotten
  # cloud override fails the JVM at boot rather than silently verifying against
  # the wrong store. Committed per dotenv role: .env.dev=sandbox (covers
  # dev/test/fuzz via layering), .env.prod=production.
  environment = ${APP_STORE_ENVIRONMENT}

  # App Store Server API credential (App Store Connect → Users and Access →
  # Integrations → In-App Purchase). All three absent together is a valid
  # (unconfigured) state — dev/test verify calls answer 503; partially present
  # fails boot. privateKey is the .p8 body as ONE-LINE base64 PKCS#8 (PEM
  # header/footer/newlines stripped) so it survives the flat KEY=VALUE
  # /etc/unicoach/env render.
  issuerId = ${?APP_STORE_ISSUER_ID}
  keyId = ${?APP_STORE_KEY_ID}
  privateKey = ${?APP_STORE_PRIVATE_KEY}

  bundleId = "coach.uni.UnicoachiOS"
  bundleId = ${?APP_STORE_BUNDLE_ID}

  # Derived from environment when unset:
  #   production → https://api.storekit.itunes.apple.com
  #   sandbox    → https://api.storekit-sandbox.itunes.apple.com
  baseUrl = ${?APP_STORE_BASE_URL}

  connectTimeoutMs = 10000
  connectTimeoutMs = ${?APP_STORE_CONNECT_TIMEOUT_MS}
  requestTimeoutMs = 15000
  requestTimeoutMs = ${?APP_STORE_REQUEST_TIMEOUT_MS}
}
```

```kotlin
// appstore/src/main/kotlin/ed/unicoach/appstore/AppStoreConfig.kt
enum class AppStoreEnvironment { SANDBOX, PRODUCTION }

/** The three credential fields as a unit: all present and key-parseable, or none. */
class AppStoreCredentials(
  val issuerId: String,
  val keyId: String,
  val privateKey: ECPrivateKey,   // parsed at load: base64 → PKCS8EncodedKeySpec → KeyFactory("EC")
)

class AppStoreConfig private constructor(
  val environment: AppStoreEnvironment,
  val credentials: AppStoreCredentials?,  // null = unconfigured (valid; verify answers 503)
  val bundleId: String,
  val baseUrl: String,
  val connectTimeoutMs: Long,
  val requestTimeoutMs: Long,
) {
  companion object {
    /**
     * Failure on: unknown environment; a proper subset of the three credential
     * keys present (a partial credential is a misconfiguration, not an
     * unconfigured box); un-decodable/non-EC private key. Each failure names
     * the offending key.
     */
    fun from(config: Config): Result<AppStoreConfig>
  }
}
```

#### `AppleJws` — decode-only JWS payloads

```kotlin
// appstore/src/main/kotlin/ed/unicoach/appstore/AppleJws.kt
/**
 * Base64url-decodes the payload segment of a JWS and parses it as a JsonObject.
 * DECODES WITHOUT VERIFYING the signature: every caller must hold a trust
 * reason — the bytes were fetched from Apple over TLS, or the value is used
 * only as an untrusted lookup key. The Notifications-V2 webhook RFC adds the
 * x5c-chain verifying sibling; nothing may route an inbound Apple-signed
 * payload through this decode-only path.
 */
object AppleJws {
  fun payload(jws: String): Result<JsonObject>  // failure: not 3 dot-segments, bad base64url, non-JSON
}
```

#### `AppStoreAuthTokens` — the API's ES256 JWT

```kotlin
// appstore/src/main/kotlin/ed/unicoach/appstore/AppStoreAuthTokens.kt
/**
 * Mints the App Store Server API bearer token via java-jwt's Algorithm.ECDSA256
 * (the hardened primitive; no hand-rolled signing): header {alg: ES256, kid,
 * typ: JWT}; claims {iss: issuerId, iat: now, exp: now + 5m, aud:
 * "appstoreconnect-v1", bid: bundleId}. Minted per call — an ECDSA sign is
 * cheap and statelessness beats an expiry cache.
 */
class AppStoreAuthTokens(
  private val credentials: AppStoreCredentials,
  private val bundleId: String,
  private val clock: Clock,
) {
  fun mint(): String
}
```

#### Transport seam + real transport

```kotlin
// appstore/src/main/kotlin/ed/unicoach/appstore/AppStoreTransport.kt
/** The narrowest HTTP boundary — what tests fake (RFC 107). */
fun interface AppStoreTransport {
  suspend fun get(path: String, bearerToken: String): AppStoreTransportResponse
}
class AppStoreTransportResponse(val status: Int, val body: String)
```

```kotlin
// appstore/src/main/kotlin/ed/unicoach/appstore/KtorAppStoreTransport.kt
/** GETs {baseUrl}{path} with Authorization: Bearer. IO failures propagate. */
class KtorAppStoreTransport(
  private val client: HttpClient,
  private val config: AppStoreConfig,
) : AppStoreTransport
```

The backing `HttpClient(CIO)` is built in the composition root and closed on
`ApplicationStopped`, mirroring the chat client's lifecycle.

#### `AppStoreServerApi` — lookup + decode + status mapping

```kotlin
// appstore/src/main/kotlin/ed/unicoach/appstore/AppStoreServerApi.kt
class AppStoreServerApi(
  private val transport: AppStoreTransport,
  private val tokens: AppStoreAuthTokens?,  // null = credentials unconfigured
) {
  /** GET /inApps/v1/subscriptions/{transactionId} (Get All Subscription Statuses). */
  suspend fun subscriptionStatus(transactionId: String): Result<AppStoreSubscriptionLookup>
}

sealed interface AppStoreSubscriptionLookup {
  data class Found(val subscription: AppleSubscription) : AppStoreSubscriptionLookup
  /** Apple 404 (unknown transaction in this environment), or a response with no auto-renewable entry. */
  data object NotFound : AppStoreSubscriptionLookup
  /** Unconfigured credentials, 401, 429, 5xx, or IO failure — each WARN/ERROR-logged with status and body. */
  data object Unavailable : AppStoreSubscriptionLookup
}

enum class AppleSubscriptionStatus { ACTIVE, EXPIRED, BILLING_RETRY, GRACE, REVOKED }

class AppleSubscription(
  val originalTransactionId: String,
  val productId: String,
  val status: AppleSubscriptionStatus,
  val periodStart: Instant,   // latest transaction's purchaseDate
  val periodEnd: Instant,     // its expiresDate; GRACE: renewal info's gracePeriodExpiresDate when present
)
```

Mapping rules, applied to the 200 response JSON
(`{environment, bundleId, data: [{subscriptionGroupIdentifier,
lastTransactions: [{originalTransactionId, status, signedTransactionInfo,
signedRenewalInfo}]}]}`):

- Flatten `data[].lastTransactions[]`, decode each `signedTransactionInfo` via
  `AppleJws` (TLS-trusted), and select the entry with the greatest `expiresDate`
  — the current transaction. Multi-group/multi-plan selection beyond that is out
  of scope with one product.
- Apple's integer `status` maps 1→`ACTIVE`, 2→`EXPIRED`, 3→`BILLING_RETRY`,
  4→`GRACE`, 5→`REVOKED`; an unknown integer is `Result.failure` (a new Apple
  state must be looked at, not guessed at).
- `GRACE` reads `signedRenewalInfo`'s `gracePeriodExpiresDate` as `periodEnd`
  (falling back to `expiresDate`, WARN-logged, if absent). Timestamps are Apple
  epoch-milliseconds → `Instant`.
- An entry lacking `expiresDate` is not an auto-renewable subscription →
  `NotFound` (logged).
- A 200 body that fails to parse → `Result.failure` (bug-grade, not
  `Unavailable`).
- `tokens == null` → `Unavailable` immediately, ERROR-logged ("App Store
  credentials not configured"), no transport call.

### Plan config — `subscriptions` block + `SubscriptionPlans` (`:service`)

`service.conf` gains the plan → budget mapping. The proposed plan facts (single
plan `coach.uni.UnicoachiOS.monthly10` at `$9.99`, `y = 0.5` ⇒ `$4.995`/period
of provider spend) are adopted as the config defaults — both are config edits,
never code changes, if App Store Connect ends up differing.

```hocon
subscriptions {
  # y: the fraction of a plan's App Store price funding provider spend
  # (0 < y < 1). The margin lives in 1 − y minus Apple's commission.
  budgetRatio = 0.5
  budgetRatio = ${?BUDGET_RATIO}

  # productId → reference USD price. Budget math only — Apple owns the real,
  # localized price. Like llmPricing: no env override; a plan change is a
  # committed-file edit plus redeploy.
  plans {
    "coach.uni.UnicoachiOS.monthly10" { priceUsd = 9.99 }
  }
}
```

```kotlin
// service/src/main/kotlin/ed/unicoach/subscriptions/SubscriptionPlans.kt
/**
 * The typed productId → period-budget table. Budgets are computed once, at
 * load: y × priceUsd exact via BigDecimal into Nanodollars.fromExactDecimal —
 * the reject-rather-than-round money policy, so a ratio/price pair with no
 * exact nano-dollar product fails boot with the key named. Each budget is also
 * held under BudgetConfig's usage-percentage ceiling (Long.MAX_VALUE / 100
 * nano-dollars), the same guard for the same Entitlement arithmetic.
 */
class SubscriptionPlans private constructor(
  private val periodBudgets: Map<String, Nanodollars>,
) {
  /** Null for a product this box does not know — the caller names the failure. */
  fun periodBudget(productId: String): Nanodollars?

  companion object {
    /** Failure on: ratio outside (0, 1); non-positive price; inexact or over-ceiling budget; empty plans. */
    fun from(config: Config): Result<SubscriptionPlans>
  }
}
```

### Subscribed entitlement branch — `BudgetService` + `Entitlement`

`Entitlement` gains the meter's identity and reset point; both derivations are
unchanged:

```kotlin
// service/src/main/kotlin/ed/unicoach/coaching/budget/Entitlement.kt
enum class EntitlementBasis { FREE_ALLOWANCE, SUBSCRIPTION }

class Entitlement(
  val spent: Nanodollars,
  val allowance: Nanodollars,
  val basis: EntitlementBasis,
  val resetsAt: Instant?,   // the period_end; present iff basis == SUBSCRIPTION
) {
  // init: require((basis == SUBSCRIPTION) == (resetsAt != null))
  val exhausted: Boolean    // unchanged
  val usedPercent: Int      // unchanged
}
```

`BudgetService` gains the plan table and the branch; the signatures the four
gates and the usage route call are unchanged:

```kotlin
class BudgetService(
  private val database: Database,
  private val config: BudgetConfig,
  private val plans: SubscriptionPlans,
) {
  fun verdict(session: SqlSession, studentId: StudentId): Result<BudgetVerdict>      // unchanged
  fun entitlement(session: SqlSession, studentId: StudentId): Result<Entitlement>    // now two-branch
  suspend fun entitlement(studentId: StudentId): Result<Entitlement>                 // unchanged wrapper
}
```

`entitlement(session, studentId)` becomes: `SubscriptionsDao.findCurrent` —

- **null** → the RFC 109 free branch verbatim: `lifetimeCost` vs
  `config.freeAllowance`, `basis = FREE_ALLOWANCE`, `resetsAt = null`. Expired,
  revoked, billing-retry, and elapsed-window rows all land here: a lapsed
  subscriber meters as a (typically exhausted) free-tier student.
- **row** → `plans.periodBudget(row.productId)`; a null budget is
  `Result.failure` naming the subscription id and product — a current
  subscription with no configured plan is config drift, and the gate fails
  **closed** (chat 500, background retry) rather than silently granting or
  denying. Otherwise
  `windowedCost(session, studentId, row.periodStart, row.periodEnd)` vs the
  budget: `basis = SUBSCRIPTION`, `resetsAt = row.periodEnd`. The DB CHECK
  `period_start < period_end` discharges `windowedCost`'s `require`. The
  uncosted/estimated counter policy is inherited unchanged.

One extra indexed read per gated operation (the partial-index `findCurrent`);
the brief's materialize-a-balance fork stays YAGNI. `budgetSkipMessage` renders
the basis and, when present, `resetsAt` alongside the existing spent/allowance —
the operator's answer to "blocked until when".

#### Period rollover — the window read IS the reset

Rollover is not an event. There is no reset action, no counter to zero, no
reconciliation job, and no balance carried between periods — the ledger is
append-only and never touched. When a renewal lands (re-verify now, the webhook
later), the row's window advances, and the very next `windowedCost` read
excludes the old period's spend because its `created_at` precedes the new
`period_start`. An exhausted student recovers entitlement the instant the row
carries the new window, with no other state change anywhere.

Two consequences are deliberate, not incidental, and are pinned by tests:

- **Overshoot is forgiven.** Principle-3 bounded overshoot in period N (spend
  past `y × price` from the last admitted turn) does not reduce period N+1's
  budget. Each period meters only its own spend.
- **Unspent budget is forfeited.** An under-used period rolls nothing forward;
  the new window starts at `spent = 0` against the same `y × price`.

The dependency this rests on: `student_llm_cost.created_at` is the row's
insert-time DB clock at the `LlmCallLog` write boundary — spend is attributed to
the period in which the call was **made**, and nothing can re-date it. A
mid-turn rollover therefore splits a turn's calls across the boundary
call-by-call; accepted, same class as principle-3 overshoot.

### REST surface

#### `POST /api/v1/subscriptions/verify`

`SubscriptionService` (`:service`) owns the flow; the route only maps arms.

```kotlin
// service/src/main/kotlin/ed/unicoach/subscriptions/SubscriptionService.kt
class SubscriptionService(
  private val database: Database,
  private val appStore: AppStoreServerApi,
  private val plans: SubscriptionPlans,
) {
  /**
   * The one verify-and-record flow: bound + decode the posted JWS (lookup key
   * only), fetch authoritative state from Apple, upsert the subscriptions row.
   * Non-entitling statuses (expired/revoked/…) still record and return
   * Verified — the row reflects Apple truth; entitlement is the gate's job.
   * Re-posting is the idempotent refresh path (same row, updated state).
   */
  suspend fun verify(studentId: StudentId, signedTransaction: String): Result<VerifyResult>
}

// service/src/main/kotlin/ed/unicoach/subscriptions/VerifyResult.kt
sealed interface VerifyResult {
  data class Verified(val subscription: Subscription) : VerifyResult
  /** Blank, over 16 KiB, undecodable JWS, or a transactionId claim not matching ^[0-9]{1,32}$ (it is embedded in the API path). */
  data class ValidationFailure(val fieldErrors: List<FieldError>) : VerifyResult
  data object UnknownTransaction : VerifyResult
  data class UnknownProduct(val productId: String) : VerifyResult
  data object OwnedByOtherAccount : VerifyResult
  data object AppStoreUnavailable : VerifyResult
}
```

Ordering: validation (no Apple call for garbage) → `appStore.subscriptionStatus`
(`NotFound` → `UnknownTransaction`; `Unavailable` → `AppStoreUnavailable`) →
`plans.periodBudget` (`null` → `UnknownProduct`, checked before writing — a row
the gate would immediately fail closed on is never created) →
`database.withConnection { SubscriptionsDao.upsert(...) }` (`Applied` and
`Unchanged` both → `Verified` — a no-op refresh is a successful verification;
`OwnedByOtherStudent` → `OwnedByOtherAccount`). The account binding is the
authenticated session: whichever student first verifies an
`originalTransactionId` owns it, and the 409 guards every later rebind attempt.
(The iOS RFC may additionally set StoreKit's `appAccountToken` at purchase;
nothing here depends on it.)

```kotlin
// rest-server/.../models/SubscriptionVerifyRequest.kt
data class SubscriptionVerifyRequest(val signedTransaction: String)

// rest-server/.../models/SubscriptionVerifyResponse.kt
data class SubscriptionVerifyResponse(val subscription: SubscriptionView)
data class SubscriptionView(
  val status: String,            // SubscriptionStatus.value
  val productId: String,
  val currentPeriodEnd: Instant, // ISO-8601 on the wire
)
```

`SubscriptionRouteHandler(authService, studentService, subscriptionService,
sessionConfig) : CallerResolution by SessionCallerResolution(...)`
— new file, `CoachingUsageRouteHandler`'s shape;
`rejectUnsupportedMethods(HttpMethod.Post)`; email-verification gate covers it
automatically. Arm mapping:

| `VerifyResult`        | Response                                                                       |
| :-------------------- | :----------------------------------------------------------------------------- |
| `Verified`            | 200 `SubscriptionVerifyResponse`                                               |
| `ValidationFailure`   | 400 `validation_failed` + field errors                                         |
| `UnknownTransaction`  | 404 `subscription_not_found`                                                   |
| `UnknownProduct`      | 500 `internal_error`; ERROR log names the productId (config drift, not client) |
| `OwnedByOtherAccount` | 409 `subscription_owned_by_other_account`                                      |
| `AppStoreUnavailable` | 503 `service_unavailable`                                                      |

`ErrorCode` gains `SUBSCRIPTION_NOT_FOUND("subscription_not_found")` and
`SUBSCRIPTION_OWNED_BY_OTHER_ACCOUNT("subscription_owned_by_other_account")`.

#### `GET /api/v1/students/me/coaching-usage` — `resetsAt`

`CoachingUsage` gains `resetsAt: Instant?` (architect decision): null on the
free tier (the lifetime allowance never resets), `period_end` when subscribed.
The brief's usage-bar fork wants "budget spent, resets on ⟨date⟩", the fact is
born in this RFC, and the 402 body stays abstract — this is its only wire home.
Additive and nullable, so the shipped iOS build is unaffected.

Handler passthrough of `entitlement.resetsAt`; `usedPercent`/`exhausted`
unchanged (for a subscribed student they now read the period meter).

### Credentials & infra

- `infra/ssm.tf`: `ssm_out_of_band_secrets` grows `APP_STORE_ISSUER_ID`,
  `APP_STORE_KEY_ID`, `APP_STORE_PRIVATE_KEY` — the RFC 95 placeholder +
  `ignore_changes` pattern; the operator seeds real values with the AWS CLI once
  the Paid Apps agreement activates and the key exists. The private key is
  seeded as one-line base64 PKCS#8:
  `grep -v 'PRIVATE KEY' AuthKey_<KEYID>.p8 | tr -d '\n'`. Issuer and key id are
  identifiers, not secrets, but seeding all three as one out-of-band unit keeps
  the credential atomic and out of the committed tree.
- `.env.dev` gains `APP_STORE_ENVIRONMENT=sandbox` (layered into test/fuzz);
  `.env.prod` gains `APP_STORE_ENVIRONMENT=production`; `.env.template`
  documents the four `APP_STORE_*` dotenv/SSM keys in their roles;
  `bin/gen-deployed-env`'s `DEPLOY_VAR_NAMES` gains `APP_STORE_ENVIRONMENT`.

### Seams left for the Notifications-V2 webhook RFC

Stated once, here, so the next RFC builds instead of reshapes: the webhook
updates the **same** `subscriptions` row through
`SubscriptionsDao.findByOriginalTransactionId`/`upsert` (its status vocabulary
already spans renew/cancel/refund/grace/revoke); `:appstore` is where its
x5c-verifying JWS decode lands beside decode-only `AppleJws`; its endpoint is
Apple-authenticated (JWS signature, no session) and rides the queue per
`ASYNC_WORK.md`; and it retires the re-post-to-`/verify` refresh gap. Nothing
else here pre-builds for it.

### API Contracts

All specified above: migration `0042` (`subscriptions` + versioned history
`subscriptions_versions`, `log_subscription_version()`); `Subscription` /
`SubscriptionId` / `SubscriptionStatus` / `SubscriptionsDao` /
`SubscriptionUpsert` (`Applied` / `Unchanged` / `OwnedByOtherStudent`);
`:appstore` (`AppStoreConfig`, `AppStoreCredentials`, `AppStoreEnvironment`,
`AppleJws`, `AppStoreAuthTokens`, `AppStoreTransport` + `KtorAppStoreTransport`,
`AppStoreServerApi`, `AppStoreSubscriptionLookup`, `AppleSubscription`,
`AppleSubscriptionStatus`); `subscriptions` config block + `SubscriptionPlans`;
`SubscriptionService` + `VerifyResult`;
`Entitlement(spent, allowance, basis, resetsAt)` + `EntitlementBasis`;
`BudgetService(database, config, plans)`; `POST /api/v1/subscriptions/verify`
(`SubscriptionVerifyRequest`/`Response`, `SubscriptionView`); two new
`ErrorCode`s; `CoachingUsage.resetsAt`. Constructor/wiring ripples:
`configureRouting` (+ `subscriptionService`), `appModule` (+
`appStoreServerApi`, `subscriptionPlans`), `startServer` (loads
`appstore.conf`), `queue-worker` `main()` (parses `SubscriptionPlans` for its
`BudgetService`). `api-specs/openapi.yaml`: new path + schemas, `resetsAt` on
`CoachingUsage`.

### Error Handling / Edge Cases

- **Renewal staleness (no webhook yet)** — a renewed subscription's row still
  shows the old window; once `NOW() >= period_end` the gate falls to the free
  branch (typically exhausted → 402). The app's re-post to `/verify` refreshes
  the row. Accepted as this RFC's end state; the webhook RFC closes it.
- **Concurrent first-time verifies of one `originalTransactionId` by two
  students** — the unique constraint serializes them; the loser's conflict arm
  fails its student-match `WHERE` → 409. Same-student concurrency resolves as
  one `Applied` and one `Unchanged` — both `Verified`, one version row.
- **Sandbox receipt against the production API** (App Review runs the prod app
  with sandbox purchases) → Apple 404 → `subscription_not_found`. The
  conventional production→sandbox 404-fallback is deliberately not built
  (architect decision): untestable until the product exists, and it is
  `paywall-ios`'s App-Review concern.
- **Apple outage / rate limit / bad credential / unconfigured credential** → one
  503 `service_unavailable` surface; the distinction lives in server logs
  (status + body). Verification unavailability never grants or revokes
  entitlement — the gate reads only local state.
- **Grace period** — entitling; `period_end = gracePeriodExpiresDate` extends
  both the entitlement and the meter window. The budget itself (`y × price`) is
  unchanged — `SubscriptionPlans.periodBudget` is computed once from config and
  does not depend on period length — so a grace extension only gives the student
  more elapsed time to reach the same fixed ceiling, never a larger one.
  Accepted.
- **Subscribed student's window vs seeded/lifetime spend** — free-tier spend
  before `period_start` never counts against the period budget (`windowedCost`'s
  `created_at` bound); deleting conversations still refunds nothing.
- **Fuzzing** — the new path is fuzzed (only `conversations` paths are
  excluded): random `signedTransaction` strings die at validation (400); a
  string that somehow decodes still hits unconfigured credentials in the fuzz
  env (503). Both are in schemathesis' accepted sets; no `schemathesis.toml`
  change.
- **Race between gate and verify** — a turn admitted on the free branch while
  `/verify` commits mid-turn simply spends free allowance; the next turn reads
  the subscription. No lock; principle-3 overshoot semantics.
- **`students` row deleted** — cannot happen: `students` carries
  `prevent_physical_delete`, and `subscriptions`' own delete guard (plus the
  versions table's `ON DELETE RESTRICT`) would block any cascade regardless. The
  `ON DELETE CASCADE` on `student_id` is sibling-table consistency
  (`fit_suggestions`), not a live path.

### Dependencies

No new third-party dependencies: `java-jwt`, Ktor client CIO, and
kotlinx-serialization are already in the version catalog; `:appstore` reuses
them. New internal edges: `:service → :appstore`; `:rest-server`/`:queue-worker`
reach the new types through existing `:service`/ `:db` edges (`:rest-server`
additionally wires `:appstore` directly in `Application.kt`, and its test source
consumes `testFixtures(project(":appstore"))`). `cron`, `admin-web`,
`public-web`, iOS: untouched.

## Tests

All suites run through `bin/test` (real Postgres); Apple is always the scripted
transport — no test reaches the network. `AppStoreTestFixtures` signs its
fixture JWSes with a generated test P-256 key so every fixture is a structurally
real, decodable JWS (wire-faithful; signatures are never verified by design).

### `db` — `SubscriptionsDaoTest` (new)

- **upsert inserts** — fresh key → `Applied`; row carries all fields at
  `version = 1`, `status` round-trips through `SubscriptionStatus`; one
  `subscriptions_versions` row exists for `(id, 1)`.
- **upsert refreshes** — same key, same student, new status/period/product →
  `Applied`; same `id`, updated fields, `version = 2`, `updated_at` advanced; a
  second `subscriptions_versions` row for `(id, 2)`.
- **upsert skips a no-op refresh** — same key, same student, identical state →
  `Unchanged`; `version` and `updated_at` untouched, no new versions row (the
  app-launch re-verify writes no history churn).
- **upsert refuses rebind** — same key, different student →
  `OwnedByOtherStudent`; row unchanged, no new versions row.
- **findCurrent filters status** — `active` and `grace` rows found; `expired`,
  `billing_retry`, `revoked` not.
- **findCurrent filters window** — a row with `period_end` in the past (or
  `period_start` in the future) is not current.
- **findCurrent picks latest** — two entitling rows → the greater `period_end`.
- **findByOriginalTransactionId** — present and absent keys.
- **constraints** — bad status, inverted period, duplicate
  `original_transaction_id` each raise; delete raises
  (`prevent_physical_delete`); a raw UPDATE that skips the version bump raises
  (`enforce_versioning`).

### `appstore` — new module suites

- **`AppStoreConfigTest`** — sandbox/production parse + baseUrl derivation and
  override; all-absent credentials → null unit; partial credentials → failure
  naming the missing key; malformed base64 / non-EC key → failure; unknown
  environment → failure.
- **`AppleJwsTest`** — decodes a fixture JWS payload; failures on 2-segment
  input, bad base64url, non-JSON payload.
- **`AppStoreAuthTokensTest`** — minted token verifies with the test public key
  via java-jwt: ES256, `kid` header, `iss`, `aud = appstoreconnect-v1`, `bid`,
  `exp − iat = 5m` against a fixed `Clock`.
- **`AppStoreServerApiTest`** (scripted transport) — happy 200 → `Found` with
  decoded product/status/period (ms → `Instant`); status ints 1–5 map to the
  five enum values; unknown int → `Result.failure`; grace reads
  `gracePeriodExpiresDate` (and falls back logged when absent); multiple
  `lastTransactions` → greatest `expiresDate` wins; missing `expiresDate` →
  `NotFound`; 404 → `NotFound`; 401 / 429 / 500 / thrown `IOException` →
  `Unavailable`; unparsable 200 body → `Result.failure`; requested path is
  `/inApps/v1/subscriptions/{id}` and the recorded bearer token parses as the
  minted JWT; null tokens → `Unavailable` with zero transport calls.

### `service`

- **`SubscriptionPlansTest`** (new) — parses the default block
  (`monthly10 → $4.995` = `Nanodollars.of(4_995_000_000)`); ratio 0, 1, negative
  → failure; inexact product (e.g. ratio `0.333` × `9.99`) → failure naming the
  plan; over-ceiling budget → failure; unknown productId → null; empty plans →
  failure.
- **`EntitlementTest`** (extend) — basis/resetsAt pairing: `FREE_ALLOWANCE` with
  non-null or `SUBSCRIPTION` with null `resetsAt` throws; existing cases updated
  to the widened constructor, derivations unchanged.
- **`SubscriptionServiceTest`** (new, DB-backed; real `AppStoreServerApi` over
  the scripted transport) — verified inserts a row matching the decoded fixture;
  re-verify with changed state updates in place (idempotent refresh); re-verify
  with identical state → still `Verified`, `version` unmoved; expired-status
  fixture still `Verified` + row `status = expired`; blank / oversized /
  undecodable JWS and non-numeric transactionId → `ValidationFailure` with
  **zero transport calls**; Apple 404 → `UnknownTransaction`, no row; unknown
  product → `UnknownProduct`, **no row written**; transport failure →
  `AppStoreUnavailable`, no row; second student re-posting the first student's
  JWS → `OwnedByOtherAccount`, row still bound to the first.
- **`BudgetServiceTest`** (extend; constructions gain `SubscriptionPlans`) —
  active subscription + in-window spend under `y × price` → entitled,
  `basis = SUBSCRIPTION`, `resetsAt = period_end`; spend seeded at/over the
  budget → exhausted; spend seeded with `created_at` before `period_start` does
  not count; **rollover restores entitlement** — spend seeded past the budget
  inside window W1 (exhausted), then the row upserted to a disjoint window W2:
  the same student is entitled again with `spent = 0` against the same budget,
  pinning both rollover consequences (overshoot forgiven, nothing carried
  forward) with no state change beyond the window; expired/out-of-window row →
  free branch (`basis =
  FREE_ALLOWANCE`); grace row → subscription branch;
  current row with an unconfigured product → `Result.failure` (fails closed);
  no-subscription student → free branch, existing assertions hold.

### `rest-server`

- **`SubscriptionRoutingTest`** (new; `appModule` wired with a scripted
  `AppStoreServerApi`) — 401 unauthenticated; 409 without a student profile; 403
  unverified email; 200 happy verify (row in DB; response
  status/productId/`currentPeriodEnd` ISO-8601); 400 on garbage
  `signedTransaction`; 404 / 409-rebind / 503 arms; **entitlement flip e2e**:
  seed free-tier exhaustion with `created_at` before the fixture's
  `period_start` → turn answers 402 → `POST /verify` → turn admitted (scripted
  chat transport) → `GET coaching-usage` shows the period meter with non-null
  `resetsAt`; **rollover e2e**: the subscribed student's period spend seeded to
  exhaustion → turn answers 402 → re-`POST /verify` with a renewed fixture (new
  disjoint window) → turn admitted and coaching-usage shows `usedPercent = 0`
  with the new `resetsAt` — the verify-driven rollover as the app will live it
  until the webhook RFC.
- **`CoachingBudgetRoutingTest`** (extend) — usage response carries
  `resetsAt: null` on the free tier; existing 402/usage assertions hold under
  the widened response.
- **`ErrorCodeTest`** — covers the two new codes automatically. Existing
  `appModule` call sites gain the new parameters mechanically.

### Contract

`api-specs/openapi.yaml` staged → `bin/test-fuzz` runs from the pre-commit hook;
the new path's documented statuses (200/400/401/403/404/409/500/503) are inside
schemathesis' accepted sets.

## Implementation Plan

1. **`db`: schema + DAO.** Add `db/schema/0042.create-subscriptions.sql`,
   `db/src/main/kotlin/ed/unicoach/db/models/Subscription.kt`,
   `db/src/main/kotlin/ed/unicoach/db/dao/SubscriptionsDao.kt`,
   `SubscriptionsDaoTest`.
   - Verify: `nix develop -c bin/test db --tests "*SubscriptionsDao*"`.
2. **`:appstore` module.** `settings.gradle.kts` include;
   `appstore/build.gradle.kts` (deps + `java-test-fixtures`);
   `appstore/src/main/resources/appstore.conf`; the six main-source files
   (`AppStoreConfig`, `AppleJws`, `AppStoreAuthTokens`, `AppStoreTransport`,
   `KtorAppStoreTransport`, `AppStoreServerApi`); testFixtures
   (`ScriptedAppStoreTransport`, `AppStoreTestFixtures` with the test P-256 key
   and JWS/response builders); the four unit suites.
   - Verify: `nix develop -c bin/test appstore`.
3. **`service`: plans + subscribed branch.** Add the `subscriptions` block to
   `service/src/main/resources/service.conf`; add `SubscriptionPlans` +
   `SubscriptionPlansTest`; widen `Entitlement` (basis, resetsAt) and
   `BudgetService` (constructor `plans`, two-branch `entitlement`); render basis
   / resetsAt in `BudgetSkipLog`; update `TestBudgetServices` and every
   `Entitlement`/`BudgetService` construction; extend `EntitlementTest` /
   `BudgetServiceTest`. `:rest-server`/`:queue-worker` stop compiling here
   (constructor) — restored in steps 5–6; no earlier verify compiles them.
   - Verify: `nix develop -c bin/test service --tests "*Budget*"`;
     `nix develop -c bin/test service --tests "*Entitlement*"`;
     `nix develop -c bin/test service --tests "*SubscriptionPlans*"`.
4. **`service`: verify flow.** `:service` gains the `:appstore` dependency (main
   - `testImplementation(testFixtures(project(":appstore")))`); add
     `SubscriptionService`, `VerifyResult`, `SubscriptionServiceTest`.
   * Verify: `nix develop -c bin/test service --tests "*SubscriptionService*"`.
5. **`queue-worker`: wiring.** Parse `SubscriptionPlans` beside `BudgetConfig`
   in `main()`; widen the `BudgetService` construction.
   - Verify: `nix develop -c ./gradlew :queue-worker:compileKotlin`.
6. **`rest-server`: endpoint + wiring.** Add the two `ErrorCode`s;
   `SubscriptionVerifyRequest`/`SubscriptionVerifyResponse` models;
   `SubscriptionRoutes.kt`; `resetsAt` in `CoachingUsageResponse` +
   `CoachingUsageRoutes`; register in `Routing.kt`; `Application.kt` — load
   `appstore.conf`, parse `AppStoreConfig` + `SubscriptionPlans`, build
   `HttpClient(CIO)`/`KtorAppStoreTransport`/`AppStoreAuthTokens`/
   `AppStoreServerApi` (client closed on `ApplicationStopped`), widen
   `appModule` (build `SubscriptionService`, widen `BudgetService`, thread
   `configureRouting`). Update every `appModule` test call site; add
   `SubscriptionRoutingTest`; extend `CoachingBudgetRoutingTest`.
   - Verify:
     `nix develop -c bin/test rest-server --tests "*SubscriptionRoutingTest*"`;
     `nix develop -c bin/test rest-server --tests "*CoachingBudgetRoutingTest*"`.
7. **Contract.** `api-specs/openapi.yaml`: the new path (+ request/response
   schemas, the six statuses) and `resetsAt` (nullable date-time) on
   `CoachingUsage`.
   - Verify: `nix develop -c bin/test-fuzz`.
8. **Config + infra.** `.env.dev` (`APP_STORE_ENVIRONMENT=sandbox`), `.env.prod`
   (`production`), `.env.template` (the four `APP_STORE_*` keys in their roles),
   `bin/gen-deployed-env` (`DEPLOY_VAR_NAMES` + `APP_STORE_ENVIRONMENT`),
   `infra/ssm.tf` (three new out-of-band secrets).
   - Verify: `nix develop -c sh -c 'cd infra && tofu validate'`;
     `grep APP_STORE_ENVIRONMENT .env.dev .env.prod bin/gen-deployed-env`.
9. **Brief.** `features/paid-subscriptions.md`: record the node split in the
   living index (`subscriptions-apple` verify path → implemented, rfc 110;
   webhook → planned), and reconcile the pending kickoff prompts (webhook +
   `paywall-ios`) with landed names (`/api/v1/subscriptions/verify` shape,
   `Entitlement` basis/resetsAt, `resetsAt` on coaching-usage,
   `SubscriptionPlans`, `subscriptions` table columns, the re-verify refresh gap
   the webhook closes).
   - Verify: `grep -E 'rfc 110|110' features/paid-subscriptions.md`;
     `nix develop -c bin/format -c`.
10. **Full gate.** Whole suite + formatter.
    - Verify: `nix develop -c bin/test`; `nix develop -c bin/format -c`.

## Files Modified

Expected scope, non-exhaustive (per convention).

**`db`**

- `db/schema/0042.create-subscriptions.sql` — new.
- `db/src/main/kotlin/ed/unicoach/db/models/Subscription.kt` — new.
- `db/src/main/kotlin/ed/unicoach/db/dao/SubscriptionsDao.kt` — new.
- `db/src/test/kotlin/ed/unicoach/db/dao/SubscriptionsDaoTest.kt` — new.

**`appstore` (new module)**

- `settings.gradle.kts` — `include("appstore")`.
- `appstore/build.gradle.kts` — new.
- `appstore/src/main/resources/appstore.conf` — new.
- `appstore/src/main/kotlin/ed/unicoach/appstore/AppStoreConfig.kt` — new.
- `appstore/src/main/kotlin/ed/unicoach/appstore/AppleJws.kt` — new.
- `appstore/src/main/kotlin/ed/unicoach/appstore/AppStoreAuthTokens.kt` — new.
- `appstore/src/main/kotlin/ed/unicoach/appstore/AppStoreTransport.kt` — new.
- `appstore/src/main/kotlin/ed/unicoach/appstore/KtorAppStoreTransport.kt` —
  new.
- `appstore/src/main/kotlin/ed/unicoach/appstore/AppStoreServerApi.kt` — new.
- `appstore/src/testFixtures/kotlin/ed/unicoach/appstore/ScriptedAppStoreTransport.kt`
  — new.
- `appstore/src/testFixtures/kotlin/ed/unicoach/appstore/AppStoreTestFixtures.kt`
  — new.
- `appstore/src/test/kotlin/ed/unicoach/appstore/` — the four unit suites, new.

**`service`**

- `service/build.gradle.kts` — `:appstore` dependency + its testFixtures.
- `service/src/main/resources/service.conf` — `subscriptions` block.
- `service/src/main/kotlin/ed/unicoach/subscriptions/SubscriptionPlans.kt` —
  new.
- `service/src/main/kotlin/ed/unicoach/subscriptions/SubscriptionService.kt` —
  new.
- `service/src/main/kotlin/ed/unicoach/subscriptions/VerifyResult.kt` — new.
- `service/src/main/kotlin/ed/unicoach/coaching/budget/Entitlement.kt` — basis +
  resetsAt.
- `service/src/main/kotlin/ed/unicoach/coaching/budget/BudgetService.kt` — plans
  parameter + subscribed branch.
- `service/src/main/kotlin/ed/unicoach/coaching/budget/BudgetSkipLog.kt` —
  render basis/resetsAt.
- `service/src/test/kotlin/ed/unicoach/subscriptions/SubscriptionPlansTest.kt` —
  new.
- `service/src/test/kotlin/ed/unicoach/subscriptions/SubscriptionServiceTest.kt`
  — new.
- `service/src/test/kotlin/ed/unicoach/coaching/budget/` — `EntitlementTest`,
  `BudgetServiceTest`, `TestBudgetServices` extended; other suites' fixture
  constructions ripple mechanically.

**`rest-server`**

- `rest-server/src/main/kotlin/ed/unicoach/rest/models/ErrorCode.kt` — two
  codes.
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/SubscriptionVerifyRequest.kt`
  — new.
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/SubscriptionVerifyResponse.kt`
  — new.
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/CoachingUsageResponse.kt`
  — `resetsAt`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/SubscriptionRoutes.kt` —
  new.
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/CoachingUsageRoutes.kt`
  — passthrough.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt` — register.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — config load +
  client/api assembly + `appModule` widening.
- `rest-server/build.gradle.kts` — `:appstore` (main + testFixtures).
- `rest-server/src/test/kotlin/ed/unicoach/rest/SubscriptionRoutingTest.kt` —
  new; `CoachingBudgetRoutingTest.kt` extended; the other `appModule`-booting
  suites gain the new arguments mechanically.

**`queue-worker`**

- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` — parse
  `SubscriptionPlans`, widen `BudgetService`.

**`api-specs`**

- `api-specs/openapi.yaml` — new path + schemas; `resetsAt`.

**Config / infra**

- `.env.dev`, `.env.prod`, `.env.template` — `APP_STORE_*` keys.
- `bin/gen-deployed-env` — `DEPLOY_VAR_NAMES`.
- `infra/ssm.tf` — three out-of-band secrets.

**Feature brief**

- `features/paid-subscriptions.md` — living index + pending-prompt
  reconciliation.
