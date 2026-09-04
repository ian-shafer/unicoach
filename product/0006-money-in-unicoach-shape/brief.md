# 0006 — Money in unicoach shape

Status: **GATE 2 APPROVED (Ian, 2026-09-02, defaults, no amendments) — EXECUTE.
Wave 1 (`shape/01/canonical-store`, `shape/06/pell-and-loans`) is startable.**\
Handle: `shape` (slice IDs are `shape/NN.n/name`).\
Opened: 2026-09-02, by Ian: "We've put replicated external data into our
database in _its_ shape, not the shape _we_ need it to be in."

    PHASE      FRAME -> [PRIORITISE gate 1] -> DISCOVER -> [SPEC & SLICE gate 2] -> EXECUTE -> LEARN
    NOW                                        ^^^^^^^^ HERE (gate 1 approved)

## Ledger

_(one line per landed slice: ID, RFC, SHAs, one-line what — appended after each
/ship run lands.)_

shape/06/pell-and-loans LANDED as RFC 159 (main@fd55df26 + b9f74e2a, 2026-09-03)
— `policy_parameters`, the award-year-keyed federal store (24-name vocabulary,
source URL per row, 29 seed rows verified against primary FSA sources this run),
and `federal_aid_policy`, the coach tool that answers "do we qualify for Pell,
roughly?" and "what would loans look like?" with cited, award-year-named facts:
Pell as eligibility and a range, loan limits never subtracted from a price, a
prior award year said plainly (2026-27 loan limits deliberately unseeded — FSA
Vol 8 unpublished, OBBBA changes lending from July 2026), and computations
narrated at read time, never stored. `money_profiles.dependency` added on the
house tri-state shape; coach prompt v17 (rollback
`COACHING_SYSTEM_PROMPT_VERSION=v16`). No `Needs:` edges, so no gate answers
were required.

## The question

Every money figure unicoach serves today is stored in the shape its publisher
chose, not the shape unicoach thinks in. `colleges` carries the College
Scorecard's own blend: `net_price_per_year_usd` is NPT4 (an in-state figure at a
public school — RFC 157 was a whole correctness RFC about exactly this),
`net_price_per_year_income_q1..q5_usd` are the Scorecard's income quintiles,
`cost_of_attendance_per_year_usd` is COSTT4_A's in-state blend, and the six
living-cost component columns are ROOMBOARD_ON/OFF and friends renamed. The
product then compensates downstream, one RFC at a time: RFC 149 rebuilt
arrangement totals at read time, RFC 151 narrated the hidden assumptions, RFC
157 withheld the wrong-basis figures at the source, and brief 0005 (gate 1
presented, unanswered) proposes still more columns in the same shape to fix
search ranking. Each fix is honest and each was necessary — and each is evidence
that the storage shape is wrong, because a correct shape would have made the
defect unrepresentable.

So the question this brief answers is: **what is the money schema unicoach
itself believes in — the one where a public school HAS in-state and out-of-state
prices as first-class facts, where "we have not collected this" and "the
institution does not report this" are different stored values, and where aid,
loans, Pell, and FAFSA are modeled as unicoach concepts — with offline
processing mapping every external source INTO that shape?**

## Why now

1. **The compensation stack is growing.** Four money RFCs (149, 151, 152, 157)
   exist to repair at read time what storage got wrong. Brief 0005 would be a
   fifth. The marginal cost of each fix is rising because each must thread every
   consumer.
2. **NULL is ambiguous everywhere.** A NULL `net_price_per_year_usd` cannot say
   whether the school reported nothing, the Scorecard suppressed it, or our
   ingest never carried it. The product's honesty rules (labelled blanks, named
   silences) are today enforced by prose and tests, not by the data model.
3. **The reference-table precedent exists and works.** RFC 147's codebook tables
   (`us_states`, `ipeds_regions`, `nces_locales`, `cip_codes`, ...) already
   proved the pattern: unicoach-keyed vocabulary tables, offline generation,
   provenance rows, loud drift reporting. But they mirror the PUBLISHED
   codebooks; Ian's directive is one step further — unicoach authors its own
   states/regions vocabulary and MAPS external codes into it.
4. **New sources are coming.** IPEDS IC (brief 0003 D7, deferred not rejected)
   adds in-district tuition and separately-reported fees; CDS is already
   partially ingested (`college_merit_aid`); an NPC or state-exchange source
   would add more. Without a canonical layer, every new source adds columns in
   ITS shape and every consumer learns another dialect.

## What the repo does today (the honest foundation)

Grepped 2026-09-02; the repo-audit research report carries file:line detail.

| Layer                     | What exists                                                                                                                                                                                                                                                                                          | Whose shape                                                                                                                  |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `colleges` money columns  | `cost_of_attendance_per_year_usd`, `net_price_per_year_usd`, `net_price_per_year_income_q1..q5_usd`, `tuition_and_fees_in_state/out_of_state_per_year_usd`, `median_debt_at_completion_usd`, `median_earnings_10y_after_entry_usd`, `pell_share`, six living-cost component columns (migration 0062) | Scorecard's (NPT4/COSTT4_A/ROOMBOARD_*), renamed by 0059 but not reshaped                                                    |
| `college_ipeds`           | housing flag etc.                                                                                                                                                                                                                                                                                    | IPEDS                                                                                                                        |
| `college_merit_aid`       | CDS H2A-derived counts                                                                                                                                                                                                                                                                               | CDS                                                                                                                          |
| `college_search_index`    | derived; `net_price_percentile_share` ranks every family on the in-state figure                                                                                                                                                                                                                      | derived FROM the Scorecard shape                                                                                             |
| Codebook tables (RFC 147) | `us_states`, `ipeds_regions`, `nces_locales`, `cip_codes`, +7 more; `codebook_sources` provenance                                                                                                                                                                                                    | published codebooks, mirrored verbatim                                                                                       |
| `money_profiles`          | the FAMILY side: income band (Scorecard quintile-shaped!), residency state, living plan; tri-state unset/declined/value                                                                                                                                                                              | half ours — but the income band is literally the Scorecard's quintile bands (RFC 142 ships their dollar ranges to the coach) |
| Missingness               | bare NULL everywhere on the college side; tri-state exists only on `money_profiles`                                                                                                                                                                                                                  | nobody's — that is the problem                                                                                               |
| Read-time repair          | RFC 149 arrangement totals, RFC 151 `comparison_basis`, RFC 152 silence taxonomy (`ArrangementGap` vs `NoTotalReason`), RFC 157 withholding                                                                                                                                                          | ours, but in code not data                                                                                                   |
| Offline processing        | `bin/ingest-colleges` phased loader, `college_index_build` provenance, `bin/fetch-codebooks` generator                                                                                                                                                                                               | the pipeline pattern to build on                                                                                             |

Note what the read-time layer already invented, because it is the embryo of the
canonical model: **arrangements** (on_campus / off_campus / with_family), **the
silence taxonomy** (`ArrangementGap` = what the school published vs
`NoTotalReason` = where we are silent), **the comparison basis** (population,
residency, arrangement, year, aid basis, blend basis), and
**vintage-per-figure** (only same-vintage figures may be summed). The canonical
schema's job is largely to move these from Kotlin into the data model.

## Relationship to other briefs

- **Brief 0003 (clear money language) — COMPLETE.** Its vocabulary and honesty
  rules are settled constraints here, not open questions: tuition and fees /
  housing and food / published price / financial aid offer; loans are never
  subtracted from a price; a partial sum is never a total; only same-vintage
  figures enter a sum.
- **Brief 0005 (price you would pay, in search) — GATE 1 PRESENTED,
  UNANSWERED.** Its D2 proposes new index columns built directly on the external
  shape. This brief supersedes that framing; 0005's fate is gate-1 decision
  **D-0005** below.
- **Brief 0001 `first-value/06` (invite-your-parent)** — independent; no money
  schema contact. Can run in parallel.

## Success criteria for this decision

1. A schema where the RFC 157 defect is **unrepresentable**: a price row names
   its residency basis, or it cannot be stored.
2. "Not collected by us" and "not reported by the institution" (and "suppressed
   by the publisher", if the sources distinguish it) are **different stored
   values**, so every honest-blank rule downstream becomes a projection of data,
   not prose.
3. Every external figure reaching a user passes through the canonical layer; no
   tool reads a publisher-shaped column. External shape survives only in the
   ingest/staging layer.
4. Adding IPEDS IC (or any next source) is a **mapping change in offline
   processing**, not a consumer change.
5. The migration has a safe path: canonical layer lands and is backfilled BEFORE
   consumers cut over, consumer by consumer, with the old columns dropped only
   at the end.

## Research (PRIORITISE input — three parallel reports)

- `research/money-domain-model.md` — how unicoach should think about money:
  price structure (residency tiers incl. in-district and tuition exchanges,
  arrangements), aid taxonomy (gift vs self-help, need vs merit, Pell,
  FAFSA/SAI, CSS Profile, loan types, work-study), family-facing questions the
  shape must answer. Cited.
- `research/source-landscape.md` — every realistic source and the exact money
  facts, keys, vintages, missing-value semantics and licences of each
  (Scorecard, IPEDS IC/SFA, CDS, NPC, exchanges). Which sources DISTINGUISH
  not-reported from suppressed. Cited.
- `research/repo-money-audit.md` — every money-bearing column/table/tool with
  file:line, every consumer of each publisher-shaped column, the ingest
  architecture as it stands, and the cutover blast radius.

## Gate 1 — prioritisation

**Ian: approving costs one word. Amend any line and I carry the amendment into
every slice.** Defaults are pre-chosen and shown as DEFAULT. Every claim below
is cited in the three research reports; report shorthand: DOMAIN =
`research/money-domain-model.md`, SOURCES = `research/source-landscape.md`,
AUDIT = `research/repo-money-audit.md`.

- **D1. The bet: a canonical money layer, filled offline, read by everything.**
  New unicoach-shaped tables are populated by a new ingest phase (the
  `search-index` slot — AUDIT §4); every user-facing money read goes through
  them; publisher shape survives only in staging tables
  (`college_ipeds`/`college_merit_aid` already ARE that pattern — AUDIT §4). The
  four read-time repair RFCs (149/151/152/157) become projections of stored
  data. **DEFAULT: yes.**

- **D2. Two entities at the core, and blends can never enter the price table.**
  **PriceFigure** — key: college × price concept × residency basis × arrangement
  × academic year; a fact the school publishes. **CohortMoneyStat** — key:
  college × measure × population basis × vintage; a statistic about a population
  (NPT4 net prices, COSTT4_A, Pell share, median debt, median earnings live
  HERE, labeled with the in-state Title-IV-aided population they describe). The
  RFC 157 defect and price/outcome mixing become unrepresentable (DOMAIN §5).
  **DEFAULT: yes.**

- **D3. Missingness is a stored status, not a bare NULL.** Per figure:
  `reported` / `not_reported_by_institution` / `not_applicable` /
  `suppressed_by_publisher` / `imputed_by_publisher` / `not_collected_by_us` —
  with a value-IFF-(reported|imputed) CHECK, the `money_profiles` tri-state
  precedent generalized (AUDIT §1.5). This is fillable: IPEDS X-flags natively
  distinguish reported/blank/not-applicable/imputed; Scorecard's
  `PrivacySuppressed` is real and currently discarded at
  `CollegeScorecardLoader.kt:1239` (SOURCES, missingness table). Recovering it
  requires re-parsing the pinned snapshots — an ingest change, no new source
  fetch. **DEFAULT: yes.**

- **D4. Residency is a four-value unicoach vocabulary:** `in_district` /
  `in_state` / `out_of_state` / `not_applicable` (a single-price school is not
  "unknown" — DOMAIN §1.2). A price row without a residency basis cannot be
  stored. **DEFAULT: yes.**

- **D5. Un-defer IPEDS IC_AY, add IPEDS SFA (the two keystone sources).** IC_AY
  (~310KB/yr, public domain, NOT the IC file we pin) is the only bulk source
  with first-class three-tier tuition AND fees split AND all-arrangement
  components including with-family housing-and-food, four years per file, full
  missingness flags — everything the Scorecard structurally lacks. SFA adds
  honestly-labeled net price, income-band aid, Pell/loan averages, aid mix by
  source, and the residency/arrangement population counts. Scorecard is demoted
  to what it is uniquely good at: NSLDS debt and Treasury earnings. When the
  same fact arrives from both, the upstream (IPEDS) value wins — recorded per
  figure with source + vintage, never averaged (SOURCES, priority order). This
  resolves brief 0003 D7. **DEFAULT: yes.**

- **D6. Vocabulary authority inverts: unicoach authors, external maps in.** The
  pattern is `subjects.json` (authored taxonomy + mapping + fatal validation at
  load — AUDIT §5), applied to residency, arrangement, figure status, price
  concept, income bands. The RFC 147 codebook mirrors are KEPT as evidence and
  become inputs to the mapping; the drift report inverts — "published code with
  no unicoach mapping" becomes the loud finding. **DEFAULT: wrap the mirrors, do
  not replace them.**

- **D7. Income bands: own the vocabulary, keep the five cut-points.** Both fill
  sources (NPT41-45, NPIS41-45) publish the same five bands, and re-banding is
  impossible without microdata. So the bands become authored vocabulary rows
  carrying their dollar ranges as data (today the range text lives in a Kotlin
  enum, `IncomeBand.kt` — AUDIT §1.5); `money_profiles` keeps its values; a
  future source with different bands maps or stays separate. **DEFAULT: keep
  five bands, promote to owned vocabulary.**

- **D8. A federal policy parameter store (new entity class).** Award-year-keyed
  authored facts: Pell max/min, loan limits by level/dependency, SAI floor —
  hand-verified against FSA sources each cycle. It answers "do we qualify for
  Pell" and "what would loans look like" with ZERO college data — the cheapest
  high-trust win in the whole brief (DOMAIN §2.2, §4). **DEFAULT: yes, modeled
  and seeded in v1.**

- **D9. Modeled ahead of data:** AidPolicyFact (css_profile_required,
  meets_full_need, need_fully_met stats — CDS §H2/H7/H8 can fill from the corpus
  we already pull) is defined in v1 and filled by a later slice.
  ExchangeParticipation (WUE/MSEP/RSP/ACM rosters + formula, participation only,
  never a stored price — DOMAIN §1.3) is deferred to its own slice after
  residency tiers exist. **DEFAULT: yes.**

- **D10. Cutover contract: beside, backfill, cut over, drop last.** Canonical
  tables land beside `colleges`; the new ingest phase backfills them; then
  consumers cut over one at a time against AUDIT §2's reader checklist (the
  blast radius is known: ~10 surfaces for `net_price_per_year_usd` alone);
  publisher-shaped columns are dropped only when their reader count is zero.
  Wire keys currently ARE column names (`CostField.wireName` — AUDIT §2), so
  each consumer's cutover slice owns its tool-contract change and prompt bump.
  The staged playbook already exists in-repo (0045/0059/0062/0064 — AUDIT
  §6.10). No user-visible money behavior may regress mid-cutover. **DEFAULT:
  yes.**

- **D11 (D-0005). Brief 0005 is PAUSED, not answered.** Its gate-1 list proposes
  new index columns in the external shape — this brief supersedes that framing.
  Its measured evidence (tau 0.561, the surface inventory) and its product
  rulings carry over as inputs; its slices get re-cut on the canonical layer,
  where "rank on the price that is yours" is a query over PriceFigure rather
  than new bespoke columns. **DEFAULT: pause 0005; its question becomes one of
  this brief's slices.**

Standing rules reaffirmed, not re-decided: every new table shows its DDL at
/ship's approval gate (0001 D10); value-before-ask on any family-facing flow
(0001 D12); brief 0003's money vocabulary and honesty rules.

**Things that can never be stored per-college facts** (DOMAIN §5, recorded so no
future slice tries): this family's net price/SAI/Pell/loan amounts; a family's
exchange entitlement; state-grant eligibility; any single "generosity score"; a
residency-blended price (blends exist only as cohort statistics with a named
basis).

## Gate 1 outcome (2026-09-02)

Ian, verbatim: **"I approve the gate"** — D1-D11 approved as defaulted, no
amendments. One clarification was asked and answered before approval (what D2
means); no decision text changed.

Consequences recorded the same day: brief 0005's Status notes the D11 pause;
`product/STATUS.md` work table carries this brief; brief 0003 D7 (IPEDS IC as
upgrade path) is resolved by D5.

## Gate 2 outcome (2026-09-02)

Ian, verbatim: **"approve. Does this change any of our current slices?"** —
D12-D16 approved as defaulted. Answer recorded with the approval: no existing
specced slice's text changes. `first-value/06/invite-your-parent` stays READY
and independent (its only contact is the shared coach prompt — the
claim-numbers-live rule covers parallel runs). `search/06/unattended-refresh`
stays DEFERRED as written. Brief 0005's slices were never specced, so nothing
there is re-cut — its question simply IS `shape/05`. Two STATUS.md Backlog
entries are absorbed: the "search ranks every family on the in-state net price"
follow-up (now `shape/05/search-on-your-price`) and "IPEDS IC as a cost-data
upgrade path, brief 0003 D7" (resolved by D5, now `shape/02/ipeds-ic-ay`).
