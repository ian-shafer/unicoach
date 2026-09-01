# Product status

One page, five jobs: a glanceable TL;DR of the next steps, what the product does
today (a user manual, one entry per feature), what we are building next
(prioritised, with honest in-progress state), a backlog for unscheduled ideas,
and paste-ready prompts to kick off new sessions. **/chart reads this file first
and updates it after every landed slice** — if this file and a brief disagree,
the brief's ledger wins and this file gets fixed.

Updated: 2026-09-01 — `money/03/comparison-contract` (M3) landed as RFC 151: a
cost answer covering two or more schools now carries `comparison_basis`, five
labelled facts stated above the table, and the coach speaks them on prompt
**v11** (migration 0066, rollback `COACHING_SYSTEM_PROMPT_VERSION=v10`). It also
put the aid basis on the wire for the first time. Before it,
`search/03b/the-index` (RFC 150) and `money/02/component-split` (RFC 149) landed
on 2026-08-31. Slices carry permanent IDs and declared `Needs:` edges, and
readiness is computed by `slice-board` rather than written down here.

At that moment the next free RFC was **152** and the next free migration was
**0067**. Those two numbers are a snapshot and are almost certainly stale by the
time you read them: recompute both at run time with the commands below, and
never copy a number out of a document.

**No RFC or migration number is recorded in this file, on purpose.** They are
claimed by live runs in other worktrees, so any number written here is wrong
within the hour. Compute both at the start of a run and again immediately before
committing, scanning **every** worktree — a claimed-but-unlanded number is
invisible from `main`:

    for w in $(git worktree list --porcelain | sed -n 's/^worktree //p'); do
      ls "$w/db/schema" | grep -Eo '^[0-9]+' | sort -n | tail -1; done | sort -n | tail -1
    ls rfc | grep -Eo '^[0-9]+' | sort -n | tail -1
    git branch -a --format='%(refname:short)' | grep -Eo 'rfc-[0-9]+'

Live runs, from any checkout: `.prime/agent/skills/ship/scripts/ship-status`

**Slice IDs are `<brief>/<milestone>.<step>/<name>`** — `first-value` = brief
0001, `deletion` = 0002, `money` = 0003, `search` = 0004. The old letter follows
in parentheses on first mention. IDs are permanent: a slice is never renumbered,
and one inserted later takes a decimal step (`first-value/03.5`). **The number
never grants or denies permission to start — only the `Needs:` line does.**
Start a slice with the **`slice` skill**: "start work on
`search/04/similar-colleges`". Invoked with no ID, it prints the board — that is
the answer to "what can I kick off?".

## TL;DR — next steps, most important first

1. **Brief 0004 — college search index — S1, S2, S3a and now S3b LANDED (RFCs
   139, 144, 147, 150; the last on 2026-08-31). The aha is real: "small public
   schools in Maine with a literature program" is answerable end to end.** One
   derived `college_search_index`, rebuilt whole by an ingest phase, now serves
   both search paths — the old `college_programs` join and every old `colleges`
   filter clause are deleted. An authored 181-subject taxonomy turns what a
   student says into CIP codes (1,690/1,710 codes, 405/405 series, 38/38
   families, every prefix validated fatally against RFC 147's published
   codebooks), and nine new filters bind codebook **slugs** with real foreign
   keys instead of raw federal codes. The coach speaks it on prompt **v10**
   (migrations 0064/0065; rollback `COACHING_SYSTEM_PROMPT_VERSION=v9`). What
   came before: S1 gave fuzzy name search and honest counts (matching later
   replaced by RFC 146), S2 ingested the IPEDS attribute layer, S3a made the
   stored federal codes explainable. **Next in 0004:
   `search/04/similar-colleges`, which this slice UNBLOCKED** — it was 0004's
   only BLOCKS edge, and its weighted distance reads the percentile columns S3b
   computes (nothing reads them yet). Run the `colleges` state/locale
   foreign-key fast-follow BEFORE it starts (Backlog). Then
   `search/05/consumer-sweep`.

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
   **Next for 0001: `first-value/05/family-cost-report` (S5)**, and **both of
   its PREFER edges are now satisfied** — `money/02` (RFC 149) and `money/03`
   (RFC 151) have landed, so the parent-facing artifact is born speaking the
   component split and carrying the five assumption lines. Nothing is holding
   S5.

3. **Brief 0003 — clear money language — M1, M1.1, RFC 143, M1.2, M2 and now M3
   LANDED (RFCs 141–143, 145, 149, 151); only M4 is left.**
   `money/03/comparison-contract` landed 2026-09-01 and closes the brief's
   honesty story: a side-by-side no longer lets a column label hide what the
   number assumes. Any cost answer covering two or more schools now carries
   `comparison_basis` — five facts, each with a code AND the sentence the coach
   may say: whose price it is; the residency held constant, with a scope code so
   an all-private table is never given a caveat about public tuition, and a
   per-school `tuition_basis`; the living arrangements comparable across
   **every** school, plus who lacks which and why; the academic year per
   vintage; and the **aid basis — the published price minus grants and
   scholarships, loans and work-study never subtracted, which had never been on
   the wire before**. Coach prompt **v11** (rollback
   `COACHING_SYSTEM_PROMPT_VERSION=v10`) says those five lines above the table,
   puts tuition and fees above the estimated living costs, keeps
   rows-are-schools inside RFC 124's three-column phone cap, and leaves a
   labelled blank rather than a zero. Before it, M2 (RFC 149) replaced the one
   blended number with three living arrangements. **Next and last for this
   brief: `money/04/where-youll-live` (M4)**, which now has real components to
   personalise.
4. **S5 Family Cost Report, then S6 invite-your-parent** — the rest of Beat 1;
   S6 is the wedge and its token becomes Beat 2's parent-account claim path.
   S5's two PREFER edges (0003 M2+M3) have both landed, so nothing is holding
   it.
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

### What a school costs, split into parts you can act on (brief 0003 M2, RFC 149)

One blended number became three priced living arrangements.

- **How a user reaches it:** ask the coach what a school costs — any cost answer
  in the chat coach about a school on the college list. No user action, live on
  the next `service` deploy after the migrations run.
- **What it does:** for each listed school the `college_cost_profile` tool
  returns `cost_by_living_arrangement` — `on_campus`, `off_campus`,
  `with_family` — each with tuition and fees, housing and food, books and
  supplies, other expenses, and that arrangement's total. The coach leads with
  the split, names which arrangement it is quoting, and marks the estimated
  lines as estimates. Living at home can now be priced against living on campus.
- **Where the numbers come from:** the same pinned Scorecard snapshot we already
  ingest — `ROOMBOARD_ON/OFF`, `BOOKSUPPLY`, `OTHEREXPENSE_ON/OFF/FAM` — landed
  as six nullable columns on `colleges` (migration 0062), each with a nonneg
  CHECK.
- **How it degrades:** an arrangement missing a part shows the parts it has and
  **no total** — a partial sum is never presented as a total. `with_family`
  shows no housing and food line at all, because the source publishes no such
  figure; that absence is explicit, never a `$0`. A school silent on a component
  says so in `data_availability`. If we do not know the family's state, the
  tuition line and every total are withheld rather than guessing a residency,
  and the coach asks the residency question instead.
- **"No residence halls" is an answer, not a gap:** read from IPEDS
  `offers_housing`, and reported whenever known. If a school flagged as having
  no housing nonetheless publishes on-campus figures, the published figures win,
  the flag is shown beside them, and the coach is told to say both — we never
  hide a number the school published.
- **Years are stated, not implied:** each academic year is emitted with the list
  of figures it covers (components and tuition are AY2022-23; the blended
  published price and net price are AY2021-22). Median debt and median earnings
  are dated by no source we hold, so they carry no year rather than borrowing
  one. The old "data ingested YYYY" phrasing — which was when we loaded the
  file, never the year of the figures — is gone.
- **Never mixed:** nothing computes `net price − tuition` (aid applies to the
  blend, not to a part), the blended cost of attendance is never presented as an
  arrangement total, and only same-vintage figures enter a sum. All three are
  enforced by tests, one of which scans the cost package's source for the
  arithmetic itself.
- **Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v8`; the v8 row is immutable and
  stays in the catalog.

### Comparing schools without hiding the assumptions (brief 0003 M3, RFC 151)

**The door:** the chat coach. Ask about more than one school on your college
list — "compare these three" — and the answer arrives as a short table with five
plain sentences above it.

A dollar figure is a statistic about a population, a year, a residency and a way
of living. A bare column label hides all four, which is how a side-by-side
quietly lies. So whenever a cost answer covers two or more schools, the tool now
returns one `comparison_basis` object and the coach states it **before** the
numbers, as ordinary copy rather than a disclaimer at the bottom:

- **whose price** — averages for first-year, full-time students who received
  federal aid, not a quote for your family;
- **the residency held constant**, said per school. An all-private table is
  never given a caveat about public tuition, and a mixed table names which
  schools the caveat is about;
- **the way of living held constant** — and it names only the arrangements every
  school in the table is actually priced for. The moment one column has no
  figure, "held constant" is false and the coach says so;
- **the academic year** each figure comes from;
- **the aid basis** — a net price is the published price minus grants and
  scholarships. Loans and work-study are never subtracted. This one had never
  been on the wire before.

Tuition and fees — the price the school sets and publishes — renders above the
estimated living costs, and the coach says which block is which. Rows are
schools, inside RFC 124's three-column phone cap, or it says it as a list.

**How it degrades.** A school that does not report a part gets a labelled blank,
never a zero, never a neighbour's number, and never a total summed from the
parts that happen to be there. A school with no residence halls is said to have
none, not "unreported". A school whose type we cannot recognise says plainly
that no published price can be selected for it, rather than being dropped or
guessed at. One school only? No comparison object rides at all — a one-school
answer is already fully labelled and must not be narrated as a comparison.

**Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v10`. The v10 row is immutable and
stays in the catalog, so this is one environment variable, no migration.

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

### Searching for colleges by what they are (brief 0004 S3, RFCs 147 + 150)

"Small public schools in Maine with a literature program" — asked in plain
English, answered honestly.

- **How a user reaches it:** the coach in chat. It calls `search_colleges` on
  coach prompt **v10**; no user action and no new screen. Live on the next
  `service` deploy once the migrations and an ingest have run.
- **What it does:** filters on **subject** (a 181-subject taxonomy —
  "literature" means CIP 23.01, 23.13, 23.14 _and_ 16.0104 Comparative
  Literature, because a person authored that), **state**, **size**,
  **selectivity**, **price**, **religious affiliation**, **test policy**,
  **Carnegie class**, **athletics**, **ROTC**, **study abroad** and **housing**
  — all against one derived `college_search_index` rather than a join across the
  raw tables. The filters bind codebook slugs with real foreign keys, so no
  federal code reaches the model and none has to be looked up at query time.
- **What it reports:** `total_matches` (the true count, not the size of the page
  it returned), a per-filter `excluded_unknown` count, and `source_years` for
  the figures it used.
- **How it degrades — the honest part:** an index that has not been built yet
  **says so** instead of answering zero (a deploy migrates first, then ingests;
  between the two, search states its own emptiness). A word the vocabulary does
  not know is a **named refusal that lists the vocabulary** — never a silent
  empty result, and never presented as "the search broke". An attribute a school
  does not report is **counted in `excluded_unknown`, never read as "no"** —
  filtering on it excludes the school and says how many it excluded.
- **Where the data comes from:** the same ingest, in two new phases — `subjects`
  loads the authored `db/data/subjects.json` (every CIP prefix validated fatally
  against RFC 147's published codes: 1,690 of 1,710 codes, 405/405 four-digit
  series, 38/38 families), and `search-index` rebuilds the index whole in one
  transaction and records the row count in the `college_index_build` provenance
  row.
- **Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v9`; the v9 row is immutable and
  stays in the catalog.
- **Honest limits:** `credential_level` **left** `search_colleges` — the program
  census carries bachelor's first majors only, so the filter had one legal value
  and asserting a choice would have been a lie. It returns only if
  `college_programs_census` ever carries more than that. The percentile-rank
  columns are computed but read by nothing until `search/04/similar-colleges`.
  `is_active` is not tri-state (Backlog), and the `colleges` state/locale
  foreign keys did not land (Backlog, triggered).

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

| Pri | Work                                       | State                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | Where                                    |
| --- | ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| P1  | College search index (brief 0004)          | EXECUTING — gates 1+2 approved (2026-08-27); `search/01`→`search/05` specced, DDL approved. **`search/01/honest-name-search` (RFC 139, matching later replaced by RFC 146), `search/02/ipeds-attributes` (RFC 144), `search/03a/published-codebooks` (RFC 147) and `search/03b/the-index` (RFC 150, 2026-08-31) have all LANDED.** S3b is the aha: the derived index serves both search paths, the coach searches by subject on prompt v10, and the CONFLICTS edge with `money/02` cost only a rebase, exactly as a CONFLICTS edge predicts. Next: **`search/04/similar-colleges`**, unblocked by S3b and the only consumer of its percentile columns — but the `colleges` state/locale foreign-key fast-follow (Backlog) is triggered to run BEFORE it. Then `search/05/consumer-sweep`. | `product/0004-college-search-index`      |
| P1  | Clear money language (brief 0003)          | **`money/01` + `money/01.1` + RFC 143 + `money/01.2` + `money/02` + `money/03` LANDED** (RFCs 141–143, 145, 149, 151; 2026-08-28 to 09-01). The coach asks residency before income, prices three living arrangements from six ingested Scorecard components, and as of RFC 151 states the five assumption lines above any side-by-side from a per-call `comparison_basis` — including the aid basis, which had never been on the wire (prompt v11; v10 is the rollback). Next and last: **`money/04/where-youll-live`**, which has real components to personalise.                                                                                                                                                                                                                        | `product/0003-clear-money-language`      |
| P1  | Beat 1 remainder: `first-value/05` → `/06` | **`first-value/04` COMPLETE** — split into `04a/admissions-data` (RFC 140) and `04b/admissions-in-chat` (RFC 148), both landed; cited merit answers are live in chat. Next: `first-value/05/family-cost-report`, whose two PREFER edges (`money/02`, `money/03`) have both landed, then `first-value/06/invite-your-parent`.                                                                                                                                                                                                                                                                                                                                                                                                                                                              | `product/0001-v1-differentiator/spec.md` |
| P3  | `bin/state-apply` (RFC 138)                | **Landed** (v1: users world file, create-only). Per-entity replace/reset waits on brief 0002's delete engine — see Backlog.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `bin/state-apply`                        |

## Sequencing — ask the board, do not read a list

**What can I kick off right now?** One command answers it, from any checkout:

    .prime/agent/skills/slice/scripts/slice-board

It prints every slice as READY / IN FLIGHT / BLOCKED / DEFERRED / LANDED,
computed from the `Needs:` lines in each `spec.md`, the LANDED rows in the brief
ledgers, and live runs from `ship-status`. Blocked slices name the unmet target
**and its reason**. `-p` is porcelain; `-q` prints doc defects only. A non-zero
exit means a doc defect — a `Needs:` entry with no reason, an unknown target, a
slice with no `Needs:` line — and the fix is the doc, not the board.

**This file no longer lists what is startable, on purpose.** It used to, in
three places, and all three drifted — including once within an hour of being
written. Readiness is a fact about the repo at this second, so it is computed,
never remembered. What stays here is what only a person can write: the bet, the
user manual, priorities, and the backlog.

**The rules the board applies**, for reading its output:

- **BLOCKS** — technical, cannot proceed, not overridable.
- **PREFER** — product judgement. Never blocks; it prints beside a READY slice
  so an override is visible instead of hidden.
- **CONFLICTS** — both slices edit the same files. A rebase risk, **not** an
  order. A conflicting live run prints a warning and the slice stays READY.
- **`Status: DEFERRED`** — parked by intent. Never READY. "We chose not to yet"
  is not a dependency and must not be written as one.
- **Adjacency in an ID grants nothing.** `03` after `02` is a plan. Only the
  `Needs:` line is permission.
- A run counts as IN FLIGHT only when its ship state carries `SLICE=<id>`. An
  unstamped run is listed as unattributed and **never guessed at** — that guess
  is what once recorded `pipeline/rfc-147` as `search/03/the-index` when it was
  `search/03a/published-codebooks`.

Slices in the same state with no edge between them are safe to run in parallel,
in separate worktrees. Live runs and their phases: `ship-status`.

## Backlog

Valuable, unscheduled, unprioritised — the parking lot. Append freely (one
bullet, enough context to pick it up cold); /chart promotes an item into the
work table by giving it a priority, or into a brief when it deserves gates.
Nothing here is committed work.

- **`colleges` state/locale foreign keys — fast-follow, ruled by Ian at RFC
  150's gate. TRIGGER: run BEFORE `search/04/similar-colleges` starts.** The
  corpus measurement PASSED — every stored `state` and `locale` value resolves
  to a codebook row — so the blocker is not the data. It is that `us_states` and
  `nces_locales` are ingest-loaded and the test bases truncate them, so the
  constraints would fail tests that load no codebooks. Two places need seeding:
  `CollegeScorecardTestBase` (8 ingest suites) and the db-module college
  fixtures, both reusing `SearchIndexFixture.seedCodebooks`, which RFC 150
  added. The exact SQL is in RFC 150 `## Deferred` (D57). One design question
  belongs to that run: whether `--codebooks` becomes mandatory at the JVM entry
  point. RFC 147 already deferred this once, which is why it now carries a
  trigger and a run of its own rather than a hope.
- **`is_active` is not tri-state** — deferred by Ian at RFC 150's gate. The
  index column is `NOT NULL` and coalesces a missing IPEDS row to TRUE, so it
  asserts "open" about a college the ingest knows nothing about; on a
  Scorecard-only ingest it reads TRUE for every row and carries no information.
  The untaken fix: `is_operating BOOLEAN NULL` (TRUE when IPEDS reports active,
  FALSE when it reports otherwise, NULL with no IPEDS row) with the default
  universe reading `IS NOT FALSE` — behaviour-preserving for every real query,
  and it would let a result say "closed in 2019". See RFC 150 `## Deferred`. Not
  a defect S3b introduced: it is brief 0004 D18 carried forward.
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

PASTE: start work on `search/04/similar-colleges`.

FIRST, THOUGH: the `colleges` state/locale foreign-key fast-follow (Backlog) is
triggered to run before this slice starts. Do that run, or tell me you are
skipping it, before you begin S4.

WHAT LANDED BEFORE IT: S3b (RFC 150, migrations 0064/0065) built the derived
`college_search_index` — 30 columns, rebuilt whole by a `search-index` ingest
phase in one transaction — plus a 181-subject taxonomy and nine new filters
binding codebook slugs with real foreign keys. Both search paths now read the
index; the old `college_programs` join and every old `colleges` filter clause
are deleted. Read the code, not the spec's sketch of it.

CARRY THESE FORWARD, they are true in code and must not regress: filtering and
counting touch the index alone and only the returned rows join back for payload;
no raw federal code reaches a tool result; an unresolvable word is a named
refusal listing the vocabulary, never a silent empty result; unknown is counted
per filter (`excluded_unknown`) and never read as "no"; an unbuilt index says so
rather than answering zero; outcome measures are never ranked (gate-1 ruling).

S4 is the first and only reader of the index's percentile columns, which S3b
computes for enrollment, admission rate, SAT and net price. "Similar" is decided
per call — axes, weights and constraints chosen by the coach with visible
defaults, not precomputed (D8 as amended) — and every response names the axes
and constraints it used.

Gate decisions D1–D25 (brief.md + spec.md) are binding context. Note D25: the
subject taxonomy has no size cap, and the spec's "~60–100 subjects" phrase is
superseded. I approve every new table with visible DDL at the /ship gate. Claim
RFC and migration numbers at run time and re-check them immediately before
committing.

I am the approval gate. Land it, then update the brief ledger and
product/STATUS.md.

### Account deletion (brief 0002) — parked in Backlog, kept ready

PASTE: Run product brief 0002 (product/0002-account-data-deletion/brief.md) with
/chart: account data deletion. The brief is FRAMED and awaiting gate 1 — bring
me its six decisions (D1–D6) with defaults before any code. Legally required
(GDPR Art. 17, CCPA, App Store 5.1.1(v) in-app deletion) and it gives us
repeatable clean-slate testing. The schema actively refuses deletion today — all
mapped in the brief. I approve every new table personally, with visible DDL at
the gate. Note RFC 138 (bin/state-apply) deliberately deferred its delete/reset
semantics to this brief's engine.

### Clear money language — M4 (brief 0003; where you'll live)

PASTE: start work on `money/04/where-youll-live`.

WHAT LANDED BEFORE IT: M2 (RFC 149) put six Scorecard components in the database
and `cost_by_living_arrangement` on the wire. M3 (RFC 151) added the per-call
`comparison_basis` — five labelled facts said above any multi-school table — and
coach prompt v11. M4 personalises the housing choice on top of BOTH. Read the
code, not the spec's sketch of it.

CARRY THESE FORWARD, they are already true in code and must not regress: an
arrangement missing a part carries no total; missing data is a labelled blank,
never a zero; unanswered residency withholds the tuition line and every total
rather than guessing; `net_price − tuition` is forbidden and a test scans the
source for it; only same-vintage figures may be summed; no bare source code
reaches a tool result (RFC 143's guard); a multi-school answer states its five
basis lines before the numbers, and "held constant" is claimed only for
arrangements every school in the table is priced for.

M4's own decisions are in `product/0003-clear-money-language/spec.md` under
"money/04/where-youll-live", and D16 stands: **M4 personalises housing only, and
the coach makes no quantitative travel-distance claim.** Prompt v11 is live; the
next prompt version is v12. Claim RFC and migration numbers at run time and
re-check them immediately before committing.

I am the approval gate. Land it, then update the brief ledger and
product/STATUS.md.

### Beat 1 remainder — S5 / S6

PASTE: Ship S<n> from product brief 0001: use the slice instruction in
product/0001-v1-differentiator/spec.md verbatim as the /ship instruction, plus
gate-2 decisions D7–D12 as standing context. Update the brief's ledger and
product/STATUS.md when the slice lands.
