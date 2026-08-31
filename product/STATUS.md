# Product status

One page, five jobs: a glanceable TL;DR of the next steps, what the product does
today (a user manual, one entry per feature), what we are building next
(prioritised, with honest in-progress state), a backlog for unscheduled ideas,
and paste-ready prompts to kick off new sessions. **/chart reads this file first
and updates it after every landed slice** — if this file and a brief disagree,
the brief's ledger wins and this file gets fixed.

Updated: 2026-08-30 (RFC 148 landed — `first-value/04b` (S4b), the admissions
layer is now user-visible. Slices now carry permanent IDs and declared `Needs:`
edges; sequencing below is the computed wave board. **Never copy a number out of
this file:** as of this line the next free RFC is 149, and the next free
migration is 0062 — because the in-flight `pipeline/rfc-147` has already claimed
`0060` and `0061` in its own worktree, which a plain `ls db/schema` on `main`
cannot see. Recompute at run time and re-check immediately before committing.)
Live-run discovery from any checkout:
`.prime/agent/skills/ship/scripts/ship-status`

**Slice IDs are `<brief>/<milestone>.<step>/<name>`** — `first-value` = brief
0001, `deletion` = 0002, `money` = 0003, `search` = 0004. The old letter follows
in parentheses on first mention. IDs are permanent: a slice is never renumbered,
and one inserted later takes a decimal step (`first-value/03.5`). **The number
never grants or denies permission to start — only the `Needs:` line does.**
Start a slice with the **`slice` skill**: "start work on
`search/04/similar-colleges`".

## TL;DR — next steps, most important first

1. **Brief 0004 — college search index — EXECUTING; S1 and S2 LANDED (RFCs 139
   and 144, 2026-08-28/29).** Fuzzy names, honest match counts and ingest
   provenance are live, and the IPEDS attribute layer is now source data:
   `college_ipeds` (religion, ROTC, study abroad, the disability band, housing,
   application fee, athletics, test policy, closure) and
   `college_programs_census` (bachelor's 6-digit CIP), loaded by
   `bin/ingest-colleges`'s optional all-or-nothing
   `--hd/--ic/--adm/--completions/--survey-year` group — 5,688 attribute rows
   and 80,632 census rows from the real 2023 files, unmatched `unit_id`s counted
   and skipped. Nothing user-visible changed yet: **that is `search/03` (S3)**,
   which was **split at its design gate** into `search/03a/published-codebooks`
   (**RFC 147 — LANDED 2026-08-30**) and `search/03b/the-index` (the derived
   `college_search_index` + subject taxonomy — the aha, **NEXT**, lands on top
   of 03a). 03a made the stored federal codes explainable: `bin/fetch-codebooks`
   pulls IPEDS's own Stata syntax files, and 10 domains / 2,015 rows of
   published labels now live in eleven reference tables, with the search tool
   and the fit lens speaking words in both directions from ONE shared schema and
   the hand-written codebook prose deleted from the fit-lens prompt. Two
   mechanical naming runs landed first, at Ian's call from the same gate:
   `ipeds_unit_id` (migration 0057) and unit-last numeric column names
   (migration 0059). Then `search/04/similar-colleges` (query-time),
   `search/05/consumer-sweep`. 0004 D13 (S1–S3 before 0001's S4) was consciously
   narrowed at RFC 140's gate and is now moot for S4a: only S1's conventions
   were a real dependency, and S4a landed after RFC 139 with its ingest CLI
   merged into 139's (aliases + `--*-source` provenance + the CDS group in one
   launcher).
2. **Brief 0001 S4 COMPLETE — S4a (RFC 140) and S4b (RFC 148, 2026-08-30).** The
   admissions layer is now user-visible. The coach can answer, with citations,
   what a school weighs in admissions, when its rounds close, and how it
   actually behaves on merit aid — and merit now rides along inside cost
   answers. Every fact names the school's own Common Data Set, its cycle, and an
   archive link. Coach prompt v8 (migration 0058, rollback
   `COACHING_SYSTEM_PROMPT_VERSION=v7`). RFC 148 also closed RFC 140's open
   item: the CDS load runs as a tracked phase **before** RFC 139's
   `college_index_build` row is written, so that row — not `PROVENANCE.json` —
   is now the provenance of record for the CDS seed. **THE HONEST DENOMINATOR IS
   LOAD-BEARING AND IMPLEMENTED:** the CDS publishes no count of no-need
   freshmen, so the only computable share is "X% of ALL full-time freshmen
   received non-need (merit) aid, average $Y". The wire key is
   `share_of_all_full_time_freshmen_pct` and tests assert the payload never
   contains "without need". A school that reports only a freshman total (28 of
   368) is a silence, not a zero; "not reported" is always the honest answer.
   **Next for 0001: `first-value/05/family-cost-report` (S5)**, which _prefers_
   `money/02` + `money/03` so the parent-facing artifact speaks the language.
   That is product judgement (PREFER), not a technical block — Ian can pull it
   forward.

3. **Brief 0003 — clear money language — M1, M1.1, RFC 143 and M1.2 LANDED (RFCs
   141–143, 145).** The coach speaks one money vocabulary, no bare source code
   reaches a tool result, and — as of 2026-08-29 — it **asks where the family
   lives before it asks what they earn**: `precision_offer` is an ordered list
   of upgrade invitations (residency first, offered only where a public college
   makes it worth something), and prompt **v7** teaches the ordering.
   **`money/02/component-split` (M2)** (DDL approved as D18/D19) is the next
   slice of this brief and **is not blocked — it can run today.** Its one real
   precondition was 0004 S1's two-phase `bin/ingest-colleges` with provenance
   and a change summary, which landed as **RFC 139** and was extended by **RFC
   144**. It was recorded for weeks as waiting on 0004 S3; that was wrong, and
   the edge is really CONFLICTS (both slices edit `bin/ingest-colleges` and
   `CollegesDao.search`), which is a rebase risk and not an order. It precedes
   `first-value/05`. Migrations: `0049`/`0050` (RFCs 141/142), `0053` (RFC 145).
4. **S5 Family Cost Report, then S6 invite-your-parent** — the rest of Beat 1;
   S6 is the wedge and its token becomes Beat 2's parent-account claim path. S5
   waits on 0003 M2+M3 so the parent-facing artifact speaks the language.
5. **Before any App Store submission: brief 0002, account deletion** — parked in
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
coach runs on `COACHING_SYSTEM_PROMPT_VERSION=v7` (RFC 145; rollback knob:
`v6`), builds durable memory from conversation (claims/observations, RFC 93
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

### Money language (brief 0003 M1, RFC 141)

One vocabulary for money, spoken everywhere the coach talks about cost.

- **How a user reaches it:** every cost answer in the chat coach — no user
  action, live on the next `service` deploy.
- **What it does:** the coach says _tuition and fees_ (the price the school
  sets), _housing and food_ (never "room and board"), _the published price_
  (never "sticker price"), _a financial aid offer_ (never an "award"), and
  always uses the word _loan_ for a loan. It never subtracts loans or work-study
  from a price. Parents and students get the same words; only the pronoun and
  time horizon change.
- **Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v4`; the v4 row is immutable and
  stays in the catalog.
- Honest limit: no test can assert what the coach actually says — the tests pin
  the seed's structure and content only.
- **RFC 142** closed the first live defect: the coach said "Q5 net price"
  because `college_search` sent it `net_price_q1..q5` and told it to cite the
  matching band. Every band now travels with its dollar range ("$110,000 or
  more") from one emitter, and prompt v6 bans source jargon generally.

### Asking where you live before asking what you earn (brief 0003 M1.2, RFC 145)

The cheap question that moves the bigger number now gets asked, and gets asked
first.

- **How a user reaches it:** any cost answer in the chat coach about a
  **public** school on their list, while we do not know their state — no user
  action, live on the next `service` deploy.
- **What it does:** the coach offers to record the state the family lives in,
  naming what it unlocks (whether they would pay the in-state or the
  out-of-state published price), **before** it raises household income —
  residency corrects a public school's tuition by a median $6,300/yr against
  ~$1,376 for a middle-band income correction. The cue rides the tool result
  (`precision_offer` is now an ordered list of invitations), not the model's
  memory.
- **Where it stays quiet:** a list of only private schools gets no residency
  offer — there is one price and the question would buy nothing.
- **Never forced (0001 D11/D12):** a declined state is never raised again, and
  every cost answer still works without it, naming the basis it used.
- **Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v6`; the v6 row is immutable and
  stays in the catalog.

### Money profile (brief 0001 S2, RFC 134)

Where the family's income band and residency state live, so the right price band
can be chosen.

- **How a user reaches it:** conversation only — the coach invites
  (`update_money_profile` tool); the student can start, stop mid-way, resume
  across sessions, skip entirely. Never forced, never a form.
- Tri-state per field (unset / declined / value), atomic upsert, admin read-only
  view.

### Finding a college by name (brief 0004 S1, RFC 139; matching replaced by RFC 146)

Typing a school's name finds it even when the typing is imperfect.

- **How a user reaches it:** the iOS college-list screen's name search
  (`GET /api/v1/colleges?q=…`), and the coach's `search_colleges` tool in chat —
  both inherited the upgrade with no API change.
- **What it does:** a typo is **one keystroke wrong** — a substitution,
  insertion, deletion, or adjacent transposition — so a school matches when
  every word of the query is within one keystroke of some word of its name or
  curated aliases, or the query is a literal substring of them. "Amhurst" finds
  Amherst College, "Amhurst Colege" finds it too, and "Mizzou" finds
  Missouri-Columbia. Exact and prefix matches still rank first. Trigram
  similarity (`pg_trgm`) and its 0.6 threshold are gone: they ranked Elmhurst
  University above an absent Amherst College for "Amhurst", and no threshold
  repaired that (RFC 146).
- **For the coach:** results now carry an unclamped `total_matches` ("312 match;
  showing 25" is finally sayable), a `sort_by` that never filters, and a
  `credential_level` word enum ("bachelors"), never raw Scorecard codes.
- **Operationally:** every ingest writes a `college_index_build` provenance row
  (source sha256s, per-table counts, skip taxonomy, non-null deltas), asserts
  its source headers before writing a single row, and prints a change summary —
  a no-op load can no longer masquerade as a real one.

### Know how a school admits and what it pays (brief 0001 S4, RFCs 140 + 148)

School-authored Common Data Set facts for the launch set, now answerable in chat
with citations.

- **How a user reaches it:** conversationally. Ask what a school weighs, when it
  closes, or whether it gives merit aid, and the coach calls the
  `college_admissions_profile` tool over the schools on the student's active
  list. Merit also appears inside cost answers without a second question.
- **What it does:** three cited sections per school — **merit aid** ("X% of all
  full-time freshmen received non-need (merit) aid; the average was $Y"), the
  **C7 admissions-factor grid** in the school's own words ("very important",
  "considered"), and the **application calendar** (which rounds a school runs,
  which it does not, and the dates it published). Each section names the
  school's own CDS document, its cycle, and an archive link.
- **The denominator is honest by construction:** the CDS publishes no count of
  no-need freshmen, so we never claim one. A share is emitted only when both
  counts exist — a freshman total alone is a denominator, not a fact.
- **Degrades gracefully:** a school with no row is named as "not reported",
  never interpolated and never a zero. `0` recipients is a real reported value.
  A month with no day reads "January, day not reported". A round the school says
  it does not offer is said plainly, because that is a fact too.
- **Coverage:** 415 launch-set colleges — merit 366, factors 374, deadline flags
  314 (234 with a concrete date), 0 student-listed schools missing.
- **Live in prod:** yes, behind coach prompt v8.

Refresh the seed with `nix develop -c bin/fetch-cds-seed` (review the diff,
commit), load with `bin/ingest-colleges -m/-a/-d`. The ingest records the seed's
digests and per-table counts into the `college_index_build` provenance row.

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

| Pri | Work                                       | State                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | Where                                    |
| --- | ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| P1  | College search index (brief 0004)          | EXECUTING — gates 1+2 approved (2026-08-27); `search/01`→`search/05` specced, DDL approved. **`search/01/honest-name-search` LANDED (RFC 139, matching later replaced by RFC 146) and `search/02/ipeds-attributes` LANDED (RFC 144). `search/03` split at its design gate: `search/03a/published-codebooks` is IN FLIGHT (RFC 147, PHASE verifying) and `search/03b/the-index` — the derived index + subject taxonomy — lands on top of it. Neither blocks `money/02/component-split`; they only CONFLICT with it on shared files — a rebase, not a wait.** | `product/0004-college-search-index`      |
| P1  | Clear money language (brief 0003)          | **`money/01` + `money/01.1` + RFC 143 + `money/01.2` LANDED** (RFCs 141–143, 145; 2026-08-28/29). The coach now asks residency before income. Next: **`money/02/component-split`, unblocked and runnable today** — its real precondition landed as RFCs 139/144; it precedes `first-value/05`. Then `money/03`, `money/04`.                                                                                                                                                                                                                                 | `product/0003-clear-money-language`      |
| P1  | Beat 1 remainder: `first-value/05` → `/06` | **`first-value/04` COMPLETE** — split into `04a/admissions-data` (RFC 140) and `04b/admissions-in-chat` (RFC 148), both landed; cited merit answers are live in chat. Next: `first-value/05/family-cost-report`, which PREFERs (does not require) `money/02` + `money/03`, then `first-value/06/invite-your-parent`.                                                                                                                                                                                                                                        | `product/0001-v1-differentiator/spec.md` |
| P3  | `bin/state-apply` (RFC 138)                | **Landed** (v1: users world file, create-only). Per-entity replace/reset waits on brief 0002's delete engine — see Backlog.                                                                                                                                                                                                                                                                                                                                                                                                                                 | `bin/state-apply`                        |

## Sequencing — the wave board

Computed from the `Needs:` edges declared on each slice in its `spec.md`, not
from habit. **Same wave = safe to run in parallel** (separate /ship worktrees).
Regenerate this board whenever a slice lands; never remember it.

Edge kinds: **BLOCKS** = technical, cannot proceed, not overridable. **PREFER**
= product judgement, overridable by Ian, and marking it PREFER makes the
override visible instead of hidden. **CONFLICTS** = both touch the same files —
a rebase risk, **not** an order; schedule apart or absorb the rebase. A `Needs:`
entry with no reason is not a dependency and is rejected. **Adjacency in an ID
never grants or denies permission to start** — only the `Needs:` line does.

### Wave 1 — startable today

| Slice                            | State                                                                                                                                                                        |
| -------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `search/03a/published-codebooks` | **IN FLIGHT** — RFC 147, `pipeline/rfc-147`, PHASE verifying, in `../unicoach-rfc-147`. Split out of `search/03` at its design gate: the codebook substrate the index reads. |
| `search/03b/the-index`           | NOT STARTED. BLOCKS `search/03a` — it lands on top of the codebook reference tables.                                                                                         |
| `money/02/component-split`       | **READY NOW.** Its stated preconditions (`search/01`, `search/02`) landed as RFCs 139 and 144.                                                                               |
| `deletion/*`                     | Gate 1 not yet run (brief 0002 is parked in the Backlog, still launch-blocking).                                                                                             |

`search/03a` and `money/02` both edit `bin/ingest-colleges`,
`CollegeScorecardLoader.kt` and `CollegesDao.kt` — that is a **CONFLICTS** edge,
verified live against `pipeline/rfc-147`. Expect a rebase, not a wait. Keep them
in separate worktrees.

**Verified from the repo, 2026-08-30, not from a summary.** RFC 147 is
"Published codebooks as reference data", a substrate slice split out of S3 at
Ian's direction during the S3 design gate — `college_search_index` exists
nowhere yet, on `main` or in that worktree.

### Wave 2 — unlocked by wave 1

| Slice                          | Edge                                                                         |
| ------------------------------ | ---------------------------------------------------------------------------- |
| `search/04/similar-colleges`   | BLOCKS `search/03b` — reads the percentile columns it creates.               |
| `search/05/consumer-sweep`     | BLOCKS `search/03b` — every search-shaped workflow routes through the index. |
| `money/03/comparison-contract` | BLOCKS `money/02` — the stable/variable blocks need the component columns.   |
| `money/04/where-youll-live`    | BLOCKS `money/02` — personalises the variable half of the breakdown.         |

### Wave 3

`first-value/05/family-cost-report` — PREFER `money/02` + `money/03` (D17: "so
the parent-facing artifact is born speaking this language"). This is a **quality
argument, not a technical one** — Ian can override it. Recommendation: honour
it, because re-languaging a parent-facing artifact after the fact is exactly the
rework D17 exists to avoid.

### Wave 4

`first-value/06/invite-your-parent` — BLOCKS `first-value/05`: the share CTA
lives on the report surface, and the report's token becomes Beat 2's
parent-account claim path.

Not scheduled: `search/06/unattended-refresh` (deferred; PREFERs `search/05`).

**Standing correction (2026-08-30).** `money/02` was recorded for weeks as
waiting on `search/03`. It never was. The binding text says something narrower —
0003 D15 ("M2 lands after 0004 **S1/S2**, rebasing onto the two-phase
`bin/ingest-colleges`") and D17 ("M2/M3 land after 0004 S1/S2 and **before**
0001's S5") — and both landed. The true edge is CONFLICTS. Verify a dependency
against the numbered decisions in a `spec.md`, never against a summary line in
this file: summaries drift, decisions do not.

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
- **IPEDS IC as a cost-data upgrade path** (brief 0003 D7) — public-domain,
  ~300KB/yr, same UNITID key; adds in-district tuition, separately-reported
  fees, and multi-year trend that the Scorecard lumps. Deferred, not rejected.
  The Common Data Set stays out until someone proves the licence.
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

**The short form now works:** open a new Prime Agent session in
`/Users/ian/Work/unicoach` and say _"start work on `money/02/component-split`"_,
naming the **`slice`** skill. It resolves the ID in `spec.md`, checks the
`Needs:` edges (BLOCKS refuses, PREFER asks you once, CONFLICTS only warns),
claims the RFC and migration numbers live, runs /ship, and then updates the
brief ledger and this file. Numbers are never copied from a doc — they move
under you.

The long prompts below remain for context a spec cannot carry. Each /ship run
claims its own worktree, so parallel runs are safe **within a wave**. YOU are
the approval gate in each session.

### College search index (brief 0004)

PASTE: Ship S<n> from product brief 0004: use the slice instruction in
product/0004-college-search-index/spec.md verbatim as the /ship instruction.
Both gates are approved; gate decisions D1–D22 (brief.md + spec.md) are binding
context — notably: Postgres-only, no queue, two-phase ingest (rows first, one
transactional index rebuild at the end), raw source codes in schema with word
enums at the tool boundary, tri-state unknowns with excluded counts, outcome
measures never ranked. I approve every new table with visible DDL at the /ship
gate; the spec's DDL is the approved shape.

S3 is next. S1 landed as RFC 139 (migrations 0051/0052) and S2 as RFC 144
(migration 0055: `college_ipeds` + `college_programs_census`, loaded by
`bin/ingest-colleges --hd/--ic/--adm/--completions/--survey-year`); S3 builds
phase 2 — the derived `college_search_index`, the subject taxonomy, and the
switch of both search paths onto the index. Extend that ingest rather than
inventing a parallel path, and read RFC 144's open items first: `sector` matched
no administrative units in the real corpus, `athletic_assoc` cannot express
"unreported", `college_programs_census_cip_idx` still has no reader, and
whole-run orchestration still sits inside `CollegeScorecardLoader.ingest` — S3
is the run that should decide each of those.

### Account deletion (brief 0002) — parked in Backlog, kept ready

PASTE: Run product brief 0002 (product/0002-account-data-deletion/brief.md) with
/chart: account data deletion. The brief is FRAMED and awaiting gate 1 — bring
me its six decisions (D1–D6) with defaults before any code. Legally required
(GDPR Art. 17, CCPA, App Store 5.1.1(v) in-app deletion) and it gives us
repeatable clean-slate testing. The schema actively refuses deletion today — all
mapped in the brief. I approve every new table personally, with visible DDL at
the gate. Note RFC 138 (bin/state-apply) deliberately deferred its delete/reset
semantics to this brief's engine.

### Clear money language — M2 (brief 0003; the component cost split)

PASTE: Ship M2 from product brief 0003 with /skill:ship. Use the slice
instruction in product/0003-clear-money-language/spec.md ("M2 — The component
cost split") verbatim as the /ship instruction, plus gate-1 D1–D9 and gate-2
D10–D19 as standing context, and brief 0001's standing D10 (Ian approves all DDL
at the gate) and D12 (value before ask). Read the brief's DISCOVER section and
research/data-feasibility.md before designing — they are the grounding.

WHY THIS IS THE PAYLOAD OF THE BRIEF: M1/M1.1/M1.2 (RFCs 141, 142, 145) changed
how the coach TALKS about money. Nothing yet changed what numbers it HAS. At a
public four-year, tuition & fees is a median of only 37% of the on-campus
sticker total; the other 63% is the part a family can actually influence, and
today we cannot see it. This slice is what makes the product's one irreplaceable
sentence sayable: "living at home instead would cost $7,368 less — most of a
year's tuition."

DDL, ALREADY APPROVED BY IAN (D18/D19) — present it explicitly at the gate
anyway, per 0001 D10. Six nullable INTEGER columns on `colleges` AND
`colleges_versions`, plus CREATE OR REPLACE FUNCTION log_college_version() (the
existing trigger_04 picks it up by name — the db/schema/0045 pattern):
housing_food_on <- ROOMBOARD_ON housing_food_off <- ROOMBOARD_OFF books_supply
<- BOOKSUPPLY other_expense_on <- OTHEREXPENSE_ON other_expense_off <-
OTHEREXPENSE_OFF other_expense_family <- OTHEREXPENSE_FAM Named for the product
vocabulary, not the source's retired wording (D18: the Scorecard field name goes
in the column comment, the 0015 pattern). All six carry a nonneg CHECK (D19) —
they are gross costs, unlike 0045's band columns where negatives are legitimate.
Six, not seven: there is no ROOMBOARD_FAM.

NO NEW DATA SOURCE. The values are already in the pinned Scorecard snapshot we
ingest — verified present in college/src/test/resources/scorecard-institutions-
real-fixture.csv. This is a migration + loader + DAO + tool change and a
re-ingest of the same file.

FIVE HARD RULES, each grounded in the research and non-negotiable:

1. COSTT4_A is NOT the component sum. It is a weighted BLEND across living
   arrangements and a year older (AY2021-22 vs the components' AY2022-23);
   measured, it equals the on-campus sum in 0% of publics, median gap -10.1%.
   Display the component sum on a NAMED living arrangement; keep COSTT4_A and
   net price labelled as the blended figures they are.
2. NEVER compute net_price - tuition, in code or in prompt. Aid applies to the
   blend, not to a component. Assert it in a test.
3. Never sum figures of different vintages. Introduce documented per-figure
   academic-year constants for the pinned snapshot and retire "data ingested
   YYYY" as a vintage claim — ingestYear is when we loaded the file, not the
   year of the figures.
4. `with_family` carries NO housing-and-food line (the source has none) — an
   explicit absence, never a zero.
5. "This school has no residence halls" is a FIRST-CLASS case, distinct from
   "not reported": 296 bachelor-predominant schools legitimately report
   off-campus figures and no on-campus ones. 78.7% of bachelor-predominant
   schools render a full on-campus split.

DO NOT build ingest observability (D15) — brief 0004 S1 (RFC 139) landed it.
REBASE ONTO THE CURRENT LOADER: RFC 139 restructured bin/ingest-colleges with
provenance, a fatal header assertion and a change summary; RFC 140 merged the
CDS seed into the same CLI (-m/-a/-d). Use that change summary to prove the six
columns actually loaded. Consider — and decide in the RFC — whether the six
columns belong in 0004's derived college_search_index.

ALSO: a coach prompt version (v8; v7 is live, seeded at db/schema/0053) teaching
the coach to lead with the split, name the living arrangement, and mark the
estimate lines as estimates. Keep RFC 141's glossary and RFC 142's source-jargon
sentence intact — say "housing and food", never "room and board" — and RFC 143's
guard ("no bare source code reaches a tool result") must keep passing. Numbers
move: claim the next free RFC and migration at run time and re-check them
immediately before committing — never copy a number out of this file. Note the
prompt version above is stale too: v8 was consumed by RFC 148 (migration 0058),
so this slice seeds the next free version.

I am the approval gate. Land it, then update the brief ledger and
product/STATUS.md.

### Beat 1 remainder — S5 / S6

PASTE: Ship S<n> from product brief 0001: use the slice instruction in
product/0001-v1-differentiator/spec.md verbatim as the /ship instruction, plus
gate-2 decisions D7–D12 as standing context. Update the brief's ledger and
product/STATUS.md when the slice lands.
