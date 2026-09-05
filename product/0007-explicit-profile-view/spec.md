# Brief 0007 — spec and slices

**Handle:** `profile`. Slice IDs are permanent and never renumbered.

Gate 1 approved 2026-09-04 (D1-D12, D5 and D9 amended by Ian). This file is the
gate-2 artifact: the slice order, each slice as a self-contained `/ship`
instruction, and the gate-2 decisions.

**GATE 2 APPROVED by Ian, 2026-09-05 — all five defaults (G1-G5), no
amendments.** The slices are dispatchable. Ian dispatches each one from its own
Prime Agent session (`start work on profile/01/served-vocabulary`), one session
per slice, and is /ship's approval gate in each.

## DISCOVER — where the brief's assumptions were wrong

Grounded against the code by `research/repo-surfaces.md`. Corrections that
change the slicing:

1. **The money-profile server is complete for both in-scope fields.** `GET` (404
   before the first write) and an idempotent 9-key `PUT` — value / declined /
   clear per field — with **no optimistic concurrency by design**
   (`MoneyProfileService` KDoc). So there is no `409` for the screen to handle,
   unlike the college list, whose `PATCH`/`DELETE` do carry a version.
2. **iOS has no money-profile code at all** — zero grep matches over `ios-app/`.
   And `APIClient` has **no `put()`** (`APIClient.swift:76-129`); that is ~4
   lines.
3. **Navigation is one `NavigationStack`, no tab bar**
   (`AuthenticatedRootView.swift:148`), with four pushed `Destination` cases
   built at `:319-349`, each reached from a slide-over menu row
   (`SlideOverMenu.swift:179-190`). A profile screen is a **fifth case plus one
   row** — the "My colleges" precedent exactly.
4. **A decline is heavier than it looks.** `PrecisionOffer` keys off
   **UNANSWERED**, not off a missing value (`CollegeCostService.kt:582-586`), so
   declining income retires the income invitation and declining residency
   retires the residency invitation **and**, at public colleges, the living-plan
   invitation (`:676-680`). Clearing is the only re-arm. This is why D5 requires
   the two controls to read differently and requires a decline to be undoable.
5. **The income-band dollar ranges have no wire representation.** REST returns
   the slug only; the ranges live in `IncomeBand.kt:24-36` and, since RFC 158,
   in `db/data/money-vocabulary.json` -> the `income_bands` table,
   cross-validated against the enum. RFC 142 forbids speaking a slug to a user,
   so the screen cannot render a picker from what the API returns today. Hence
   slice `profile/01`.
6. **Residency is a closed 59-code set** validated in
   `MoneyProfileService.parseResidencyState:136-158` (case-insensitive; the DB
   backstop is only `^[A-Z]{2}$`), and the set is **not published in OpenAPI**.
7. **iOS tests refuse to run inside the Nix dev shell** (`bin/test-ios:70-74`) —
   run `bin/test-ios` directly. New Swift files must be added to
   `project.pbxproj`. The house pattern is `@MainActor XCTestCase` + a protocol
   mock + `makeViewModel()` (`CollegeListViewModelTests.swift:4-59`).

## Slices

### `profile/01/served-vocabulary`

**Needs:** — (nothing; independent of every other slice).\

**What and why.** The screen must never show a user a slug (RFC 142). Serve the
two closed vocabularies the profile screen needs so the client holds no
unvalidated fourth copy of the dollar ranges: **income bands** (slug + spoken
label + dollar range, in display order) and the **59 residency codes** (code +
name, in display order).

**Foundations.** RFC 158's `income_bands` table and `MoneyVocabularyLoader`;
`IncomeBand.kt`; `MoneyProfileService.parseResidencyState`'s code set;
`MoneyProfileRoutes` for the auth umbrella and the response idiom.

**Decided here.** The vocabulary is served, not duplicated in Swift (D6). Values
carry their spoken form, never a bare slug. The residency set served is exactly
the set the write path validates — one source, or a test proving the two agree.

**Left to /ship's design phase.** The endpoint's shape and URL (a dedicated
vocabulary route vs. extending the money-profile `GET` payload), whether the
ranges are read from the RFC 158 table or the Kotlin enum, and caching.

**Acceptance.** A test proves the served income-band set equals `IncomeBand`'s
entries in order, and the served residency set equals the set
`parseResidencyState` accepts — so a code the client can pick can never be a
code the server rejects. No new table, so no DDL gate. Reachability: not
user-visible alone; it is consumed by `profile/02` in the same wave.

### `profile/02/your-details-screen`

**Needs:** BLOCKS profile/01/served-vocabulary — the picker renders the served
labels and dollar ranges, and cannot invent them client-side without violating
D6.\

**What and why.** The brief's whole point: a family can see and change their
household income band and state of residence **without talking to the coach**. A
fifth pushed `Destination` plus one slide-over menu row (D2), holding those two
fields only (D3), with a link out to the existing college-list screen.

**Foundations.** `GET`/`PUT /api/v1/students/me/money-profile` (RFC 134) —
complete, no server change expected; `AuthenticatedRootView`'s `Destination`
enum; `SlideOverMenu`'s footer rows; `CollegeListViewModel` + its protocol-mock
test pattern; `APIClient` (needs a `put()`).

**Decided here (the product judgement, not /ship's to re-open).**

- **Three states, always legible** (D5): _not answered_ / _answered (value)_ /
  _you chose not to say_. Never an empty field standing in for a decline.
- **Both controls, with the consequence named** (D5): "remove my answer" (clear
  -> unanswered) and "prefer not to say" (decline). The decline control says
  what it costs — the coach stops inviting that question.
- **A decline is always undoable, both ways** (D5, Ian's amendment): a declined
  field is fully editable — answer it outright, or remove the answer and return
  it to unanswered, which re-arms the coach's invitation. Never greyed out,
  never a one-way door. No server work: `upsert` is an absolute per-field
  statement and the declined -> clear -> unanswered path already passes a test
  (`MoneyProfileRoutingTest.kt:226-233`).
- **404 is "nothing answered yet"** (D4), never an error state.
- **Saving says so on the screen** (D7) — a terse in-surface confirmation. **The
  coach never mentions the edit in chat**; the coach is already given the
  current values on every turn (`CoachingService.activeMoneyProfile`) and
  already names the basis in cost answers (RFC 145/157). Nothing to build there,
  and nothing to add to the prompt.
- **Never a gate** (D8): reachable from the menu, never interposed, never
  blocking, no onboarding form, no completion meter.

**Left to /ship's design phase.** Layout and controls, the menu row's wording,
offline/failure copy, and whether the two fields save individually or together.

**Acceptance.** Reachability named: slide-over menu row -> profile screen.
ViewModel tests over a protocol mock cover all three states per field, both undo
directions, the 404-as-unanswered read, and a failed save that does not silently
drop the user's input. Run `bin/test-ios` (NOT under `nix develop`). No DDL.

**First-session test.** A brand-new user opens the menu, sees the row, opens it,
and sees two questions in plain English with dollar ranges and no jargon — every
one of them optional, none of them blocking the chat they came for.

### `profile/03/list-screen-parity`

**Needs:** — (no slice edge; this slice's subject is the request shape changed
by brief 0007 D9's fix, which lands as its own RFC OUTSIDE this brief and so is
not a slice the board can name. Do not start this slice until that RFC is on
`main`; the ledger below records it when it lands.)\
_Unblocked 2026-09-05: **RFC 164** landed (`main@8989cfd0` + `112eb660`), the
REST three-state `livingPlan` fix from brief 0007 D9. The `Status: DEFERRED`
line was removed then, as its own text required._

**What and why.** The college list keeps its own screen and is not duplicated
into the profile (D3). What it needs is not a new door but an honest one: an
audit of the iOS list screen against everything `update_college_list` can do,
and closure of the gaps the audit finds. The known one is the `livingPlan` key —
after the D9 fix an omitted key is safe, and the screen should additionally be
able to send an explicit clear rather than being unable to express it.

**Foundations.** `CollegeListView` / `AddCollegeView` / `CollegeEntryDetailView`
(RFC 137); `CollegeListModels.swift`; `update_college_list` (RFC 136) and
`CollegeListChatTool`'s `LivingPlanUpdate`; the D9 RFC.

**Decided here.** Per-college living-plan **editing UI** is not in this brief
(scope, D3) — the slice makes the client's writes non-destructive and
expressible, it does not add a living-plan picker. Any other gap the audit finds
is **reported**, not silently absorbed: a gap outside income / residency / list
becomes a Backlog line or a new slice, never a scope creep.

**Acceptance.** A test proves an iOS-shaped Save preserves a chat-set living
plan, and that the client can send an explicit clear. The audit's findings are
written into the brief's ledger section. Reachability: the existing list screen.
No DDL.

## Gate 2 decisions

- **G1. Three slices, in this order:** `profile/01/served-vocabulary`,
  `profile/02/your-details-screen`, `profile/03/list-screen-parity`. **Default:
  yes.**
- **G2. The aha lands at `profile/02`** — that is the slice where a family can
  fix their own facts without the coach. `01` is substrate in the same wave;
  `03` is honesty debt on a door that already exists. **Default: yes.**
- **G3. `profile/01` and `profile/02` are one wave and may be run back to back;
  `profile/03` waits on the D9 fix landing.** **Default: yes.**
- **G4. No new tables in this brief.** Nothing here reaches Ian's DDL gate (0001
  D10). If a `/ship` run finds it needs a table, it stops and comes back here.
  **Default: yes.**
- **G5. The success criteria claim correction and control, not trust or
  retention** (D12) — the external evidence for a trust lift does not exist, and
  `research/dual-entry-patterns.md` says so plainly. **Default: yes.**
