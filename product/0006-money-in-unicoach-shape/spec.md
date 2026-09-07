# Brief 0006 spec — Money in unicoach shape

Status: **GATE 2 APPROVED (Ian, 2026-09-02, defaults, no amendments).** Gate-1
decisions D1-D11 (approved 2026-09-02, defaults, no amendments) are binding
context for every slice. Handle: `shape`.

## DISCOVER preamble — what the code actually is (corrections to assumptions)

Grounded by `research/repo-money-audit.md` (file:line for everything below):

1. **`colleges` holds only publisher blends and pairs.** Tuition exists as an
   in/out pair (in-district collapsed into "in" by the Scorecard itself);
   COSTT4_A and the NPT4 family are in-state-based cohort statistics stored as
   sibling columns of prices. Six arrangement components exist (0062); there is
   NO with-family food-and-housing figure anywhere — not in the Scorecard, and
   **not in IPEDS IC_AY either** (`CHG7/8AY` is off campus **NOT** with family;
   the with-family arrangement has only `CHG9AY`, other expenses). Corrected
   2026-09-05 by RFC 161 against the pinned codebook; resolved by **D17**, which
   treats living at home as $0 food-and-housing and says so.
2. **Missingness is unrecoverable at the row today.** `toIntOrNull()` collapses
   Scorecard `NULL` and `PrivacySuppressed` (`CsvIngestSupport.kt:272-275`).
   Recovering D3's status values means re-PARSING the pinned snapshots — an
   ingest change, no new fetch.
3. **The canonical embryo is Kotlin, and it is complete.** `LivingArrangement`,
   `ArrangementGap` vs `NoTotalReason`, `ComparisonBasis`'s six facts,
   `ResidencyAxis` + compile-time withholding, the closed `ScorecardVintage`
   enum. shape/01's tables are these types, as rows.
4. **Wire keys ARE column names** (`CostField.wireName`, CostField.kt:92-96).
   Every consumer cutover is a tool-contract change plus a prompt bump, not a
   silent re-plumb.
5. **The ingest has an exact slot** for the canonical fill: derived phase 2,
   beside `search-index` ("rows first, derived state second",
   CollegeScorecardLoader.kt:675-680), row counts into `college_index_build`.
   `college_ipeds` / `college_merit_aid` already are external-shaped staging.
6. **Migration traps are enumerated** (audit §6): `log_college_version()`
   restates every column; the index rebuild SQL is a second copy of the price
   list; `ForbiddenCostArithmeticTest` scans only `service/.../costs/`;
   ingest-loaded FKs make phase order a write precondition; 63-char constraint
   names. Every slice below inherits this register.

Standing rules in every slice without restating: brief 0003's money vocabulary
and honesty rules (a labelled blank, never a zero, never a partial total; loans
never subtracted); value-before-ask on any family-facing flow (0001 D12); every
new table shows its DDL at /ship's approval gate (0001 D10); numbers (RFC,
migration, prompt version) claimed live from the rebased tree, never from docs.

**Wave board** (same wave = safe in parallel): wave 1: shape/01, shape/06 · wave
2: shape/02, shape/03, shape/07 · wave 3: shape/04, shape/05 · wave 4: shape/08.
Deferred: shape/09.

---

## shape/01/canonical-store

**Needs:** —\
**What:** The canonical money schema itself, plus its fill from the sources we
already pin. One sentence of intent: give unicoach a money store shaped like
what money IS — a price has a residency, an arrangement and a year; a statistic
has a population; an absence has a reason.

Build:

- **Vocabulary tables (unicoach-authored, D6):** `residency_bases` (in_district
  / in_state / out_of_state / not_applicable), `arrangements` (on_campus /
  off_campus / with_family — plus the RFC 149 rule that some concepts are
  arrangement-invariant), `figure_statuses` (D3's six values), `price_concepts`
  (tuition_and_fees, fees_only, housing_and_food, books_and_supplies,
  other_expenses, published_price), `income_bands` (D7: the five cut-points as
  rows carrying their dollar ranges — the range text leaves `IncomeBand.kt` and
  becomes data). Authored seed files in the `subjects.json` pattern: committed,
  diffable, fatally validated at load.
- **`price_figures`** (D2): college × price_concept × residency_basis ×
  arrangement × academic_year → amount-or-status, source, source_variable,
  publisher_flag (raw IPEDS X-code where one exists). CHECKs: value IFF status
  in (reported, imputed_by_publisher); no row without a residency basis —
  `not_applicable` is an explicit choice, never a default.
- **`cohort_money_stats`** (D2): college × measure × population_basis ×
  income_band? × vintage → value-or-status. Measures v1: avg_net_price
  (overall + by band), published_cost_blend (COSTT4_A, stored AS a stat),
  pell_share, median_debt_at_completion, median_earnings_10y. The population
  basis is a structured row attribute (population, residency scope, aid scope),
  not prose — RFC 151's `comparison_basis` moved into data.
- **`aid_policy_facts`** (D9, modeled ahead): college × policy × year, empty
  until shape/07 fills it.
- **A `canonical-money` ingest phase** in derived phase 2: re-parses the pinned
  Scorecard snapshot PRESERVING `PrivacySuppressed` and mapping NULL/absence per
  D3; fills both tables; writes row counts + per-status counts into
  `college_index_build`. Upstream-wins rule (D5) is implemented here even though
  only one source fills each figure yet.

**Decided here vs left to /ship's RFC:** the logical keys, vocabularies, and
CHECK semantics above are decided (gate 1). The PHYSICAL layout — narrow
figure-per-row vs wider per-(college × year) tables, index strategy, whether
`price_figures` is one table or one-per-concept-family — is /ship design, argued
in the RFC against the audit's risk register (§6). Staging-table shape for the
re-parse (if any) is also /ship's.

**Acceptance criteria:**

- Every gate-1 unrepresentability claim is a database constraint or a loader
  fatal, each with a test: no price row without residency basis; no blend in
  `price_figures`; no value without a value-bearing status; no mixed-vintage sum
  (moves from `CostBreakdown` Kotlin into the build).
- After one ingest of the current pinned snapshot: tuition in/out pairs land as
  two `price_figures` rows each; NPT4/COSTT4_A/debt/earnings/pell land in
  `cohort_money_stats` with their true population basis; `PrivacySuppressed`
  rows exist with status `suppressed_by_publisher` (count asserted > 0).
- `bin/ingest-colleges` stays idempotent, phase-transactional, and its
  provenance row says what the canonical fill did.
- NO consumer changes. The door for this slice's value opens at shape/04 and is
  named there; this is a substrate slice by design (the search/03a precedent).
- DDL at the /ship gate: this is the brief's big DDL moment — every table above,
  visible, with the CHECKs.

**First-session test:** none user-visible (substrate). Operator-visible: the
ingest summary names the canonical row counts and the per-status breakdown.

---

## shape/02/ipeds-ic-ay

**Needs:**

- BLOCKS shape/01/canonical-store — the canonical tables are this slice's write
  target; without them the new source has nowhere honest to land.
- CONFLICTS shape/03/ipeds-sfa — both edit `bin/ingest-colleges`, the loader
  phase list and `bin/fetch-*` plumbing; rebase risk only.

**What:** Un-defer IPEDS IC_AY (D5, resolving brief 0003 D7): pin
`IC2023_AY.csv` (+ its Stata codebook) via the `bin/fetch-*`/PROVENANCE pattern,
load it as staging, and map into `price_figures`: three-tier tuition
(`CHG1/2/3AY*` — **in-district becomes real**), fees split from tuition
(`*AT/*AF` → `fees_only` concept), arrangement components including
~~**with-family housing-and-food** (`CHG7/8AY`)~~ — struck 2026-09-05: that
variable is off campus NOT with family and no source publishes the figure (D17),
four academic years per file, and the X-imputation flags mapped to D3 statuses
(B/D/H → not_reported_by_institution; A → not_applicable; G/J/K/L/N/P/Z →
imputed_by_publisher; R/C → reported).

**Decided here vs /ship:** the variable→concept mapping and flag→status mapping
are decided (they are in `research/source-landscape.md` §2); staging table shape
and whether to load all four carried years or the newest two is /ship design
(D15 says store history, serve latest).

**Acceptance criteria:**

- Where IC_AY and the Scorecard both carry a figure, the IPEDS value wins and
  the row records source (D5); a test pins one real disagreement.
- Imputed values are never presented as reported: status is carried through to
  the row, count asserted.
- A community college gains a priced `in_district` tier distinct from `in_state`
  (test on a real fixture).
- `_RV` refusal, all-or-nothing fetch group, provenance digests — the existing
  `bin/fetch-ipeds` contract extended, not forked.
- New staging DDL at the /ship gate.
- Reachability: still substrate — the door is shape/04/05 and this slice's spec
  says so; its user-visible payoff (in-district, fees split, and the with-family
  $0 assumption stated per D17) is named in shape/04's criteria.

**First-session test:** none user-visible yet; operator sees per-tier row counts
and flag distribution in the ingest summary.

---

## shape/03/ipeds-sfa

**Needs:**

- BLOCKS shape/01/canonical-store — writes `cohort_money_stats`; the
  population-basis attribute it needs is that slice's schema.
- CONFLICTS shape/02/ipeds-ic-ay — same ingest/fetch files; rebase only.

**What:** Pin and ingest IPEDS SFA (D5): net price with honest population labels
(`NPIST*` publics = in-state-rate-paying grant-aided; `NPGRN*` privates),
income-band net price (`NPIS41-45`, three years per file), Pell
number/share/average, aid mix by source (federal/state/institutional grants),
loan share/average, and the **population counts** that make bases concrete:
residency-split fall cohort (`SCFA11N` — what share of a public's students each
residency price actually applies to) and arrangement counts (`GIS4*`). All with
X-flag → status mapping as in shape/02.

**Decided here vs /ship:** variable→measure mapping decided (source report §3);
whether population counts live in `cohort_money_stats` or a small sibling table
is /ship design.

**Acceptance criteria:**

- Scorecard NPT4-derived stats and SFA stats coexist with distinct
  source+vintage; upstream-wins selects SFA when both exist for the same
  measure×basis×vintage; never averaged (D5).
- Every stat row's population basis is queryable structure, and the coach's
  existing `comparison_basis` sentences could be generated from it (proven by
  test, not yet wired — that is shape/04).
- Pell average (a figure the Scorecard lacks) exists for a fixture school.
- Staging DDL at the /ship gate.

**First-session test:** substrate; operator-visible ingest summary.

---

## shape/04/cost-answers-from-canonical

**Needs:**

- BLOCKS shape/01/canonical-store — reads the canonical tables.
- PREFER shape/02/ipeds-ic-ay — technically cuttable on Scorecard-only rows, but
  the user-visible payoff (in-district tier, fees split, the stated with-family
  assumption per D17, imputation named) is IC_AY's; cutting over before it lands
  ships plumbing with no visible change.

**What:** The first consumer cutover (D10): `CollegeCostService`,
`CollegeCostChatTool`, the family cost report, and `FitLensService`'s cost
digest read ONLY canonical tables. The RFC 149/151/152/157 layer becomes a
projection: withholding = residency comparison on row keys; `ArrangementGap` vs
`NoTotalReason` = figure statuses vs profile statuses; `comparison_basis`
sentences = generated from stored population bases; vintages = row attributes,
and `ScorecardVintage`'s closed enum is deleted rather than extended.

**The door (reachability, named):** the chat coach and the shared cost report —
ask what a school costs. New user-visible truths, each an acceptance criterion:

- a community-college family sees the **in-district** price, labeled;
- **living with family** shows a complete total that counts food and housing as
  **$0**, with the assumption stated in words — "living at home, we count no
  food-and-housing cost" — never a silent zero line and never a blank attributed
  to the school (D17). No source publishes a with-family food-and-housing
  figure; this is unicoach's stated assumption, not a missing datum, and the
  copy must not imply the school failed to report it;
- fees appear split from tuition where reported;
- a suppressed figure says "withheld by the publisher for privacy", an imputed
  figure is marked as the publisher's estimate, and "we have not collected this"
  (e.g. a pre-canonical vintage) is said as OUR gap — D3's six statuses, spoken
  in brief-0003 vocabulary;
- no regression: every RFC 152/157 honesty behavior reproduced, the tests moved
  to the projection (and `ForbiddenCostArithmeticTest`'s scan scope extended to
  any new package this slice creates — audit §6.3).

**Decided here vs /ship:** wire-key strategy is /ship design within D10's rule
(this slice owns its tool-contract change + prompt bump; old keys may be aliased
for one version but the target state is canonical concept names).

**Value-before-ask:** unchanged flows; no new questions are asked. The residency
question stays where RFC 145 put it.

**First-session test:** a new user with no profile asks "what does Foothill
College cost" and sees three tiers named (in-district / in-state / out-of-state)
with the residency question offered, not forced.

---

## shape/05/search-on-your-price

**Needs:**

- BLOCKS shape/01/canonical-store — the index rebuild's price inputs become
  canonical rows.
- PREFER shape/02/ipeds-ic-ay — residency-correct published price per school is
  far more complete (fees split, four vintages) with IC_AY; Scorecard-only rows
  would ship the feature with thinner coverage.\ **Build on what RFC 166
  landed** _(added 2026-09-05; the code wins over this spec text, which was
  written before `shape/04` ran)_: the **FitLens digest half of this slice is
  already done** — `FitLensService` reads the canonical store through the
  injected `CanonicalCostReader` and emits basis, vintage and status — so keep
  it rather than rebuilding it. The search-index net-price column and its whole
  path were deliberately left alone and are still this slice's. Four constraints
  the landed code imposes: read only through `CanonicalCostReader`, keyed on the
  FULL address, copying the `CanonicalAddressContractTest` pattern (this is what
  gives D14c's "one ruler" teeth); normalise `VINTAGE_UNDATED` out before any
  latest-vintage pick, because it sorts lexicographically ABOVE every real
  `YYYY-YY`; never collapse a SUPPRESSED figure into "we do not have it"; and
  decode per row, so one bad row cannot fail a search page.

**What:** Brief 0005's question, re-cut on the canonical layer (D11): the search
index's price axes are rebuilt from `price_figures` / `cohort_money_stats`, and
ranking stops pretending the in-state figure is everyone's. Gate-2 decision D14
(below) fixes the product rules; the index columns, rebuild SQL and
`CollegeSearchIndexRebuildTest` change together (audit §6.2).
`similar_colleges`' `cheaper_than_anchor` and the price axis, the
`maxNetPricePerYearUsd` filter, cheapest-first sort, and `FitLensService`'s
digest line all follow. 0005's research and measured evidence
(`product/0005-price-you-would-pay/research/`, tau 0.561) are binding inputs.

**The door:** the chat coach — "small schools in Oregon under $30k", "like
Bowdoin but cheaper" — plus every fit-lens sweep.

**Acceptance criteria:**

- One ruler per query (D14c), pinned by a test that fails if two price
  percentile sources mix.
- A known non-matching residency ranks/filters on that family's
  residency-correct published price (D14a); aid's absence from that ruler is
  said in the tool result, not silent.
- **A district price is disclosed, never ranked on (D18).** The ruler is the
  in-state / out-of-state pair; an in-district row is never admitted to it,
  because a state answer cannot select one. Where a school publishes a lower
  district price, the result names it and says who it is for.
- **A tuition figure whose tier the publisher does not separate is ranked and
  labelled (D19)**, never silently treated as in-state and never dropped into
  `excluded_unknown`. Reuse `ResidencyTiers.residencyTiersOf` and the landed
  `ResidencyTierBasis` statements — do not write a second wording of a closed
  vocabulary (that second derivation site is what produced two of RFC 166's
  blockers), and note that the vocabulary has **six** codes, not the four this
  spec was written against.
- Unknown residency ranks on the net-price stat as today but the basis is in the
  metric NAME on the wire, and the search-side residency offer (D14d) invites
  the state question — never gates.
- `excluded_unknown` accounting extends to the new axes; unknown is dropped,
  counted, named — never substituted (brief 0004's standing rule).
- Prompt bump + tool description changes at this slice's gate.

**First-session test:** a family that has said "we live in Washington" asks for
cheap California publics and the ordering is built from out-of-state published
prices, with the tool result saying so in one sentence.

---

## shape/06/pell-and-loans

**Needs:** — (independent of every other slice; wave 1).\
**What:** D8's policy-parameter store and the two school-independent family
questions: `policy_parameters` (award_year × parameter: Pell max/min, Direct
Loan limits by level/dependency incl. sub caps, SAI floor), authored seed
hand-verified against FSA Handbook sources (citations in
`research/money-domain-model.md` §2; the Pell max dollar figure was flagged
unverified there and MUST be verified at /ship time), and a coach surface that
answers "do we qualify for Pell, roughly?" and "what would loans look like?"
with cited parameters — computations narrated, never stored (gate-1 never-store
list).

**The door:** the chat coach, in the flow of money conversations.

**Acceptance criteria:**

- Every parameter row carries award_year + source URL; answers cite them and
  name the award year.
- Pell answers are ranges/eligibility framing, never a promised award; loan
  answers state limits and never subtract loans from any price (0003).
- Value-before-ask: the family-size/dependency inputs Pell needs are invited
  in-flow with the value named, declinable, resumable; every answer degrades to
  the parameter facts alone (0001 D11/D12). Any `money_profiles` addition keeps
  the tri-state pattern and shows DDL at the gate.
- A stale award year (parameters older than the current cycle) is said plainly,
  not silently served.
- Prompt bump; rollback = previous prompt version.

**First-session test:** session one, no college list, no profile: "can we get a
Pell grant?" gets an honest cited eligibility answer plus one invited follow-up
question, and declining still yields the general answer.

---

## shape/07/need-and-forms

**Needs:**

- BLOCKS shape/01/canonical-store — writes `aid_policy_facts` (modeled there).
- CONFLICTS shape/02/ipeds-ic-ay — shares `bin/ingest-colleges` phase plumbing;
  rebase only.
- CONFLICTS shape/03/ipeds-sfa — same plumbing; rebase only.

**What:** Fill D9's modeled-ahead facts from the CDS corpus we already pull:
extend `bin/fetch-cds-seed` extraction from H2A to **H2** (% of need met, need
fully met, average need-based grant), **H4/H5** (borrowing incl. private loans),
**H7/H8** (FAFSA / CSS Profile / noncustodial required), and surface them: "do
they meet full need?", "do we have to do the CSS Profile?" answered with per-row
CDS citations (the `college_merit_aid` source_url/archive_url pattern).

**Acceptance criteria:**

- Coverage honesty: CDS covers hundreds of mostly-selective schools, not 6k; a
  school without a filing reads "not reported in this school's CDS", and
  coverage counts land in provenance.
- The H2 need ladder keeps its honest denominators (the RFC 148 lesson: emitted
  shares name their exact cohort; no "of those with need" claim unless both
  counts exist).
- `parse_error` from the corpus is OUR gap (not_collected_by_us), never the
  school's.
- Licence note from `research/source-landscape.md` §4 (corpus attribution;
  re-check before scale) is resolved in the RFC.
- Prompt bump for the new answers.

**First-session test:** "does Amherst meet full financial need?" → cited CDS
answer with year; "does it require the CSS Profile?" → yes/no with citation.

**SPLIT IN DESIGN (at RFC 170's design gate, 2026-09-05).** This instruction is
delivered as two slices, so both carry permanent IDs:

- **`shape/07a/need-and-forms`** — H2 (how a school treats need) and H8 (the
  forms it requires), the door, and the canonical shapes they land in. **LANDED
  as RFC 170.** Needs: BLOCKS `shape/01/canonical-store` — the facts land in the
  canonical tables that slice created.
- **`shape/07b/borrowing`** — CDS H4/H5 borrowing, including private loans.
  **SPECCED BELOW** at `## shape/07b/borrowing`, which is its one home: the
  section carries the What, the Needs edges, the acceptance criteria and the
  first-session test. Nothing here restates them.

**First-session test (07a only):** "does Amherst meet full financial need?" →
cited CDS answer with year, naming the cohort it is reported over; "does it
require the CSS Profile?" → the forms this school's CDS lists, with citation. A
bare "no" is NOT available: the corpus carries required checkboxes and no
unrequired ones (RFC 170 D5), so an unlisted form reads "not listed in this
school's CDS".

---

## shape/07b/borrowing

_Specced 2026-09-05, after `shape/07a/need-and-forms` landed as RFC 170 and the
split gave this half a permanent ID. The ID is older than the text; that is
normal (IDs are assigned once and never renumbered)._

**APPROVED (Ian, 2026-09-05).** Verbatim: "approve" — given after the
loan-type-as-measure choice (decision 1 below) was argued in full and its
weakest point named. No amendments. This is a slice-text approval under the
already-approved gate 2, not a third gate._Recorded because a decision made in
conversation and not written down is a decision nobody can check later._

**Needs:**

- BLOCKS shape/01/canonical-store — the borrowing figures land in
  `cohort_money_stats` and `cohort_population_counts`, which that slice created.
- BLOCKS shape/07a/need-and-forms — reuses that slice's CDS machinery whole: the
  `source_documents` row per filing that every fact cites, the extraction phase
  in `bin/fetch-cds-seed`, the `academic_year` domain, and the decimal reader it
  added (`get_int` refuses a fractional value, and the average-borrowed cells
  are fractional).

**What:** The CDS H4/H5 block answers a question no federal source answers well:
**what do students who finish a degree here actually borrow, and how much of it
is private?** The Scorecard's `median_debt_at_completion` covers federal loans
only, so a family reading unicoach today sees the tidier half of the debt and
never the part that hurts most — private loans, which carry no federal
protections and no income-driven repayment. This slice makes the private column
speakable.

The source publishes, per school per filing year, three rows for each of five
loan types (any / federal / institutional / state / **private**):

- `H.401` — the graduating class, the cohort every H4/H5 figure is reported over
  (312 of the 417 seeded colleges);
- `H.501-H.505` — the COUNT who borrowed, by loan type (any 338, private 326);
- `H.506-H.510` — the school's own PERCENT who borrowed, by loan type;
- `H.511-H.515` — the AVERAGE cumulative principal borrowed, by loan type
  (private 327).

Field ids are stable across the corpus's two schema versions (2024-25, 2025-26),
so the ingest keys on ids, not on labels.

**Decided here (product shape), physical layout to /ship's RFC (D13):**

1. **Loan type is part of the MEASURE, not a new dimension and not a new
   relation.** `cohort_money_stats`' dimensions describe the POPULATION a number
   is about — who they are, where they live, what aid they receive. Loan type
   describes the THING MEASURED, and this spec has already answered that
   question once: `shape/03` landed the grant mix as one measure per source
   (`federal_grant_share`, `state_local_grant_average_award`,
   `institutional_grant_share`), not as a `grant_sources` dimension. Borrowing
   is the same shape and takes the same answer, so there is one rule in the
   money layer rather than one per aid type. A sixth axis would also force every
   price and every existing statistic to answer a loan-type question it is not
   about, and a separate relation would fork the canonical store two slices
   after `shape/01` unified it. **No `loan_types` vocabulary is added to
   `money-vocabulary.json`.** The same rule applies to the borrower headcounts:
   a per-loan-type borrower cohort is a `population` value in
   `cohort_population_counts`, named the way the source names it.
2. **The published percent cells (H.506-H.510) are NOT ingested.** They are
   corpus-typed `text` with NULL `value_num` and mixed 0..1 and 0..100 raw
   values, so a stored share would be a coin flip on whose scale a school used.
   The share is DERIVED at read time from the borrower count over the H.401
   graduating class, and only when both exist — the `avg_need_met_share` pattern
   RFC 170 landed. This also keeps the gate-1 never-store rule: a figure
   computable from stored counts is computed, never stored.
3. **A loan type is never summed with another, and never dropped into a nameless
   total.** Reporting is genuinely partial — a school may file federal and leave
   private blank. "Borrowed" with no named loan type is a different fact from
   "borrowed federally", so every stored row and every spoken sentence carries
   its loan type, and the `any` figure is the school's own H.501/H.511, never
   our addition of the four others.
4. **What a family is told.** The coach answers "what do students here borrow?"
   by naming the cohort out loud — **the students who graduated from this school
   in year Y**, not all undergraduates, not this year's freshmen — and gives the
   average cumulative amount at graduation plus the share who borrowed at all,
   split federal vs. private where the school filed both. Brief 0003 binds
   without exception: **a loan is never subtracted from any price, and a debt
   figure is never presented as a price.** Debt sits beside the price
   conversation as an outcome of it, in its own sentence.

**Acceptance criteria:**

- **Every measure names its own denominator in data, and a test pins measure to
  population.** This brief has hit the denominator defect three times (RFC 148,
  162, 170); assume a fourth unless the slice is built to refuse it. A share is
  over the H.401 graduating class (`aid_scope` `all`); an average cumulative
  principal is over the BORROWERS OF THAT LOAN TYPE and carries its own
  receiving scope, exactly as the 0085 rule already says for the grant averages.
- Loan type is legible in every row without a join, and no `cohort_money_stats`
  row outside this slice changes shape. DDL at the /ship gate for any new table
  (0001 D10); a CHECK-list extension is shown too, since it is the vocabulary.
- **Partial reporting is pinned by test:** a fixture school with a federal
  figure and no private figure yields the federal fact, no private row (or a
  non-value-bearing status), no sum, and a spoken answer that says the private
  figure is not in this school's filing. A test asserts that no code path adds
  two loan types together.
- The published-percent cells are proven unread (a test naming H.506-H.510), and
  every average is read with RFC 170's decimal reader — an integer reader that
  silently rejects `28,193.50` is the same silent gap in a different costume.
- **Statuses keep RFC 170's discipline:** a value exists exactly when the status
  bears one; a cell no source carries gets NO row; OUR failure to read a cell is
  `not_collected_by_us` and is never spoken as the school's silence.
- **Coverage honesty, three ways.** (a) The ~90 seeded colleges with no filing
  read "we don't hold a Common Data Set for this school", never "students here
  don't borrow". (b) A school with a filing but no borrowing block reads "this
  school's CDS doesn't report borrowing". (c) A borrower count with no `H.401`
  cohort yields no share — the average amount is still said, the share is
  withheld rather than computed against a guessed denominator.
- **The door is the existing `college_cost_profile` tool** — the same door
  `shape/07a` opened, no new tool, no profile required, nothing gated. Coach
  prompt bump; rollback = previous prompt version. Every figure cites the
  school's own filing (`source_documents`) with the year on it.
- Value-before-ask (0001 D12): borrowing is told unasked when a family is
  looking at a school's costs, and nothing about it is gated behind a profile
  step. The answer degrades to whatever the filing carries.
- Full `bin/test check` is the gate, as ever.

**First-session test:** session one, no profile, no college list — "do students
at Amherst take out loans?" returns the graduating class's borrowing, cited,
with the year and the cohort named in words, federal and private separately, and
a plain statement where the private figure is not filed. Asked next "so what
will that cost us?", the answer never subtracts the loan from a price.

---

## shape/08/drop-the-publisher-shape

**Needs:**

- BLOCKS shape/04/cost-answers-from-canonical — a reader of the publisher-shaped
  columns, which may be dropped only at reader-count zero.
- BLOCKS shape/05/search-on-your-price — the other reader, same reason.

**What:** The end state (D10): cut the remaining readers (admin-web
`CollegesResource`, any stragglers from the audit §2 checklist) to canonical,
then DROP the publisher money columns from `colleges`/`colleges_versions`
(restating `log_college_version()` — audit §6.1), shrink the search-index copy,
and retire the interim labeling code RFC 157 left behind. `colleges` ends as
identity/location/codes; money lives only in the canonical layer.

**Acceptance criteria:**

- A tree-wide audit proves zero readers before the DROP (grep-based test naming
  each dropped column).
- History honesty: `colleges_versions` money history predating the canonical
  layer is preserved (archived table or documented cutoff — /ship design), never
  silently lost.
- `college_index_build.change_summary`'s frozen keys stay readable (append- only
  contract, audit §6.6).
- Full `bin/test check` is the gate, as ever; this slice is pure removal and
  must land with zero behavior change.

**First-session test:** none (invisible when done right) — the test is that
nothing changes.

---

## shape/09/exchange-participation

**Status:** DEFERRED

**Needs:**

- BLOCKS shape/01/canonical-store — participation rows need the canonical store.
- PREFER shape/02/ipeds-ic-ay — the WUE ceiling formula (≤150%) hangs off a
  stored in-state tuition row.\ Parked by intent (gate 1 D9):
  WUE/MSEP/NEBHE-RSP/SREB-ACM rosters as participation facts + read-time ceiling
  narration, never a stored price. Needs a curated roster source and a licence
  look; schedule when a real family conversation wants it. Remove this Status
  line when scheduled.

---

## Gate 2 — decisions

- **D12. The slice set, order and waves above.** Two substrate slices before the
  first door opens (shape/01, then 02/03), because the door's payoff IS the new
  data; shape/06 runs in wave 1 so the product says something new while the
  substrate lands. **DEFAULT: approve.**
- **D13. Logical model decided, physical layout to /ship.** Keys, vocabularies,
  statuses and CHECK semantics are gate-1-fixed; narrow-vs-wide tables, indexes,
  staging shapes are shape/01's RFC, argued against the audit's risk register,
  with all DDL at the gate as always. **DEFAULT: yes.**
- **D14. Search price rules (0005's question, decided here):** (a) known
  non-matching residency → the whole query ranks/filters on the family's
  residency-correct published price, and the tool says aid is not in that ruler;
  (b) unknown residency → net-price stat as today, basis in the metric name,
  never silently; (c) one ruler per query, pinned by test; (d) a search-side
  residency offer — ask the state earlier, never gate. **DEFAULT: yes to all
  four.**
- **D15. History: store, serve latest.** IC_AY/SFA carry 3-4 years per file;
  canonical rows keep academic_year in the key and consumers serve the latest;
  trend surfaces are a future brief. **DEFAULT: yes.**
- **D16. Coverage: the full ingested universe (~6k UNITIDs),** not the launch
  set — canonical fill is a mapping over whatever staging holds; CDS-derived
  facts (shape/07) stay coverage-honest per school. **DEFAULT: yes.**
- **D17. Living at home carries no food-and-housing figure, and that counts as
  $0 in a total.** _(Added 2026-09-05, after `shape/02/ipeds-ic-ay` landed as
  RFC 161. This corrects a factual error in this spec's DISCOVER preamble §1 and
  in D5, both of which said IPEDS IC_AY publishes an off-campus-with-family
  food-and-housing figure. It does not: `CHG7/8AY` is off campus **NOT** with
  family, and the with-family arrangement has exactly one variable, `CHG9AY`
  (other expenses). No source anywhere publishes with-family food and housing —
  IPEDS assumes zero.)_ **Ian's call:** treat it as **$0** and say so. Eating at
  home is not free, but it is negligible against the household's existing
  baseline and it is **not a new expense caused by enrolling** — which is what a
  cost-of-attendance figure is for. So a with-family total IS shown, it is
  complete, and the surface states the assumption in words rather than printing
  a silent zero line. **DEFAULT: yes.**

- **D18. A district price is disclosed, never ranked on.** _(Added 2026-09-05,
  after `shape/04/cost-answers-from-canonical` landed as RFC 166 and made the
  in-district tier real on a consumer surface. D14a predates that tier and is
  under-specified for it.)_ unicoach asks for a **state**. It never asks which
  district a family lives in, and there is no vocabulary for districts, so a
  state answer cannot select an in-district price. **Ian's call:** search ranks
  and filters on the **in-state** price for a school that publishes both, and
  where a lower district price exists the result **says so** — "this school also
  publishes a district price of $X for students living in its district". One
  ruler per query (D14c) is preserved, residency is never invented, and the
  cheaper possibility is disclosed rather than hidden. Ranking on in-district
  where the family's state merely matches is refused: it asserts a fact about
  the family, and the error is large — Austin Community College's district price
  is $2,550 against an in-state price of $8,580. Applies to the 269 institutions
  that publish a distinct in-district figure. **DEFAULT: yes.**

- **D19. A tuition figure whose tier the publisher does not separate is ranked,
  and the ambiguity is named.** _(Added 2026-09-05, same occasion. RFC 161
  deferred "what a family is told" about these figures to `shape/04`, which
  answered it for the coach; this decides it for search.)_ About **2,300**
  institutions report on a program-year calendar, are absent from IPEDS IC_AY,
  and carry only the Scorecard figure, which folds in-district into "in". Their
  tier is genuinely unknown. **Ian's call:** rank them on the published figure
  and label it — the source does not separate a district price from a state
  price for this school — exactly as `ResidencyTierBasis` already says it in the
  coach. They are **not** dropped into `excluded_unknown`: that set is
  disproportionately community colleges, which are the schools a price search
  exists to surface, and hiding them to keep the ruler tidy defeats the feature.
  Silently treating them as in-state (today's behaviour) is refused — it is
  measurably wrong for at least the 269 cases we can check. **DEFAULT: yes.**

Approve the gate and wave 1 (shape/01, shape/06) is startable; amendments are
carried into the slice texts before anything runs.

**Gate-2 amendments after approval:** D17 (2026-09-05, carried into `shape/04`
before it ran), D18 and D19 (2026-09-05, approved by Ian after `shape/04`
landed; carried into `shape/05` below).
