# 0017 — Beat the chatbots on cost

**Handle:** `cost` (brief 0017)

## Status

- **Phase:** EXECUTE — gates 1 and 2 passed (2026-11-04, 2026-11-05). Six
  `cost/` slices plus brief 0006's `shape/09`; wave 1 (`cost/01`, `cost/03`,
  `cost/04`, `shape/09`) is startable now — say _"/slice start work on
  `cost/01/the-latest-release`"_. The coordinator re-runs the six-school
  question after each land (D7, D14) and records it under LEARN.
- **Gate 1 (prioritisation):** **PASSED 2026-11-04 — Ian approved all seven
  defaults, verbatim: "approve"**, after two questions recorded under "Gate 1
  outcome" below. D1–D7 stand as written. D7 is `standing` for this brief.
- **Gate 2 (spec):** PRESENTED 2026-11-04 — D8–D14 in `spec.md`, defaults
  pre-chosen. **Amended 2026-11-05** on Ian's rule at the gate (recorded under
  "Gate 2 amendment" below and as D15, `standing`): `cost/01` and `cost/02`
  rewritten without years, `cost/06` added, D16 added. Re-presented the same day
  and **PASSED 2026-11-05 — Ian, verbatim: "Approve"**. D8–D16 stand as written;
  D15 is `standing` for every brief, D14 for this one.
- **Post-gate (2026-11-05):** D17 appended in `spec.md` — raised by Ian at RFC
  274's approval stop (`cost/01`): fourteen search and admissions figures stored
  on `colleges` with no year move to one `college_figures` table, each row
  carrying its own academic year. New slice
  `cost/07/the-figures-carry-their-year`, BLOCKED by `cost/01`; `cost/01`
  continues without a schema change.
- **Research:** 4 reports, 2,192 lines — `research/side-by-side-2026-11-04.md`
  (297 lines; the raw test, written by the coordinator from the browser
  session); `research/what-the-others-give.md` (643 lines, external, competitor
  practice and what families want, every fetch and failure in METHOD);
  `research/fresher-prices-and-exchanges.md` (503 lines, external, data
  feasibility: IPEDS and Scorecard release state, CDS section G, the six
  schools' own pages, the four exchange compacts, calculator links);
  `research/what-we-hold-today.md` (749 lines, internal, every claim
  `path:line`).
- **Ledger:** _(one line per landed slice: ID, RFC, SHA, one-line what —
  appended by /slice after each land.)_

  cost/01/the-latest-release LANDED as RFC 274 (main@3a2c3047, 2026-11-08) — the
  year on every Scorecard money and program figure is read from the publisher's
  dictionary as fetched and stamped on the row; `program_money_stats` gains
  `amount_usd_year`; an ingest refuses a dictionary that drops, re-words or
  back-dates a served variable.

Opened: 2026-11-04, by Ian, from a side-by-side test he ran: the same cost
question put to unicoach, Google's AI Overview, ChatGPT and Gemini.

    PHASE      FRAME -> [PRIORITISE gate 1] -> DISCOVER -> [SPEC & SLICE gate 2] -> EXECUTE -> LEARN
    NOW                                                                             ^^^^^^^ HERE

## The question

A Washington student with six schools on their list — UW Seattle, Western
Washington, Cal Poly SLO, UC Berkeley, San Diego State, Oregon State — and a
family income around $120,000 asked unicoach what the list costs. Then the same
question, as one typed line, went to Google's AI Overview, ChatGPT (logged out)
and Gemini (logged out, Flash-Lite). The full outputs are in
`research/side-by-side-2026-11-04.md`.

unicoach gave the most **trustworthy** answer and the least **useful** one.

It is the only one of the four that says whose numbers these are (averages for
aided first-years), what a net price is (published minus grants, loans never
subtracted), that the federal net price is an in-state figure and so does not
apply at the four out-of-state publics, and the one income-band net price a
$120k family actually wants. Gemini told that family to look at the Husky
Promise, a Pell-and-state-grant programme they cannot qualify for, and priced
Oregon State at $38,000–45,000 as if a competitive scholarship were the default.

And yet a student would learn more from the chatbots, on four counts:

1. **Our figures are two to three years old.** Every competitor cited the
   schools' own 2026-27 budgets. Our published-price components are the College
   Scorecard's, which its own dictionary dates to 2024-25, and our blended
   figures (the published cost of attendance and every net price) are 2023-24.
   On this list that puts UW about $2,000 low and UC Berkeley about $14,000 low,
   the most expensive school on the page. A student who checks our number
   against the school's website will trust the website.
2. **We missed the biggest lever on the list.** Oregon State participates in the
   Western Undergraduate Exchange (WUE), Washington is an eligible state, and
   the exchange caps tuition at 150% of the resident rate — roughly halving the
   out-of-state premium. Both chatbots said so. unicoach said "there is no
   comparable out-of-state figure", which at Oregon State is the wrong
   conclusion drawn from a true premise.
3. **At an out-of-state school we stop where the others coach.** "There is no
   figure" is honest. "Nonresident need-based aid is thin, so look at the
   school's merit grid and run its net price calculator" is honest AND tells the
   family what to do next. We hold the merit grids and the calculator links
   already.
4. **The shape loses to the table.** Two paragraphs of provenance before any
   number; no four-year total; and the follow-up question is where the student
   would live, where ChatGPT's follow-up asked the aid inputs (household size,
   savings) that would move the family towards a decision.

The question this brief answers: **how should unicoach's cost answer change so
that it beats a general-purpose assistant on usefulness without giving up the
accuracy and provenance that are the reason to prefer it?**

## Settled constraints — not re-litigated here

1. **The money vocabulary stands** (brief 0003): tuition and fees, housing and
   food, the published price, a financial aid offer; loans and work-study are
   never subtracted from a price.
2. **Never invent an out-of-state blend** (RFC 149,
   `ForbiddenCostArithmeticTest`): an out-of-state total is out-of-state tuition
   plus the school's own component estimates, which the cost tool already
   builds; never out-of-state tuition added to a blended in-state average.
3. **Every figure names its publisher, its kind and its year** (brief 0008: RFCs
   177, 179, 183). A fresher figure arrives through the same seam or not at all.
4. **Value before ask** (brief 0001 D12) and **Ian approves every new table with
   visible DDL** (0001 D10) bind every slice.
5. **The coach never scores or labels a school** (brief 0011, RFC 219). A WUE
   flag or a "next step" is a fact about the school and the family's state,
   never a recommendation to attend.

## Candidates

Each row names what the repo already holds for it, from grep, not memory.

| #  | Candidate                                                                                                                                                                                                                                                                                                                                      | Existing foundation in the repo                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| -- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| C1 | **Fresher published prices.** The published-price components (tuition and fees by residency tier, housing and food, books, other) from the newest release the publisher has, cited by year — and, where a newer year exists only on the school's own page, that figure beside the federal one with its own publisher and read date.            | the IPEDS published-charges survey loaded today is `ic2023_ay.csv`, **2023-24** (a fetch artefact pinned by digest in `db/seed/ipeds/PROVENANCE.json`; the CSVs themselves are gitignored); the Scorecard pin (`CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR = 2024`, `BLENDED_AVERAGE_VINTAGE = 2023`, `ScorecardDictionaryPinTest`) dates charges to 2024-25. The canonical money tables (`db/schema/0083`) key every figure on `(source, academic_year)`, so a newer vintage is a seed bump plus a pin change, not a schema change. The CDS ingest (`bin/fetch-cds-seed`) parses section H only; section G (the school's own current-year charges) is not read. |
| C2 | **The exchange tier.** Where the family's state and the school both sit in a regional tuition exchange (WUE in the West; MSEP, NEBHE, SREB's Academic Common Market elsewhere), say so, give the capped price as a derived tier with a named basis ("if admitted under WUE: 150% of in-state tuition"), and say it is competitive where it is. | No roster, no tier. Brief 0006's `research/source-landscape.md` §6 designed exactly this ("a derived price tier with a named basis … never a stored dollar pretending to be a published price") and deferred it "until residency tiers exist". They exist now (RFC 157, migration `0075`, the in-state / out-of-state / in-district tiers in `CollegeCostChatTool`). `db/seed/automatic-awards/awards.csv` already carries `residency_basis` per award row.                                                                                                                                                                                                     |
| C3 | **A next step at every out-of-state school.** Beside "no family-specific figure": whether the school gives need-based aid to nonresidents (its own stated policy), its out-of-state automatic merit grid, and its net price calculator link — the three things a family can act on.                                                            | `db/seed/cds/aid_policy.csv` holds 22 stated aid-policy facts (section H), none of them about nonresidents; `awards.csv` holds `out_of_state` score grids (e.g. Alabama–Birmingham, Utah State); the IPEDS HD file carries each school's net price calculator URL (`NPRICURL`) but `IpedsLoader` does not load it and nothing serves it; the cost service already carries each school's automatic awards (`CollegeCostService.automaticAwards`) but the chat tool never emits them — only the Family Cost Report page does.                                                                                                                                     |
| C4 | **A four-year total for the student.** Each school's published price and price after grants over the programme's length, labelled as ours, the way the parent's coach already does it.                                                                                                                                                         | `FamilyMoneyPlanChatTool` (RFC 261) does this for the parent only; the student coach prompt (v51) has no all-years rule and `CollegeCostChatTool` emits per-year figures only.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| C5 | **Ask the aid inputs, estimate the aid.** Household size and non-retirement assets on the money profile, a Student Aid Index estimate from the published federal formula, and from it a family-specific figure at every school rather than at the two in-state ones.                                                                           | `money_profiles` holds income band, residency state, living plan and FAFSA dependency (migrations `0046`, `0070`, `0078`; RFC 270's web page); no household size, no assets, no SAI arithmetic anywhere. `FederalAidPolicyService` holds the Pell maximum and income tests (RFC 159). The largest candidate by far and the only one that puts a number we computed in front of a family.                                                                                                                                                                                                                                                                        |
| C6 | **Table first, provenance after.** Lead the cost answer with the per-school table, say the provenance once underneath, and make the follow-up question the one that changes the answer most.                                                                                                                                                   | Brief 0009 (`concise`) did this for the Family Cost Report page; its quiet-provenance slice `concise/07` was CANCELLED (0009 D16, 2026-09-11), so nothing is in flight here; coach prompt v51 lines 36–60 and 135–148 fix the money sentences but not their order. The follow-up offer is `PrecisionOffer` (RFC 145): residency first, then income, then living plan.                                                                                                                                                                                                                                                                                           |

## Success criteria for the decision

Re-run the same six-school question after the chosen slices land. unicoach must:

1. show published prices no more than one academic year behind the schools' own
   current pages, and say which year each figure describes;
2. name the Western Undergraduate Exchange at Oregon State for a Washington
   family, with the capped price as a labelled tier and the word "competitive"
   where the school's own page says so;
3. give a next step at each out-of-state school that is a fact we hold, not a
   guess;
4. keep every figure attributed to a named publisher, kind and year — nothing
   the chatbots got wrong (Husky Promise at $120k, a scholarship as the default
   price) may appear;
5. not be longer than today's answer.

A candidate that cannot be measured against these in the first session after it
lands is not ready to slice.

## Ranked table

What the three reports agree on, candidate by candidate. "Value" is for the
family in the test and for families like them; "effort" is the internal report's
smallest landable slice; "risk" is the feasibility report's honest risk. The two
findings that reorder the brief's own framing are marked **▲**.

| Rank | Bet                                                                 | What the others give (external)                                                                                                                                                                                                                                                                                                                                                                           | Feasibility (external)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | What we hold (internal)                                                                                                                                                                                                                                                                                                          | Verdict                                                                                                                                                                                                                                                                                        |
| ---- | ------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1    | **C1 fresher prices** — Scorecard bump now, CDS section G beside it | Currency was the chatbots' one clear win; ChatGPT's Berkeley $93,944 reconciles to the school's page to the dollar. Families rate AI accurate "largely because of visible source citations". **▲** The Scorecard the chatbots are "beating" is the same 2023-24 cohort we show.                                                                                                                           | **▲** The Scorecard release of 2026-11-02 already dates charges to **2025-26** and blends to **2024-25** — a fetch bump and a re-dated dictionary transcription, two pin tests as the guard, no loader. IPEDS renamed its charges file (`COST1_{yyyy}`); `IC2024_AY` does not exist; the newest is 2024-25, so an IPEDS bump buys only the fallback year. **CDS section G is the only structured source a year AHEAD**: 2025-26 filings carry 2026-27 charges, 0.1–0.7% off Oregon State's own page; 276 schools filed for 2025-26, 51 publics with an out-of-state figure. School pages: six schools, six layouts, every total a judgement — not a scraper. | `price_figures` keys on `academic_year`, so two years coexist with no migration; `ScorecardDictionaryPinTest` fails BY DESIGN on a bump until the transcription is re-dated; an IC_AY bump displaces 29,704 Scorecard rows under `ORDERED_SOURCES` — a decision, not a drop-in. CDS: a fifth fact group in `bin/fetch-cds-seed`. | **Do first.** The Scorecard bump is the cheapest year we will ever buy. Section G is the only way to match the schools' own year and arrives through the publisher-kind-year seam we already have. Defer IPEDS `COST1` until 2025-26 ships (~Dec 2026). No page scraper, no curated page seed. |
| 2    | **C2 the exchange tier** — WUE first, as a labelled possibility     | Residency was worth ~$40k/yr to this family; WUE at Oregon State ~$18k/yr but a ~30% competitive award with a 3 Feb deadline. unicoach missed it, Gemini made it the price, ChatGPT named it with no odds or date. No consumer tool applies an exchange automatically.                                                                                                                                    | WICHE's Savings Finder embeds the whole dataset (169 institutions, 13,688 major rows, a dollar WUE tuition, deadline) but its terms forbid commercial reuse without written consent. The award basis (automatic / competitive / share) is per-school prose. MSEP, NEBHE and SREB have no machine-readable roster. ~370–400 institution rows US-wide; WUE alone ~170.                                                                                                                                                                                                                                                                                         | No roster, no derivation; the tiers, `tuition_applicable`, the `assumed_by_unicoach` line origin and the awards seed's `residency_basis` keying all exist. `ForbiddenCostArithmeticTest` forbids only subtraction. Derivation lives in `:service`, never the loader. One new table — the DDL gate.                               | **Do second.** The single largest fact we withheld. Read each school's own WUE page as the publisher (the awards-seed pattern), never ingest WICHE's dataset; present as a possibility with its basis and deadline, never inside a total.                                                      |
| 3    | **C3 a next step at every out-of-state public**                     | The estimate a family can take into an aid office is the school's, not ours; 81% trust the aid office. The correct line is one sentence per out-of-state school: the nonresident aid stance, the merit grid, the calculator with its data year.                                                                                                                                                           | `NPRICURL`: 96.7% of four-year schools have one; ~70% resolve, about half of those to a real calculator — needs the link-status pattern. **No structured source** for "need-based aid to nonresidents" (CDS H6 is international students; H14 only says residency is a criterion) — curated only.                                                                                                                                                                                                                                                                                                                                                            | HD column exists, nothing loads it. The cost SERVICE already carries each school's automatic awards; the CHAT TOOL never emits them (only the report page does). Of the six, only UW has an awards row. Smallest: one column, one emit, one prompt line.                                                                         | **Do third, smallest form.** Link + grid are cheap and already ours; the residency aid stance grows one list school at a time as a dated, human-read fact.                                                                                                                                     |
| 4    | **C4 a four-year total for the student**                            | 79% of families eliminate a school on cost alone; 58% plan all four years before enrolling; ChatGPT gave a flat four-year table.                                                                                                                                                                                                                                                                          | Nothing external needed.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | **Strong.** `MoneyPlan.of` and `payloadOf` from the parent's tool (RFC 261) are reusable as-is; a thin student `money_plan` tool, a registry line and a prompt paragraph. Putting it on the cost tool would touch the load-bearing emit order.                                                                                   | **Do fourth.** Cheap, and the parent and student then read the same arithmetic.                                                                                                                                                                                                                |
| 5    | **C6 table first, provenance after**                                | Two provenance paragraphs before the first number cost more than they earned; a dated source per row costs nothing. The follow-up should ask what moves the number most.                                                                                                                                                                                                                                  | Nothing external needed.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | Copy-only but test-pinned: prompt v51 says "say those five lines first … above the table" and `SystemPromptCatalogTest` asserts it in the served body. The follow-up is `PrecisionOffer` declaration order; in the test, residency and income were already answered, so living plan was the only offer left.                     | **Do fifth, as copy.** Keep the residency line above the table (it chooses the column); move whose-figures, what-a-net-price-is and the at-home $0 below it, said once.                                                                                                                        |
| 6    | **C5 ask the aid inputs, estimate the aid**                         | **▲** For this family the inputs ChatGPT asked barely matter: a $120k Washington family of four sits in the Washington College Grant's bottom tier — $1,320 at UW, $880 at Western, from a published table — and UC/CSU give nonresidents no need-based grant. The SAI formula is public and a simplified one lands within $100 for 75% of applicants, but it refuses business, divorce and rental cases. | Nothing to ingest; a formula.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | **None.** No household size, no assets, no SAI arithmetic; `FederalAidPolicyService` states the opposite contract ("computes NOTHING with money").                                                                                                                                                                               | **Defer.** Under $1,500 of movement for ~$40,000 of residency; the first number we would have computed rather than read. The state-grant table is the better idea and is a brief of its own.                                                                                                   |

## Gate 1 decisions

Defaults pre-chosen. Approving costs one word; amending costs one line. Each
decision gets a short label; the verbatim text stays binding.

**D1 — fresher prices, two routes now.** Take the College Scorecard release of
2026-11-02 as the first slice: tuition and fees, housing and food, books and
other move to 2025-26, the published cost of attendance and every net price to
2024-25, by a fetch bump and a re-dated transcription of the publisher's own
cohort map; the two pin tests are the guard, and the year each figure describes
is said as it is today. Add the Common Data Set's section G as a second slice:
where a school's 2025-26 filing states its 2026-27 charges, show that figure
beside the federal one, with the school as publisher, the filing's year and the
read date, through the same figure seam; where no filing exists, nothing
changes. Defer the IPEDS charges bump until the 2025-26 file ships (expected
December 2026), because today it buys only the fallback year and the file family
changed under the fetcher. No scraper of schools' own pages and no curated
page-total seed: six pages needed six judgements, and a wrong confident total is
the worst failure this brief can produce. _Default: as written._

**D2 — the exchange tier, read from the school.** Build a curated exchange
roster, WUE first, one row per school and exchange, read from the school's own
exchange page as the publisher (the automatic-awards pattern: source URL,
archive, checked-on, expires-on), carrying the rule (150% of resident tuition,
or the dollar figure the school states), the award basis in the school's own
words (automatic consideration, competitive, a share where published) and the
deadline. The coach says it as a labelled possibility beside the out-of-state
tier — "if admitted under WUE" — never as the price, never inside a total, and
always with its basis and deadline. WICHE's finder is used to find which schools
to read, not as a source: its terms forbid commercial reuse without written
consent, and we do not ingest a dataset whose terms forbid our use. MSEP, NEBHE
and SREB are deferred until a school on a student's list is in one. New table:
DDL at /work's approval stop (0001 D10). _Default: as written._

**D3 — one next step at every out-of-state public.** Three facts, in one
sentence per school: the school's net price calculator link, loaded from the
IPEDS header file and link-checked on the existing external-link-status pattern
(a dead or generic link is withheld, not shown); the school's automatic merit
grid, which the cost service already holds and the chat tool will now emit; and,
where a human has read the school's page on a dated visit, whether it gives
need-based institutional aid to nonresidents, as a curated fact on the awards
seed's residency pattern — never inferred from a federal field, because none
exists. Where we hold none of the three, the coach says the calculator is the
school's own answer and stops. _Default: as written._

**D4 — a four-year total for the student, the parent's arithmetic.** A thin
student tool reusing the parent's money plan (RFC 261): each four-year school's
published price and price after grants over the programme's length, flat, no
growth assumption, labelled as ours beside the per-year figure, never summed
across schools, a reason instead of a number where the data cannot support one.
The student and the parent then read the same numbers. _Default: as written.
Option B: name a per-school growth assumption where the school published one
(Oregon State: +6.25% for 2026-27) — rejected for now, because it is a second
computed number and the parent's plan does not carry one._

**D5 — the table first, provenance once, after.** The residency sentence stays
above the table, because it decides which column applies. "Who these figures
describe", "what a net price is" and the at-home $0 move below the table and are
said once per answer, with the publisher and year on the row or in one line
under it. The follow-up offer keeps its order (residency, income, living plan)
and no aid-input question is added (D6). The two prompt lines that put the basis
statements above the table are re-pinned to the new order, not removed.
_Default: as written._

**D6 — aid inputs and a Student Aid Index estimate: deferred, with a better idea
recorded.** Not in this brief. For the family in the test the inputs move the
answer by under $1,500 a year against ~$40,000 for residency and ~$18,000 for
the exchange; nothing in the repo computes with money and the federal aid
service states that contract; and it would be the first number we computed
rather than read. The better idea the research found goes to the backlog as a
future brief: **the state's own grant** — a published table by income and
household size (Washington College Grant 2026-27: $1,320 at UW, $880 at Western
for a $120k family of four) is a read figure, not an estimate, and it is what
makes an income-band average misleading for a family at the band's floor.
_Default: defer._

**D7 — the measure.** The six-school question in
`research/side-by-side-2026-11-04.md` is re-run after each slice lands and the
answer recorded in this brief's LEARN section against the five success criteria
above. A slice whose re-run does not move its criterion is not done. _Default:
as written; standing for this brief._

## Gate 1 outcome (2026-11-04)

Ian, verbatim: **"approve"** — all seven defaults, no amendments.

Two questions came before the answer, and both are recorded because they shape
the spec:

1. _"Do we already have a brief for WUE?"_ — Yes, in part. Brief 0006 parked
   `shape/09/exchange-participation` by intent (0006 gate 1 D9: "schedule when a
   real family conversation wants it"), with the same design D2 reaches:
   participation facts plus a read-time ceiling, never a stored price, and "a
   curated roster source and a licence look" as its two open needs. Both of its
   dependency edges have landed. **So D2 is executed by unparking `shape/09`
   rather than minting a `cost/` slice for the same work**: its `DEFERRED` line
   goes, D2's three refinements (the award basis in the school's own words, the
   deadline, and the "if admitted under WUE, beside the tier, never inside a
   total" rule) are appended to its slice text, and this brief's spec points at
   it. Ian did not object; recorded as the coordinator's reading of D2, not as
   an amendment.
2. _"A four-year total for the student — we already did work for this, right?"_
   — For the parent only (RFC 261, `family/07`). The student's coach has no such
   tool and the Family Cost Report shows per-year figures only. D4 stands as the
   thin student tool over the same arithmetic.

## Gate 2 amendment (2026-11-05)

Ian, reading `cost/02`'s line "Where a school's filing states 2026-27 charges",
verbatim:

> We should not be writing specs for specific years. We should always strive to
> have the latest data

Recorded as **D15** in `spec.md`, `standing` for every brief. What it changed
before gate 2 was answered:

- `cost/01/the-scorecard-year` became `cost/01/the-latest-release`: the year on
  every Scorecard figure is read from the publisher's dictionary as fetched, so
  a newer release is a fetch and an ingest with no code or seed edit; the two
  Kotlin year pins and the hand transcription (RFC 183) go. The release D1 names
  by date was the newest on the day D1 was written; D1 is read as "the newest
  release, every time".
- `cost/02/the-schools-own-next-year` names no year: the school's figure is
  labelled with the year its newest filing states, read from the filing's
  template version, and a newer filing moves the label by a fetch alone.
- `cost/06/the-newer-release` added, under **D16** (default A): a periodic
  discovery job records the newest release stamp per source beside the served
  one, in one operator place; ingest stays a human act.
- The same rule reaches `bin/fetch-ipeds` (pins `SURVEY_YEAR`) and the CDS
  corpus fetch; both go to the backlog as mechanism work. D1's deferral of
  buying the IPEDS year stands.

## Gate 2 outcome (2026-11-05)

Ian, verbatim: "Approve". All nine defaults (D8–D16) stand as written in
`spec.md`, including the amendment above. Wave 1 is startable.
