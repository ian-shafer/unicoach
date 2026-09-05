# Repo surfaces for brief 0007 — explicit iOS profile view

**Scope:** household income band, state of residence, college list. Living plan,
dependency, and report sharing appear only as out-of-scope neighbours.

## Method

Static read of the working tree at `/Users/ian/Work/unicoach` (no branch change,
no code changed, no build or test run). Every claim below is cited as
`path:line`. Files read in full: the iOS navigation/settings/college-list
sources, `APIClient.swift`, `CollegeListClient.swift`, `StudentClient.swift`,
`MoneyProfileRoutes.kt`, the two money-profile REST models,
`CollegeListRoutes.kt`, `MoneyProfileService.kt`, `IncomeBand.kt`,
`AnswerStatus.kt`, `db/schema/0046.create-money-profiles.sql`, `bin/test-ios`.
Read in part (line ranges cited): `CoachingService.kt`, `CollegeCostService.kt`,
`CollegeListService.kt`, `api-specs/openapi.yaml`,
`db/data/money-vocabulary.json`.

**Not verified by execution** (called out again where it matters): the Jackson
behaviour for an _omitted_ JSON key on a nullable Kotlin parameter with no
default. I did not run the server or the suites.

---

## 1. iOS app: screens and navigation

### Navigation structure (RFC 117)

There is exactly **one `NavigationStack` for the authenticated tree**, owned by
`AuthenticatedRootView` (`ios-app/UnicoachiOS/AuthenticatedRootView.swift:11`,
stack at `:148`). The root of that stack is **always a fresh conversation**
(`:265-274`); every other screen is a _pushed destination_, driven by a private
`Destination` enum with exactly four cases
(`AuthenticatedRootView.swift:40-45`):

```
case conversation(Conversation)   // one existing thread
case conversations                // ConversationListView
case collegeList                  // CollegeListView
case settings                     // SettingsView
```

The destination builder is `AuthenticatedRootView.swift:319-349`. Pushes come
only from the slide-over drawer, via `push(_:)` (`:361-364`), which closes the
drawer and appends to `path`.

The drawer itself is `SlideOverMenu`
(`ios-app/UnicoachiOS/SlideOverMenu.swift:21`). Its layout is: **New
conversation** (the one filled control, `:107-114`), a "Recent" list of at most
3 conversations (`:76-103`, `:117-141`), then a pinned footer of three rows —
**My colleges** (`:179-181`), **All conversations** (`:184-186`), a divider, and
**Settings** (`:188-190`). The drawer **must not scroll**; the recents count
degrades 3 → 2 → 1 → 0 via `ViewThatFits` so the footer always survives
(`:46-59`).

Other screens outside this stack: `VerificationRequiredView` keeps its own stack
as a sibling auth state (`AuthenticatedRootView.swift:8-10`); `OnboardingView`
(student-profile creation) and the auth flow are separate root states.
Subscription surfaces are **sheets** presented from the root, not pushes —
`PaywallView` / `SubscriptionView` via `.sheet(item:)`
(`AuthenticatedRootView.swift:187-194`).

### Where a profile screen would attach

Two mechanical options, both small:

1. **A fifth `Destination` case** plus a builder arm
   (`AuthenticatedRootView.swift:40-45` and `:319-349`), pushed from a new
   drawer footer row next to "My colleges" (`SlideOverMenu.swift:179-190`, using
   the shared `menuRow` helper at `:195-218`). This is the "My colleges"
   precedent exactly (RFC 137).
2. **A section inside `SettingsView`** (see below). Cheaper, but Settings is
   currently _account_ chrome, not student data.

There is **no tab bar anywhere in the app**; a "profile tab" would be a new
navigation idiom, not an extension of one. The drawer footer is the app's
existing index of durable non-chat surfaces (`SlideOverMenu.swift:3-15`,
`:177-178`).

### What `SettingsView` contains today

`ios-app/UnicoachiOS/SettingsView.swift:46-104`, in order:

- **identity** — "Signed in as", name, email, read-only (`:124-141`).
- **Appearance** — a 3-way `SegmentedSelector` bound to `@AppStorage`
  (`:110-122`, `:28`). This is the existing pattern for a small
  closed-vocabulary picker on this design system.
- **`SubscriptionSection`** — the coaching meter + subscription state (`:53`).
- **Change Email** button, opening a sheet (`:56-63`, `:88-103`).
- **Log Out** button (`:65-78`).

No student-data fields at all. No money profile, no residency, nothing from
`/students/me`.

### College-list screens, field by field

`CollegeListView` (`ios-app/UnicoachiOS/CollegeListView.swift:7`):

- Lists entries as cards: college name + a status pill + the first line of
  `reasons` (`:140-161`, `:178-184`).
- Toolbar `+` → `AddCollegeView` (`:33-43`, `:210-212`).
- Row tap → `CollegeEntryDetailView` (`:113-119`).
- Swipe → Remove, gated by a confirmation dialog (`:126-133`, `:49-63`); the
  dialog copy says "Its status and reasons are discarded."
- `.task { await viewModel.refresh() }` on appear — this is what picks up an
  entry the **chat tool** moved (`:44-48`).
- Empty state: "No colleges yet. Add one, or ask your coach." (`:186-208`).

`AddCollegeView` (`ios-app/UnicoachiOS/AddCollegeView.swift:7`): one debounced
name field (`:39-48`), result rows, tap-to-add. The add sends **only
`collegeId`** — status defaults server-side to `considering`
(`CollegeListClient.swift:30-40`; `CollegeListModels.swift:68-74`).

`CollegeEntryDetailView` (`ios-app/UnicoachiOS/CollegeEntryDetailView.swift:7`)
— the complete editable surface:

| Field                    | Control                                      | Evidence                |
| ------------------------ | -------------------------------------------- | ----------------------- |
| `status`                 | 4-way `SegmentedSelector`                    | `:53-64`                |
| `reasons`                | multiline `TextField`, client-capped at 2048 | `:69-90`, `:17`         |
| `supportingObservations` | **read-only** quotes                         | `:92-109`               |
| `livingPlan`             | **absent** — not shown, not editable         | (no occurrence in file) |
| Save                     | one filled button, enabled only when dirty   | `:111-127`              |

The view model is `CollegeListViewModel`
(`ios-app/UnicoachiOS/CollegeListViewModel.swift:23`): states
`loading / loaded / empty / failed` (`:7-12`), a separate `actionError` channel
(`:40`), OCC handling that reloads on `version_conflict | conflict | not_found`
and escalates `student_profile_required` (`:133-143`), and a `MutationOutcome`
enum that tells the detail screen to pop (`:28-32`).

---

## 2. The iOS API client

`ios-app/UnicoachiOS/APIClient.swift:4` is the single transport owner:

- Verbs: `post` / `get` / `get(query:)` / `delete` / `delete(query:)` / `patch`
  (`:76-129`). **There is no `put` method.** A money-profile write needs one
  added (~4 lines, mirroring `patch` at `:127-129`).
- Auth is **cookie-based session** — the client sends no token; it only adds
  `X-Unicoach-Client-Key` when the bundle baked one in (`:157-159`, `:9-13`).
- Errors: `decode(data:response:expectedStatus:)` throws a decoded
  `ErrorResponse` with `status` attached on any status mismatch (`:131-142`,
  `:242-253`); transport failures map to `TIMEOUT` / `NETWORK_ERROR`
  (`:232-240`); an unparseable error body becomes `SERVER_ERROR`.
- Dates decode ISO-8601 with or without fractional seconds (`:27-56`) — the
  money profile's `createdAt` / `updatedAt` would decode with no change.
- The known client-side error vocabulary is
  `ios-app/UnicoachiOS/Models.swift:80-107`: `student_profile_required`,
  `version_conflict`, `conflict`, `not_found`, `coaching_budget_exhausted`,
  `TIMEOUT`, `NETWORK_ERROR`, `SERVER_ERROR`, `DECODE_ERROR`, plus an unknown
  fallback.

Per-surface clients are thin bindings over `APIClient`: `StudentClient`
(`StudentClient.swift:9`), `CollegeListClient` (`CollegeListClient.swift:15`),
each behind a `…Protocol` for mocking. They are built once in `AppViewModel`
over a shared `APIClient` (`AppViewModel.swift:92`, `:94`).

**Does iOS know about `/api/v1/students/me/money-profile`?** **No.**
`grep -rn "money-profile\|MoneyProfile" ios-app/` returns **zero matches** — no
client, no model, no view, no test, no fixture. The iOS app has never read or
written the money profile. `StudentClient` reaches only `/api/v1/students` and
`/api/v1/students/me` (`StudentClient.swift:19`, `:26`), and the student model
it decodes carries only the graduation date (`Models.swift:151-161`).

---

## 3. REST contracts

### `GET /api/v1/students/me/money-profile`

`rest-server/src/main/kotlin/ed/unicoach/rest/routing/MoneyProfileRoutes.kt:54-67`.

- `200` + `{"profile": {…}}` when a row exists (`:60`).
- **`404` `{"code":"not_found","message":"No money profile yet"}` before the
  first write** (`:63-65`). This is the normal state for a brand-new student — a
  profile screen must treat 404 as "all three fields unanswered", not as an
  error. The `StudentClient.fetchProfile()` precedent already does this shape
  (`StudentClient.swift:27-29`).
- `401` unauthenticated (`:55`); `409 student_profile_required` when the user
  has no student row (`:56`, spec `api-specs/openapi.yaml:908-913`).

### Wire shape (`MoneyProfileResponse.kt:5-23`, spec `openapi.yaml:1786-1825`)

```json
{
  "profile": {
    "incomeBandStatus": "unanswered|answered|declined",
    "incomeBand": "under_30k|30k_to_48k|48k_to_75k|75k_to_110k|over_110k|null",
    "residencyStatus": "unanswered|answered|declined",
    "residencyState": "CA|null",
    "livingPlanStatus": "unanswered|answered|declined",
    "livingPlan": "on_campus|off_campus|with_family|null",
    "version": 3,
    "createdAt": "…",
    "updatedAt": "…"
  }
}
```

**Tri-state:** `AnswerStatus` is `unanswered | answered | declined`
(`db/src/main/kotlin/ed/unicoach/db/models/AnswerStatus.kt:11-17`). The value is
non-null **exactly when** the status is `answered` — enforced by DB CHECKs
(`db/schema/0046.create-money-profiles.sql:52-55`) and restated in the model doc
(`db/.../models/MoneyProfile.kt:5-11`). So a screen must render three states per
field, and can never show a stale value behind a decline.

Note the response carries **three** fields only. The DB row and domain model
also carry `dependency` / `dependencyStatus` (RFC 159, `MoneyProfile.kt:30-31`),
and the service update type has a `dependency` member
(`MoneyProfileService.kt:51`), but neither the REST request
(`UpdateMoneyProfileRequest.kt:9-19`) nor the response
(`MoneyProfileResponse.kt:13-23`) nor the projection
(`MoneyProfileRoutes.kt:205-216`) exposes it. Dependency is **not reachable over
REST today** — out of scope, but worth knowing the surface is 3-field.

### `PUT /api/v1/students/me/money-profile`

`MoneyProfileRoutes.kt:69-91`. Body (`UpdateMoneyProfileRequest.kt:9-19`) — nine
optional keys, three per field:

```
incomeBand / incomeBandDeclined / incomeBandClear
residencyState / residencyDeclined / residencyClear
livingPlan / livingPlanDeclined / livingPlanClear
```

- **Omitted field = untouched.** Only fields the body names are written
  (`UpdateMoneyProfileRequest.kt:3-8`; `MoneyProfileUpdate` members are nullable
  and `null` means "no update", `MoneyProfileService.kt:29-52`). A profile
  screen can therefore PATCH-like one field at a time with a PUT.
- **At most one of value / declined / clear per field**; two is a `400` naming
  that field (`MoneyProfileRoutes.kt:136-139`, `:160-163`, `:184-187`).
- **Every invalid field reports at once** in one `400` with a `fieldErrors` list
  — never just the first (`:105-120`).
- Value → `FieldUpdate.Set`, declined → `Decline`, clear → `Clear` (`:147-154`);
  folded to the DAO's `Answer / Declined / Cleared`
  (`MoneyProfileService.kt:128-134`).
- `200` returns the **full post-write profile** (`:82`).
- `404` only for the "student row vanished mid-write" race (`:85-89`).

**Optimistic concurrency: there is none, deliberately.** "There is no
caller-supplied OCC version: each field update is an absolute statement of that
field's new state, and untouched fields are kept by the DAO's apply-or-keep
column semantics" (`MoneyProfileService.kt:78-82`). The write is one atomic
`INSERT … ON CONFLICT DO UPDATE`, so two concurrent first writes cannot race
into a uniqueness error (`:74-78`). `version` is returned for display/history
only; sending it back buys nothing. **Consequence for the brief:** a profile
screen never has to handle `409 version_conflict` on money fields — unlike the
college list, which does (`CollegeListRoutes.kt:75-77`).

### College list (for contrast)

`CollegeListRoutes.kt:48-62`: `POST`/`GET` on `/college-list`,
`GET`/`PATCH`/`DELETE` on `/college-list/{entryId}`.

- `POST` → `201`; `409 conflict` when already on the list (`:132`, `:139-141`);
  `404` for an unknown college (`:135-137`); `400` on bad reasons (`:143-145`).
- `PATCH` requires `version` in the body and answers `409 version_conflict`
  (`:199`, `:216-218`).
- `DELETE` requires `?version=` and answers `204` / `409` (`:235-253`).
- Entry projection: `PublicCollegeListEntry`
  (`rest-server/.../models/CollegeListEntryResponse.kt:10-22`) — includes
  `collegeName` and `livingPlan`.

### What an iOS profile screen could do today with **no server change**

Can do:

- Read all three money fields with their tri-state status (`GET`, treating 404
  as "row not yet created").
- Set, decline, or clear the income band, from the fixed 5-value vocabulary.
- Set, decline, or clear the residency state (case-insensitive 2-letter input;
  the server uppercases).
- Write one field without disturbing the others; no version to carry.
- Do everything the college-list screens already do (list / add / restatus /
  reasons / remove), because those screens already exist.

Cannot do without a server change:

- **Get the income-band dollar ranges from the server.** The labels
  (`"$30,001 to $48,000"`) live only in Kotlin (`IncomeBand.kt:24-36`) and the
  seed file; the REST surface returns the bare slug. An iOS picker must either
  hardcode the five ranges (a second home for RFC 142's copy — a real risk) or a
  small vocabulary endpoint must be added.
- **Get a list of valid state codes.** The 59-code set is server-side only
  (`MoneyProfileService.kt:145-150`). iOS either hardcodes a picker or
  free-texts and lets the `400` teach it.
- **Read or write dependency status** (not on the REST surface — out of scope
  anyway).
- **Edit the per-college living plan from the list screen** — the field exists
  on the wire but is out of scope here (see §5's warning, which is _not_
  optional).
- Client work still needed regardless: `APIClient` has **no `put`**
  (`APIClient.swift:76-129`), and there is no `MoneyProfileClient`, model, or
  mock.

---

## 4. The coach side

`service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt`.

- `activeMoneyProfile(session, studentId)` (`:830-836`) does a **live DAO read**
  of the student's active row, returning `null` before the first write.
- It is called on **`startConvo`** (`:272`) and on **`postTurn`** (`:337`).
  `postTurn` is every mid-conversation turn, and the comment is explicit: "The
  money-profile block (RFC 134) IS composed on every turn: what may be used and
  what must not be re-asked applies mid-conversation too" (`:332-337`).
- `composeSystem` (`:845-905`) appends the block to the prompt body; with no
  profile row the prompt is returned verbatim (`:851`).

**So: yes.** A profile edit made from a screen is visible to the coach on the
**next turn of the same conversation**, not merely the next conversation. There
is **no caching** — `grep -n "cache" CoachingService.kt` returns nothing, the
read runs inside the per-turn transaction, and a DB failure fails the turn
rather than silently dropping the declined-field guard (`:826-829`). The only
latency is "the student must send another message"; nothing pushes the change
into an in-flight streamed reply.

**What the block literally says** (`:868-901`):

> Money profile (use answered values; a declined field was asked and declined —
> never re-ask it unless the student reopens the topic; an unanswered field is
> still open):
>
> - household income band: `answered ($30,001 to $48,000)` | `declined` |
>   `unanswered`
> - state of residency: …
> - usual living plan: …

Per-field rendering is `renderMoneyField` (`:914-937`): `answered (value)` /
`declined` / `unanswered`. The income band is rendered as its **dollar range**,
never the `over_110k` code (`:874-884`, `IncomeBand.kt:14-21`) — RFC 142's rule,
because the coach may read the block aloud. An `answered` status with a null
value throws `CorruptPersistedValueException` rather than being rendered
(`:921-928`).

---

## 5. What breaks or gets weird on CLEAR / DECLINE from a screen

### Decline is permanent-ish and suppresses upgrade offers

`PrecisionOffer` (`service/.../costs/CollegeCostService.kt:593-657`) — the
in-answer "want a sharper number?" invitations — are each keyed off
`AnswerStatus.UNANSWERED`, **not** off a missing value, and the enum doc says
why: "an offer derived from a missing value would re-raise a closed topic on
every cost answer, because a decline leaves the value missing too" (`:582-586`).

Consequences a profile screen must own:

- **Decline income band** → `INCOME_BAND` offer stops applying (`:652-657`); the
  coach stops offering to make net price family-specific, and the block tells it
  never to re-ask (`CoachingService.kt:871-872`).
- **Decline residency** → `RESIDENCY` offer stops applying (`:615-648`), and at
  a **public** college the tuition line stays null, so no arrangement carries a
  total, so the **living-plan offer also stops applying** — a documented
  knock-on (`:676-680`). One decline from a screen silently removes two coach
  affordances.
- **Clear** puts a field back to `unanswered` (`MoneyProfileService.kt:133`,
  `MoneyProfileUpsert.FieldWrite.Cleared`), which **re-arms** the offers and
  makes the coach treat the topic as open again (`CoachingService.kt:872`). So
  "Clear" is the undo for an accidental decline — and it is the only one; there
  is no history-restoring path in the REST surface.

The weirdness worth designing around: from chat, a decline is something the
student _said_ ("I'd rather not say"), so "never re-ask" is honest. From a
screen, a tapped "Prefer not to say" produces the identical permanent state with
none of the conversational context, and the coach will then never raise the
topic again on its own. The screen therefore needs to make the difference
between **clear** ("I'll answer later") and **decline** ("stop asking") legible,
because the server treats them very differently and the coach obeys.

Nothing else breaks: the money PUT has no OCC, so a screen write cannot lose a
race against a chat-tool write in a confusing way — last write per field wins,
and untouched fields are preserved (`MoneyProfileService.kt:78-82`).

### A live college-list hazard the brief should know about

`UpdateCollegeListEntryRequest` on the server has **`livingPlan: String?` with
no default** and the OpenAPI spec lists it as **required**, with the reason
spelled out: "null clears the override back to their usual plan, so a client
that omitted it would delete a fact it never meant to touch"
(`rest-server/.../models/UpdateCollegeListEntryRequest.kt:7-15`;
`api-specs/openapi.yaml:1856`, `:1869-1876`;
`service/.../collegelist/CollegeListService.kt:141-152`).

The iOS request struct **omits `livingPlan` entirely**
(`ios-app/UnicoachiOS/CollegeListModels.swift:78-82`), and the iOS entry model
does not even decode it (`:36-44`). So today, saving status or reasons from
`CollegeEntryDetailView` sends a body with no `livingPlan` key.

**Unverified but strongly indicated:** with the Jackson Kotlin module a missing
nullable parameter that has no default deserializes to `null`, which the service
treats as _clear the override_. If so, an iOS Save on the detail screen wipes a
per-college living plan the student set in chat, silently. I did **not** run the
server to confirm this, and there is no test covering the omitted key — the
existing test only covers an _explicit_ null
(`rest-server/src/test/kotlin/ed/unicoach/rest/CollegeListRoutingTest.kt:304`,
`:349-350`). **This should be confirmed with one test before the brief relies on
it either way.** It is out of the brief's stated scope (living plan) but it is a
live data-loss path on exactly the screen the brief will touch.

---

## 6. iOS test setup

**Command:** `bin/test-ios` — and it must run **outside** the Nix dev shell:

```sh
bin/test-ios              # default target: simulator
bin/test-ios simulator -- -only-testing:UnicoachiOSTests/CollegeListViewModelTests
```

Evidence: `bin/test-ios:1-13` (runs under system Xcode, not the dev shell),
`:70-74` (it _refuses_ to run inside `nix develop`), `:100-120` (target env,
per-checkout simulator via `bin/ios-device`, `xcodebuild test` with
`-derivedDataPath ios-app/build/DerivedData`). Everything after `--` is passed
verbatim to `xcodebuild` (`:29-31`, `:50-53`). This is the one exception to the
repo's `nix develop -c` rule.

**ViewModel test pattern** — `@MainActor final class …Tests: XCTestCase`, a
protocol mock built in `setUp`, a `makeViewModel()` helper closing over a
callback counter, then `await viewModel.<method>()` and assert on the published
state. See `ios-app/UnicoachiOSTests/CollegeListViewModelTests.swift:4-59`.

The mock shape is "configure per-call `Result`s, read back captured calls":
`ios-app/UnicoachiOSTests/MockCollegeListClient.swift:6-45`. A new
`MockMoneyProfileClient` would follow it line for line.

There is also a snapshot suite (`SnapshotTests.swift`, `SnapshotHost.swift`) —
new screens are expected to have snapshot scenes; `AddCollegeView` carries a
dedicated snapshot-seam `init` for it
(`ios-app/UnicoachiOS/AddCollegeView.swift:30-35`).

Note: `ios-app/UnicoachiOS.xcodeproj/project.pbxproj` still lists sources
explicitly (`CollegeListView.swift` appears 4 times in it), so new files must be
added to the project file, not just dropped in the directory.

---

## 7. Residency and income-band vocabularies

### Residency state

- **Shape:** a 2-letter USPS code, uppercase.
- **Authoritative validation:** `MoneyProfileService.parseResidencyState`
  (`service/.../moneyprofile/MoneyProfileService.kt:152-158`) — `trim()`,
  `uppercase()`, then membership in a **closed 59-code set**: the 50 states, DC,
  and AS FM GU MH MP PR PW VI (`:136-150`). The doc is explicit that
  _membership_, not a two-letter shape, is the boundary; the set is the College
  Scorecard `STABBR` domain.
- **Input is case-insensitive** (the `uppercase()` above), so a screen may send
  `"ca"`.
- The REST layer only wraps this and supplies the wording "Must be a two-letter
  US state postal code, got: $raw" (`MoneyProfileRoutes.kt:164-169`).
- **DB backstop:**
  `CHECK (residency_state IS NULL OR residency_state ~
  '^[A-Z]{2}$')`
  (`db/schema/0046.create-money-profiles.sql:46-47`) — coarser than the code set
  on purpose.
- The OpenAPI schema documents `pattern: '^[A-Z]{2}$'` on output
  (`api-specs/openapi.yaml:1800-1803`) — it does **not** publish the 59-code
  enum, so a generated client learns nothing about membership.

### Income band

Five values, and they are still owned by the **Kotlin enum**
`db/src/main/kotlin/ed/unicoach/db/models/IncomeBand.kt:12-37`:

| value         | dollar range (`bracket`) | Scorecard |
| ------------- | ------------------------ | --------- |
| `under_30k`   | $0 to $30,000            | NPT41     |
| `30k_to_48k`  | $30,001 to $48,000       | NPT42     |
| `48k_to_75k`  | $48,001 to $75,000       | NPT43     |
| `75k_to_110k` | $75,001 to $110,000      | NPT44     |
| `over_110k`   | $110,000 or more         | NPT45     |

The `bracket` string is declared as "the one home for display copy … Phrased to
read inside a sentence" (`IncomeBand.kt:14-21`), and the enum also owns the band
→ `net_price_per_year_income_qN_usd` selection (`:44-88`).

**After RFC 158** the same five bands are additionally **data**: an authored
seed file `db/data/money-vocabulary.json` (section `income_bands`, each row
`slug`, `min_usd`, `max_usd`, `bracket_label`, `sort_order`) loaded into the
`income_bands` table by `MoneyVocabularyLoader`
(`college/src/main/kotlin/ed/unicoach/college/MoneyVocabularyLoader.kt:29-45`;
table at `db/schema/0083.create-canonical-money-tables.sql:145-156`; row model
`db/src/main/kotlin/ed/unicoach/db/models/CanonicalMoneyRows.kt:40`).

Crucially, the loader **cross-validates both ways**: "the parsed vocabulary must
agree with the Kotlin enums … a seed row with no enum value, or an enum value
with no seed row, is fatal … the income-band labels are each pinned to their
enum's own declaration" (`MoneyVocabularyLoader.kt:38-45`). So the table is a
second, verified copy — not a replacement. The `money_profiles.income_band`
column is still `TEXT` + `CHECK IN (...)` listing the five slugs literally
(`db/schema/0046.create-money-profiles.sql:41-43`), i.e. **not** an FK to
`income_bands`.

**Implication for the brief:** the dollar ranges exist in three places
server-side (enum, seed JSON, `income_bands` table) and are cross-checked. A
fourth hardcoded copy in Swift would have **no** such check. If the profile
screen shows dollar ranges — and it should, since the coach is required to speak
in ranges rather than slugs (RFC 142, `CoachingService.kt:874-876`) — the honest
options are a small vocabulary endpoint, or an accepted-and-tested Swift
constant. That is a real decision for the spec, not a detail.

---

## Blunt list of what I could not determine

1. **Jackson's handling of an omitted `livingPlan` key** on
   `UpdateCollegeListEntryRequest` — the §5 hazard. Strongly indicated by the
   code and the spec, not proven; no test covers omission.
2. **Whether any RFC already designs this screen.** I read the code, not the
   whole `rfc/` corpus; RFC 117 (navigation), 134 (money profile), 137 (college
   list), 142, 152, 158, 159 are the ones the code cites.
3. **Snapshot-test requirements for a new screen** — I saw the suite and the
   seam `init` convention but did not read `SnapshotTests.swift` in full, so I
   cannot state what a new screen owes it.
4. **Whether `bin/test-ios` currently passes** — I ran nothing.
