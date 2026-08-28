# Product status

One page, five jobs: a glanceable TL;DR of the next steps, what the product does
today (a user manual, one entry per feature), what we are building next
(prioritised, with honest in-progress state), a backlog for unscheduled ideas,
and paste-ready prompts to kick off new sessions. **/chart reads this file first
and updates it after every landed slice** — if this file and a brief disagree,
the brief's ledger wins and this file gets fixed.

Updated: 2026-08-27. Live-run discovery from any checkout:
`.prime/agent/skills/ship/scripts/ship-status`

## TL;DR — next steps, most important first

1. **Brief 0004 — college search index — EXECUTING.** Both gates approved
   (2026-08-27); slices S1→S5 specced with approved DDL in
   `product/0004-college-search-index/spec.md`. Next /ship run: **0004 S1**
   (trigram fuzzy names + aliases, honest totalMatches/sortBy, ingest
   provenance + header assertion + change summary — absorbs the
   ingest-observability item). By 0004 D13, S1–S3 land **before** 0001's S4.
2. **S4 — Admissions Intelligence Layer v0** (brief 0001, the largest slice of
   Beat 1). CDS-derived merit-aid / admissions-factors / deadlines tables,
   seeded for the ~300–500-school launch set, exposed as a cited tool; merit
   practice feeds S3's cost answers. May split in design. Adds new tables → D10
   applies: DDL goes in front of Ian at the /ship gate. Kickoff prompt below.
   **Waits for 0004 S1–S3** (the admissions layer builds on the search index —
   0004 D13); note 0004 D10 declined CDS scraping for now, which narrows S4's
   deadline sourcing — revisit at S4's design gate.
3. **S5 Family Cost Report, then S6 invite-your-parent** — the rest of Beat 1;
   S6 is the wedge and its token becomes Beat 2's parent-account claim path.
4. **Before any App Store submission: brief 0002, account deletion** — parked in
   the Backlog (Ian, 2026-08-27), but 5.1.1(v) still blocks review and GDPR Art.
   17 / CCPA still apply. Nothing in Beat 1 is affected; launch is.

_Rule: this list is rewritten every time the file is updated; it never says "see
below"._

## The product today

What a real user can do, and through which door. An entry is added or amended
when its slice lands — reachability is part of the entry, per the chart skill's
standing rule.

### Chat coaching (the core)

The product is a chat-first AI college coach (iOS app, RFC 117 navigation). The
coach runs on `COACHING_SYSTEM_PROMPT_VERSION=v3` (RFC 135; rollback knob:
`v2`), builds durable memory from conversation (claims/observations, RFC 93
commitments), and calls tools mid-conversation. Door: the iOS chat screen; new
users can chat before subscribing (chat-before-subscription is the house
value-before-ask pattern).

### Know your real price (briefs 0001 S1–S3, RFCs 133–135)

The v1 differentiator: per-school cost truth in session one.

- **How a user reaches it:** ask the coach about cost — or the coach raises it
  unprompted (prompt v3 makes it a first-session moment). Needs schools on the
  list (see college list, below) for the per-school table.
- **What it does:** for each listed school, sticker vs. likely net price for the
  family's income band, plus debt/earnings context, every number cited to its
  source-year. Sources: Scorecard income-band net price (NPT41–45) and median
  debt on `colleges` (RFC 133); `college_cost_profile` tool (RFC 135).
- **Degrades gracefully:** no money profile → overall net price, labeled as
  such; partial profile → best answer from what exists; unreported data says
  "not reported", never evades. No cost feature is gated on profile completion
  (0001 D11/D12).
- **Live in prod** (2026-08-27): end-to-end phone test passed — the coaching
  holds — and S1–S3.5 (RFCs 133–136) is deployed.

### Money profile (brief 0001 S2, RFC 134)

Where the family's income band and residency state live, so the right price band
can be chosen.

- **How a user reaches it:** conversation only — the coach invites
  (`update_money_profile` tool); the student can start, stop mid-way, resume
  across sessions, skip entirely. Never forced, never a form.
- Tri-state per field (unset / declined / value), atomic upsert, admin read-only
  view.

### College list (RFC 91 schema/REST; RFC 136 chat door)

The student's working list of schools — the substrate the cost feature keys off.

- **How a user reaches it:** conversationally, via the `update_college_list`
  chat tool (RFC 136): add, restatus, remove. The coach offers, never nags; an
  entry is always changeable.
- **Native door:** the iOS college-list screen (RFC 137) — view the list, add
  via name search (`GET /api/v1/colleges?q=…`), restatus, edit reasons, remove.
  Entries now carry `collegeName` on the wire.
- History: this was the reachability lesson — S1–S3 shipped behind a list no
  user could edit; S3.5/RFC 136 opened the door.

### Accounts and subscriptions

Signup with email verification; Apple/Google SSO identities
(`user_auth_identities`); paid subscriptions (parents are the payers). Known
gap: **no in-app account deletion** — a review-blocking App Store requirement
(5.1.1(v)) tracked as brief 0002, P1 below.

_Backfill note: features that predate this file (college search tool, fit lens,
synthesis commitments, admin surfaces) are documented in their RFCs; entries get
added here as slices touch them._

## Work — prioritised

P1 = needed for public launch or unblocking others; P2 = the differentiator
beat's remainder; P3 = in flight but not on the critical path. Unprioritised
ideas live in the Backlog below, not in the table. "State" is honest partial
progress — this is the column /chart reads to know what "halfway done" means.

| Pri | Work                              | State                                                                                                                                                                                         | Where                                    |
| --- | --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| P1  | College search index (brief 0004) | EXECUTING — gates 1+2 approved (2026-08-27); S1→S5 specced, DDL approved. S1 (trigram names + honest counts + provenance) is the active /ship run; precedes 0001 S4.                          | `product/0004-college-search-index`      |
| P1  | Beat 1 remainder: S4 → S5 → S6    | Not started; S1–S3.5 is LIVE IN PROD (2026-08-27), so the beat's remainder is the next build. S4 Admissions Intelligence Layer (largest, may split), S5 Family Cost Report, S6 invite-parent. | `product/0001-v1-differentiator/spec.md` |
| P3  | `bin/state-apply` (RFC 138)       | **Landed** (v1: users world file, create-only). Per-entity replace/reset waits on brief 0002's delete engine — see Backlog.                                                                   | `bin/state-apply`                        |

## Backlog

Valuable, unscheduled, unprioritised — the parking lot. Append freely (one
bullet, enough context to pick it up cold); /chart promotes an item into the
work table by giving it a priority, or into a brief when it deserves gates.
Nothing here is committed work.

- **Account deletion (brief 0002)** — moved out of the work table (Ian,
  2026-08-27). Brief is FRAMED with six decisions D1–D6 awaiting gate 1 at
  `product/0002-account-data-deletion`. Still App-Store-blocking when we submit:
  5.1.1(v) requires in-app account deletion, and GDPR Art. 17 / CCPA apply
  regardless — so this is deferred, not resolved, and it gates public launch.
  Also blocks `bin/state-apply`'s per-entity reset (RFC 138 defers to its
  engine) and repeatable clean-slate testing.
- **Beat 2: parent partner accounts** — claim-the-report onboarding, linked
  family, parent-side coaching. Deliberately unspecced until Beat 1 ships and
  share-rate is observed (0001 D9).
- **`bin/state-apply` growth** — per-entity replacement/reset once brief 0002's
  delete engine exists (RFC 138 explicitly defers to it); more resource types
  (students, college lists, money profiles) for one-command test worlds.
- **Ingest observability** — dropped from the work table (Ian, 2026-08-27), kept
  here as a known trap, not committed work: `bin/ingest-colleges` runs a
  PREBUILT launcher and checks only that it is executable, so a stale jar loads
  every row, logs `colleges=N`, and leaves new columns NULL — indistinguishable
  from a real load. It has cost an hour twice (dev S1, prod deploy). Verify a
  load by hand instead: `SELECT count(<new_col>), max(version) FROM colleges`.
  The same shape likely exists in other loaders.

## Kickoff prompts

Open a new Prime Agent session in `/Users/ian/Work/unicoach` and paste one. Each
/ship run claims its own worktree, so parallel runs are safe. YOU are the
approval gate in each session. Slices from a brief are kicked off with the slice
instruction from its `spec.md` — the prompts below add only session context the
spec can't know.

### College search index (brief 0004)

PASTE: Ship S<n> from product brief 0004: use the slice instruction in
product/0004-college-search-index/spec.md verbatim as the /ship instruction.
Both gates are approved; gate decisions D1–D22 (brief.md + spec.md) are binding
context — notably: Postgres-only, no queue, two-phase ingest (rows first, one
transactional index rebuild at the end), raw source codes in schema with word
enums at the tool boundary, tri-state unknowns with excluded counts, outcome
measures never ranked. I approve every new table with visible DDL at the /ship
gate; the spec's DDL is the approved shape.

### Account deletion (brief 0002) — parked in Backlog, kept ready

PASTE: Run product brief 0002 (product/0002-account-data-deletion/brief.md) with
/chart: account data deletion. The brief is FRAMED and awaiting gate 1 — bring
me its six decisions (D1–D6) with defaults before any code. Legally required
(GDPR Art. 17, CCPA, App Store 5.1.1(v) in-app deletion) and it gives us
repeatable clean-slate testing. The schema actively refuses deletion today — all
mapped in the brief. I approve every new table personally, with visible DDL at
the gate. Note RFC 138 (bin/state-apply) deliberately deferred its delete/reset
semantics to this brief's engine.

### S4 — Admissions Intelligence Layer v0 (next up)

PASTE: Ship S4 from product brief 0001 (Admissions Intelligence Layer v0). Use
the S4 slice instruction in product/0001-v1-differentiator/spec.md verbatim as
the /ship instruction; gate-2 decisions D7–D12 are standing context, and D8
fixes the launch set at ~300–500 schools ranked by college-list popularity plus
national popularity. Scope: new reference tables for CDS-derived facts — H2A
merit-aid practice (% of no-need freshmen receiving merit, average award), C7
admissions factors, and deadlines by round — seeded from the collegedata.fyi
corpus for the launch set and exposed as a cited LLM tool, with merit-aid
feeding S3's cost answers ("X% of freshmen without need got merit here, avg
$Y"). Explicitly NOT in scope: any net-price-calculator automation.

Four things this session must respect:

D10 is binding — this slice adds new tables, so the /ship approval gate must put
the proposed DDL in front of me explicitly, columns and constraints and
versioning choice, not buried in the RFC. I approve every new table personally.

This is the largest slice in the beat and the spec pre-authorises splitting it
in design. If the honest read is that the ingest, the schema, and the tool are
three slices, say so at the design gate rather than shipping one 3000-line
branch.

The ingest tooling is deliberately quiet: bin/ingest-colleges runs a PREBUILT
launcher and only checks it is executable, so a stale jar loads every row, logs
a cheerful count, and leaves new columns NULL. Rebuild the dist before any load,
and prove the seed landed with a direct SELECT on the new tables plus a
launch-set coverage report — a successful-looking load is not evidence.

Cited means cited: every fact the tool returns carries its source and
source-year, matching how S1–S3 already cite Scorecard data. "Not reported" is
an honest answer; inventing or interpolating a figure is not.

AC (from the spec): launch-set coverage report, and cited merit answers in chat.
Update the brief's ledger and product/STATUS.md when the slice lands.

### Beat 1 remainder — S5 / S6

PASTE: Ship S<n> from product brief 0001: use the slice instruction in
product/0001-v1-differentiator/spec.md verbatim as the /ship instruction, plus
gate-2 decisions D7–D12 as standing context. Update the brief's ledger and
product/STATUS.md when the slice lands.
