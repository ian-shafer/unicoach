# RFC 152 — Where you'll live

Slice `money/04/where-youll-live` (brief 0003 M4). Adds one tri-state field to
the money profile — **where the family plans to live** — so that a cost answer
can lead with the one arrangement the family actually means, instead of offering
three and letting them pick.

The arrangement choice is a $7,368/yr swing at the worked example — larger than
in-state tuition at that school. Until the family says which one they mean,
every total we show is honest but generic.

## Context

RFC 149 (`money/02/component-split`) built `CostBreakdown`: three
`LivingArrangement`s (`on_campus`, `off_campus`, `with_family`), each a list of
components, each with a total that is `null` unless every part is published. RFC
151 (`money/03/comparison-contract`) built `ComparisonBasis`, whose
`ArrangementBasis` states which arrangements every compared school is priced
for. RFC 134 built `money_profiles`: two tri-state fields (`income_band`,
`residency_state`), each `unanswered | answered | declined`, with a
value-IFF-answered CHECK.

This RFC adds the third tri-state field and wires it into both read paths.

## Non-goals

- **No travel personalisation.** Per brief 0003 OQ1/D16, M4 personalises housing
  only. The coach makes no quantitative claim about distance from home.
- **No new cost data.** The six component columns from RFC 149 are unchanged.
- **No filtering of the breakdown.** See D2.

## Decisions

### D1 — `LivingArrangement` moves to `:db`; it stays one enum

`LivingArrangement` already exists with exactly the three wire names we want,
but it lives in `:service` (`coaching/costs/CostBreakdown.kt`) and carries a
`components: List<CostField>` list that is `:service`'s business. `MoneyProfile`
lives in `:db`, and `:db` cannot depend on `:service`.

CLAUDE.md's schema convention is explicit: an own enumeration is `TEXT` +
`CHECK IN (...)` plus **exactly one** Kotlin `enum class Foo(val value: String)`
with a `fromValue` companion in `db/src/main/kotlin/ed/unicoach/db/models`.
Minting a second enum would give one concept two vocabularies — the precise harm
`IncomeBand.bracket` and `LivingArrangement.label` were written to prevent.

So: **move** `LivingArrangement` to `db/models/LivingArrangement.kt` carrying
`value`, the spoken `label`, and a `fromValue` companion. The arrangement →
`CostField` mapping stays in `:service`, as a `components` lookup keyed by the
enum. `InstitutionControl` (RFC 143) is the precedent for hoisting a vocabulary
to one home for every module.

`CostField`'s "declared" set derives from `LivingArrangement.entries` lazily and
deliberately (init re-entrancy); that lazy derivation is preserved verbatim.

### D2 — A chosen plan **leads**; it never filters

`CostBreakdown.of` keeps emitting all three arrangements. The chosen plan
decides what the coach leads with, and what a comparison column holds constant.
It does not remove true facts from the payload.

Filtering would silently narrow `ArrangementBasis.comparable` for every caller
and break the RFC 149 arrangement-invariant suite. It would also make a "what if
we lived at home instead?" question unanswerable from the same result.

### D2a — The plan is a global **default** plus a per-college **override**

A living plan is two different facts wearing one name. _Preference_ — "we'd
rather he lived at home" — is global. _Feasibility_ — "he can only live at home
if the school is commutable" — is a fact about the student–college **pair**. The
slice text collapsed them into one field; Ian's UW-vs-UCSD case is where that
breaks (brief 0003 D20, 2026-09-01).

Our data cannot decide feasibility and never will: the Scorecard publishes an
`OTHEREXPENSE_FAM` figure for essentially every school, because a school prices
a commuter category whether or not _this_ student can commute to it. We hold no
distance-from-home data and brief 0003 D16 bars quantitative travel claims. So
feasibility is only ever something the family tells us.

Therefore:

- `money_profiles.living_plan` — the default: where the student would live
  **when they have the choice**. Tri-state, as the slice specifies.
- `college_list_entries.living_plan` — a nullable per-college override. `NULL`
  means "no override, use the default", so no second tri-state is needed here:
  the entry has nothing to decline, and a decline is a global stance.
- Resolution, in one helper: **override → default → show all three.**

**`with_family` is never inferred by us.** It applies to a school only because
the family said so — either as the default they set, or as that school's
override. When the default is `with_family` and a school has no override, the
coach names the assumption in the same breath ("assuming you'd commute to
UCSD"), and a correction is written as that school's override.

### D3 — Degrading: unanswered keeps today's behaviour

Brief 0003's M4 text says an unanswered plan should show "the on-campus basis,
named as such, plus the swing to the cheapest reported arrangement". What RFC
149 actually landed is strictly better: **all three** arrangements, each named
and labelled, with a labelled blank where a component is unpublished. The code
wins over the spec (CLAUDE.md). Unanswered therefore renders exactly as it does
today, plus a precision offer (D4). Declined renders exactly as today, forever.

### D4 — One more `PrecisionOffer`, offered last

`PrecisionOffer`'s own KDoc predicts this member verbatim. `LIVING_PLAN` is
added **last** in declaration order (declaration order is wire order), so
residency and income band — which change the number more often — are still
offered first. Its `appliesTo` keys off `livingPlanStatus == UNANSWERED` **and**
the school having at least two arrangements that carry a **total** — priced, not
merely present. An offer must never rest on a school with nothing to choose
between, and three arrangements with no totals give the family nothing to choose
between. Keying off a missing _value_ would re-raise a declined topic on every
cost answer; keying off the _status_ is why `AnswerStatus` exists.

### D5 — `ArrangementBasis` gains a code per statement and four cases

`ArrangementBasis` is today the one comparison fact with no `basis` code, only
lists. Adding the chosen plan is the natural moment to give it one.
`ArrangementBasis.of` reads the resolution off `CollegeCost.chosenLivingPlan`
rather than re-resolving it from the profile and the entries. Resolution has
exactly one home; a second copy could disagree with the number the same payload
renders. It states one of four things, and ships **one wire code per statement**
— which is five codes, not four, because case 4 below carries RFC 151's two
statements and RFC 151 D-D gives every fact a code per sentence:

1. one plan resolved for every school, and every school priced for it — the
   column holds that one way of living, named;
2. **different plans across the compared schools** — the override case: at home
   for the in-state school, on campus for the far one. The column then holds the
   family's actual situation rather than one arrangement, and the statement says
   so and names the plan used for each school. This is not a breach of the RFC
   151 comparison contract; it is the contract working. The contract requires
   the assumption to be stated before the numbers, and here the assumption is
   per school;
3. a plan resolved but some school is not priced for it — those schools are
   named with their reason, reusing the existing `ArrangementGap` vocabulary
   (`no_on_campus_housing` vs `not_reported`) through one shared
   `ArrangementGap.forMissing` rule — RFC 151's `gapsOf` no-dorms filter is
   rewritten onto it so the two cannot drift; never a substituted arrangement;
4. nothing resolved (unanswered or declined, no overrides) — today's two
   statements, byte-for-byte.

### D6 — Coach prompt v14, additive

v14 = v13 byte-identical as a prefix, plus exactly one appended paragraph (the
0047/0048/0058/0063/0065/0066 shape). The v7 money paragraph — which says
"either field" and is asserted byte-for-byte by `SystemPromptCatalogTest` — is
**not** reworded; the new paragraph states the third field's own flow. Rollback
is one env var (`COACHING_SYSTEM_PROMPT_VERSION=v13`).

The version number moved twice while this run was open: sibling runs landed v12
(RFC 154) and v13 (RFC 153) first. The rule that made that safe is that a run
claims its prompt version and its migration numbers from the **rebased** tree
immediately before commit, never from its own design doc.

### D7 — The tool grows a field; it does not gain a sibling

`update_money_profile` gains `living_plan` + `living_plan_declined`, following
the existing `KNOWN_FIELDS` / `FieldParse` ladder. A sibling tool would split
one concept ("tell me about your family's money situation") across two tools the
model must choose between.

## Proposed DDL — presented at the gate (brief 0001 D10)

No new table. Two migrations, on the `0045` "add columns to a versioned table"
precedent.

```sql
-- 0070.add-money-profile-living-plan.sql
ALTER TABLE money_profiles
    ADD COLUMN living_plan        TEXT NULL,
    ADD COLUMN living_plan_status TEXT NOT NULL DEFAULT 'unanswered',
    ADD CONSTRAINT money_profiles_living_plan_check
        CHECK (living_plan IS NULL OR living_plan IN
               ('on_campus','off_campus','with_family')),
    ADD CONSTRAINT money_profiles_living_plan_status_check
        CHECK (living_plan_status IN ('unanswered','answered','declined')),
    -- Value present exactly when answered: a declined/unanswered field can
    -- never smuggle a stale value to a consumer.
    ADD CONSTRAINT money_profiles_living_plan_value_iff_answered_check
        CHECK ((living_plan IS NOT NULL) = (living_plan_status = 'answered'));

-- The history table takes the same pair. living_plan_status is NOT NULL there
-- too, so it needs the DEFAULT or every pre-existing history row fails the
-- ALTER (and would then break parseStatus in the admin history panel).
ALTER TABLE money_profiles_versions
    ADD COLUMN living_plan        TEXT NULL,
    ADD COLUMN living_plan_status TEXT NOT NULL DEFAULT 'unanswered';

-- Redefine the history writer to carry the new columns. CREATE OR REPLACE is
-- picked up by the existing trigger_04_log_money_profile_version by name with
-- no re-wiring (the 0023/0045 pattern).
CREATE OR REPLACE FUNCTION log_money_profile_version() ... ;
```

The per-college override, on the same precedent. `college_list_entries` has not
been altered since `0024`, so the versions table and
`log_college_list_entry_version()` follow the same shape. One column, not a
pair: `NULL` **is** "no override".

```sql
ALTER TABLE college_list_entries
    ADD COLUMN living_plan TEXT NULL,
    ADD CONSTRAINT college_list_entries_living_plan_check
        CHECK (living_plan IS NULL OR living_plan IN
               ('on_campus','off_campus','with_family'));

ALTER TABLE college_list_entries_versions
    ADD COLUMN living_plan TEXT NULL;

CREATE OR REPLACE FUNCTION log_college_list_entry_version() ... ;
```

The last migration is the `v14` coach-prompt seed
(`0072.seed-coach-system-prompt-v14.sql`), an insert-only row on the existing
`system_prompts` table; no DDL.

## Detailed Design

### Storage and model (`:db`)

`LivingArrangement` (moved) carries `value`, `label`, `fromValue`.
`MoneyProfile`, `NewMoneyProfile`, `MoneyProfileEdit` each gain
`livingPlan: LivingArrangement?` and `livingPlanStatus: AnswerStatus` after the
residency pair; `MoneyProfileUpsert` gains
`living: FieldWrite<LivingArrangement>?`. `MoneyProfilesDao` gains the column in
`mapProfile` (with a `parseLivingPlan` corrupt-value path identical to
`parseIncomeBand`, raising `CorruptPersistedValueException` rather than
relabelling the row), in the upsert SQL (hand-numbered binds `1..9` → `1..13`:
seven values and six flags), and in `create`/`update`.

`CollegeListEntry` (and its `New`/`Edit`/upsert siblings) gains a nullable
`livingPlan: LivingArrangement?`, with `CollegeListEntriesDao` carrying the
column through the row mapper, insert and update. `LivingArrangement` in `:db`
serves both tables — one vocabulary, two homes for two different facts.

### Write path

`MoneyProfileService.MoneyProfileUpdate` gains
`living: FieldUpdate<LivingArrangement>?` — named for its `income`/`residency`
siblings and for `MoneyProfileUpsert.living`, not for the column, mapped by the
existing generic `mapFieldUpdate`. `MoneyProfileChatTool` gains `living_plan`
(enum of the three wire names, spoken label in the description) and
`living_plan_declined` (`const: true`) in its input schema and `KNOWN_FIELDS`, a
`parseLivingPlanUpdate` on the existing ladder (a `false` is an error; value +
decline together is a conflict), and echoes `living_plan_status` / `living_plan`
/ `living_plan_label` from `profileObject`. The REST twin
(`MoneyProfileResponse`, `UpdateMoneyProfileRequest`, `MoneyProfileRoutes`,
`openapi.yaml`) and `admin-web`'s `MoneyProfilesResource` (field, cell, history
panel; value marked sensitive) follow the residency pattern exactly, including
the at-most-one `state | declined | clear` guard.

`CollegeListChatTool` gains the per-college override on its existing entry
update path — the same field, on the school, clearable back to `NULL` ("use my
usual plan"). The REST college-list surface follows.

A decline is recorded, not absorbed: `FieldUpdate.Decline` →
`FieldWrite.Declined` → `living_plan_status = 'declined'` with the value cleared
by the CHECK. It is permanent and never re-raised, and reversible if the student
reopens the topic — the version history keeps the whole trail.

### Read path — the per-college answer

`CollegeCostService.MoneyProfileStatuses` gains the status and the value
(`ALL_UNANSWERED` and `requireIntactAnswers` extended with a third
`requireStoredValueWhenAnswered` call, so an answered-with-no-value row is
refused, not relabelled "never asked"). A single
`chosenLivingPlanOf(entry, moneyProfile, breakdown, offersOnCampusHousing)`
helper resolves **override → default → none** (D2a) and is the only place that
resolution exists; it mirrors `netPriceOf`. It also reports _why_ it chose, so
the renderer can say "you told us this for this school" versus "this is your
usual plan, assumed here". Resolving the plan and pricing it are two jobs and
are two functions, orchestrated in `costOf`. `CollegeCost` carries the result as
a field — not re-derived by the renderer — as a sealed type with **three**
priced outcomes, because "we cannot price it" and "the school does not publish
it" are different sentences and only one of them is a claim about the school:

- `Chosen(ArrangementCost, LivingPlanSource)` — the arrangement is present and
  carries a total. The total is guaranteed by the type, so the payload's
  `total_per_year_usd` is unconditional and never a silent blank.
- `NoTotalHere(ArrangementCost, LivingPlanSource, NoTotalReason)` — the
  arrangement is present but has no total. It carries a reason code from its
  **own** vocabulary, `NoTotalReason`, never from the school-facing one:
  `awaiting_residency_answer` (a public school prices nothing until we are told
  the student's residency — the gap is ours, and one question closes it),
  `tuition_applicability_unknown` (we cannot tell which published price applies,
  also our gap, but no question the family can answer closes it), and
  `part_not_published` (the school does not publish every part). Its statement
  quotes the parts that exist, names what is missing, and forbids adding up what
  is there and calling it the total.
- `NotPricedHere(LivingArrangement, ArrangementGap)` — the arrangement is
  absent. This is the only case allowed to make a claim about what the school
  published, and it keeps RFC 149's `ArrangementGap` vocabulary
  (`no_on_campus_housing` vs `not_reported`) unextended.

`NotChosen` is the fourth case, for no plan resolved at all. `LivingPlanSource`
(`PER_COLLEGE` | `PROFILE_DEFAULT`) is the wire enum behind "you told us this
for this school" versus "this is your usual plan, assumed here".

**Every case states which case it is.** `ChosenLivingPlan` declares a
`LivingPlanPricing` code (`priced` | `no_total_here` | `not_priced_here` |
`not_chosen`) on the interface, so a fifth case cannot compile without one, and
the payload emits it in every branch. The coach is never asked to infer the case
from which keys are absent — which is how every other fact in this payload
already works. The two reason vocabularies keep separate wire keys: `reason` for
what the **school** published, `no_total_reason` for **our** silence. A reader
never has to know the case to know which words it is reading.

`ArrangementBasis` carries the same distinction typed rather than rendered: a
small `PlanSilence` union wraps either an `ArrangementGap` or a `NoTotalReason`,
so the code survives to the point where the sentence is formatted.

A school in `NoTotalHere` can therefore never sit under the comparison's "every
school is priced for this plan" statement; it is named with its own honest
phrase instead.

### Read path — rendering

`CollegeCostChatTool` emits one new key, `chosen_living_arrangement`, inside
`collegeObject` **after** the breakdown and **before** `putVintageLabels` — that
ordering is load-bearing and documented in place. It carries the wire name, the
spoken label, a statement, and either the total or a reason code. `offerCopy` is
exhaustive, so the new `PrecisionOffer` will not compile without its copy
constant. `moneyProfileObject` echoes the third field.
`CoachingService.composeSystem`'s money block gains a third `renderMoneyField`
line, so the coach sees the status every turn.

### Read path — comparison

`ArrangementBasis.of(colleges, moneyProfile)`; a sealed `ComparedLivingPlan`
mirroring `ComparedResidency`, so a plan is reachable only through an `Answered`
case; a `basis` code; three statement cases (D5). The renderer
(`arrangementBasisObject`) gains the code and the chosen keys and keeps the
absent-never-empty list convention.

### Copy and vocabulary

All new copy speaks brief 0003's money vocabulary: _tuition and fees_, _housing
and food_, _the published price_, _a financial aid offer_; no `room and board`,
`sticker` or `award`; every `subtract` preceded by `never`; no raw source codes.
`with_family` carries no housing-and-food line, and that is stated as data,
never rendered as a zero (brief 0003 D12).

## Files Modified

**New**

- `db/schema/0070.add-money-profile-living-plan.sql`
- `db/schema/0071.add-college-list-entry-living-plan.sql`
- `db/schema/0072.seed-coach-system-prompt-v14.sql`
- `db/src/main/kotlin/ed/unicoach/db/models/LivingArrangement.kt` (moved here)

**Changed — `:db`**

- `db/src/main/kotlin/ed/unicoach/db/models/MoneyProfile.kt`,
  `NewMoneyProfile.kt`, `MoneyProfileEdit.kt`, `MoneyProfileUpsert.kt`,
  `AnswerStatus.kt` (KDoc)
- `db/src/main/kotlin/ed/unicoach/db/dao/MoneyProfilesDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/CollegeListEntriesDao.kt` and the
  `CollegeListEntry` model siblings

**Changed — `:service`**

- `coaching/moneyprofile/MoneyProfileService.kt`,
  `coaching/MoneyProfileChatTool.kt`
- `coaching/collegelist/CollegeListChatTool.kt` and its service (the override)
- `coaching/CoachingService.kt` (money block)
- `coaching/costs/CostBreakdown.kt` (enum move + components lookup),
  `CostField.kt`, `CollegeCostService.kt`, `CollegeCostChatTool.kt`,
  `ComparisonBasis.kt`
- `service/src/main/resources/service.conf` (prompt pin → v14)

**Changed — edges**

- `rest-server/.../models/MoneyProfileResponse.kt`,
  `models/UpdateMoneyProfileRequest.kt`, `routing/MoneyProfileRoutes.kt`,
  `api-specs/openapi.yaml`
- the four college-list REST files carrying the per-college override
- `admin-web/.../resources/MoneyProfilesResource.kt`
- `service/.../coaching/StudentCollegeSelection.kt` and its one callee ripple,
  `coaching/admissions/CollegeAdmissionsService.kt` — the shared selection
  helper now yields the whole `CollegeListEntry`, because the override lives on
  the entry

No front-end change: `public-web` and `ios-app` carry zero money-profile
references.

## Implementation Plan

1. Migration for the money-profile columns + history table +
   `log_money_profile_version`; migration for the college-list override column +
   history table + `log_college_list_entry_version`.
2. Move `LivingArrangement` to `:db` with `fromValue`; leave the `CostField`
   mapping in `:service`. Compile `:service` green before touching anything
   else.
3. `:db` models + DAO, including the corrupt-value path.
4. `MoneyProfileService` + `MoneyProfileChatTool` + REST + admin; the
   college-list override write path (`CollegeListChatTool` + REST).
5. `CollegeCostService`: statuses, `chosenLivingPlanOf` (override → default →
   none), `CollegeCost` field, `PrecisionOffer.LIVING_PLAN`.
6. `CollegeCostChatTool`: the new key, offer copy, description block.
7. `ComparisonBasis`/`ArrangementBasis`: profile in, code + three statements
   out.
8. `CoachingService` money block line.
9. Coach prompt v14 seed + `service.conf` pin.
10. Tests throughout; full `nix develop -c bin/test` as the gate.

## Tests

**Changed**

`MoneyProfilesDaoTest` (round-trip, decline, re-answer, corrupt value, version
history), `MoneyProfileChatToolTest` (schema, parse ladder, conflict, echo),
`MoneyProfileRoutingTest`, `OpenApiMoneyProfileTest` (enum lists are
order-sensitive), `MoneyProfilesResourceTest` (redaction), `CoachingServiceTest`
(third money line), `CollegeCostServiceTest` (chosen / not-priced-here /
unanswered / declined, and the new precision offer), `CollegeCostChatToolTest`
(the new key, its position before the vintage labels, offer copy),
`ComparisonBasisTest` (the statement cases, the new codes), `CostsTestDb`,
`SystemPromptCatalogTest` (v14 = v13 prefix + one paragraph; v6 source-jargon
sentence and v7 money paragraph preserved byte-for-byte; copy hygiene on the
appended span).

**New**

- A binding test that the persisted enum's values are exactly the arrangement
  wire names the cost surfaces emit — one vocabulary, asserted.
- Resolution: override beats default; no override falls back to the default;
  neither set shows all three; an override cleared to `NULL` returns to the
  default. Ian's own case as a named test — a `with_family` default with an
  `on_campus` override on the far school, both totals correct in one comparison.
- The per-college override round-trips through the college-list tool and its
  version history.
- Set → change → decline → re-answer across sessions, entirely through the chat
  tool (the slice's first acceptance criterion).
- A student who says "I'd live at home" gets a total without the housing and
  food line in the same turn (the slice's first-session test).

**Must keep passing untouched — the regression signal**

`ForbiddenCostArithmeticTest` and the RFC 149 arrangement-invariant suite.
