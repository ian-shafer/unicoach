# Brief 0003 — Clear money language

Status:

    PHASE: EXECUTE. Both gates approved; spec.md is binding.
    GATE 1: APPROVED by Ian 2026-08-28 — "Settled. go".
            D1, D3-D9 approved as defaulted. D2 approved AS REVISED
            (price-vs-estimate, not CCTI's billed-vs-unbilled).
            Amendment A1 (Ian, in discussion): the "travel" component stays
            named in the other-costs bucket -- it is inside OTHEREXPENSE and
            omitting the word invites families to double-count it -- but the
            product asserts NOTHING about how far students travel until a
            primary source is cited. See spec.md open question OQ1.
    GATE 2: APPROVED by Ian 2026-08-28 — "approve". D10-D17 as defaulted;
            D18 (columns named housing_food_*, not room_board_*) and D19
            (nonneg CHECK on all six) decided by Ian at the same gate.
    PHASE NOW: EXECUTE — M1 first (copy-only, no dependencies).
    LEDGER:
      M1 LANDED as RFC 141 (main@7ecd6a5a + 935c6f2d, 2026-08-28) -- coach
         prompt v5: the money glossary, the contrastive ban list, and the
         three rules. Copy-only; v4 remains the rollback. 1727 tests green.
      M1.1 LANDED as RFC 142 (main@e5cdca5b + a8008ad4, 2026-08-28) -- the
         "Q5 net price" fix. The cause was OURS, not the model's: college_search
         emitted net_price_q1..q5 as literal keys AND told the model to cite
         the matching band. One putIncomeBand construct now emits code+label
         together at every emitter; search's five keys became one labelled
         array; prompt v6 bans source jargon generally. 1734 tests green.
         OPEN: college_search still emits the raw IPEDS `control` integer with
         no phrase beside it -- same defect class, deferred, pick up in M2/M3.
      RFC 143 LANDED (main@3e03ed92 + f3dad8a9, 2026-08-28) -- the item M1.1
         deferred: college_search's raw IPEDS control integer now carries its
         label from one home (InstitutionControl), and the jargon guard became
         a PROPERTY (no bare source code in a tool result, allowlist-based)
         rather than a string match. 1738 tests green.
      M1.2 ADDED 2026-08-28 (Ian): ask residency BEFORE income. Measured on
         our own data, residency is worth ~$6,300/yr at a public vs ~$1,376
         for a mid-band family's income correction -- and prompt v6 never
         asks for residency at all. See spec.md M1.2.
      M1.2 LANDED as RFC 145 (main@90dedee6 + e53d3c32, 2026-08-29) -- the
         coach now asks where the family lives, and asks it first.
         precision_offer stopped being income-only: it is an ordered array of
         {field, offer} objects, residency first, derived from a PrecisionOffer
         enum whose members own their own applicability rule, so a public
         college with tuition_applicable "unknown" and residency unanswered
         cues the question while an all-private list cues nothing. The offer
         keys off residency_status, not TuitionApplicable.UNKNOWN (which
         covers declined too) -- that is what keeps a decline permanent.
         Prompt v7 (db/schema/0053) REPLACES v6's money paragraph rather than
         appending, because v6's income-only precision_offer rule would have
         misfired; RFC 142's source-jargon sentence survives byte-identically
         and RFC 143's guard still passes. v6 is the rollback. One INSERT, no
         DDL. 1,800 tests green; bin/test check and 272 shell assertions pass.
         Also generalised the money-profile row-intactness guard, since
         residency_status is now decision-bearing.
      M2 (money/02/component-split) LANDED as RFC 149 (main@534b0ac4 +
         56904f63, 2026-08-31) -- the component cost split. Migration 0062 adds
         six nullable INTEGER columns to colleges and colleges_versions
         (housing_and_food_on/off_campus, books_and_supplies,
         other_expenses_on/off_campus/with_family), each with a nonneg CHECK
         (D19) and a COMMENT naming its Scorecard field. Named per D18's
         vocabulary AND 0059's unit convention: housing_and_food_*, never
         room_board_*, and every dollar column ends _per_year_usd. The
         college_cost_profile tool gains cost_by_living_arrangement with three
         arrangements; with_family carries no housing line (no ROOMBOARD_FAM),
         an arrangement missing a part carries no total, and unanswered
         residency drops the tuition line and every total. "No residence halls"
         is read from IPEDS offers_housing (spending D7, whose reason expired
         when RFC 144 landed IPEDS) and emitted whenever known; when the flag
         says no housing but the school published on-campus figures, the
         figures win, the flag rides beside them and the contradiction is
         logged. Academic-year vintages replaced "data ingested YYYY" and are
         emitted as objects naming the figures they date; median debt and
         median earnings carry NO year rather than borrowing one. Coach prompt
         v9 (0063), v8 is the rollback. 1,881 tests executed green; bin/test
         check passed as the gate. Declined and open: no vintage-typed
         CostField split, no money value class (every cost figure in the repo
         is a bare Int).

## Slice IDs

Permanent IDs for this brief's slices (`money/<milestone>/<name>`). The old
letters stay valid as references; the ledger above is left as written.

| Old  | ID                                 | State          |
| ---- | ---------------------------------- | -------------- |
| M1   | money/01/language-standard         | LANDED RFC 141 |
| M1.1 | money/01.1/bands-in-dollars        | LANDED RFC 142 |
| M1.2 | money/01.2/residency-before-income | LANDED RFC 145 |
| M2   | money/02/component-split           | LANDED RFC 149 |
| M3   | money/03/comparison-contract       | NOT STARTED    |
| M4   | money/04/where-youll-live          | NOT STARTED    |

Per-slice dependencies (`Needs:` lines) live in `spec.md`.

## The question

unicoach already tells a student what a school will _probably_ cost their family
(RFCs 133–135): sticker cost of attendance, an income-band net price, debt and
earnings context, every figure cited to the Scorecard. What it does not yet have
is a **money language**: one consistent vocabulary, used identically by the
coach, the college-list screen and the (unbuilt) family cost report, in which
every number means the same thing every time it appears — and in which two
schools can be put beside each other without the comparison quietly lying. Ian's
framing: talk about money in a very clear way; consistent and clear language;
apples-to-apples comparisons must be easy; split the **stable** costs (tuition &
fees — set by the institution, the same for every admitted student in a
residency category) from the **variable** costs (room and board, travel, books,
personal — driven by taste, requirement and choice); and it must make sense to a
parent _and_ to a student.

That is one product question with three separable parts: a **vocabulary** (what
words we use and never use), a **model** (what cost components we store and how
we decompose a number), and a **comparison contract** (what we hold constant,
and what we say out loud, whenever two schools appear side by side).

## Why now

- S1–S3.5 are live in production, so cost truth is reachable today — the
  vocabulary is already being spoken, by the coach prompt, and it is currently
  defined in exactly one place (a paragraph of prompt v4) with no shared
  glossary behind it.
- **S5 (Family Cost Report) is the next money-facing surface** and is
  parent-facing by design. It is the wrong artifact to invent vocabulary on the
  fly for; whatever we decide here should land _before_ or _inside_ S5.
- The variable/stable split is not merely presentational: `colleges` carries no
  room-and-board, books or other-expense columns at all today, so a real split
  is (at minimum) an ingest slice.

## What the repo actually does today (grepped, not assumed)

| Thing                   | Where                                   | State                                                                                                                                                                                                                                           |
| ----------------------- | --------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `colleges` cost columns | `db/schema/0015`, `0045`                | `cost_attendance` (COSTT4_A), `net_price` (NPT4), `tuition_in_state`/`tuition_out_state`, `net_price_q1..q5` (NPT41–45), `median_debt`, `median_earnings`. **No room/board, books, transport, personal.**                                       |
| Ingest                  | `college/.../CollegeScorecardLoader.kt` | Version-pinned Scorecard CSV pair, upsert on UNITID. Adding a component column = migration + loader field + fixture.                                                                                                                            |
| Money profile           | RFC 134, `money_profiles`               | Income band + residency state, tri-state (unset / declined / value).                                                                                                                                                                            |
| Cost read path          | RFC 135, `college_cost_profile` tool    | Per listed school: sticker COA, both tuitions, `tuition_applicable` for publics, net price **with an explicit `basis` label** (band-specific vs overall average), debt/earnings, a `data_availability` "not reported" list, source attribution. |
| Where the words live    | prompt v4 (`db/schema/0048`)            | One paragraph. Lead with the band number; name the basis when it is an average; never estimate; always attribute. This is the entire current style guide.                                                                                       |
| Parent-facing surface   | —                                       | None yet. S5 is the plan.                                                                                                                                                                                                                       |

The important correction to any imagined design: **the numbers we have are
already basis-labelled and honest; what is missing is decomposition, a shared
glossary, and a comparison frame.** We are not fixing a truthfulness bug. We are
turning one good tool output into a language the whole product speaks.

## Candidate bets

Each is a plausible "the thing we build for this idea". Ranked at gate 1 after
research lands.

| # | Bet                                                  | What it is                                                                                                                                                                                                                | Existing foundation                                                               | Cost                                       |
| - | ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- | ------------------------------------------ |
| A | **Money glossary + copy standard**                   | One canonical term list (with the banned synonyms), enforced in the coach prompt, tool output field names and any UI copy; a single place the words are defined.                                                          | prompt v4's paragraph; `CostField` wire names in the cost tool                    | Small                                      |
| B | **Component cost model (the stable/variable split)** | Ingest and store the COA components (tuition & fees, housing & food, books, transport, personal), and expose every cost figure as `stable + variable = total`, with the variable part explicitly marked as choice-driven. | `colleges` + loader; the cost tool's per-college object                           | Medium (migration + ingest + tool)         |
| C | **The comparison contract**                          | A defined, stated set of held-constant assumptions (residency, living arrangement, year, who the net price is _for_) that accompanies every side-by-side, plus a per-school "what would change this" note.                | `money_profiles` residency; the tool's `basis` labelling and `tuition_applicable` | Medium                                     |
| D | **Side-by-side compare surface**                     | An actual apples-to-apples comparison artifact — chat table and/or a screen — over the student's list, built on A–C.                                                                                                      | `college_cost_profile` over the college list; RFC 137 iOS list screen             | Medium/large                               |
| E | **Personalised variable costs**                      | Let the family record their own choices (live at home / on campus, travel distance, meal plan) so the variable half becomes theirs rather than an average.                                                                | money-profile pattern (tri-state, resumable, never forced)                        | Medium                                     |
| F | **Four-year / total-cost framing**                   | Move from per-year to a stated multi-year total, with escalation and time-to-degree made explicit.                                                                                                                        | `graduation_rate` on `colleges`                                                   | Small/medium, high risk of false precision |

## Success criteria for this decision

1. A parent and a student, shown the same number, describe it the same way.
2. Any two schools on the list can be compared without the app hiding an
   assumption that changes the answer (residency, living arrangement, who the
   average is for).
3. Stable versus variable is visible: the family can see which part of the
   number they can actually influence.
4. Nothing overstates precision. Missing components say "not reported", exactly
   as the current tool already does.
5. Every choice here is implementable against real, licensed data — no
   estimation dressed as fact.

## Open questions research must answer (dispatched)

- **vocabulary.md** — the canonical terms, what families demonstrably
  misunderstand, and a recommended glossary + ban-list.
- **data-feasibility.md** — whether the pinned Scorecard snapshot can support a
  tuition/room-and-board split at all, what IPEDS or the CDS would add, and the
  cheapest credible path.
- **comparability.md** — the documented ways cost comparisons mislead, and the
  comparison contract that follows.

## PRIORITISE — research synthesis (2026-08-27)

Three reports landed: `research/vocabulary.md`, `research/data-feasibility.md`,
`research/comparability.md`. All three cite primary sources; both the vocabulary
and comparability children note that websearch was unavailable (no Serper key),
so each is a targeted primary-source read rather than a literature sweep, and
GAO/commondataset.org were reachable only via Wayback or not at all.

### The four findings that decide this brief

1. **The split is a migration, not a data project.** The pinned Scorecard
   institution CSV we already ingest carries `ROOMBOARD_ON`, `ROOMBOARD_OFF`,
   `BOOKSUPPLY`, `OTHEREXPENSE_ON/OFF/FAM` — verified present with real values
   in our own committed fixture header; `CollegeScorecardLoader` maps none of
   them. Six nullable INTEGER columns on the `db/schema/0045` pattern plus a
   re-ingest of the same snapshot delivers the stable/variable split with **no
   new data source and no licence question**. (Supersedes the vocabulary
   report's suggestion to ingest IPEDS: IPEDS IC is strictly richer and cheap,
   but buys nothing this brief needs — defer it.)
2. **The split is where the money actually is.** For public four-years, tuition
   & fees is a median of only **37%** of the on-campus sticker total; the
   variable side is a median **$18,220/yr** (private nonprofit: 68% / $17,814).
   Showing one lump number hides the majority of a public school's cost _and_
   the part a family can influence.
3. **There is a published standard for exactly Ian's split.** The College Cost
   Transparency Initiative's _costs you pay the school_ vs _costs you pay to
   others_, which maps onto 20 U.S.C. 1087ll, where tuition & fees is a **set
   price** and books, travel, personal and living expenses are **allowances**.
   The FAFSA Simplification Act rewrote the statute's "room and board" as
   "living expenses, including food and housing costs", and NCES renamed the
   IPEDS field to "food and housing" in the 2023 collection. **"Room and board"
   is retired vocabulary.** uAspire found 136 distinct names for one federal
   loan across 455 colleges (24 omitting the word "loan"); GAO-23-104708 found
   91% of colleges omit or understate net price. The harm is term _variance_ —
   which is an argument for one glossary, used everywhere, not two.
4. **Our two existing headline numbers are on a different basis from the
   components, and cannot be mixed.** `COSTT4_A` and `NPT4x` are
   **weighted-average blends across living arrangements** and are a year older
   (AY2021-22) than the components (AY2022-23); measured, `COSTT4_A` equals the
   on-campus component sum in 0% of public and 1.1% of private cases (median gap
   −10.1% at publics). Net price subtracts grants from the _blend_, so
   `net_price − tuition` is not "living costs after aid" and must be forbidden
   in prompt and code. Two further first-class cases fall out: **"this school
   has no residence halls"** (15% of bachelor-predominant schools) is a real
   answer, not missing data; and there is **no `ROOMBOARD_FAM`** — living with
   family is asymmetric by construction.

### Ranked bets

| Rank | Bet                                    | Verdict across reports                                                                                                                                                                   | Cost         |
| ---- | -------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------ |
| 1    | **A — Money glossary + copy standard** | All three converge; vocabulary report supplies the glossary and ban-list; copy-only, no schema, ships immediately                                                                        | Small        |
| 2    | **B — Component cost split**           | Feasibility report: available today from the pinned snapshot; comparability report calls it "the single highest-leverage finding"; carries the majority of a public's cost               | Medium       |
| 3    | **C — Comparison contract**            | Comparability report's core deliverable: five stated assumptions, stable/variable blocks, missing data as a labelled blank                                                               | Small/medium |
| 4    | **E — Family-specific variable costs** | Only way the variable half stops being an average; the money-profile pattern already exists; asymmetric with-family data is a known wrinkle                                              | Medium       |
| 5    | **D — Side-by-side compare surface**   | Valuable, but is the _rendering_ of A+B+C; folds into S5's report rather than standing alone                                                                                             | Medium/large |
| 6    | **F — Four-year total framing**        | Actively discouraged: only 49.1% of first-time full-time students finish in four years (45.3% at publics), plus tuition escalation and guaranteed-tuition cohorts — high false precision | Defer        |

## Gate 1 — decisions (defaults pre-chosen; approving costs one word)

- **D1. The bet = a four-slice "money language" beat, in this order.** M1
  language standard (copy-only: glossary + ban-list into the coach prompt and
  the `college_cost_profile` field labels — ships without touching schema); M2
  component cost split (six columns + loader + tool + the no-mixing rules); M3
  comparison contract (the five stated assumptions + stable/variable blocks
  wherever two schools appear together, chat first); M4 family living
  arrangement in the money profile (the variable half becomes theirs, not an
  average). DEFAULT: yes, M1→M4 in that order.
- **D2. (REVISED before approval — the draft default was wrong.) The axis is
  PRICE vs ESTIMATE, not billed vs unbilled.** CCTI's "Costs Payable to the
  School" explicitly includes on-campus housing and meals, so adopting CCTI's
  buckets would put room and board on the STABLE side and defeat the brief. The
  correct line is the statutory one (20 U.S.C. 1087ll): tuition and fees is a
  **price the school sets**; housing and food, books, travel and personal
  spending are **allowances the school estimates**, and the student's choices
  move them. Copy spine: _one number the school sets, two numbers you can move_.
  CCTI's phrasing is kept only where it is genuinely about billing (e.g.
  explaining what lands on the bursar's bill). DEFAULT: yes, price-vs-estimate.
- **D3. "Room and board" is retired; the bucket is "housing and food".** Adopt
  the vocabulary report's glossary and ban-list wholesale ("tuition & fees",
  "published price" not "sticker price", "financial aid offer" not "award",
  loans always carrying the word "loan", no bare "COA"/"NPT4"/"EFC"), one
  glossary for parents and students differing only in pronoun and time horizon.
  DEFAULT: yes.
- **D4. The sticker total we display is the component sum on a named living
  arrangement; `COSTT4_A`/net price stay labelled as the blended figures they
  are.** Corollary standing rule: never add across vintages, and **never compute
  `net price − tuition`** — aid applies to the blend, not to a component.
  DEFAULT: yes.
- **D5. The comparison contract is visible copy, not a disclaimer** — five short
  assumption lines above any side-by-side (whose price, residency held constant,
  living arrangement held constant, one year and its vintage, aid basis = grants
  only). Missing data is a labelled blank, never a zero; "no residence halls" is
  modelled as its own case, distinct from "not reported". DEFAULT: yes.
- **D6. Default framing is per-year.** Four-year totals only on explicit
  request, and then with time-to-degree and escalation stated (49.1% four-year
  completion). Bet F is deferred, not adopted. DEFAULT: yes.
- **D7. Defer IPEDS IC and the Common Data Set.** Everything this brief needs is
  in the pinned Scorecard snapshot; IPEDS goes to the backlog as the cheap
  upgrade path (in-district tuition, multi-year trend), CDS stays out until
  someone proves the licence. DEFAULT: yes.
- **D8. Sequencing against brief 0001.** M1 is small enough to land immediately.
  M2+M3 land **before S5 (Family Cost Report)**, so the parent-facing artifact
  is born speaking the language; S4 keeps its own P1 slot and is unaffected.
  DEFAULT: yes.
- **D9. No new tables in this brief** — six `ALTER TABLE colleges` columns (+
  `colleges_versions` + the history function), and one money-profile column in
  M4. Standing rule D10 still applies: the /ship gate shows the DDL explicitly.
  DEFAULT: yes.

## The recommended language (draft copy standard for M1)

The mental model, one sentence: **one number the school sets, two numbers you
can move, and then what aid takes off.** Every money surface — coach, tool
labels, the college-list screen, S5's report — uses these words and this order.

| # | What we say                              | What it is                                                                                                              | How we qualify it                                                                                                                             |
| - | ---------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| 1 | **Tuition and fees**                     | The price the school charges to enrol. The school sets it; it is the same for every student in your residency category. | Always say in-state or out-of-state when the number is residency-dependent. Never call it "tuition" alone — the field includes required fees. |
| 2 | **Housing and food**                     | What it costs to live while you study.                                                                                  | Always name the arrangement: living on campus / renting off campus / living at home. Marked an **estimate**.                                  |
| 3 | **Books, travel, and everyday spending** | Everything else the school allows for.                                                                                  | Marked an **estimate**; the school reports one figure and it rarely varies by arrangement.                                                    |
| = | **Total cost for a year**                | 1 + 2 + 3, on the named arrangement.                                                                                    | Never `COSTT4_A` — that is a blend across arrangements and a year older.                                                                      |
| − | **Grants and scholarships**              | Money you do not pay back.                                                                                              | Loans and work-study are **never** subtracted from a price, anywhere.                                                                         |
| = | **What you'd actually pay**              | Net price. Gloss the term once, then use the plain phrase.                                                              | Basis always stated: your income band, or the overall average, per the existing RFC 135 rule.                                                 |

Worked example (Auburn University at Montgomery, in-state, from the pinned
snapshot):

    Tuition and fees                     $9,700   the school's price
    Housing and food (living on campus)  $7,368   estimate — your choice moves this
    Books, travel, everyday spending     $6,045   estimate
    -----------------------------------------------------------------
    Total cost for a year               $23,113

    Living at home instead: $15,745 — a $7,368 difference, most of a year's tuition.

That last line is the product. It is the sentence no other tool says plainly,
and it is only sayable once the components are stored.
