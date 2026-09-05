# Brief 0007 — an explicit profile view

**Slice ID handle:** `profile`

    Status:
      Phase:   EXECUTE — both gates approved; slices dispatched one per session;
               D9's fix shipped as RFC 164. Two of the three slices are done
               (see the ledger); only your-details-screen remains, and it is
               the slice where the value becomes visible.
      Gate 1:  APPROVED by Ian 2026-09-04 — all 12 defaults, with D5 and D9
               amended by Ian before approval (both amendments are IN the
               decision text below, not appended after it)
      Gate 2:  APPROVED by Ian 2026-09-05 — all 5 defaults G1-G5, no amendments
      Ledger:
        D9 fix LANDED as RFC 164 (main@8989cfd0 + 112eb660, 2026-09-05) — an
          omitted `livingPlan` now KEEPS the stored value; `livingPlanClear:
          true` clears it; both together is a 400. Shared LivingPlanUpdate moved
          into CollegeListService, deleting the chat tool's echo-the-current-
          value workaround. Non-breaking for the shipped iOS build. Gate: 2666
          JVM tests, 0 failures; 552 shell assertions. Landed OUTSIDE the brief
          by D9, and it unblocks profile/03/list-screen-parity.
        profile/01/served-vocabulary LANDED as RFC 165 (main@f837dc81 +
          dfc3b539, 2026-09-05) — GET /api/v1/vocabularies: ONE registry
          endpoint, not a money-profile-only route (Ian widened it at the /ship
          gate: "this is a pattern we will need across many forms"). Every entry
          is `value` + `label`; extras allowed, never fewer. Registers
          `income_bands` (from IncomeBand.entries) and `residency_states` (59
          codes — literally MoneyProfileService's accepted set, labelled from
          us_states), so a code the client can pick can never be a code the
          server rejects. Admission rule written into the RFC: closed, small,
          user-independent, display-facing with a real label. Session auth, no
          student-profile gate. No table, no migration. Gate: 2686 JVM tests, 0
          failures; 39 review lenses, 19 findings, 18 applied. Substrate — no
          user-visible change until profile/02.
        profile/02/your-details-screen — nothing landed; a live run stamped
          to it (pipeline/rfc-171) was in flight on 2026-09-05
        profile/03/list-screen-parity LANDED as RFC 168 (main@a3d851e7 +
          9f7e7809, 2026-09-05) — the iOS client now STATES the living-plan
          write contract instead of satisfying it by accident.
          UpdateCollegeListEntryRequest carries a three-state
          LivingPlanUpdate (keep / set / clear) with a hand-written
          encode(to:): .keep emits neither wire key, so a Save cannot destroy
          a plan the coach set; .set emits `livingPlan`; .clear emits
          `livingPlanClear: true`. The server's 400 for both keys together is
          unrepresentable in the type. `reasons` keeps clear-by-omission — the
          shipped Clear button (RFC 164 D4) — asserted in all three states. A
          new server test sends the .set and .clear bodies verbatim as the
          Swift encoder emits them. NO UI CHANGE: D3 defers the per-college
          living-plan picker, so this slice is not user-visible on its own;
          the door is the existing college-list screen, and what changed is
          that a Save from it is provably non-destructive and the app can now
          express a clear when a control is added. No DDL, no migration, no
          OpenAPI change, no server behaviour change. Gate: iOS bin/test-ios
          594 tests 0 failures; JVM 2742 tests 0 failures; pre-commit
          `bin/test check` passed. 39 review lenses, tiers 0-3.

      Backlog from profile/03's audit (REPORTED, not fixed — the slice's own
      scope rule: a gap outside income / residency / list becomes a Backlog
      line or a new slice, never scope creep):
        B1. `livingPlan` is not decoded on iOS, so the app cannot show a
          per-college plan it can now clear
          (ios-app/UnicoachiOS/CollegeListModels.swift:36-44).
        B2. No per-college living-plan editing UI — deferred by brief 0007 D3
          (product/0007-explicit-profile-view/spec.md:152-157).
        B3. The add flow can send only `collegeId`, so a school already
          applied to costs two round trips
          (rest-server/.../models/CreateCollegeListEntryRequest.kt:5-11).
        B4. College search has no `limit` and no paging — a silent 20-row
          ceiling (rest-server/.../routes/CollegeRoutes.kt:80-87, :99).
        B5. An unknown `status` fails the whole list decode, against the app's
          raw-String-plus-`known…` convention, so a future fifth status would
          black out the screen for every shipped build
          (CollegeListModels.swift:10-24 vs Models.swift:250-265).
        B6. `createdAt` / `updatedAt` are dropped by the client, so no surface
          says when an entry last changed
          (rest-server/.../models/CollegeListEntryResponse.kt:19-20).
        B7. `GET /college-list/{id}` is unused, so a version conflict discards
          the student's typed edits instead of rebasing them
          (CollegeListRoutes.kt:195-210; CollegeEntryDetailView.swift:147-161).
        B8. Chat cannot clear `reasons` while iOS can — a door asymmetry in
          the opposite direction to the one this slice fixed
          (CollegeListChatTool.kt:248-256).
        B9. Reorder does not exist on any surface: no position column, no DTO
          field, no route — list order is server-fixed by `created_at, id`
          (db/schema/0024.create-college-list.sql:26-44,
          CollegeListEntriesDao.kt:138, CollegeListChatTool.kt:524-530).

## The question

Everything the coach can do in conversation should also be doable
**explicitly**, from a dedicated screen. Ian, 2026-09-04:

> New iOS App idea: Anything that can be done in the Chat interface e.g. add /
> remove school from list, set income level, state residence, etc. should be
> able to be done explicitly from a dedicated view e.g. a profile view.

Chat is a good door. It is a bad **only** door. A family that wants to correct
one wrong field today has to talk the coach into it, and there is no place to
look at what unicoach believes about them. This is brief 0001's reachability
lesson (three slices of cost truth landed behind a college list no user could
edit; S3.5 had to be added after the fact) generalised from one feature to the
product's whole input surface.

**Scope is deliberately narrow (Ian, 2026-09-04): household income band, state
of residence, and the college list. Nothing else.** Living plan, dependency
status, report sharing and the opt-out are chat-only today and stay chat-only in
this brief — they are named below as out-of-scope neighbours so a later brief
can pick them up without re-deriving the reasoning.

## Four things Ian already decided, before gate 1

These are answers to questions raised when the idea was recorded in the Backlog.
They are inputs to the brief, not decisions it will re-ask.

- **A user navigating to a screen to give data is NOT the coach re-asking.** The
  standing rule that a declined field is never re-raised (brief 0001 D11/D12)
  binds the **coach**. It does not lock a user out of a field they chose to
  open. Ian: _"There's a big difference between the coach re-asking and the user
  navigating to give data."_ So a screen shows every in-scope field, including
  one declined in conversation, and answering it there is legitimate.
- **Read-only provenance ("where did this fact come from?") is out of scope.**
  Focus on income, residence and the school list being editable.
- **The form CAN un-answer a field — clear it, or mark it declined** (Ian,
  2026-09-04). The wire already supports both (`UpdateMoneyProfileRequest`'s
  `*Clear` and `*Declined` flags), so a screen is not limited to writing values.
  This makes a real design obligation, carried into gate 1: **cleared and
  declined must be two visibly different states on the screen**, and neither may
  licence the coach to nag. A field the user **declined on the screen** is a
  decline like any other — the coach never re-raises it (brief 0001 D11/D12). A
  field the user **cleared** returns to unanswered, which is the one state where
  the coach may invite it again in the flow of an answer; the screen must
  therefore say plainly that clearing is different from declining, or a user
  will clear a field to stop being asked and get asked more.
- **The coach never announces a screen edit.** There is no push channel — the
  coach speaks only in reply to a message — so the only question was what it
  says in its NEXT reply. Ian, 2026-09-04, on an announcement: _"Obv that is not
  a great experience."_ So: the coach silently uses the new value, and where the
  value changes an answer it **names the basis** it used ("at the Washington
  resident rate"), which RFC 145 and RFC 157 already make it do. **This needs no
  new work, so candidate C4 below is withdrawn — no acknowledgement behaviour,
  no new prompt version, no migration.**

## What the repo does today

Grepped, not assumed; the research reports carry the path:line evidence.

| Thing                              | Chat door                                                 | Native (iOS) door                                                         | Server contract                                                   |
| ---------------------------------- | --------------------------------------------------------- | ------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| Household income band              | `update_money_profile` (RFC 134)                          | **none**                                                                  | `GET`/`PUT /api/v1/students/me/money-profile` — exists, tri-state |
| State of residence                 | `update_money_profile` (RFC 134/145)                      | **none**                                                                  | same endpoint                                                     |
| College list add/remove/restatus   | `update_college_list` (RFC 136)                           | `CollegeListView` + `AddCollegeView` + `CollegeEntryDetailView` (RFC 137) | `/api/v1/students/me/college-list`                                |
| Living plan (global + per-college) | `update_money_profile` / list entry (RFC 152)             | none (wire carries it)                                                    | on both endpoints — **out of scope**                              |
| Dependency status                  | chat tool only (RFC 159)                                  | none                                                                      | **not on the money-profile wire at all** — out of scope           |
| Share / revoke the cost report     | `share_cost_report`, `revoke_cost_report_share` (RFC 155) | none — iOS share sheet deferred at RFC 155 **D-I**                        | — out of scope                                                    |
| "Never ask me again"               | `stop_cost_report_offers` (RFC 160)                       | none                                                                      | — out of scope                                                    |

Two facts shape every candidate below:

1. **The server is largely already there.** `MoneyProfileRoutes` (RFC 134)
   serves `GET` (200 with the profile, 404 before the first write) and an
   idempotent `PUT`, with per-field tri-state status and explicit `*Declined` /
   `*Clear` flags. An income-band and residency screen is plausibly a
   **client-only** slice.
2. **A profile edit is already visible to the coach.**
   `CoachingService.composeSystem` recomposes the money-profile block from the
   database on **every turn** (`activeMoneyProfile`), and `CollegeCostService`
   re-reads the profile at tool-call time. Nothing caches it. So "the coach does
   not know what I just changed" is not a problem this brief has to solve —
   which leaves only the _behavioural_ question of whether the coach should
   **acknowledge** the change.

## Candidates

| #  | Bet                                          | What it is                                                                                                                                                      | Existing foundation                                         | Cost signal                                |
| -- | -------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------- | ------------------------------------------ |
| C1 | **Money facts on a screen**                  | A profile screen that reads and writes income band + residency state, tri-state honest, with a clear "not answered / you skipped this" state                    | `GET`/`PUT /money-profile` already complete for both fields | Client-only if the wire is sufficient      |
| C2 | **Finish the college-list screen**           | The native list screen exists; audit it field-for-field against what `update_college_list` can do and close the gaps                                            | RFC 137 screen + REST list routes                           | Small, but only measurable after the audit |
| C3 | **A parity contract, enforced**              | A durable rule and a test that every student-owned fact writable by a chat tool is writable over REST and reachable from a screen — so this never drifts again  | the drift itself is the evidence (dependency, living plan)  | Cheap to state, real to enforce            |
| C4 | ~~**The coach acknowledges a screen edit**~~ | **WITHDRAWN before gate 1** (Ian, 2026-09-04): announcing an edit is a bad experience, and naming the basis inside an answer already happens (RFC 145, RFC 157) | —                                                           | none — nothing to build                    |
| C5 | **Everything, all surfaces**                 | Living plan, dependency, sharing, opt-out too                                                                                                                   | each needs its own wire work (dependency has none)          | Out of scope by Ian's ruling               |

## Success criteria for the decision

Gate 1 is decided well if, afterwards:

1. A user who wants to fix a wrong income band or state can do it **without
   talking to the coach**, and the coach's next answer uses the new value.
2. The brief says exactly what happens when a screen field is left blank,
   answered, or cleared — and none of those states lies to the family or
   licences the coach to nag.
3. Chat and screen are not two truths. One store, one vocabulary, one set of
   valid values.
4. Nothing in scope forces a user through a field they did not ask for. Value
   before ask still holds on a screen the user opened themselves.
5. The parity drift that produced this idea is either fixed or explicitly and
   visibly deferred — never left implicit.

## Research verdict (both reports, `research/`)

**Ranked, after `repo-surfaces.md` and `dual-entry-patterns.md`.**

| Rank | Bet                              | Verdict across the two reports                                                                                                                                                                                                                                                                                                                                                                                         |
| ---- | -------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1    | **C1 — money facts on a screen** | Server complete, client empty. Apple's Settings HIG puts "general, infrequently changed" data in a settings area, and its ML/generative-AI guidance prefers a **guided** correction (a picker) over freeform, plus a non-AI fallback. Every chat-first product with a comparable store (Claude, Copilot, ChatGPT) exposes one. Gemini, which does not, tells users to "correct it in chat" and is the cautionary case. |
| 2    | **C2 — the college list**        | The screen already exists and is already the door. Apple's own guidance argues **against** moving list editing into a profile screen: task-bound options belong "within the task itself". So this is an audit and a bug fix, not a new surface.                                                                                                                                                                        |
| 3    | **C3 — a parity contract**       | Real drift exists (dependency is in the DB and the chat tool, absent from the wire), but enforcement machinery is speculative. Name the drift; do not build a framework for it.                                                                                                                                                                                                                                        |
| —    | **C4 — coach acknowledges**      | **WITHDRAWN before gate 1** (Ian). Shipped precedent is a terse in-surface receipt ("Memory updated"), never a chatty proactive line; Apple warns proactive features earn less patience for mistakes.                                                                                                                                                                                                                  |
| —    | **C5 — all surfaces**            | Out of scope by Ian's ruling.                                                                                                                                                                                                                                                                                                                                                                                          |

**Two findings that change the shape of the work:**

- **A production bug, proven by an executed test**
  (`research/living-plan-hazard.md`). A college-list `PATCH` that omits
  `livingPlan` returns **200** and NULLs the family's per-college living plan.
  `FAIL_ON_MISSING_CREATOR_PROPERTIES` does not fire for a **nullable** Kotlin
  constructor parameter, so the KDoc's "REQUIRED on the wire" has no
  enforcement. Every iOS restatus or edit-reasons Save silently deletes an RFC
  152 fact.
- **Declining is heavier than answering.** Precision offers key off
  **unanswered**, not off a missing value (`CollegeCostService.kt:582-586`), so
  a decline on the screen permanently retires an invitation — and declining
  residency also retires the living-plan invitation at public schools. Clear is
  the only undo.

**One honest negative:** there is **no** credible external evidence that an
editable view of what an AI stored raises trust or retention. Do not put a trust
lift in the success criteria as though it were evidenced.

## Gate 1 — decisions

Defaults are pre-chosen. Approving costs one word; amending costs one line.

- **D1. Build it.** Ship an explicit profile view for the in-scope facts.
  **Default: yes.**
- **D2. Where it lives.** A fifth pushed `Destination` plus one slide-over menu
  row, exactly like "My colleges" — no tab bar, no re-architecture. **Default:
  yes.**
- **D3. What is on it.** Household income band and state of residence only. The
  **college list keeps its own screen** and is not duplicated into the profile —
  Apple's guidance is that task-bound options belong inside the task, and a
  second editing path for the same list is a second truth to keep in sync. The
  profile screen links to the list screen. **Default: money facts on the profile
  screen; the list stays where it is, linked.**
- **D4. A 404 is not an error.** `GET` before the first write returns 404; the
  screen renders it as "nothing answered yet". **Default: yes.**
- **D5. Three states, plainly different — and a decline is always undoable.**
  Every field shows one of **not answered** / **answered (value)** / **you chose
  not to say**, and offers both "remove my answer" (clear -> unanswered) and
  "prefer not to say" (decline). The decline control states its consequence,
  because a decline permanently retires the coach's invitation while a clear
  does not.

  **A declined field stays fully editable on the screen, forever** (Ian,
  2026-09-04). There are two ways out and the screen offers both: **answer it
  outright** (declined -> answered) or **remove the answer** (declined ->
  unanswered, which re-arms the coach's invitation — the user's choice, and
  exactly the "unless the student reopens the topic" clause the coach's own
  context block already carries). A decline is never a one-way door and is never
  greyed out.

  **This needs no server work.** There is no state machine and no guard on
  writing to a declined field: `MoneyProfileService.upsert` is an absolute
  per-field statement with apply-or-keep column semantics, and history preserves
  the "answer -> decline -> re-answer trail" by design (`MoneyProfileService.kt`
  KDoc). The declined -> clear -> unanswered transition is already covered by an
  existing passing test (`MoneyProfileRoutingTest.kt:226-233`).

  **Default: yes — both controls, the consequence named, and a decline
  reversible in both directions.**
- **D6. The screen never speaks a slug.** An income band must display its dollar
  range (RFC 142 forbids source jargon), and the ranges live in Kotlin plus RFC
  158's `income_bands` table. **Default: serve the vocabulary from the server**
  — income-band options (slug + spoken label + dollar range) and the 59
  residency codes — so Swift holds no fourth, unchecked copy. Alternative if you
  prefer less surface: hardcode in Swift and accept the copy.
- **D7. Saving says so, on the screen.** A terse in-surface confirmation,
  matching shipped precedent. **No message in chat, ever.** **Default: yes.**
- **D8. Never a gate.** The screen is reachable but never interposed: no
  onboarding form, no blocking, no forced completion. Value before ask survives
  intact (brief 0001 D12, standing). **Default: yes.**
- **D9. The living-plan bug ships separately and first — and the REST design
  itself is the bug** (Ian, 2026-09-04). Not folded into this brief; its own
  `/ship` run.

  **What is actually wrong.** REST's college-list `PATCH` says `livingPlan` is
  "REQUIRED on the wire", where `null` means clear
  (`UpdateCollegeListEntryRequest` KDoc, `CollegeListService.updateEntry`
  KDoc:141-152). That contract makes an **omitted key destructive**, which is
  the opposite of what the same KDoc claims it is protecting ("clearing stays an
  act a caller performs rather than one an omitted key performs for it").
  Nothing enforces the requirement, so the destruction is silent and shipped.

  **The repo already has the right pattern, twice.** The chat tool carries a
  three-way `LivingPlanUpdate` — `Set` / `Clear` / absent-means-keep — precisely
  because "leave it alone" and "drop it back to the usual plan" are two
  different writes onto one nullable column and "a bare null cannot tell them
  apart" (`CollegeListChatTool.kt:318-337`). The money profile solved the
  identical problem with explicit `*Clear` flags. **REST is the only surface
  that got it wrong**, and it is the one surface a shipped client talks to.

  **So the fix is server-side and non-breaking, not client-first.** Make the
  REST request three-state like its two siblings — an omitted `livingPlan`
  **keeps** the stored value, and an explicit `livingPlanClear: true` clears it
  — and give the service the shared vocabulary rather than a nullable absolute,
  which also deletes the chat tool's echo-the-current-value workaround. **The
  already-shipped iOS build stops destroying data the moment the server
  deploys**, with no App Store release in the path. The Swift change (send a
  clear when the user clears) becomes additive and can ride brief 0007.

  This supersedes the earlier client-first / `@JsonProperty(required = true)`
  recommendation in `research/living-plan-hazard.md`: enforcing the requirement
  would have hard-400'd every shipped build and kept a wire contract whose
  default is data loss.

  **Default: yes — start it now, in parallel, server-side three-state fix.**
- **D10. Name the parity drift; do not build enforcement.** Record that
  `dependency` (RFC 159) is writable in chat and absent from the wire, and that
  living plan is on the wire with no screen. A Backlog line, not a framework.
  **Default: record only.**
- **D11. Provenance stays out (your ruling), recorded as an opportunity.** No
  product we could find labels a stored fact "you set this" vs "I learned this
  from your conversation", and Apple already has the pattern. Out of scope here;
  noted in the Backlog as a cheap differentiator. **Default: out of scope.**
- **D12. No trust or retention claim in the success criteria.** The evidence
  does not exist; the justification is correction, control and Apple convention.
  **Default: yes.**

## Gate 1 outcome

**APPROVED by Ian, 2026-09-04 — all twelve defaults, one word.** Two decisions
were amended by Ian during the gate conversation and the amendments are written
into D5 and D9 themselves, because a committed decision is immutable and a later
reader must not have to reconstruct it from an appendix:

- **D5** gained the requirement that **a decline is always undoable** from the
  screen, in both directions (declined -> answered, and declined -> unanswered).
  Verified to need no server work.
- **D9** was re-decided outright. Ian: _"The server's college-list PATCH
  requires livingPlan on the wire. This seems like a bug and we should fix if
  it's true."_ It is true, and the wire contract — not the iOS omission — is the
  defect. The fix became a **non-breaking server-side three-state change**
  instead of a client-first rollout, which stops the data loss on deploy with no
  App Store release in the path.

**One candidate was withdrawn before the gate** (C4, coach acknowledgement) and
one product question was ruled out of scope (provenance, D11).
