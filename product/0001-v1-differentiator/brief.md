# Product Brief 0001 — The v1 differentiator (DRAFT / pilot run)

Status: EXECUTE in progress. Gate 1: report-as-wedge (C4 via scoped C1,
invite-parent mechanic first-class). Gate 2: slices S1-S6 approved (see spec.md,
D5-D12). LEDGER: S1 LANDED as RFC 133 (main@dcf93207 + 8e65011f, 2026-08-24). S2
LANDED as RFC 134 (main@583c108a + d550012d, 2026-08-25) — money_profiles
tri-state entity, atomic DAO upsert, service-owned write path,
update_money_profile chat tool, context injection, admin read-only;
IncomeBand.netPriceFor ready for S3. S3 LANDED as RFC 135 (main@c9935c51 +
14ccbe37, 2026-08-25) — college_cost_profile tool (list x money profile x
Scorecard costs, self-describing basis, derived honest precision_offer) + coach
prompt v3 (rollback COACHING_SYSTEM_PROMPT_VERSION=v2). THE FIRST-SESSION AHA IS
LIVE END-TO-END. S3.5 LANDED as RFC 137/138 (the conversational door to the
list). S4 SPLIT IN DESIGN into S4a (schema + seed + ingest) and S4b (cited tool

- merit feed + prompt); **S4a LANDED as RFC 140 (main@51a1ab8c + 81354dbf,
  2026-08-28)** — three CDS reference tables (migration 0054: college_merit_aid,
  college_admission_factors, college_deadlines, all cited by source_url +
  archive_url), bin/fetch-cds-seed over the MIT-licensed collegedata.fyi corpus
  with a committed seed + PROVENANCE.json, and the launch-set coverage report:
  **415 launch-set colleges — merit aid 366, admission factors 374, deadline
  flags 314 (234 with a concrete date), 0 student-listed schools missing**. Ian
  approved the DDL at the gate (D10) plus three tightening deltas
  (day_requires_month CHECK, CREATE DOMAIN for the rating vocabulary and the
  year bound). **S4b LANDED as RFC 148 (main@0657dda7 + c94570ef, 2026-08-30)**
  — the cited `college_admissions_profile` tool (merit aid, the C7 admission
  factors and the application calendar, each section citing the school's own
  Common Data Set by cycle + archive_url), merit aid fed into S3's cost answers
  as an additive `merit_aid` block read in the same connection, and coach prompt
  v8 (migration 0058, rollback COACHING_SYSTEM_PROMPT_VERSION=v7). It also
  closed RFC 140's open item: the CDS load now runs as a tracked phase
  **before** RFC 139's `college_index_build` row is written, and that row
  carries the three seed digests and per-table counts, so PROVENANCE.json is no
  longer the provenance of record (verified against the committed seed at
  method_version 4: 366/374/1022 rows, every remaining seed row explicitly
  skipped with its IPEDS unit id recorded). **S4's AC is met: cited merit
  answers in chat.** The binding honesty constraint is enforced in three places
  — the data (a share only when both counts exist; a freshman total alone is a
  denominator, not a fact, so the 28 of 368 rows carrying only that read as
  silence), the wire (the key is `share_of_all_full_time_freshmen_pct`, and
  tests assert the payload never contains "without need"), and the prompt. **S5
  LANDED as RFC 155 (main@47cf9d62 + 6777c7c7, 2026-09-01)** — the Family Cost
  Report: the student asks the coach to share, `share_cost_report` returns a
  link, and a parent opens `https://app.uni.coach/report?token=...` on a phone
  with no login and no account, seeing the student's college list as a live cost
  table (the five comparison assumption sentences, a cross-school summary table,
  per-school living-cost detail, cited merit practice, debt context, and a
  sources block); `revoke_cost_report_share` kills it, and revoke means every
  link ever sent is dead. The token is derived, never stored —
  `HMAC-SHA256(shareTokenSecret, row id)`, with only the SHA-256 hash in the
  row, so re-sharing reproduces the same link while a database leak yields none,
  and rotating the secret is a global revoke; one live share per student
  (partial unique index). `publicWeb.urlBase` is now the single public-web
  origin feeding both the verify-email and report links. Migrations 0073 + 0074,
  coach prompt **v15** (rollback `COACHING_SYSTEM_PROMPT_VERSION=v14`);
  unsetting `COST_REPORT_SHARE_TOKEN_SECRET` leaves the feature dark and
  declining honestly. Gate: 2405 JVM tests, 0 failures; 431 shell assertions, 0
  failures. **S5's two PREFER edges (`money/02`, `money/03`) were already
  satisfied when it started, so nothing was overridden.** Next: S6
  (`first-value/06/invite-your-parent`), now UNBLOCKED — its BLOCKS edge was
  exactly this slice's report surface and token.

## Slice IDs

Permanent IDs for this brief's slices (`first-value/<milestone>/<name>`). The
old letters stay valid as references; the prose and ledger above are left as
written.

| Old  | ID                                    | State          |
| ---- | ------------------------------------- | -------------- |
| S1   | first-value/01/net-price-and-debt     | LANDED RFC 133 |
| S2   | first-value/02/money-profile          | LANDED RFC 134 |
| S3   | first-value/03/know-your-real-price   | LANDED RFC 135 |
| S3.5 | first-value/03.5/college-list-in-chat | LANDED RFC 136 |
| S4a  | first-value/04a/admissions-data       | LANDED RFC 140 |
| S4b  | first-value/04b/admissions-in-chat    | LANDED RFC 148 |
| S5   | first-value/05/family-cost-report     | LANDED RFC 155 |
| S6   | first-value/06/invite-your-parent     | NOT STARTED    |

Per-slice dependencies (`Needs:` lines) live in `spec.md`.

## The question

Unicoach v1 is chat-centric and reads as "another AI chat app." Before public
launch, which feature should we build (or finish) so a first-time student or
parent immediately sees value that ChatGPT / competitors don't offer?

## Candidates

| ID | Candidate                                                                                     | Existing foundation in repo                                           |
| -- | --------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| C1 | Rich institution database — every post-HS institution, incl. data not readily on the internet | `colleges` + Scorecard ingest (RFC 82), CollegeSearchTool (RFC 67/94) |
| C2 | Top-schools / college list surface                                                            | `college_list_entries` (RFC 91), fit-lens (RFC 98)                    |
| C3 | Todo / roadmap to keep students on track                                                      | Synthesis commitments + triggers (RFC 93), periodic tasks (RFC 97)    |
| C4 | Financial piece — what will it actually cost                                                  | Scorecard has net-price fields; nothing surfaced                      |
| C5 | Parent–student collaboration                                                                  | Nothing; auth is single-user                                          |

## Success criteria for the decision

- The chosen bet is visibly different from generic AI chat within the first
  session of a new user.
- It compounds: later candidates get cheaper or better because it exists.
- It is buildable to a launchable slice by /ship in weeks, not quarters.
- Parents (the payers — paid-subscriptions exists) can see the value.

## Process (pilot of the product layer)

FRAME -> PRIORITIZE (Ian gates) -> DISCOVER -> SPEC & SLICE (Ian gates) ->
EXECUTE via /ship -> LEARN

Research fan-out (parallel subagents), reports land in `research/`:

1. `competitor-scan.md` — landscape, table stakes vs. gaps
2. `data-feasibility.md` — can we build the data moat; sources, cost, ToS
3. `user-value.md` — what students/parents value and pay for; parent-payer
   dynamics

## PRIORITIZE — synthesis of the three research reports

All reports in `research/`. Three independent agents converged:

| Rank | Bet                                                  | Verdict across reports                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| ---- | ---------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1    | **C4 cost truth**, powered by a scoped C1 data slice | #1 in competitor-scan and user-value. Cost opacity is the top family pain (Princeton Review 2026: #1 stressor at 37%; Sallie Mae: 79% eliminate schools on cost alone). Competitors are structurally conflicted — lead-gen paid by colleges. NPT4 net-price-by-income-band and earnings/debt-by-major are ALREADY in the Scorecard ingest; the scarce layer (CDS H2A real merit-aid practice, C7 admissions factors) is buildable for ~$1-5K/cycle with an open-source seed corpus (collegedata.fyi). |
| 2    | **C5 parent collaboration — one thin slice**         | Parent is the payer (counselor packages $4-6.5K; 75% of parents want direct communication) but live-monitoring backfires. v1 slice: student-initiated shareable **Family Cost Report** — gives the parent a reason to subscribe with zero surveillance surface. Full collaboration is a fast-follow, not v1.                                                                                                                                                                                          |
| 3    | **C3 roadmap**                                       | Invisible coach scaffolding on RFC 93 commitments; table-stakes retention spine, never the pitch.                                                                                                                                                                                                                                                                                                                                                                                                     |
| 4    | **C2 top-schools list**                              | Fully commoditized (every competitor has search/match/chancing; 57% of parents already use ChatGPT for this). Keep RFC 91 as scaffolding; leading with it guarantees the "another chat app" read.                                                                                                                                                                                                                                                                                                     |
| 5    | **C1 as a standalone product**                       | Not a headline feature ("rich database" is not an aha) — it is the FUEL for C4 and the coach. Build the Admissions Intelligence Layer (~CDS C+H, deadlines, prompts for ~1,200 schools) behind the cost feature and the chat tool, cited with receipts.                                                                                                                                                                                                                                               |

**Positioning candidate:** "Every other app helps colleges find students.
Unicoach is the coach your family pays — so it tells you the truth, starting
with the price."

**First-session aha:** "Know your real price" — net price + merit-aid estimate
for each school on the student's list, in session one, with citations.

## Gate 1 decision (defaults, veto or amend)

- D1. v1 differentiator = C4 cost truth ("Know your real price"). DEFAULT: yes
- D2. Data build = scoped Admissions Intelligence Layer (CDS C+H, deadlines,
  merit practice) for ~300-500 schools at launch, seeded from collegedata.fyi +
  existing Scorecard NPT4; NO net-price-calculator automation. DEFAULT: yes
- D3. Include the thin C5 slice (student-initiated Family Cost Report) in the v1
  scope. DEFAULT: yes
- D4. C2 list and C3 roadmap stay scaffolding; no new headline work. DEFAULT:
  yes
