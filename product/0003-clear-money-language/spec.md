# Brief 0003 — SPEC & SLICE (gate 2 draft)

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

## M1 — The money language standard (copy-only, small)

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

## M2 — The component cost split (medium; adds columns, DDL at the gate)

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
  0045 exactly: `room_board_on`, `room_board_off`, `books_supply`,
  `other_expense_on`, `other_expense_off`, `other_expense_family`. **No nonneg
  CHECK is required by the data, but follow the sibling-column house pattern —
  present the DDL at the gate and let Ian rule (0001 D10).**
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

## M3 — The comparison contract (small/medium)

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

## M4 — Where you'll live (medium; adds one money-profile field, DDL at the gate)

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
