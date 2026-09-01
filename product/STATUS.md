# Product status

One page, five jobs: a glanceable TL;DR of the next steps, what the product does
today (a user manual, one entry per feature), what we are building next
(prioritised, with honest in-progress state), a backlog for unscheduled ideas,
and paste-ready prompts to kick off new sessions. **/chart reads this file first
and updates it after every landed slice** — if this file and a brief disagree,
the brief's ledger wins and this file gets fixed.

Updated: 2026-09-01 — `money/04/where-youll-live` (brief 0003 M4) LANDED as
**RFC 152** (`main@f7fcc99c` + `5d067bf0`), and with it **brief 0003 is
COMPLETE**. The coach now leads with the one way of living the family said they
plan. Ian rejected the slice's one-global-field model at the gate: preference is
global, feasibility is a fact about the student–college pair, so
`money_profiles.living_plan` (migration 0070) is the default,
`college_list_entries.living_plan` (0071) is a nullable per-college override,
and resolution is override → default → show all three (brief 0003 **D20**). When
a total cannot be shown, the answer names which kind of silence it is — our
unanswered residency, a price we cannot select, or a part the school does not
publish — on a wire key separate from what the school published. Coach prompt
**v14** (migration `0072.seed-coach-system-prompt-v14.sql`, rollback
`COACHING_SYSTEM_PROMPT_VERSION=v13`). Gate: 2318 JVM tests, 0 failures, plus
the full `bin/pre-commit` hook. Before it, `search/04/similar-colleges` (RFC
153, prompt v13) completed brief 0004's core; `search/05/consumer-sweep` (RFC
154, prompt v12) added `find_college`; the `colleges` codebook foreign-key
fast-follow (migration 0067); `money/03/comparison-contract` (RFC 151);
`search/03b/the-index` (RFC 150); and `money/02/component-split` (RFC 149). **At
this moment the next free RFC number was 157** (155 and 156 are claimed by live
runs) **and the next free migration was 0073.** Four concurrent runs collided on
coach prompt v12 during this slice; the rule that settled it is that a run
claims its prompt version and migration numbers from the **rebased** tree
immediately before commit, never from its own design doc. Slices carry permanent
IDs and declared `Needs:` edges, and readiness is computed by `slice-board`
rather than written down here.

At that moment the next free RFC was **157** (155 and 156 are claimed by live
runs) and the next free migration was **0073**. Those two numbers are a snapshot
and are almost certainly stale by the time you read them: recompute both at run
time with the commands below, and never copy a number out of a document.

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

1. **Brief 0003 — clear money language — COMPLETE. `money/04/where-youll-live`
   LANDED as RFC 152 (`main@f7fcc99c` + `5d067bf0`, 2026-09-01), and with it
   every slice in the brief.** The coach now leads with the one way of living
   the family said they plan, instead of offering three and letting them pick —
   a $7,368/yr swing at the worked example, larger than that school's in-state
   tuition. **The slice spec was wrong and Ian caught it at the gate:** it
   modelled the plan as ONE global field, but living at home is possible at the
   in-state school and impossible at the far one. Preference is global;
   feasibility is a fact about the student–college pair, and our data can never
   decide it, because the Scorecard prices a commuter category at essentially
   every school. So there are two places (brief 0003 **D20**):
   `money_profiles.living_plan` is the default,
   `college_list_entries.living_plan` is a nullable per-college override, and
   resolution has exactly one home — **override → default → show all three**.
   `with_family` is never inferred by us; where the default is assumed onto a
   school the coach names the assumption, and the correction is stored as that
   school's override. **When it cannot show a total it now says which kind of
   silence it is** — our unanswered residency (one question closes it), a
   published price we cannot select, or a part the school does not publish — on
   two separate wire keys, so what the school published can never be confused
   with what we have not asked yet. Because the invitation needs two _priced_
   arrangements, the coach asks where you live AFTER which state you live in,
   which is money/01.2's ordering arrived at a second time. Coach prompt **v14**
   (migration 0072, rollback `COACHING_SYSTEM_PROMPT_VERSION=v13`). Gate: 2318
   tests, 0 failures, plus the full hook. **Nothing in brief 0003 is startable —
   the brief is done.**

2. **Brief 0004 — college search index — CORE COMPLETE. Every slice has landed
   (RFCs 139, 144, 147, 150, 154 and now 153, `search/04/similar-colleges`,
   2026-09-01); only `search/06/unattended-refresh` is left, and it is DEFERRED
   by intent.** The last slice answers Ian's second founding query. A
   **`similar_colleges`** chat tool decides "similar" per call and runs one
   query over `college_search_index`: the default universe, the caller's hard
   constraints, a weighted-distance `ORDER BY` and `LIMIT <= 10`. No new table,
   no precompute, no method registry. Five axes — size, selectivity and price
   over RFC 150's percentile columns (their first reader), setting by locale
   equality, and subject mix by Jaccard over `subject_slugs`. A bare "schools
   like Bowdoin" ranks size + selectivity + setting, same control, active
   four-years; **price is deliberately not a default**, or a question about
   character would silently become a question about budget. **Unknown data is
   dropped, counted and named, never substituted**, and outcome percentiles are
   never an axis (gate-1 ruling). "But cheaper" and "but where I'd likely get
   in" are anchor-relative constraints echoed back in words; the second also
   drops selectivity as an axis. Coach prompt **v13** (migration 0069, rollback
   `COACHING_SYSTEM_PROMPT_VERSION=v12`) sends a school named in words through
   RFC 154's `find_college` first. Gate: 2062 tests executed, 0 failures, plus
   the full hook. Before it S5 gave chat a name→id path, S3b made the aha real —
   one derived `college_search_index` serving both search paths, a 181-subject
   taxonomy, nine slug-bound filters — S3a made the stored federal codes
   explainable, S2 ingested the IPEDS attribute layer, and S1 gave fuzzy name
   search and honest counts. **Nothing in 0004 is startable now.** The debt it
   leaves is in the Backlog: the `NewCollege` test fixture is a 5th copy and the
   shared helper is in the wrong source set, parked twice over.

3. **Brief 0001 S4 COMPLETE — S4a (RFC 140) and S4b (RFC 148, 2026-08-30).** The
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

4. **Brief 0003's earlier slices, for context.** M3
   (`money/03/comparison-contract`, RFC 151) closed the brief's honesty story
   for side-by-sides: any answer covering two or more schools carries
   `comparison_basis` — five facts, each with a code AND the sentence the coach
   may say, including the aid basis, which had never been on the wire. M2 (RFC
   149) replaced one blended number with three living arrangements over six
   ingested Scorecard components, a labelled blank never a zero. M1.2 (RFC 145)
   put residency before income. M4 above completes the set.
5. **S5 Family Cost Report, then S6 invite-your-parent** — the rest of Beat 1;
   S6 is the wedge and its token becomes Beat 2's parent-account claim path.
   S5's two PREFER edges (0003 M2+M3) have both landed, so nothing is holding
   it.
6. **Before any App Store submission: brief 0002, account deletion** — parked in
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

### Where you'll live, and what that costs (brief 0003 M4, RFC 152)

**Door:** the chat coach. When a cost answer would change materially depending
on where the student lives, the coach invites the question in the flow of the
answer — never as a form. Saying "I'd live at home" changes the totals in the
same turn.

Two places hold the answer, because it is two facts. **Your usual plan** lives
on the money profile (`on_campus` / `off_campus` / `with_family`) and means
"where I'd live when I have the choice". **A per-school override** lives on the
college-list entry and means "not at this one". A Seattle family can live at
home for the in-state school and cannot for the far one, and no data we hold can
decide that — the Scorecard prices a commuter category at essentially every
school. So the family decides, and the coach never infers living at home.
Resolution is **override → default → show all three arrangements**.

What the coach does with it: it **leads** with the resolved plan and names it in
the student's words ("living at home"), and says whether that came from what
they said about this school or from their usual plan. The other two arrangements
stay in the answer, so "what if he lived on campus there instead?" is still
answerable in the same turn.

**When there is no total, it says which kind of silence it is.** Three cases,
each labelled: _we have not been told which state the student is a resident of_
— our gap, and one question closes it; _we cannot tell which of this school's
published prices applies_ — also our gap, but no question the family can answer
closes it; and _the school does not publish every part of that way of living_ —
the school's gap, said plainly. The first two are never reported as the school
publishing no price. In all three the coach quotes the parts that are there,
names what is missing, and never adds up what is there and calls it a total.

**Degrades (guided, not gated):** with no answer, every cost surface works
exactly as it did before — all three arrangements, each labelled. A decline is
permanent and never re-raised. The invitation only appears where the school has
at least two _priced_ arrangements, so it is never asked where the answer would
change nothing — which means at a public school it follows the residency
question rather than competing with it.

**Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v13`. The v13 prompt row is
immutable and stays in the catalog; the columns are additive and unread by the
older prompt.

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
  `is_active` is not tri-state (Backlog). The `colleges` state/locale foreign
  keys, which did not land with S3b, landed in the 2026-08-31 fast-follow
  (migration 0067): `state` and `locale` now reference the published codebooks,
  and so does `college_search_index.state`.

### Naming a school in chat and having the coach find it (brief 0004 S5, RFC 154)

**The door:** the chat coach. Say "add Mizzou to my list" — or name any school
in words, including a misspelling or a nickname — and the coach looks it up and
acts on it. No new screen and no user action; live on the next `service` deploy
once migration 0068 has run.

Until this landed, fuzzy name resolution existed in exactly one place and was
reachable only over REST (the iOS college-list picker). `search_colleges` takes
no free text and `update_college_list` demands a UUID, so a school the student
named in words had no path to a `college_id` in conversation.

- **What it does:** a new `find_college` chat tool resolves a name to a college
  over the picker's own `CollegeSearchService.searchByName` — the same
  one-keystroke matching the picker uses (RFC 146). No new SQL, no new table, no
  DDL. Coach prompt **v12** tells the coach to resolve a named school with
  `find_college` and then use the returned id **verbatim** for
  `update_college_list`, the cost tool and the admissions tool;
  `search_colleges` stays for attribute-shaped discovery ("small public schools
  in Maine"). Two tools, one division of labour.
- **How it degrades — honestly, in three different ways:** an index that has not
  been built yet says **the search is unavailable**, and never says the school
  does not exist. A **blank** name is refused by name — the tool says which of
  its own fields was empty, rather than reporting "no school by that name" for
  an input that named nothing. A real **zero-match** is an honest "no school by
  that name", not an error. An over-long name is a rejected input, not a failed
  search, so the coach asks for a shorter name instead of apologising for an
  outage.
- **The module convention is now written down**, in `CollegeSearchService`'s
  KDoc and cross-referenced from `CollegesDao`: search goes through the service
  over `college_search_index`; point-reads by id or unit_id stay on `colleges`;
  ingest and versioning write `colleges`. Recorded ruling: admin-web's college
  browse stays on `colleges`, because it is an unfiltered browse of raw source
  columns the index does not carry.
- **Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v11`; the v11 row is immutable
  and stays in the catalog, so this is one environment variable, no migration.
- **Honest limits, carried forward on purpose:** the `NewCollege` test fixture
  builder is now a 4th copy and the seed+rebuild transaction is duplicated, and
  there is still no shared `JsonTool` / `DelegatingChatTool` abstraction —
  `FindCollegeChatTool` is a line-for-line copy of `CollegeChatTool`. Both are
  best fixed when `search/04/similar-colleges` adds the next copy.

### Finding schools like the one you already love (brief 0004 S4, RFC 153)

**The door:** the chat coach. Name a school — "what are some schools like
Bowdoin?" — and the coach resolves the name to a college with `find_college`
(RFC 154) and then calls `similar_colleges`. No new screen and no user action;
live on the next `service` deploy once migration 0069 has run.

- **What it does:** answers with up to ten peers from one query over
  `college_search_index` — the default universe, the caller's hard constraints,
  a weighted-distance `ORDER BY`, `LIMIT <= 10`. There is no similarity table
  and nothing is precomputed: "similar" is chosen per call, because there is no
  single true answer to what makes two schools alike (Ian, gate 1: "I'm not sure
  it even makes sense to pre-define it"). Five axes are available — **size**,
  **selectivity** and **price** over RFC 150's percentile columns, **setting**
  by locale, and **subject mix** by overlap of the 181-subject taxonomy. A bare
  "schools like X" ranks size + selectivity + setting, holds control constant,
  and stays inside active four-years.
- **Price is not a default, on purpose.** Ranking on price by default would turn
  a question about what a school is like into a question about what it costs.
  You get it by asking: **"like X but cheaper"** and **"like X but where I'd
  likely get in"** are anchor-relative asks — the tool expands them against the
  anchor's own numbers and the coach says in words what it did. The second one
  also stops ranking on selectivity, because otherwise the ranking pulls back
  toward the anchor while the constraint pushes away from it.
- **Outcome measures are never a similarity axis** (gate-1 ruling). Earnings and
  completion are reported and cited; they never decide who is "like" whom.
- **How it degrades — by naming what it could not judge, never by guessing:** an
  axis the anchor has no data for is dropped for the whole query and reported
  with its reason; a candidate missing an axis is scored on the rest and says
  which axes it was scored on; a candidate that shares no axis at all is
  excluded and counted. Nothing is substituted, averaged in, or treated as zero.
  An index that has not been built says the search is unavailable rather than
  answering zero. **Every response names each axis and each constraint it
  actually used**, so the coach's explanation is literally the query that ran.
- **Reproducibility is traded away on purpose** (Ian, gate 2: "This is okay to
  give up"). A future surface that needs a stable peer list pins a preset then.
- **Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v12`. The tool stays registered
  but un-prompted, so the coach stops reaching for it — one environment
  variable, no migration.

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

| Pri | Work                                       | State                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | Where                                    |
| --- | ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| P1  | College search index (brief 0004)          | **CORE COMPLETE** — gates 1+2 approved (2026-08-27); every specced slice has landed: `search/01/honest-name-search` (RFC 139, matching later replaced by RFC 146), `search/02/ipeds-attributes` (RFC 144), `search/03a/published-codebooks` (RFC 147), `search/03b/the-index` (RFC 150), `search/05/consumer-sweep` (RFC 154) and `search/04/similar-colleges` (RFC 153, 2026-09-01). S3b was the aha — the derived index serves both search paths. S5 turned out to be an audit (RFC 150 had already repointed every consumer, so there was nothing to delete) and closed the real gap instead with the `find_college` chat tool. S4 closes the brief: `similar_colleges` answers "schools like X" with one query-time weighted distance over the index, no similarity table, on coach prompt **v13** — and it is the first and only reader of the percentile columns S3b computed. The triggered `colleges` state/locale foreign-key fast-follow also LANDED (`main@9789b823`, migration 0067). **Nothing here is startable.** `search/06/unattended-refresh` stays DEFERRED — automate the quarterly ingest only if running it by hand proves annoying. The debt S4 declined moved to the Backlog: the 5th `NewCollege` fixture copy, and genericising `CollegeSearchOutcome`. | `product/0004-college-search-index`      |
| P1  | Clear money language (brief 0003)          | **COMPLETE — every slice landed.** `money/01` + `01.1` + RFC 143 + `01.2` + `02` + `03` + **`04/where-youll-live`** (RFCs 141–143, 145, 149, 151, 152; 2026-08-28 to 09-01). The coach asks residency before income, prices three living arrangements from six ingested Scorecard components, states the assumption lines above any side-by-side, and now leads with the one way of living the family said they plan — a global default with a per-college override, because living at home is possible at the in-state school and not at the far one (D20). When it cannot show a total it says which kind of silence it is: our unanswered residency, a price we cannot select, or a part the school does not publish. Prompt v14; v13 is the rollback. Nothing left in this brief.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `product/0003-clear-money-language`      |
| P1  | Beat 1 remainder: `first-value/05` → `/06` | **`first-value/04` COMPLETE** — split into `04a/admissions-data` (RFC 140) and `04b/admissions-in-chat` (RFC 148), both landed; cited merit answers are live in chat. Next: `first-value/05/family-cost-report`, whose two PREFER edges (`money/02`, `money/03`) have both landed — **IN FLIGHT** as `rfc-155` (verifying), so do not start it again; ask the board. Then `first-value/06/invite-your-parent`, which BLOCKS on it.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | `product/0001-v1-differentiator/spec.md` |
| P3  | `bin/state-apply` (RFC 138)                | **Landed** (v1: users world file, create-only). Per-entity replace/reset waits on brief 0002's delete engine — see Backlog.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `bin/state-apply`                        |

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

- **The money profile's `(value, status)` pairs are not a type** — "answered
  with no value" is representable in `:db`'s models and caught only by the
  database CHECK. A sealed `StoredAnswer<T>` would make it unrepresentable.
  Flagged by review on RFC 152 and declined there as a repo-wide refactor at the
  end of a run; it touches income band and residency, not just the living plan.
- **The tri-state parse ladder now exists in three copies across two surfaces**
  (income band, residency, living plan; REST routes and the chat tools). Four
  RFC 152 review lenses flagged it independently. The extraction belongs on
  `StudentScopedChatTool`, which already declares itself the home for tool input
  scaffolding — but it rewrites code no single slice owns, so it wants its own
  slice.
- **The money-profile wire echo is maintained by hand in two files**
  (`MoneyProfileChatTool` and `CollegeCostChatTool`); RFC 152 added its third
  field to both by hand. One emitter, one test.
- **The living-plan invitation is not suppressed by a per-college override.** A
  school-level answer never closes the global question, so a student who has
  overridden one school still sees the invitation on that school's result.
  Deliberate in RFC 152 and documented in the enum, but it is worth a product
  decision about whether the coach should ask once and stop.

- **The `NewCollege` test fixture is duplicated five times, and the shared
  helper is in the wrong source set** — parked TWICE now: RFC 154 declined it,
  RFC 153 declined it again after adding the fifth copy. The 33-field builder
  exists as a shared helper, but it sits in `:db`'s **test** source set rather
  than `testFixtures`, so `:college` cannot see it and every college-module test
  base writes its own. The fix is to move it to `testFixtures` and delete the
  copies; that touches ~9 files across modules, which makes it **its own slice**
  rather than a fix smuggled into the next feature. It should be scheduled — a
  thing declined twice on the grounds that the next slice will pay for it is a
  thing nobody is paying for.
- **Genericise `CollegeSearchOutcome<out P>` to absorb
  `CollegeSimilarityOutcome`** — declined in RFC 153. It would dedup three
  near-identical result arms, but it ripples into the sibling search feature's
  call sites, so the change is wider than the duplication it removes. Worth
  doing beside the fixture slice, not on its own.
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

### College search index (brief 0004) — nothing to start

**Brief 0004's core is COMPLETE.** Every specced slice has landed: `search/01`
(RFC 139, matching replaced by RFC 146), `search/02` (RFC 144), `search/03a`
(RFC 147), `search/03b` (RFC 150), `search/05` (RFC 154) and
`search/04/similar-
colleges` (RFC 153, 2026-09-01). The old paste-ready prompt
for `search/04` was removed on the day it landed, so nobody starts a finished
slice.

The only slice left is **`search/06/unattended-refresh`**, and it is **DEFERRED
by intent**, not blocked: a `periodic_jobs` quarterly cron enqueueing the
ingest, seeded `enabled = FALSE`, worth building only if running the ingest by
hand quarterly proves annoying. If you want it, un-defer it in
`product/0004-college-search-index/spec.md` first — the board will never call a
DEFERRED slice READY.

What the brief left behind, if you are picking up the debt: the two Backlog
items above (the fifth `NewCollege` fixture copy with the shared helper stranded
in `:db`'s test source set, and genericising `CollegeSearchOutcome`). Both are
real, both were declined with reasons, and the first has now been parked twice.

### Account deletion (brief 0002) — parked in Backlog, kept ready

PASTE: Run product brief 0002 (product/0002-account-data-deletion/brief.md) with
/chart: account data deletion. The brief is FRAMED and awaiting gate 1 — bring
me its six decisions (D1–D6) with defaults before any code. Legally required
(GDPR Art. 17, CCPA, App Store 5.1.1(v) in-app deletion) and it gives us
repeatable clean-slate testing. The schema actively refuses deletion today — all
mapped in the brief. I approve every new table personally, with visible DDL at
the gate. Note RFC 138 (bin/state-apply) deliberately deferred its delete/reset
semantics to this brief's engine.

### Clear money language (brief 0003) — nothing to start

Every slice in this brief has landed: `money/01` (RFC 141), `01.1` (142), RFC
143, `01.2` (145), `02` (149), `03` (151) and `04/where-youll-live` (152). There
is no kickoff prompt because there is nothing to kick off.

What the brief leaves behind, for whoever works near this code next — all of it
true in code and none of it allowed to regress: an arrangement missing a part
carries no total; missing data is a labelled blank, never a zero; unanswered
residency withholds the tuition line and every total rather than guessing;
`net_price − tuition` is forbidden and a test scans the source for it; only
same-vintage figures may be summed; no bare source code reaches a tool result
(RFC 143's guard); a multi-school answer states its basis lines before the
numbers; and a resolved living plan LEADS an answer without removing the other
arrangements from it. Two vocabularies must stay apart: `ArrangementGap` says
what the **school** published, `NoTotalReason` says where **we** are silent.

Its declined items are in the Backlog above, not lost: `StoredAnswer<T>`, the
tri-state parse ladder's third copy, the two-file wire echo, and the question of
whether a per-college override should stop the coach asking for a usual plan.

### Beat 1 remainder — S5 / S6

PASTE: Ship S<n> from product brief 0001: use the slice instruction in
product/0001-v1-differentiator/spec.md verbatim as the /ship instruction, plus
gate-2 decisions D7–D12 as standing context. Update the brief's ledger and
product/STATUS.md when the slice lands.
