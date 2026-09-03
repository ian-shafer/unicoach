# 0005 — The price you would actually pay, in search

Status: **PAUSED (2026-09-02, brief 0006 D11) — gate 1 was presented but never
answered; superseded before decision.** Brief 0006 ("Money in unicoach shape",
gate 1 approved the same day) rules that new index columns must not be built on
the external data shape; this brief's question — what does "cheaper" rank on
when we do not know where the family lives — is re-cut as a slice of 0006's
canonical layer (`shape/05`). The research reports and measured evidence here
(tau 0.561, the surface inventory in `.scratch/blended-figure-surfaces.md`)
carry over as inputs; the D1-D12 list below is historical and was never
decided.\
Handle: `price` (slice IDs are `price/NN.n/name`).\
Opened: 2026-09-02, by Ian, out of RFC 157 D-G.

    PHASE      FRAME -> [PRIORITISE gate 1] -> DISCOVER -> [SPEC & SLICE gate 2] -> EXECUTE -> LEARN
    NOW                              ^^^^^^ HERE

## Ledger

_(one line per landed slice: ID, RFC, SHAs, one-line what — appended after each
/ship run lands. Empty until the first slice lands.)_

## The question

Search and similar-colleges rank every family on a price that may not be theirs.
The College Scorecard's blended figures — `COSTT4_A` (the published price) and
the `NPT4` family (the net price) — are **in-state** figures at a public
institution. RFC 157 proved it arithmetically at UC San Diego and fixed the
**cost** surfaces: at a public school whose state does not match a KNOWN
residency both figures are now withheld at the source, and an unanswered
residency states the basis instead. RFC 157 **D-G deliberately did not fix
search**, because search needs new index columns and a product decision rather
than new copy, and folding an index migration into a labelling change would have
smuggled it past the gate.

So the question this brief answers is: **what does "cheaper" rank on when we do
not know where the family lives?**

It is the same class of defect as RFC 157 on a surface with far more traffic,
and with one aggravating difference: the cost surfaces at least printed prose
that could be made honest. **Search prints no prose at all.** Nothing on this
surface states a basis, so nothing can be corrected by wording — the number is
simply wrong, silently, and the family never sees a claim they could challenge.

## Where it bites (all of it brief 0004's surface)

Evidence: `.scratch/blended-figure-surfaces.md` §4, with file:line.

| surface                                     | what it does                                                                          | why it is wrong for a non-resident                                                            |
| ------------------------------------------- | ------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `CollegesDao` index rebuild                 | `net_price_percentile_share = percent_rank() OVER (ORDER BY net_price_per_year_usd)`  | every family ranked on the in-state figure                                                    |
| `db/schema/0064`                            | **carries no tuition columns at all**                                                 | net price is the ONLY price axis search and similarity have                                   |
| `maxNetPricePerYearUsd` filter              | `net_price_per_year_usd <= ?`                                                         | an out-of-state family filters on someone else's price                                        |
| cheapest-first sort                         | `net_price_per_year_usd ASC NULLS LAST`                                               | the ordering itself is the wrong answer                                                       |
| `SimilarCollegesTool` `cheaper_than_anchor` | `net_price_per_year_usd < ?`                                                          | "like Bowdoin but cheaper" compares two in-state figures and calls it the family's comparison |
| `FitLensService`                            | reads the index row directly, round `CollegeCostService` and so round its withholding | RFC 157 could only **label** here, not withhold                                               |

RFC 157 landed the honest interim: `FitLensService`'s prompt key now reads
`inStateNetPricePerYearUsd`, and both search tool DESCRIPTIONs carry a shared
`NET_PRICE_BASIS_NOTE`. That tells the **model** the basis. It does not make the
**ranking** right, and a filter or a sort cannot be fixed by a note.

## Settled constraints — not re-litigated in this brief

1. **There is no out-of-state cost of attendance to ingest.** All 85 Scorecard
   cost elements were enumerated (RFC 157); residency appears only in
   `TUITIONFEE_IN`/`TUITIONFEE_OUT`. An out-of-state price is obtainable only as
   out-of-state tuition **+ components** — exactly what the cost surfaces
   already build as arrangement totals.
2. **Never invent an out-of-state blend** by adding tuition to a blended
   average. RFC 149 forbids that arithmetic and `ForbiddenCostArithmeticTest`
   scans for it.
3. The **money vocabulary** (brief 0003) and the **honest-denominator** rule
   stand.
4. **Any new table or index column hits Ian's DDL gate** at /ship (0001 D10),
   with the DDL shown at the approval gate, not buried in the RFC.
5. The **percentile corpus is pinned** to the default search universe and
   asserted by `CollegeSearchIndexRebuildTest`. A new axis has to answer to that
   pin.

## Candidates for gate 1

The bet is not "fix search" — that is given. The bet is **which shape of fix**,
and the four candidates are genuinely different products.

| # | candidate                           | what it is                                                                                                                                          | existing foundation in the repo                                                                                                                                  | first cost                                                                                         |
| - | ----------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| A | **Two price axes in the index**     | add an out-of-state price column built the way the cost surfaces build arrangement totals, plus its own percentile ladder; pick the axis per family | `CollegeCostService` already computes arrangement totals from `TUITIONFEE_OUT` + the seven components; `0064` has a rebuild path and a percentile ladder to copy | one migration (DDL gate), a second ladder, a rebuild-cost increase, and the pinned-corpus question |
| B | **Tuition columns only**            | index `tuition_in`/`tuition_out`; let the caller combine, and never store a blended out-of-state price                                              | ingest already reads both columns                                                                                                                                | cheapest DDL; but the caller doing arithmetic on a blended figure is exactly what RFC 149 forbids  |
| C | **Residency-consistent subsetting** | no new price column; when residency is known, price-rank only within a residency-consistent set, and otherwise refuse the price axis                | `control` and `state` are already in the index                                                                                                                   | no migration at all; but "refuse to sort by price" is a visible product loss                       |
| D | **Label only, rank unchanged**      | keep the in-state ranking, state the basis to the coach everywhere (the RFC 157 interim, made permanent)                                            | already shipped as the interim                                                                                                                                   | free; and the ranking stays wrong, which is the thing the brief exists to fix                      |

Orthogonal to A-D, and decided separately:

| # | sub-question                                                                                | why it is its own decision                                                                                                                                                                     |
| - | ------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| E | **Unknown residency**: rank on in-state, refuse the price axis, or rank and state the basis | RFC 157 chose WITHHOLD over CAVEAT for a parent reading **alone with no coach**. Search has a coach in the loop, so the same answer is **not** automatic and must be argued on its own merits. |
| F | **Private-only result sets**: does any of this apply?                                       | In-state/out-of-state does not exist at a private (RFC 135). A private-only set may be able to skip the whole mechanism.                                                                       |
| G | **Does the ANSWER change, or only its label?**                                              | A: the answer changes. D: only the label. C: the answer is sometimes withheld. This is the real fork.                                                                                          |

## Success criteria for the decision

The gate-1 answer is good if:

1. **No family is ranked, filtered, or sorted on a price whose residency basis
   is not theirs** — or, where that cannot be achieved, the surface says so
   instead of ranking silently.
2. It is **decidable with the data we actually hold** — no candidate depends on
   a Scorecard field that does not exist.
3. The **index work and any copy work can land as separate slices**, so a
   migration and a wording change never share an approval gate. (Ian's explicit
   ask.)
4. It **answers to the pinned percentile corpus** rather than quietly redefining
   it.
5. The rebuild stays operationally sane — index size and rebuild time stated in
   real numbers, not adjectives.

## Also in scope: doc drift found while landing RFCs 155 and 157

The code is right; these specs are stale. Cheap to carry, and each one is a lie
a future run would read as truth. **They are a slice, not a footnote** — and
deliberately a _separate_ slice from any index work.

1. `product/0003-clear-money-language/spec.md:376` and
   `product/0001-v1-differentiator/spec.md:101` both say **"five assumption
   lines"**. It has been **six** since RFC 157 added the blended-figure basis.
2. Neither spec permits **withholding** the published price or the net price,
   yet the code now does exactly that at a public school outside a known
   residency (RFC 157 D-A).
3. Brief 0004's spec describes the **price axis** and `cheaper_than_anchor` with
   **no residency caveat at all** — the very gap this brief exists to close.
4. `first-value/06/invite-your-parent` carries unresolved drift from RFC 155:
   - the share CTA is a **chat affordance**, not a parent-page one;
   - **"share events tracked" has no storage** — that means a new table, so a
     DDL gate;
   - the token is **derived, not stored**, and cannot be reversed to a student,
     which constrains the Beat 2 parent-claim path;
   - **one live share per student** means there is no per-recipient attribution
     to track.

These four are a spec correction to brief 0001, not new product. They belong in
this brief only because this is the run that found them; the fix lands in 0001's
spec text.

## Research (DISCOVER, running)

Three parallel subagents, one per independent question. Each writes its own file
and must cite every load-bearing claim.

| file                                       | question                                                                                                                                                            |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `research/data-and-index-feasibility.md`   | Can we carry an out-of-state axis at all? Coverage measured, rebuild cost, and what the pinned percentile corpus would have to answer to.                           |
| `research/search-surface-and-residency.md` | Is residency even **reachable** at search time, across the `:college`/`:service` module boundary? Every consumer of the price axis, and every test that would move. |
| `research/comparison-practice-and-harm.md` | What do Scorecard, Niche, CollegeVine, BigFuture do about residency in search? How big is the in-state/out-of-state gap in dollars, and how many students pay it?   |

## Reading list

- `rfc/157-blended-figure-residency-basis.md` — especially **D-G**, which states
  this boundary and hands it here.
- `.scratch/ship-archive/rfc-157/report.md`
- `.scratch/blended-figure-surfaces.md` — the full surface survey, file:line.
- `product/0004-college-search-index/spec.md` — the surface being corrected.

## Synthesis — what the three reports settled

All three landed 2026-09-02. Files, not paraphrase, are the record; the numbers
below are quoted from them.

### 1. The cost objection is dead. This is not an expensive fix.

| measured                                                      | value                                                              | source                             |
| ------------------------------------------------------------- | ------------------------------------------------------------------ | ---------------------------------- |
| whole search index on disk                                    | 22 MB (10 heap + 11 indexes), 9 MB of it two GIN program indexes   | `data-and-index-feasibility.md` §3 |
| one INT + one DOUBLE + one btree                              | **≈ 0.4 MB**                                                       | §3                                 |
| percentile rebuild, 4 ladders vs 5                            | 92–229 ms vs 141–205 ms — **the fifth ladder is inside the noise** | §3, timed in `BEGIN..ROLLBACK`     |
| publics with a net price that ALSO have an out-of-state total | **771 of 774**                                                     | §2                                 |
| out-of-state on-campus total buildable                        | ~68% of public four-years (~89% if any arrangement counts)         | §2, national snapshot, 6,429 rows  |

The axis loses almost nobody, costs almost nothing, and rebuilds in noise. Every
argument against fixing this has to be a product argument now, not a cost one.

### 2. The defect is large, understating, and concentrated where it hurts

Median out-minus-in tuition
**$9,159**; median out-of-state on-campus total minus
`COSTT4_A` **$13,990** (feasibility §5). Externally: the average published gap
is **$19,930/yr** (College Board 2025-26), **$41,798** at Michigan. The
direction is **understatement** — GAO-23-104708's words for it are "makes a
college appear less expensive than it is". **18.6%** of Fall-2022 first-years at
public four-years are non-resident, but **31% of public four-year seats sit at
campuses that are ≥25% non-resident** — exactly the flagships a cheapest-first
sort floats to the top — and **71.9%** of Common App 2025-26 applicants applied
to at least one out-of-state school. On our own dev DB, **32.4% of every ranked
universe is on a basis that is not that family's, for the best-covered state**;
no state does better (`search-surface-and-residency.md` §5).

### 3. Nobody in the industry asks — and that is a reason to label, not to relax

Not one verifiable mainstream product asks residency before showing, filtering
or sorting price: Scorecard, Niche, CollegeVine, BigFuture, Tuition Tracker,
CollegeData, Appily, Scholarships360 all personalise on **income** and rank on
the in-state-at-publics net price. Scorecard itself sorts `avg_net_price:asc`
labelled only "Annual Cost" — literally our `ORDER BY`. **Our defect is the
industry default.** But TICAS found only 32% of non-template net price
calculators ask residency, and some "provide an estimate assuming in-state
tuition without asking" — a documented defect in the neighbouring category, not
a neutral convention.

Two things worth borrowing, one worth rejecting:

- **Borrow ED's own sentence**: "For public schools, this is only the average
  cost for in-state students."
- **Borrow Tuition Tracker's technique**: put the basis in the metric **NAME**
  ("in-state net price"), not in a footnote.
- **Borrow CollegeResults.org's shape** for similarity: it keeps
  `costs_avg_coa_in_state` and `costs_avg_coa_out_state` **separate** and
  matches on both separately rather than collapsing to one key.
- **Reject** Appily's "the yearly cost listed by the institution", which hides
  that two rates exist; and reject Niche's arrangement of a correct out-of-state
  tooltip sitting beside an unlabelled Net Price — the same amplifier as our own
  `CostReportPage.kt:421`.

### 4. Residency is already in the room. It is just not in the call.

The coach writes "state of residency: answered (WA)" into its system prompt on
**every turn** (`CoachingService.kt:272/:337/:886-889`), while the search tools
are a boot-time registry with no student (`CollegeSearchTool.kt:79`,
`Application.kt:352-385`). The module boundary is **not** the obstacle:
`:college → :common,:db`, `:service → :college`, and `MoneyProfilesDao` lives in
`:db`. The dispatcher already supports `StudentScopedChatTool`
(`CoachingService.kt:729`). This is plumbing, not a redesign.

**But the sequencing runs the wrong way.** Residency is only raised by a cost
`precision_offer`, which needs a college list (prompt `0076:103-110`). Search is
**upstream** of that. So a student's FIRST search is the least likely to have
residency on file — which makes the unknown-residency answer the load-bearing
decision of this brief, not a corner case.

### 5. `state` and `control` are ALREADY in the index — so the honest label needs no DDL

`0064:99` and `0064:109-111`, and both are already on the wire
(`CollegeMatchRow.kt:60,64`). RFC 157's three-outcome vocabulary (`APPLIES` /
`WITHHELD` / `BASIS_STATED`) is therefore a pure function of two columns we
already have. **A real per-row honesty fix can land with zero migrations and
zero DDL gate**, before any index work — which is exactly the separation Ian
asked for.

### 6. THE SHARP TECHNICAL FINDING: two ladders can never be mixed

Percentiles are corpus-relative. Measured across the two bases: mean |p_net −
p_oos| = **0.154**, max **0.899**, Kendall **tau 0.561** — so **~22% of school
pairs order the opposite way**. The killer detail: on an in-state→out-of-state
swap **the privates move too** (mean 0.052) although no private's price changed.
Their percentile moved because the publics moved past them.

And the existing pin does **not** catch this:
`CollegeSearchIndexRebuildTest:634` pins _membership_ (ranked iff returned by a
default search) and reads only the enrollment percentile. `SimilarColleges`
**subtracts two percentiles** (`CollegesDao.kt:1283-1291`), which is where a
mixed ladder would do its damage.

**So the spec needs a new pin: one query, one price ruler — anchor and
candidates from the same column, never two.** This is a decision below (D3), not
an implementation note.

### 7. A trap the candidate table did not see: net price and published price are different MEASURES

There is no out-of-state **net** price and there never can be — subtracting an
in-state average grant from an out-of-state total is exactly the arithmetic RFC
149 forbids. So a residency-correct number for a non-resident is a **published
price**, while today's axis is a **net price** (after aid). They rank
differently (tau 0.561, above).

That kills the naive fix of "swap the column per school". Swapping per school
inside one query mixes two measures in one ladder — the very thing §6 forbids.
What is coherent is **one measure per query, residency-correct per school within
it**: a published-price ruler on which a private uses its published price, an
in-state public its in-state published price, and a non-matching public its
out-of-state built total. That is precisely the construction the Family Cost
Report already prints (RFC 157's UCSD row: on 77,102 · off 77,659 · family
59,923), so it is a construction we have already shipped and Ian has already
read.

The price of that coherence is that aid drops out of the ranking for a family
with a known non-matching residency. That is a real product loss and it is D4.

### 8. Two scope corrections to this brief's own FRAME

1. There are **six** cost components, not seven (`0062:33-39`; there is no
   `ROOMBOARD_FAM`). An arrangement total is the tuition line plus that
   arrangement's own 2–3 components, **all-or-nothing**
   (`CostBreakdown.kt:170-172`).
2. `ForbiddenCostArithmeticTest` scans **only**
   `service/src/main/kotlin/ed/unicoach/coaching/costs` (`:30`). **Price
   arithmetic added to `CollegesDao`'s rebuild SQL is outside the guard.** Any
   slice that builds a price in SQL ships it unguarded unless the scan is
   extended. This is D9.

### 9. One extra defect found in passing

**No test pins `NET_PRICE_BASIS_NOTE` reaching either tool description.** RFC
157 Design 10's label is unpinned on the search side, so it can be deleted by
accident tomorrow. Cheap to fix; folded into the first slice.

### Candidate ranking after research

| rank | candidate                                                                                                                                   | verdict across the three reports                                                                                                                                                              |
| ---- | ------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1    | **A, in the (a)+(d2) form** — one arrangement-named out-of-state published-price column; ONE ruler per query, chosen per query, never mixed | feasibility: recommended, ~0.4 MB, ~0 ms, coverage 771/774; surface: needs student scoping, which the dispatcher already supports; practice: CollegeResults.org is the working precedent      |
| 2    | **(iii)+per-row basis** — a per-row applicability fact from `state`+`control`, no DDL                                                       | surface: "the cheapest real improvement"; it is honest labelling over a still-wrong ranking, so it is a **first slice, not the destination**                                                  |
| 3    | **B** — tuition in/out as labels and filters                                                                                                | 93% coverage, fixes no ranking; and handing a caller a tuition beside a blended figure hands it both halves of forbidden arithmetic                                                           |
| 4    | **C** — residency-consistent subsetting                                                                                                     | zero DDL and honest, but the set collapses: **mean 16.7 publics per state** (min 1, max 67). Trades a wrong answer for a narrow one. Good interim, poor destination                           |
| 5    | **D** — label only                                                                                                                          | already shipped as the RFC 157 interim; a label cannot change a `WHERE` clause                                                                                                                |
| —    | **(ii) refuse to rank on price**                                                                                                            | **rejected**: contradicts guided-not-gated (0001 D11, RFC 157 D-B, prompt `0076:330-331`), and `ChatTool.definition` is a boot-time `val`, so the schema cannot advertise a conditional field |

## Gate 1 — prioritisation

**Ian: approving costs one word. Amend any line and I carry the amendment into
every slice.** Defaults are pre-chosen and shown as DEFAULT.

- **D1. The bet is to fix the RANKING, not only the label.** Candidate D (label
  only) is the shipped floor, not the destination: a label cannot change a
  `WHERE` clause, and RFC 157 D-A's own argument applies harder here, where the
  number decides which schools the family ever SEES. **DEFAULT: yes.**

- **D2. The index gains a residency-correct published-price axis** — an
  **arrangement-named** out-of-state column (`..._on_campus_...`, never an
  unlabelled fifth blend), built exactly as the cost surfaces build arrangement
  totals, all-or-nothing NULL. New index columns, so the /ship run **presents
  the DDL at its approval gate** (0001 D10). **DEFAULT: yes.**

- **D3. ONE RULER PER QUERY, and it is a pinned test.** No query ever reads two
  price percentile columns; a `similar_colleges` anchor and its candidates come
  from the same column; no filter, sort or spoken sentence mixes bases.
  Justification is measured: tau 0.561, ~22% of pairs invert, and privates move
  0.052 without changing price. **DEFAULT: yes.**

- **D4. Which measure ranks, and the loss we accept.** With a KNOWN residency
  that does not match, the whole query ranks on the **published-price** ruler
  (residency-correct per school, one measure). Otherwise it ranks on the **net
  price**, as today. **The cost of this is that aid drops out of the ranking for
  a known non-resident**, because an out-of-state net price does not exist and
  constructing one is forbidden arithmetic (RFC 149). _Alternative if you prefer
  one ruler for everyone:_ always rank on the published-price axis — simpler and
  more consistent, but it throws away the after-aid number that is our
  differentiator, for every family. **DEFAULT: the split above (net price by
  default; published-price ruler for a known non-matching residency).**

- **D5. Unknown residency: rank on the in-state figure, but NEVER silently.**
  Withholding is rejected here — unlike RFC 157's parent reading alone, **search
  has a coach in the loop**, and D-B already says an unanswered question is not
  licence to hide the only price we hold. Instead the basis goes in the metric
  **NAME** — the index column, the tool field, and the sort label — not in a
  footnote (Tuition Tracker's technique), and the row carries the RFC 157
  three-outcome fact. Ranking on in-state when we do not know is also
  modal-correct: 81% of public enrolees pay it, and no private is affected.
  **DEFAULT: yes.**

- **D6. Ask the state EARLIER — a search-side residency offer.** Today residency
  is only raised downstream by a cost `precision_offer` that needs a college
  list, so a first search never has it. One question converts an unstated
  assumption into a correct ranking for everything that follows, and it is the
  thing no competitor can structurally do. Offer, never gate (0001 D12: value
  before ask). **DEFAULT: yes.**

- **D7. The per-row honesty fact lands FIRST, with no DDL.** `state` and
  `control` are already in the index and already on the wire, so RFC 157's
  `APPLIES` / `NOT_THIS_FAMILY` / `BASIS_STATED` vocabulary can reach every
  match row before any migration exists. This is what makes Ian's "index work
  and copy work land separately" true in practice rather than as an intention.
  **DEFAULT: yes.**

- **D8. The rule is per ROW, not per result set.** A private-only
  `search_colleges` result is clean, but a private-only `similar_colleges`
  result **still ranks on a ladder calibrated against in-state public prices**,
  so private-only sets do not get to skip the mechanism. **DEFAULT: yes.**

- **D9. Extend `ForbiddenCostArithmeticTest` beyond `coaching/costs`.** It does
  not currently scan `:db` or `:college`, so the moment a price is built in
  rebuild SQL the guard is blind to exactly the arithmetic this brief must not
  commit. **DEFAULT: yes — extended by the slice that first builds a price
  outside `:service`.**

- **D10. Slice separation is a rule, not a preference.** No migration ever
  shares an approval gate with a wording change; the labelling/plumbing work
  lands before the DDL work, and each is independently revertible. **DEFAULT:
  yes.**

- **D11. The doc drift is its own slice**, against briefs 0001/0003/0004's spec
  text (five→six assumption lines, withholding not permitted by either spec,
  0004's price axis described with no residency caveat) plus
  `first-value/06/invite-your-parent`'s four drift items from RFC 155. No code
  changes in that slice. **DEFAULT: yes.**

- **D12. Out of scope for this brief:** ingesting a real national snapshot,
  `search/06/unattended-refresh` (still DEFERRED by intent), and any change to
  what the cost surfaces do — RFC 157 owns those and is landed. **DEFAULT:
  yes.**

_Ian's answers are recorded here verbatim, including amendments, once given._
