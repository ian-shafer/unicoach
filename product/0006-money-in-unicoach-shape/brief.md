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

shape/01/canonical-store LANDED as RFC 158 (main@d0c2fbf0 + 9b75a0b1,
2026-09-04) — the canonical money store: `price_figures` (college × price
concept × residency basis × arrangement × academic year) split from
`cohort_money_stats` (measure × population basis × vintage), so a blend can
never enter the price table — no blend concept exists for its FK to name. Five
unicoach-authored vocabulary tables seeded from a committed, fatally-validated
`db/data/money-vocabulary.json` (the `subjects.json` pattern), with the seed
proven against the Kotlin enums both ways; D7's five income bands become rows
carrying their dollar ranges as data. D3's six-value stored missingness is real:
the Scorecard's `PrivacySuppressed` used to collapse into the same NULL as "not
reported" at `CsvIngestSupport.toIntOrNull`, and now survives the fill as a
`suppressed_by_publisher` row — a value exists exactly when the status bears
one, by DB CHECK and by a sealed `FigureReading` that will not compile the
invalid pairing. `aid_policy_facts` is modeled ahead and empty for shape/07. A
`canonical-money` ingest phase rebuilds both fact tables wholesale in one
transaction after `search-index`; `METHOD_VERSION` 5 → 6, three new provenance
columns on `college_index_build`, row counts and the per-status breakdown on
stderr. Substrate by design (the search/03a precedent) — NO consumer reads the
tables yet; the door opens at shape/04. Migration 0083, renumbered from 0077
twice as RFCs 159 and 160 landed while the run was open. Review caught two real
bugs: a vocabulary slug retirement would have wedged every future ingest on an
FK, and a missing `CONTROL` silently classified a college private (wrong NPT4
column family, wrong residency scope). No `Needs:` edges, so no gate answers
were required.

shape/02/ipeds-ic-ay LANDED as RFC 161 (main@c7ad0dfa + 7f5b7fbf, 2026-09-05) —
IPEDS `IC2023_AY.csv`, the published-charges file, becomes a second canonical
source ahead of the Scorecard under RFC 158's upstream-wins rule, and it makes
the in-district tier real: measured over the whole file, five of the six figures
the two sources share agree 100.0%, but `TUITIONFEE_IN` agrees only 92.1% and
all 269 mismatches equal the in-district figure exactly — Austin Community
College was served as $2,550 "in-state" when its real in-state price is $8,580.
Fees split from tuition (`fees_only`), four academic years land per file, and
the X-imputation flags become D3 statuses. Migration 0084's
`college_ipeds_charges` is narrow (one row per UNITID × charge variable ×
academic year) so a new year needs no migration; `source` became an owned
`MoneySource` enum with a domain CHECK on both fact tables, decided at the gate
(D6-style reasoning, but NOT a vocabulary table: a source is provenance, not a
unicoach concept, and precedence must not be operator-editable data). Two spec
facts were corrected against the pinned codebook and are defects for /chart to
carry: **`CHG7/8AY` is off campus NOT with family, so no source publishes
with-family food and housing** — the slice's third payoff is withdrawn and the
gap stays a labelled gap — and IC_AY covers 3,825 institutions, not ~6,100
(program-year reporters are a different file). Review over 39 lenses found two
future-dated bugs no test could catch: staged charges were never pruned, so an
institution leaving IC_AY would keep rows that beat the Scorecard forever; and
the survey year never reached the loader, so a 2024 file would have stamped
every price one year stale and exited green. Both fixed and tested. Substrate by
design — no consumer reads the rows; the door is shape/04. No `Needs:` gate
answers were required (BLOCKS shape/01 was already LANDED; CONFLICTS shape/03
had no live run).

shape/03/ipeds-sfa LANDED as RFC 162 (main@31df6154 + 72f0523a, 2026-09-05) —
IPEDS SFA (aid year 2022-23) becomes the third canonical source and the first
one about AID rather than price: net price with an honest population label,
income-band net price, Pell share/average, grant mix by source, loan share and
average, and — in a new `cohort_population_counts` table — the residency and
living-arrangement headcounts that make a basis concrete (College of DuPage:
1,488 of 2,099 students pay the in-district rate, so "71% of them" is now a
queryable fact rather than a blend). Three spec corrections, each measured
against the real published files rather than the source report, and each a
defect for /chart to carry: **SFA names the same concept differently for publics
and privates** (`NPIST`/`NPGRN`, `NPIS41-45`/`NPT41-45`), so the spec's
public-only variable list would have silently dropped ~65% of colleges; **`Z` is
an implied zero — a real published 0, not an imputation** (17,357 cells in one
file), so the spec's flag map would have labelled that many honest zeros
publisher guesses, and this also CORRECTS landed RFC 161, which mapped `Z` to
`imputed_by_publisher` — one flag vocabulary now serves both surveys and the
code wins per `rfc/README.md`; and **`not_reported_by_institution` is unfillable
from SFA** (NCES imputes rather than blanking), so nothing synthesises it. Aid
scope now follows the DENOMINATOR of the measure — a share is over the cohort,
an average is over recipients — which is what the old test missed by asserting
measure and value without the scope. Review over 39 lenses found three defects
no test could catch: the canonical fill read SFA from ambient database state, so
an ingest without the SFA group would have emitted rows from a previous run's
staging; the public/private split was spelled three ways and dropped an
unmatched institution silently; and an unparseable published value became the
same NULL as an empty cell, so OUR parse loss would have been reported as the
publisher's disagreement. All three fixed, each with a test that fails on the
old code. Migration 0085; `MoneySource.IPEDS_SFA` leads upstream-wins but never
overwrites `NPT4_PUB`, which describes a different population. Substrate by
design — no consumer reads the rows; the door is shape/04. No `Needs:` gate
answers were required (BLOCKS shape/01 was already LANDED; CONFLICTS shape/02
WAS live and did land first, so this run rebased onto it and collapsed three
duplicated abstractions — `MoneySource`, the imputation-flag vocabulary, and the
ingest option-group parser — to one each).

shape/04/cost-answers-from-canonical LANDED as RFC 166 (main@1cf8bcf1 +
7ab71b90, 2026-09-05) — the door. Every cost answer the product gives now comes
from `price_figures` and `cohort_money_stats` instead of the publisher-shaped
columns on `colleges`, and four things a family could not be told before are
told now: the **in-district** tuition tier named as its own tier (value-bearing
for ~269 colleges today, the exact set RFC 161 measured); **fees split from
tuition** where the publisher reports them split; a complete **living-at-home
total** whose food-and-housing line is a labelled `$0`, typed
`ASSUMED_BY_UNICOACH`, with D17's assumption stated in words as OURS and never
attributed to the school; and the **six figure statuses, spoken** — the
publisher's suppression named as the publisher's, an imputation named as the
publisher's estimate, and a gap of ours said as ours. The RFC 149/151/152/157
honesty layer survives intact as a **projection**: the domain types are
unchanged and only the source under them moved, which is the whole claim of D1.
Coach prompt **v19** (rollback `COACHING_SYSTEM_PROMPT_VERSION=v18`), migration
0086. `colleges` keeps its exact row shape — dropping those columns is
`shape/08`, after a reader audit, and this slice deliberately left the
search-index net-price path alone because that reader is `shape/05`. Review over
39 lenses in four sequential tiers found **ten blockers, every one a wrong or
misattributed money statement rather than a style call**, and three of them
existed only because an earlier tier's own fix created or exposed them. The most
instructive: **RFC 162 landed mid-run and added a second `avg_net_price`
series** — different cohort, different aid scope, newer vintage — and the
projection keyed cohort rows on `(measure, income_band)` and took the newest
vintage, so the coach payload, the parent report and the FitLens digest would
all have served the SFA grant-aided figure as "the overall average net price";
nothing failed, because the fixtures wrote at the reader's own addresses, so a
green suite proved only that the reader agreed with itself. Fixed by keying on
the full address and closed by a **cross-module contract test asserting the
reader's address grid matches the loader's in both directions**. Second:
**suppressed cohort figures spoke as the school's silence** — a withheld net
price, the most-read money figure on the surface, was published as "this college
does not report it" and the privacy sentence never reached the family; status
notes now route to the parent report so it cannot blame the school for our gap
or the publisher's. Third: **"take the newest" is a defect when the key is
incomplete** — three separate blockers were a `max`-by-date over rows never
narrowed to one address, and the year fix had to be taken twice, because after
the first fix the newest of the two _served_ addresses still put the sticker
price under the net price's year for any family who answered the income-band
question; `ServedFigures` now binds the year to the figures it dates. Structural
fixes taken: one injected `CanonicalCostReader` for both consumers, per-row
decode so one unreadable row cannot fail a whole college's answer, and the
FitLens digest degrading as a state (key omitted) rather than asserting a false
data claim. Gate: **2865 tests, 0 failures**, base `main@54113515`. Open and
recorded, not silent: the year-gap blank still shares one phrase (new copy needs
a product decision), `IncomeBand.bracket` is **superseded** rather than deferred
because RFC 165 serves the bands from the enum at `/api/v1/vocabularies`, and
`IncomeBand.netPriceFor(College)` survives only for the search-index row builder
for `shape/05` to retire. No `Needs:` gate answers were required (BLOCKS
shape/01 and PREFER shape/02 were both already LANDED; CONFLICTS-free, though
shape/03 landed mid-run and rebased in without conflict).

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
  components, four years per file, full missingness flags — everything the
  Scorecard structurally lacks. SFA adds honestly-labeled net price, income-band
  aid, Pell/loan averages, aid mix by source, and the residency/arrangement
  population counts. Scorecard is demoted to what it is uniquely good at: NSLDS
  debt and Treasury earnings. When the same fact arrives from both, the upstream
  (IPEDS) value wins — recorded per figure with source + vintage, never averaged
  (SOURCES, priority order). This resolves brief 0003 D7. **DEFAULT: yes.**
  _(Corrected 2026-09-05 by RFC 161: this decision originally claimed IC_AY
  carries with-family housing-and-food. It does not — `CHG7/8AY` is off campus
  NOT with family, and no source publishes the figure. Resolved by spec decision
  **D17**: living at home counts as $0 food-and-housing, stated in words.)_

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

shape/07a/need-and-forms LANDED as RFC 170 (main@f69119b0 + 43f97191,
2026-09-05) — the CDS answers what a school ASKS you to file and how it TREATS
need. A family can ask "does Amherst meet full financial need?" or "do we have
to do the CSS Profile?" and get that school's own Common Data Set back, cited,
with the year on it; the door is the existing `college_cost_profile` tool, no
profile required. The slice SPLIT at design: H4/H5 borrowing became its own
slice, `shape/07b` (a per-loan-type statistic needing a loan dimension
`cohort_money_stats` does not have, whose published percent cells are
corpus-typed text with mixed 0..1 and 0..100 values) — /chart must spec it.
**The modeled-ahead table was dropped, not filled**: `aid_policy_facts` (RFC 158
D9) was one key/value bag guessed before anyone had seen the data, and the data
is two shapes — a REQUIREMENT (`aid_forms` vocabulary + `aid_form_requirements`,
college × form × applicant group × year) and COHORT STATISTICS (the average
need-based grant and average share of need met in `cohort_money_stats`, the two
headcounts in `cohort_population_counts`, the fully-met share derived at read
time). Nothing in the schema names a CDS cell; the source is
`source = 'common_data_set'` with the field id in `source_variable`.
**Denominators are data.** The CDS reports its H2 cells against three different
cohorts — line d awarded any aid, line e awarded a need-based grant, line c
determined to have need, which no one publishes a matching numerator for — and
the first implementation used one slug for all three, which would have made
every sentence name a cohort the school never reported (the RFC 148/162 defect,
third occurrence). Each measure is now pinned by test to the line it is reported
over, and the copy says the cohort out loud because "need fully met" sounds like
line c and is not. **Duplication came out where it was found**:
`source_documents` holds one row per filing, so the three older CDS tables stop
keeping their own copies of `source_url`/`archive_url` (~1,777 rows over ~417
documents that could already disagree); `academic_year` and `money_source`
became DOMAINs, five `'YYYY-YY'` TEXT columns became SMALLINT start years, the
`'undated'` sentinel became NULL, and the label is rendered at read time.
Coverage is the honest CDS universe: 338 colleges of 417 seeded, need figures
312-321, FAFSA 272, CSS Profile 73, noncustodial 46. Forms are `required` or
`unknown`, never "not required" — the corpus carries no false checkbox — which
narrows the spec's "yes/no with citation" first-session test on purpose, and OUR
collection gap is said as ours (`not_collected_by_us`) rather than as the
school's silence. Coach prompt **v20** (rollback
`COACHING_SYSTEM_PROMPT_VERSION=v19`), migrations 0087/0088. Review over 39
lenses in four sequential tiers produced 117 findings in six fix batches;
**three were user-facing honesty defects** — a school whose filing we hold being
told "we hold no Common Data Set filing", our own gap rendered as the school's
silence, and an ingest without the optional CDS phase erasing every CDS fact and
exiting green — and **one was created by an earlier tier's own fix** (a nullable
document key plus an INNER join reopened the first bug; closed with a per-source
CHECK in the schema). Three slices landed under this one: RFC 162 before
implementation, RFC 166 mid-review (its `"undated"` sentinel deleted in favour
of D14's type, taking with it a comparator guard that existed because
`"undated" > "2023-24"` sorts by character code) and RFC 172 at the gate (its
`getopt` conversion and stderr discipline superseded ours wholesale; in return
this slice fixed its brand-new one-command reload, which built the ingest
command with `-m -a -d` and would have been refused with exit 21 once the CDS
group became four). No `Needs:` gate answers were required: its BLOCKS edge to
the canonical store was already LANDED, and its two CONFLICTS edges cost a
rebase, as predicted.

shape/07b/borrowing LANDED as RFC 175 (main@3924931d + 64b4875a, 2026-09-08) —
CDS H4/H5 borrowing at graduation, including the private loans the Scorecard's
federal-only debt figure never shows. Migration 0089 extends four CHECK lists —
five measures, three aid scopes, six populations on both cohort tables — and
adds NO table: loan type rides in the measure for an average and in the
population slug for a count, the grant-mix rule of RFC 162. The published
percent cells H.506-H.510 are never ingested (175 filings write 0..100, 38 write
0..1, all as text), so the share is derived from counts and only when both
exist. The door is the existing `college_cost_profile` tool plus the Family Cost
Report page, which Ian added at the gate (D9), and every figure is spoken as the
school's OWN claim (D10, also his call: these are unaudited self-reports, and
modelling that softness as DATA goes to /chart as its own brief). Review added
D11: a zero average over zero borrowers is the source's sentinel for "nobody
borrowed", not money — 91 of 138 state-loan averages and 81 of 148 institutional
ones were that placeholder, and the coach would have said "they owed $0 on
average"; refused at ingest, while the zero COUNT stands, because "no student
here borrowed a state loan" is true and worth saying. Four review tiers over 39
lenses found 95 findings; the ones that mattered were three D7 inversions, each
one level deeper than the last (the unread-cell test covered two silences, then
the unread flag was filing-wide, then the unread GRADUATING-CLASS cell reached
no surface at all), a citation edge re-derived on (college, source, year)
instead of following `source_document_id`, and one school's contradictory counts
thrown as corruption inside a BATCHED read, which would have denied the price
answer for every other college in a family's list. Gate: 2932 JUnit tests and
1187 shell assertions green. Corpus drift rode in with the seed regeneration and
was proven upstream by experiment (the pre-change fetcher run today reproduces
it), so it landed with a sign-off paragraph in the RFC rather than a revert.

shape/05/search-on-your-price LANDED as RFC 169 (main@8aaaa49b + d5610753,
2026-09-08) — search now runs on exactly ONE price ruler, and it is the ruler
the family would actually be billed on. Every ranking, filter and cheapest-first
sort used to read the Scorecard net price, which at a public school is the
IN-STATE figure after federal aid, so a family in Washington asking for cheap
California publics was ordered on a price it would never pay; over 2,971
operating four-year schools that ladder and an out-of-state published-total
ladder order about 22% of school pairs the opposite way (Kendall tau 0.561,
product/0005 research, carried in under D11). With the family's state on file
the whole query ranks and filters on each school's PUBLISHED on-campus total at
that family's own tuition tier, and the tool result says in ONE SENTENCE that
aid is not in that ruler — there is no out-of-state net price and there never
can be, because subtracting an in-state average grant from an out-of-state total
is the arithmetic RFC 149 forbids. With no state on file the ruler is the net
price exactly as before and the basis is in the metric NAME on the wire, not a
footnote. The two rulers can NEVER mix: `PriceRuler` is a value resolved once
before any SQL is built, the anchor carries the ruler it was read on, and a
mixed pairing is refused rather than quietly averaged. Search also now ASKS for
the state — an offer that never gates a result. **D19 landed with this slice at
zero DDL cost**: a school whose publisher does not separate an in-district price
is ranked AND labelled with the sentence the cost tools already use, never
silently treated as in-state (641 of 3,264 schools in the default universe carry
that label; 354 correctly stay silent because the publisher answered). The door
is the chat coach — "small schools in Oregon under $30k", "like Bowdoin but
cheaper" — plus every fit-lens sweep. Degrades two ways, both named: no
residency → the net-price ruler with its basis said out loud; no published total
→ the school is DROPPED, counted and named under `excluded_unknown`, never
substituted. Migration 0091 adds four nullable columns to `college_search_index`
(two tier values, two positions on the ONE out-of-state ladder) and replaces the
named percentile CHECK, 4 clauses → 6, keeping its name; it adds **no index** —
the btree drafted for the price bound was dropped at review because every
published-price read is a `CASE` over TWO tier columns, which no single-column
btree serves, so the index was write cost paid by no reader. Coach prompt
**v22** (rollback `COACHING_SYSTEM_PROMPT_VERSION=v21`); the columns are
additive and a query with no residency on file resolves to the net-price ruler,
which is the pre-RFC-169 behaviour column for column. Gate: **2990 tests, 0
failures**, 1126 shell assertions. Both readers of the publisher-shaped money
columns are now landed, so the cutover slice's two BLOCKS edges are satisfied;
its spec text predates this RFC and wants a /chart pass first.

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
