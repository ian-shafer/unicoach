# Brief 0003 — SPEC & SLICE

**Gate 2 APPROVED by Ian 2026-08-28** (D10-D19). Slices are /ship instructions;
a /ship RFC may refine implementation detail, but the decisions here are binding
— a change comes back as a new numbered decision.

Gate-1 decisions (Ian, approved 2026-08-28, verbatim in `brief.md`): build the
money language as four slices M1-M4; the axis is **price vs estimate**, not
CCTI's billed-vs-unbilled; "room and board" is retired for "housing and food";
the displayed total is the **component sum on a named living arrangement**, with
`COSTT4_A`/net price kept labelled as blends; the comparison contract is visible
copy; per-year framing by default; IPEDS and CDS deferred; M2+M3 land before
brief 0001's S5; no new tables.

Standing rules inherited from brief 0001: **0001 D10** (Ian personally approves
any DDL, shown explicitly at the /ship gate — this brief's `ALTER TABLE`s count)
and **0001 D12** (value before ask: invite, allow stop/resume/later, degrade
gracefully, never gate on completion).

## DISCOVER — corrections to what the brief assumed

Grounded against the code on 2026-08-28. Where this section and a slice's prose
disagree, this section wins; where this section and the code disagree, the code
wins.

1. **The six component columns are absent everywhere, but the pattern is
   well-worn.** `CollegeScorecardLoader.mapInstitution` reads only `COSTT4_A`,
   `TUITIONFEE_IN/OUT`, `NPT4*`, `NPT41..45*`, `GRAD_DEBT_MDN`,
   `MD_EARN_WNE_P10`. Adding a column is the `db/schema/0045` five-file change:
   migration (`ALTER TABLE colleges` + `ALTER TABLE colleges_versions` +
   `CREATE OR REPLACE FUNCTION log_college_version()`, picked up by the existing
   `trigger_04_log_college_version` by name), `College`/`NewCollege`,
   `CollegesDao` select/upsert lists, one `intInDomainOrNull(...)` per column in
   the loader, then a re-ingest of the same pinned CSV (idempotent upsert on
   `unit_id`).
2. **The data is already in the pinned snapshot.** `ROOMBOARD_ON`,
   `ROOMBOARD_OFF`, `BOOKSUPPLY`, `OTHEREXPENSE_ON`, `OTHEREXPENSE_OFF`,
   `OTHEREXPENSE_FAM` are present with real values in the committed fixture
   `college/src/test/resources/scorecard-institutions-real-fixture.csv` (3,308
   columns, header identical in membership and order to the live release). No
   new download, no new source, no licence question.
3. **`CostField` is the one shared vocabulary and must stay that way.**
   `service/.../costs/CostField.kt` is a six-entry enum whose `wireName`s are
   both the tool's JSON keys and the only things `data_availability` can name.
   New figures are new entries there, never ad-hoc JSON keys.
4. **`NetPrice`, `CollegeControl` and `TuitionApplicable` already encode the
   honesty rules in the type system** (a band cannot be attached to an overall
   average; a private college cannot carry in-state/out-of-state). Extend that
   style; do not add a stringly-typed basis field beside it.
5. **We have NO data-vintage information — only an ingest year.**
   `CollegeCostProfile.ingestYear` is derived from `colleges.updated_at` and the
   tool prints "data ingested YYYY". That is the year we loaded the file, not
   the academic year of the figures. The components are AY2022-23 while
   `COSTT4_A`/`NPT4*` are AY2021-22, and D5's contract requires printing the
   real vintage. M2 must introduce per-figure academic-year labels for the
   pinned snapshot (documented constants tied to the snapshot, not derived from
   `updated_at`), and the "data ingested" phrasing must stop being used as if it
   were a vintage.
6. **`money_profiles` is a two-field tri-state table** (`income_band`,
   `residency_state`, each with a `*_status` in `unanswered|answered|declined`
   and a `value IFF answered` CHECK), with a `money_profiles_versions` mirror
   and a `log_money_profile_version()` writer. M4's living-arrangement field
   follows that shape exactly, including the versions mirror and the IFF check.
7. **There is no iOS cost surface at all** (`grep` over `ios/**` finds no
   cost/price view). Every slice here is reachable through the chat coach only.
   That is acceptable for M1-M4 — but it means brief 0001's S5 report is the
   first place this language meets a parent who is not in the chat, which is why
   D8 orders M2+M3 before it.
8. **The coach prompt is immutable-and-versioned.** A copy change is a NEW seed
   file (`db/schema/00NN.seed-coach-system-prompt-vN.sql`) following
   0044/0047/0048, with the previous version left in the catalog as the rollback
   knob (`COACHING_SYSTEM_PROMPT_VERSION`). `SystemPromptCatalogTest` and the
   prompt fixtures (RFC 129) must be updated in the same slice.
9. **Ingest observability is NOT ours to build — brief 0004 owns it.** While
   this brief was being framed, brief 0004 (college search index) was approved
   through both gates and is EXECUTING. Its S1 absorbs the ingest-observability
   item (snapshot provenance, header assertion, change summary) and its S1/S2
   restructure `bin/ingest-colleges` into a two-phase run (phase 1 upserts
   normalised source tables including `colleges`; phase 2 rebuilds a derived
   `college_search_index` in one transaction). **M2 edits the same loader**, so
   M2 must land after 0004 S1/S2 and rebase onto the two-phase shape rather than
   duplicating the observability work — and its six new columns must be
   considered for inclusion in the derived index. Migration number 0049 and RFC
   139 are already claimed by 0004; M2 claims the next free ones.

## Open questions carried into the slices

- **OQ1 (from Ian, gate-1 amendment A1).** How far from home do students
  actually enrol? Needed only for M4's optional travel personalisation. Web
  search is unconfigured (no Serper key) and distance-from-home is in neither
  Scorecard nor IPEDS, so this must come from a cited primary source (NCES,
  HERI, or ACT) or **M4 personalises housing only** and the coach says nothing
  quantitative about travel distance. Default if unresolved: personalise housing
  only.

---

## money/01/language-standard (M1) — The money language standard (copy-only, small)

**Needs:** —

**Why it exists.** Every number the coach already gives is honest; the words
around them are improvised. One glossary, spoken identically everywhere, is what
makes an apples-to-apples comparison legible to a parent and a student at the
same kitchen table. Ships with no schema change, so the existing live cost
feature gets better immediately.

**Build on.** Coach prompt v4 (`db/schema/0048`) — its cost paragraph is the
thing being replaced; `SystemPromptCatalogTest`; RFC 129 prompt fixtures.

**Decided here.** The vocabulary is `research/vocabulary.md`'s glossary as
amended by gate 1:

| Say                                                                       | Never say                                |
| ------------------------------------------------------------------------- | ---------------------------------------- |
| tuition and fees (always naming in-state / out-of-state where it matters) | tuition (alone), sticker price           |
| housing and food                                                          | room and board, board                    |
| books, travel, and everyday spending                                      | miscellaneous, incidentals               |
| total cost for a year                                                     | COA, cost of attendance (abbreviated)    |
| grants and scholarships (money you don't pay back)                        | gift aid, free money                     |
| loans (money you pay back, with interest) — the word "loan" every time    | self-help, unmet need                    |
| what you'd actually pay / net price (glossed once)                        | out-of-pocket, average annual cost, NPT4 |
| financial aid offer                                                       | award, award letter                      |
| Student Aid Index (only if the user raises it)                            | EFC                                      |

Plus three rules the prompt states outright: **loans and work-study are never
subtracted from a price**; **the school sets tuition and fees, and estimates
everything else** (price vs estimate — gate-1 D2); and v4's existing net-price
basis rule is carried over **verbatim** (lead with the band-specific figure,
name the overall average as such, never estimate, always attribute).

**Left to /ship's design phase.** The prose of the prompt paragraph, and whether
the glossary also lives as a Kotlin doc-comment constant near `CostField`.

**Explicitly NOT in this slice.** Renaming `CostField.wireName`s. Those are
model-facing keys and M2 restructures them anyway; renaming twice is churn.

**Acceptance criteria.**

- A new immutable seed lands coach prompt v5; v4 stays in the catalog and
  `COACHING_SYSTEM_PROMPT_VERSION=v4` still rolls back cleanly.
- The v5 body carries the glossary, the ban list, the three rules, and v4's
  net-price basis sentence unchanged.
- Prompt catalog tests and RFC 129 fixtures updated in the same commit.
- **Reachability:** every cost answer in the chat coach, from the next deploy;
  no user action required.
- **First-session test:** a new student who asks "what will this cost?" hears
  "tuition and fees", "housing and food", and a net price whose basis is named —
  and never hears "room and board" or "sticker price".

---

## money/01.1/bands-in-dollars (M1.1) — Name the band in dollars, never in source jargon (small; inserted after M1 landed)

**Needs:** BLOCKS money/01/language-standard — extends the vocabulary prompt
version that slice created.

**Why it exists.** Found in the first phone test of M1 (Ian, 2026-08-28): the
coach described a figure as the **"Q5 net price"**. It explained the meaning
correctly, but "Q5" is College Scorecard's internal quintile label — exactly the
jargon this brief exists to eliminate, and a term no family can read.

**Root cause, grounded.** Nothing in our wire says "Q5". `college_cost_profile`
emits `income_band: "over_110k"` and `net_price.basis: "your_income_band"`, and
the `net_price_qN` names are column names that never leave the DAO. The model
supplied "Q5" from its own knowledge of the Scorecard because **we never handed
it the human phrase**. `IncomeBand.bracket` ("$110k+") already exists and its
KDoc calls it "the one home for display copy" — but it is used only in the
money-profile tool's _description_, never in the cost tool's _answer_.

So this is the impossible-misuse shape: the coach was left to name a bucket we
gave it only a code for, and it reached for the source's vocabulary. The durable
fix is on the wire; the prompt rule is the belt to that braces.

**Decided here.**

- `college_cost_profile` emits a human band label beside the code, from
  `IncomeBand.bracket` — one home, already written — everywhere a band appears
  (`net_price.income_band` and the `money_profile` echo).
- Coach prompt **v6**: never name a data source's internal buckets, codes or
  field names — not Q1-Q5, not NPT4, not quintiles, not `net_price_q5`. Name the
  band by its dollar range ("families earning $110,000 or more"). This
  generalises beyond income bands: source jargon of any kind is banned from
  user-facing copy.
- The wording of `IncomeBand.bracket` itself is reviewed for reading aloud
  ("$110k+" is a spreadsheet label; "$110,000 or more" is a sentence).

**Left to /ship's design phase.** The JSON key for the label; whether the
`bracket` strings are rewritten in place or given a second, prose form.

**Acceptance criteria.**

- Every band the tool emits carries a dollar-range label the coach can say
  verbatim.
- A test asserts the cost tool's band output contains no `qN`-style code beyond
  the enum's own `value`, and that the label is present whenever a band is.
- Prompt v6 carries the source-jargon ban; v5 remains the rollback.
- **Reachability:** every band-specific cost answer in the chat coach.
- **First-session test:** a student whose income band is recorded hears "for
  families earning $110,000 or more" — never "Q5", "NPT45", or "quintile 5".

---

## money/01.2/residency-before-income (M1.2) — Ask where they live before asking what they earn (small; inserted after M1.1 landed)

**Needs:** BLOCKS money/01.1/bands-in-dollars — reuses the single
band/dollar-range emitter it introduced.

**Why it exists.** Ian, 2026-08-28: residency is the biggest lever on the
_stable_ half of the cost, and it is a far cheaper question than household
income. He is right, and the gap is worse than an ordering problem — **coach
prompt v6 never mentions residency at all.** It asks for the household income
band three times over and never once asks what state the family lives in, even
though `money_profiles.residency_state` has existed since RFC 134 and
`TuitionApplicable` is `UNKNOWN` without it.

**The evidence, measured on our own `colleges` table.**

| Question                            | What knowing it corrects                                            | Median size                                                                 |
| ----------------------------------- | ------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| What state do you live in?          | in-state vs out-of-state tuition at a public (n=1,689)              | **$6,300/yr** (in-state $5,507 vs out-of-state $11,010 — 2x)                |
| What is your household income band? | the net price, versus the overall average we already show (n=3,223) | **$1,376/yr** for a middle-band family ($1,983 lowest band, $5,007 highest) |

So for the typical family the cheap question is worth roughly **4.5x** the
sensitive one. The asymmetry is sharper than the numbers alone: residency
selects a **published price** we can state exactly, while the income band
selects an **average within a bracket** — a statistic about other families.
Value before ask (0001 D12) points the same way as the arithmetic.

**The honest limit.** Residency only moves tuition at **public** colleges (1,689
of 3,612 reporting tuition — 47%). A list of only private schools gains nothing
from it, and there the income band is the whole story. This is a re-ordering and
a repair, not a demotion of the income band.

**Decided here.**

- Coach prompt **v7**: ask residency first, and ask it at all. The invitation is
  offered when a **public** college is on the list and residency is unanswered —
  naming what it unlocks ("whether you'd pay the in-state or out-of-state
  price"). The income-band offer follows, unchanged in substance.
- The tool's in-answer invitation stops being income-only: `precision_offer`
  gains a residency case, so the coach is cued by the result rather than by
  memory. A public college with `tuition_applicable: "unknown"` is the trigger.
- Both offers keep the 0001 D11/D12 ethos verbatim: invite, accept a decline
  permanently, never gate an answer on either field.

**Left to /ship's design phase.** Whether `precision_offer` becomes a list or a
richer object; whether the residency ask is one question or two (state, then
"have you lived there a while?" — residency is a legal test, not an address).

**Acceptance criteria.**

- With a public college listed and residency unanswered, the coach offers to
  record the state before it raises income, and says what that unlocks.
- With only private colleges listed, no residency offer is made — it would buy
  nothing.
- A declined residency question is never re-raised, and every cost answer still
  works without it, naming the basis it used.
- **Reachability:** the chat coach, in the flow of a cost answer.
- **First-session test:** a student with one in-state public on their list is
  asked what state they live in, and sees the tuition line change from "$5,507
  in-state / $11,010 out-of-state" to the one number that applies.

---

## money/02/component-split (M2) — The component cost split (medium; adds columns, DDL at the gate)

**Needs:**

- BLOCKS search/01/honest-name-search and search/02/ipeds-attributes — D15 ("M2
  lands after 0004 S1/S2, rebasing onto the two-phase `bin/ingest-colleges`")
  and D17 ("M2/M3 land after 0004 S1/S2 and before 0001's S5"); DISCOVER #9
  gives the reason — M2 edits the same loader those slices restructured. LANDED
  as RFC 139 and RFC 144. **SATISFIED.**
- CONFLICTS search/03/the-index — both edit `bin/ingest-colleges` phases and
  `CollegesDao.search`/`search_colleges`. Rebase risk only. NOT an order.

NOTE: money/02/component-split was recorded for weeks as blocked on search/03.
It never was.

**Why it exists.** Tuition and fees is a median of only 37% of a public
four-year's on-campus total; the other 63% is the part a family can actually
influence, and today we cannot see it. This slice is what makes the product's
one irreplaceable sentence sayable: _living at home instead would cost $7,368
less — most of a year's tuition._

**Build on.** RFC 133's column-add pattern (`db/schema/0045`),
`CollegeScorecardLoader.mapInstitution`, `CollegesDao`, `CostField`,
`CollegeCostService`/`CollegeCostChatTool` (RFC 135), `bin/ingest-colleges`.

**Decided here.**

- Six nullable INTEGER columns on `colleges` + `colleges_versions`, mirroring
  `0045` exactly (`ALTER TABLE` x2 +
  `CREATE OR REPLACE FUNCTION
  log_college_version()`, picked up by the
  existing `trigger_04_log_college_version` by name). Whole USD per year,
  AY2022-23:

  | Column                 | Scorecard field    | Meaning                              |
  | ---------------------- | ------------------ | ------------------------------------ |
  | `housing_food_on`      | `ROOMBOARD_ON`     | housing + food, living on campus     |
  | `housing_food_off`     | `ROOMBOARD_OFF`    | housing + food, renting off campus   |
  | `books_supply`         | `BOOKSUPPLY`       | books and supplies (one figure, all) |
  | `other_expense_on`     | `OTHEREXPENSE_ON`  | travel + personal, on campus         |
  | `other_expense_off`    | `OTHEREXPENSE_OFF` | travel + personal, off campus        |
  | `other_expense_family` | `OTHEREXPENSE_FAM` | travel + personal, living at home    |

  Six, not seven: there is no `ROOMBOARD_FAM`, which is why `with_family`
  renders no housing line rather than a `$0` one.
- All six carry a **nonneg CHECK** (D19). They are gross costs, not net of
  anything, so a negative is meaningless and the check catches a loader bug.
  This deliberately differs from `0045`'s band columns, which have no such check
  because Scorecard publishes legitimate negative NET prices.
- The tool gains a per-college **breakdown keyed by living arrangement** —
  `on_campus`, `off_campus`, `with_family` — each carrying tuition and fees
  (selected by the existing `TuitionApplicable`), housing and food, books-and-
  other, and the arrangement's total. `with_family` carries **no** housing and
  food line because the source has no `ROOMBOARD_FAM`; that absence is explicit,
  never a zero.
- **`has_on_campus_housing = false` is a first-class case**, distinct from "not
  reported" — 296 bachelor-predominant schools legitimately have no residence
  halls. New `CostField` entries for the components; the no-dorms case is its
  own signal, not a `data_availability` entry.
- **Forbidden, in code and in prompt:** summing figures of different vintages;
  presenting `COSTT4_A` as the same number as the component sum; and computing
  `net_price − tuition` in any form. Aid applies to the blend, not to a
  component.
- Per-figure academic-year vintages become documented constants for the pinned
  snapshot (components AY2022-23; `COSTT4_A`/`NPT4*` AY2021-22), replacing the
  "data ingested YYYY" phrasing as the vintage claim (DISCOVER #5).
- **Ingest observability is not built here** (DISCOVER #9): brief 0004 S1 lands
  the header assertion, provenance and change summary. M2 rebases onto the
  two-phase `bin/ingest-colleges` and relies on that summary to prove the six
  columns actually loaded.
- A prompt version (v6) teaching the coach to lead with the split, name the
  arrangement, and mark the estimate lines as estimates.

**Left to /ship's design phase.** The JSON shape of the breakdown object;
whether the arrangement totals are computed in `CollegeCostService` or rendered
from raw components by the tool; the exact wording of the change summary.

**Acceptance criteria.**

- Migration + loader + DAO + models land together and a re-ingest of the pinned
  snapshot populates all six columns; the change summary shows the non-null
  counts.
- The tool returns the three arrangements with the four lines each, the no-dorms
  case where it applies, and "not reported" where the school is genuinely
  silent.
- No code path computes `net_price − tuition`; a test asserts it.
- **DDL is presented explicitly at the /ship approval gate** (0001 D10).
- **Reachability:** the chat coach, on any cost question about a listed school.
- **First-session test:** a student with a public university on their list asks
  what it costs and gets tuition and fees, housing and food, and other costs as
  three named lines with a stated living arrangement — plus the at-home
  comparison when the school reports it.

---

## money/03/comparison-contract (M3) — The comparison contract (small/medium)

**Needs:** BLOCKS money/02/component-split — builds on M2's breakdown object.

**Why it exists.** A side-by-side is where cost advice either earns trust or
quietly lies. The failure is always the same shape: a dollar figure is a
statistic about a population, a year, and a living arrangement, and the label
hides all three.

**Build on.** M2's breakdown; `MoneyProfileStatuses`; `TuitionApplicable`; the
existing `NetPrice.basis` labelling; RFC 124 markdown house style (three-column
table limit on a phone).

**Decided here.**

- Whenever two or more schools appear together, five assumption lines appear
  **above** the table as ordinary copy, not a disclaimer: whose price (averages
  for first-year full-time federal-aid recipients, not a quote); residency held
  constant and stated per school; living arrangement held constant and named;
  the year and its vintage; aid basis (grants and scholarships only — loans are
  not subtracted).
- The stable block (tuition and fees) renders above the estimate block (housing
  and food; books, travel and everyday spending), visually separated.
- **Missing data is a labelled blank, never a zero, never interpolated**; "no
  residence halls" says so.
- The tool returns a `comparison_basis` object carrying those facts so the coach
  reports them rather than composing them from memory.

**Left to /ship's design phase.** Whether `comparison_basis` is per-call or
per-college; the exact table layout within the three-column phone limit.

**Acceptance criteria.**

- Any multi-school cost answer carries all five lines, with residency named per
  school.
- A school missing a component renders a labelled blank and is never summed as
  zero.
- Schools on different residency or arrangement bases are never silently mixed
  into one column.
- **Reachability:** the chat coach, whenever a student asks about more than one
  school — the most common cost question there is.
- **First-session test:** "compare these three" returns a table a parent can
  read aloud, with the assumptions stated before the numbers.

---

## money/04/where-youll-live (M4) — Where you'll live (medium; adds one money-profile field, DDL at the gate)

**Needs:** BLOCKS money/02/component-split — the living-plan field selects among
M2's three arrangements.

**Why it exists.** The arrangement choice is a $7,368/yr swing at the worked
example — larger than its in-state tuition. Until the family says which one they
mean, every total we show is a guess with a label on it.

**Build on.** RFC 134 `money_profiles` + `update_money_profile` tool; M2's
breakdown; 0001 D11/D12 (never forced).

**Decided here.**

- One tri-state field pair on `money_profiles` +`money_profiles_versions`:
  `living_plan` in `on_campus|off_campus|with_family` and `living_plan_status`
  in `unanswered|answered|declined`, with the same value-IFF-answered CHECK.
- **Never forced** (0001 D11/D12): the coach invites when the arrangement would
  change the answer materially, accepts a decline permanently, and every cost
  surface degrades — unanswered means we show the on-campus basis, **named as
  such**, plus the swing to the cheapest reported arrangement.
- Per **OQ1**, M4 personalises **housing only** unless a cited primary source on
  distance-from-home lands first; the coach makes no quantitative claim about
  travel.

**Left to /ship's design phase.** Whether this extends the existing
`update_money_profile` tool or adds a sibling; the invitation's trigger point.

**Acceptance criteria.**

- The field can be set, changed, declined and resumed across sessions, entirely
  in chat; a decline is never re-raised.
- With no answer, cost surfaces still work and name the basis they used.
- **DDL presented explicitly at the /ship approval gate** (0001 D10).
- **Reachability:** the chat coach; the invitation is offered in the flow of a
  cost answer, not as a form.
- **First-session test:** a student who says "I'd live at home" sees their
  totals drop by the housing and food line, immediately, in the same turn.

## Gate 2 decisions (defaults)

- **D10.** Slice order M1 → M2 → M3 → M4; M1 can start immediately and is
  independent of the rest. DEFAULT: yes.
- **D11.** M1 ships copy only; `CostField` wire names are renamed once, in M2.
  DEFAULT: yes.
- **D12.** The living-arrangement breakdown is exposed as three arrangements,
  with `with_family` carrying no housing line (no source data) rather than a
  zero. DEFAULT: yes.
- **D13.** "No on-campus housing" is modelled as its own case, not as missing
  data. DEFAULT: yes.
- **D14.** M2 introduces documented per-figure academic-year vintages and
  retires "data ingested YYYY" as a vintage claim. DEFAULT: yes.
- **D15.** M2 does **not** build ingest observability — brief 0004 S1 owns it —
  and M2 lands after 0004 S1/S2, rebasing onto the two-phase
  `bin/ingest-colleges`. Whether the six component columns also enter 0004's
  derived `college_search_index` is settled in M2's /ship design phase. DEFAULT:
  yes.
- **D16.** OQ1 unresolved ⇒ M4 personalises housing only and the coach makes no
  quantitative travel-distance claim. DEFAULT: yes.
- **D17.** Sequencing across briefs: M1 is independent and can land now; M2/M3
  land after 0004 S1/S2 (loader collision) and **before** 0001's S5 (Family Cost
  Report), so the parent-facing artifact is born speaking this language. 0001 S4
  is unaffected. DEFAULT: yes.
- **D18. (Ian, 2026-08-28) Schema vocabulary follows the product vocabulary, not
  the retired source vocabulary:** the columns are `housing_food_on` /
  `housing_food_off`, not `room_board_*`. The Scorecard field name lives in the
  column comment, per the `0015` house pattern (`cost_attendance` for
  `COSTT4_A`). Schema, `CostField` wire names and user copy therefore all speak
  one language. DECIDED.
- **D20. (Ian, 2026-09-01) A living plan is a global default PLUS a per-college
  override, not one global field.** M4's slice text decided "one tri-state field
  pair on `money_profiles`". That collapses two different facts: _preference_
  ("we'd rather he lived at home") is global, _feasibility_ ("he can only live
  at home if the school is commutable") is a fact about the student–college
  pair. A Seattle family can live at home for UW and cannot for UCSD, and our
  data can never tell us which: the Scorecard publishes an `OTHEREXPENSE_FAM`
  figure for essentially every school because schools price a commuter category
  regardless of any one student, and D16 bars us from travel-distance claims. So
  `money_profiles.living_plan` is the default (tri-state, as specified) and
  `college_list_entries.living_plan` is a nullable per-college override, `NULL`
  meaning "use the default"; resolution is override → default → show all three.
  `with_family` is never inferred by us — it applies only where the family said
  so, and where the default is assumed onto a school the coach names the
  assumption. Carried by RFC 152. DECIDED.

- **D19. (Ian, 2026-08-28) All six component columns carry a nonneg CHECK,**
  matching the sibling `cost_attendance`/`tuition_*` columns and deliberately
  unlike `0045`'s net-price band columns. DECIDED.
