# RFC 244 — A subscription belongs to an account

Slice `family/03.5/who-pays` of product brief 0014 (parents in the room).

**This RFC implements a NEW product decision that supersedes gate-2 decision
D10.** D10 (approved 2026-09-14, on its default) chose _"parent coaching rides
the linked student's subscription"_. At this run's approval gate Ian rejected
that framing — "I thought that we'd have two subscriptions always" — and
decided: **every account pays for itself.** No account rides another's
subscription. D10 stands as committed and is superseded, not edited; the new
decision is recorded in `product/0014-parents-in-the-room/spec.md` at close-out.

A second question was put at the same gate and answered the same day: **the
shared-Apple-ID case is counted before it is built.** See §2.

## The two facts the decision rests on

1. **unicoach is not family-shareable.** `"familyShareable": false`
   (`ios-app/UnicoachiOS.storekit:21`). Apple does not hand a student's
   subscription to the rest of the family, so two Apple IDs hold two
   subscriptions at the same price. The brief's correction X1 calls the failure
   "a Family-Sharing Apple ID"; that is the wrong mechanism, and the code wins
   over the doc.
2. **One Apple ID cannot buy the same subscription twice.** Within a
   subscription group an Apple ID holds at most one active subscription, so a
   second unicoach account signed in to the SAME Apple ID gets the first
   account's `original_transaction_id` back. That is the real source of
   `409 subscription_owned_by_other_account` (`SubscriptionsDao.kt:78,112-114`,
   `SubscriptionRoutes.kt:104-109`), and no product design can move it. Proper
   Family Sharing does NOT reach it — there each member has their own Apple ID.

## Problem

- Billing is keyed to a STUDENT: `subscriptions.student_id NOT NULL`
  (`db/schema/0042.create-subscriptions.sql:33`). An account that is not a
  student cannot hold a subscription at all, and
  `POST /api/v1/subscriptions/verify` refuses it by role
  (`SubscriptionRoutes.kt:49`, `CallerResolution.kt:71-75`). This is the blocker
  correction X1 named: a parent can neither subscribe, nor hold a meter, nor
  have spend recorded.
- The entitlement answer is `BudgetService.verdict(session, studentId)` — six
  call sites, all `StudentId` (`service/.../budget/BudgetService.kt:48-75`).
- The cost ledger `student_llm_cost` is a VIEW over four student-keyed owner
  tables (`db/schema/0041.add-llm-response-cost.sql:58-82`), so a supporter's
  spend has nowhere to be recorded and an allowance for a supporter cannot be
  enforced.
- The shared-Apple-ID refusal is a dead end that also destroys the purchase: iOS
  treats the 409 as permanent and FINISHES the StoreKit transaction
  (`TransactionRecorder.swift:49,121-124`) while Subscribe stays on screen and
  can never succeed (`SubscriptionViewModel.swift:208-212,587-588`).
- A supporter account (RFC 241) reaches no billing surface at all: a sibling
  scene, and `SettingsView.Caller` makes "supporter, with a rail" fail to
  compile (`SettingsView.swift:21-45`). The seam was left for this slice
  (`SupporterHomeView.swift:231-235` names `family/03.5` and D10).

## Why a user may not be keyed by role

`subscriptions` is keyed by `user_id`, not by a `student_id`/`supporter_id`
pair, because **one user may hold both rows**: `SupporterService.ensure` is
"deliberately silent about whether the caller is ALSO a student: a `supporters`
row is a home surface, not a claim about what somebody is not"
(`service/.../family/SupporterService.kt:112-115`). A role-keyed subscription
has no answer for that person, and would make one human need two subscriptions
that Apple will not sell on one Apple ID. The account is who pays; the roles
hang off it, one indexed lookup away.

## What this RFC does NOT do

- **No family plan, no shared billing, no second price point.** Two accounts pay
  twice, at the same price.
- **No account rides another's subscription.** A student's subscription covers
  the student's account and nothing else.
- **No purchase surface for a supporter.** Nothing is sold to a parent here: the
  parent's coach (`family/06`) does not exist, and selling access to a coach
  that is not built would be dishonest. A supporter's Subscribe button arrives
  with the coach that justifies it.
- **No parent coaching and no subject-scoped reads of student data** —
  `family/05` and `family/06`.

## Detailed Design

### 1. A subscription belongs to an ACCOUNT

Migration `subscriptions-key-on-account.sql` (no number, RFC 180) replaces
`subscriptions.student_id` with `user_id`:

```sql
ALTER TABLE subscriptions ADD COLUMN user_id UUID NULL REFERENCES users(id);
UPDATE subscriptions s SET user_id = st.user_id FROM students st WHERE st.id = s.student_id;
ALTER TABLE subscriptions ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE subscriptions DROP COLUMN student_id;
-- the same pair on subscriptions_versions, and log_subscription_version() restated
```

The backfill is a total function: `students.user_id` is `NOT NULL` with a TOTAL
unique index (0005), so every existing row has exactly one account. `user_id`
takes **no `ON DELETE` action**, exactly as `students.user_id` (0005) and
`supporters.user_id` take none — `users` carries the physical-delete guard, so a
cascade could never fire and would only state a policy this table does not own.

**The hazard, named so the implementation does not discover it:**
`subscriptions` carries live guards — `prevent_immutable_updates`,
`prevent_physical_timestamp_update`, `enforce_versioning` and the history writer
`log_subscription_version()` (0042). A backfill `UPDATE` on a POPULATED table
(the test harness migrates one) meets all of them. The migration states, per
trigger, whether it passes or must be dropped and re-created, and restates the
history function because it names columns.

`SubscriptionsDao` reads by account and keeps ONE definition of "current":

- `findCurrentForUser(userId)` — the account's own subscription, the only read.
- A caller holding a `StudentId` resolves the account one indexed hop away
  (`students.user_id`) and asks that read. `BudgetService` does this for all six
  student call sites, so each keeps its signature AND its answer. A caller that
  already HOLDS the account — the parent's usage route, whose session names it —
  asks for the account directly and makes no hop at all.
- The entitling clause is the window plus `status IN (...)`, and the status set
  is not written in the DAO: it is read from `SubscriptionStatus.entitles`, the
  enum that owns it. The one remaining copy is the partial index's predicate,
  which is how the index serves this read, and
  `SubscriptionEntitlingStatusPinTest` fails when the two disagree — a drift
  Postgres cannot catch and whose cost (a sequential scan per gated turn, or
  coaching refused to somebody who paid) is otherwise silent.

**One behaviour DID change, and it is not a wording difference.** A `StudentId`
that names no row now fails CLOSED: the account hop raises. The old
student-keyed read answered `null` for such an id, and the gate then metered a
full free allowance for a subject that does not exist. It raises as config drift
does — an `IllegalStateException`, a 500 — and deliberately NOT as the DAO's
`NotFoundException`, which the REST boundary publishes as a `404` carrying the
DAO's own message: a dangling reference is not an absence the caller has an
answer for.

### 2. The shared Apple ID is counted, not covered

Two unicoach accounts on ONE Apple ID still cannot both subscribe: Apple will
not sell the second. This RFC does **not** build coverage between linked
accounts. Ian's decision at the gate, and its reason: the rate is unknown (there
are no real users yet), the workaround is free (a second Apple ID), and the
refusal is exactly where a coverage branch would hook in later.

So the refusal keeps its code and status —
`409 subscription_owned_by_other_account` — and gains two things:

- **Words that name the situation and a way out**, in the app (§7b).
- **A counter.** The refusal logs one WARN line with a stable, greppable event
  name and both account ids:

  ```
  subscription_apple_id_already_bound original_transaction_id=[…] owner_user_id=[…] caller_user_id=[…]
  ```

  No new table, because the thing being measured is exactly the thing that was
  deferred. When the count justifies it, coverage is a new slice and this line
  is the evidence for it.

### 3. The meter's subject is the account

The cost ledger gains a per-ACCOUNT view, built from the two owner-side views so
that neither is rewritten:

```sql
CREATE VIEW account_llm_cost AS
SELECT st.user_id, c.created_at, c.cost_nanodollars, c.cost_is_estimated,
       c.input_tokens, c.output_tokens, c.cache_read_tokens, c.cache_write_tokens
FROM student_llm_cost c JOIN students st ON st.id = c.student_id
UNION ALL
SELECT sp.user_id, c.created_at, c.cost_nanodollars, c.cost_is_estimated,
       c.input_tokens, c.output_tokens, c.cache_read_tokens, c.cache_write_tokens
FROM supporter_llm_cost c JOIN supporters sp ON sp.id = c.supporter_id;
```

For every account alive today the second arm is empty, so **every existing
student's meter reads exactly the number it read before** — a claim the tests
make, not one the prose makes. For the dual-role user the two arms sum, which is
the right answer for one account with one subscription.

`BudgetService` keeps its public student signatures (the six call sites do not
move), resolves the account once, and answers on the two branches it already
has:

1. **Own subscription** (`findCurrentForUser`): the window is its period, the
   allowance is the plan's `budgetRatio × price`, basis
   `EntitlementBasis.Subscription` — unchanged.
2. **None**: the lifetime account ledger against `budget.freeAllowanceUsd`,
   basis `EntitlementBasis.FreeAllowance` — unchanged.

It gains ONE entry, keyed by the subject it meters — a suspend
`entitlement(userId)`, for a caller that already holds the account. There is no
supporter-keyed overload. A supporter's spend reaches the answer through
`account_llm_cost`, which re-keys the supporter spine onto the account, so a
`SupporterId` would buy nothing but a second read of the `supporters` row its
one caller had just read. `family/06` adds a role-keyed gate together with the
call it gates, which is also where that gate's refusal shape is decided.

**No new configuration, and no third entitlement basis.** A supporter with no
subscription gets the same free allowance every account gets ($5.00,
`service.conf:558-564`); a supporter who holds one gets the same budget a
student holds. `EntitlementBasis` stays a sealed pair.

### 4. A supporter's spend can be recorded — `supporter_llm_costs`

**ADDS A TABLE — DDL at the approval gate** (standing D10, already approved for
this shape). Migration `supporter-llm-cost.sql`:

```sql
-- The adult side's owner row on the RFC 106 call log, mirroring convo_requests
-- and extraction_runs: an owner table carries the llm_request_id, and a cost view
-- joins it to the frozen per-call cost. One row per attributed call.
CREATE TABLE supporter_llm_costs (
    llm_request_id BIGINT      NOT NULL PRIMARY KEY REFERENCES llm_requests(id),
    supporter_id   UUID        NOT NULL REFERENCES supporters(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX supporter_llm_costs_supporter_id_idx ON supporter_llm_costs (supporter_id);

-- A log row: immutable like llm_requests (0038).
CREATE TRIGGER trigger_00_prevent_supporter_llm_costs_update
BEFORE UPDATE ON supporter_llm_costs FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();

-- Shaped exactly like student_llm_cost (0041), windowing on the RESPONSE's
-- created_at so a period means the same thing on both sides of the house.
CREATE VIEW supporter_llm_cost AS
SELECT slc.supporter_id, resp.created_at, resp.cost_nanodollars, resp.cost_is_estimated,
       resp.input_tokens, resp.output_tokens, resp.cache_read_tokens, resp.cache_write_tokens
FROM supporter_llm_costs slc
JOIN llm_responses resp ON resp.request_id = slc.llm_request_id;
```

Without it a supporter's meter reads a constant zero and the free allowance can
never be reached, so "a parent's spend is attributable" — this slice's own
acceptance criterion — would be false. It is the one row `family/06` writes per
call, which is why that slice PREFERS this one. It adds no writer to any
existing path, so no existing query changes its answer.

The shared aggregate model `StudentLlmCost` is renamed `LlmCostAggregate`
(mechanical), so no DAO returns a type named after another subject.

### 5. `POST /api/v1/subscriptions/verify` stops refusing by role

The route resolves the USER and no longer requires a student profile — billing
is per account, so `student_profile_required` is not an answer this route can
give any more, and `api-specs/openapi.yaml` stops offering it: the published 409
names only the shared-Apple-ID collision. Its outcomes are otherwise exactly
today's: bind or refresh the subscription for the caller's account (200), or the
four refusals it already has, with the 409 now carrying the counter of §2. The
response body does not change.

### 6. `GET /api/v1/supporters/me/coaching-usage`

New, mirroring the student route in shape and in what it refuses to say (no
dollars, no tokens):

```json
{
  "usage": { "usedPercent": 0, "exhausted": false, "resetsAt": null },
  "coverage": { "kind": "free_allowance" }
}
```

`coverage.kind` is `own_subscription` or `free_allowance` — the two branches §3
has, named on the wire so the app renders words rather than inferring them from
a null. The client decodes an unknown word as unrecognised and renders the
unavailable line (the `SupporterRelationship` precedent), never a default kind:
a shipped build outlives the vocabulary it was compiled against. **404** when
the caller holds no `supporters` row, the answer `GET /api/v1/supporters/me`
already gives for the same absence — that `supporters` read is what the 404 is
for, and nothing else: the meter is read on the ACCOUNT the session already
names. Published in `api-specs/openapi.yaml`, and the `usage` half is projected
by the one `Entitlement` → `CoachingUsage` mapper the student route publishes
through, so the two bars cannot come to disagree about what 100% means.

### 7. The doors

**a. The parent home says how coaching is paid for.** A new
`SupporterCoachingSection` in `SupporterHomeView`, visible in both roster states
— the reachable door for this slice, since the parent's chat does not exist yet:

| state                  | words                                                                                 |
| ---------------------- | ------------------------------------------------------------------------------------- |
| free allowance, unused | "You have a free allowance to start with." + the usage bar, "One-time free allowance" |
| free allowance, spent  | "You've used your free allowance."                                                    |
| own subscription       | "Your subscription." + the usage bar, caption "Resets ⟨date⟩"                         |
| unavailable            | "Coaching usage is unavailable right now." — the existing idiom, never a silent zero  |

The own-subscription arm IS reachable today, by the account that holds both a
student and a supporter row. It reads §6 through a new
`SupporterCoachingUsageClient`, on appear and on return to the foreground, as
the roster refresh already does (`SupporterHomeView.swift:104`,
`UnicoachiOSApp.swift:127-129`). `SettingsView` keeps its `Caller` enum and
gains no rail, so the claim in its doc comment stays true: the supporter's read
is a supporter-scoped route on the supporter's own scene.

No StoreKit listener is added to the supporter scene. Without coverage there is
nothing for it to discover, and a parent has nothing to buy.

**b. The student-side dead end becomes honest.** For
`subscription_owned_by_other_account` the words become:

> This Apple ID's Unicoach subscription belongs to another Unicoach account. Use
> a different Apple ID to subscribe on this account.

and `offersSubscribe` goes false for the rest of the session once that refusal
arrives, because the button cannot succeed. The transaction is still finished —
an unfinished transaction is redelivered for ever, and there is nothing else
this app can do with a purchase Apple has attached elsewhere.

The sentence is rendered from STATE as well as from the refusal banner — a new
`Offer.appleIdBoundElsewhere` arm — and is authored once, in
`SubscriptionViewModel.appleIdBoundElsewhereText`. The banner alone would not
do: `notice` is cleared on every appearance while the withdrawal lasts the whole
session, so one refresh later the surface would show a meter, no Subscribe and
no reason. `.bound`, the arm this case was first folded into, is silent for a
different reason — its remedy is the management link, which cannot render for an
account that holds no subscription.

Every `RecordOutcome` is read by ONE construct, so the fact cannot be learnt on
one path and dropped on another: it was dropped on the app-open path, which is
the commonest way a fresh install on a shared Apple ID meets the refusal.

## Files Modified

**Schema** — `db/schema/subscriptions-key-on-account.sql`,
`db/schema/supporter-llm-cost.sql`, `db/schema/account-llm-cost.sql` (new), and
their lines in `db/schema/ORDER` (position is the author's to declare;
`ship-order` re-derives the file at land)

**db** — `SubscriptionsDao.kt` (account reads, the entitling predicate derived
from the enum), `AccountLlmCostDao.kt` (new — the ONE cost read production
makes, carrying the projection, window and mapper that read means),
`models/StudentLlmCost.kt` → `LlmCostAggregate.kt`, `models/Subscription.kt`
(`SubscriptionStatus.entitles`). `StudentLlmCostDao.kt` is DELETED and no
per-supporter DAO is added: once the gate reads the account spine neither has a
production caller, and the two owner-side views are proved through the account
spine instead (`StudentLlmCostViewTest`, `SupporterLlmCostViewTest`). The one
costed-call test fixture is `testFixtures/dao/LlmCallRowFixture.kt`.

**service** — `coaching/budget/BudgetService.kt` (account subject, the
account-keyed entry), `subscriptions/SubscriptionService.kt` (account-keyed
verify, the counter), `family/SupporterService.kt` (the usage read)

**rest-server** — `routing/SubscriptionRoutes.kt` (no role refusal),
`routing/SupporterRoutes.kt` (the usage route), `models/*` (including the ONE
`Entitlement` → `CoachingUsage` projection both usage routes publish through),
`api-specs/openapi.yaml`, and in tests the shared `appModule` boot harness
(`E2eSupport.startAppModuleServer`) with `appstore`'s
`testFixtures/SwappableAppStoreTransport.kt`

**iOS** — `SupporterCoachingUsageClient.swift`,
`SupporterCoachingViewModel.swift` (new — the four-state rule of §7a, where the
tests can reach it without a view harness), `SupporterCoachingSection.swift`
(new), `SupporterHomeView.swift`, `UnicoachiOSApp.swift`, `AppViewModel.swift`,
`Models.swift`, `CoachingUsageMeter.swift` (the unavailable sentence, shared
with the parent surface), `SubscriptionViewModel.swift`,
`SubscriptionOffer.swift` (the `appleIdBoundElsewhere` arm),
`ios-app/UnicoachiOS.xcodeproj/project.pbxproj` (a new Swift file needs project
entries in this repo), plus tests and two snapshot scenes —
`supporter-coaching-states` and `paywall-apple-id-bound-elsewhere`, the second
because the standing explanation of §7b has no other witness and iOS is outside
the commit gate

## Implementation Plan

1. `subscriptions-key-on-account.sql` and `SubscriptionsDao`: the account
   column, the restated history writer, the shared entitling predicate, both
   reads. DAO tests first. Two measured facts to work from: the backfill
   `UPDATE` trips THREE guards (`enforce_versioning`, `update_timestamp`,
   `log_subscription_version`), and dropping and re-creating them is right where
   bumping `version` would write a history row claiming the subscription changed
   at the migration — a lie in an entitlement dispute. **A migration-only edit
   must be verified with `bin/test -f`**: `db/schema/*.sql` is not a Gradle
   input, so `:db:test` comes back `FROM-CACHE` and green over a schema change
   nobody ran.
2. `supporter-llm-cost.sql`, `account-llm-cost.sql`, `AccountLlmCostDao`, the
   model rename.
3. `BudgetService`: the account subject and the account-keyed entry.
4. `/subscriptions/verify` — role refusal removed, the counter at the 409.
5. `GET /api/v1/supporters/me/coaching-usage` and the OpenAPI entries.
6. iOS: the client, the section, the copy change and the withdrawn button.
7. `nix develop -c bin/test` through `ship-run`; iOS tests and snapshots under
   **system Xcode, typed bare** — every iOS script refuses the nix dev shell
   (`bin/snapshot-ios:118-120`, `bin/test-ios:64`). iOS is NOT in the commit
   gate (`bin/gate-scope:118` excludes `ios-scripts-tests`), so this run's
   report is the only iOS evidence there will be. A new snapshot scene is one
   element appended to the list in `SnapshotScenes.swift` (42 scenes today; RFC
   241's four are the precedent), and pixel drift never fails the gate, so the
   scene is looked at, not trusted.

## Tests

**The regression this design promises.** Every existing subscription and budget
test passes unchanged after the key moves from student to account, and a
subscribed student's usage reading is identical before and after. That is the
point of the change, not a formality.

**Migration** — note first what CANNOT be tested by running it: `bin/test` does
`db-reset (drop → create → migrate)`, so the backfill runs on an EMPTY table
every time (`UPDATE 0`). Its totality is argued, not measured —
`subscriptions.student_id` was `NOT NULL` and `students.user_id` is `NOT NULL`
under a TOTAL unique index, so `ALTER COLUMN user_id SET NOT NULL` IS the
assertion, and it fails the migration if the argument is wrong. What is tested
is the state the migration leaves: a post-migration `UPDATE` still bumps
`updated_at` and still writes a history row (so the three guards dropped for the
backfill were re-created), still refuses an immutable-column change (so the two
that passed were never touched), and `subscriptions_versions` carries the new
column and not the old one.

**Reads** — `findCurrentForUser` filters status (active and grace entitle;
expired, billing-retry and revoked do not), filters the window, picks the latest
`period_end` of two entitling rows, and answers for an account with no student
row. The student-keyed answer is unchanged where it is now made, at the service
layer: `BudgetService`'s student tests read the same numbers they read before
the key moved, including the lapsed, revoked and elapsed-window fall-back to the
free branch.

**Ledger** — read through `AccountLlmCostDao`, the only cost read there is:
`supporter_llm_cost` counts one supporter's attributed call and not another's
and is invisible to the student spine; an uncosted call contributes 0 and an
estimated call counts (RFC 108's policy, inherited); the window is half-open and
an inverted one is refused; and `account_llm_cost` sums a dual-role account's
student and supporter spend while equalling the student figure exactly for an
account that is only a student.

**Entitlement** — a supporter with no subscription exhausts at the free
allowance and blocks; a supporter whose account holds a subscription meters over
its period; a failed read is never `Entitled`; config drift fails closed; a role
id naming no row fails rather than metering a subject that does not exist, on
both sides.

**Routes** — verify: a caller with no student profile is no longer refused by
role; a receipt bound to another account still answers
`409 subscription_owned_by_other_account` and writes nothing. Usage: 200 for
both coverage kinds with the exact body; 404 with no supporter row; 401
unauthenticated; no dollars or tokens in the payload; the published OpenAPI
schema matches the response model.

**iOS** — the section's four states as `SupporterCoachingViewModel` states,
including the refusal that must read "unavailable" rather than 0%; the four sets
of WORDS are evidenced by the one snapshot scene and are looked at, not
asserted; the withdrawn Subscribe button after the 409; a supporter session
makes no student-scoped call.
