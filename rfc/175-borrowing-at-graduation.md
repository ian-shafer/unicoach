# RFC 175 — Borrowing at graduation: what students here actually borrow, and how much of it is private

Slice: `shape/07b/borrowing` (brief 0006, split out of `shape/07` at design —
RFC 170 D1). Lane A.

## Summary

A family reading unicoach today hears one debt sentence, and it is the tidier
half: the Scorecard's `median_debt_at_completion` counts **federal loans only**.
The part that hurts most — private loans, with no federal protections and no
income-driven repayment — is invisible.

The Common Data Set corpus we already pull answers the whole question. Section
H4/H5 publishes, per school per filing year, the graduating class (`H.401`), the
number who borrowed by loan type (`H.501`–`H.505`: any / federal / institutional
/ state / private), and the average cumulative principal borrowed by loan type
(`H.511`–`H.515`). This RFC ingests those cells into the canonical money store
and makes the coach speak them through the door RFC 170 already opened — the
existing `college_cost_profile` tool, no new tool, nothing gated.

It adds **no table**. Loan type rides in the measure name (for an average) and
in the population slug (for a borrower count), which is the pattern `shape/03`
already landed for the grant mix.

## Decisions

**D1. Loan type is part of the MEASURE, not a new dimension and not a new
relation.** `cohort_money_stats`' three cohort columns describe the POPULATION a
number is about; loan type describes the THING MEASURED. RFC 162 answered this
once already for the grant mix — `federal_grant_share`,
`state_local_grant_average_award`, `institutional_grant_share`, not a
`grant_sources` axis — and borrowing is the same shape. A sixth axis would force
every price and every existing statistic to answer a loan-type question it is
not about; a separate relation would fork the canonical store two slices after
`shape/01` unified it. **No `loan_types` vocabulary is added to
`money-vocabulary.json`** (measures are TEXT + CHECK mirrored by a Kotlin enum —
RFC 158 P4 — not authored taxonomy).

The same rule carries the borrower headcounts. `cohort_population_counts`'
natural key is `(college_id, population, residency_basis, arrangement, vintage)`
and has **no measure column**, so five borrower counts for one school-year would
collide unless the loan type rides in `population`. It does: one population slug
per loan type, named the way the source names it.

**D2. The published percent cells `H.506`–`H.510` are NOT ingested.** Measured
over the live corpus: 175 documents publish those cells as `0..100` text and 38
publish them as `0..1` text (`'0.5802197802197803'`), all with
`value_kind = 'text'` and `value_num = NULL`. A stored share would be a coin
flip on whose scale a school used, and `get_percent`'s percent branch never
fires on a text-typed cell, so today's reader would emit "0.58% borrowed". The
share is **derived at read time** from the borrower count over the `H.401`
graduating class, and only when both exist — RFC 170's `avg_need_met_share`
pattern. This also keeps the gate-1 never-store rule.

**D3. A loan type is never summed with another, and never dropped into a
nameless total.** Reporting is genuinely partial: `H.502` (federal) reaches 239
of our seeded colleges and `H.503` (institutional) only 170. "Borrowed" with no
named loan type is a different fact from "borrowed federally", so every stored
row and every spoken sentence carries its loan type, and the `any` figure is
always the school's own `H.501`/`H.511`, never our addition of the other four.

**D4. Existing loan denominators are reused, not duplicated.** `aid_scope`
follows the DENOMINATOR (the 0085 rule). An average cumulative principal is an
average over the BORROWERS OF THAT LOAN TYPE, so it carries a receiving scope
per type. Two of the five already exist and mean exactly the right set:
`loan_receiving` ("borrowed a loan of ANY kind") and `federal_loan_borrowing`
("borrowed federal loans"). Three are new: `institutional_loan_borrowing`,
`state_loan_borrowing`, `private_loan_borrowing`. Reuse is safe because an
address is `(measure, population, aid_scope)`: the Scorecard's completer debt is
`(median_debt_at_completion, federal_loan_borrowing_completers,
federal_loan_borrowing)`
and the CDS federal average is
`(federal_loan_debt_average, graduating_class, federal_loan_borrowing)` — the
same denominator, a different measure over a different cohort. Minting
`cds_federal_loan_borrowing` beside `federal_loan_borrowing` would make the
store say the source, which is exactly what brief 0006 exists to stop.

**D5. Borrowing rides the existing `aid_policy` seed file, not a fifth one.** A
new fact group costs five coupled edits (`FACT_GROUPS`, a flag in
`bin/ingest-colleges`, a flag in `bin/load-external-data`, a `CdsSources`
member, a PROVENANCE digest) and buys nothing: the CSV is already
`(unit_id, source_year, fact, value, status, source_variable, ...)`, so eleven
new facts are eleven new rows and eleven new coverage floors. Same section H,
same filing, same `source_documents` row.

**D6. A filing whose borrowing block contradicts itself is dropped, stickily.**
In 14 of 249 documents the borrower count EXCEEDS the graduating class (ipeds
166629: class 39, borrowers 2588), and `H.503` carries a dollar amount in a
count cell for ipeds 145637. The `h2_is_contradictory` precedent applies: a
filing whose `H.501`–`H.505` exceed `H.401`, or whose per-type count exceeds
`H.501`, has its whole borrowing block dropped with sticky reason
`aid_policy_h5_contradictory`. The rest of that filing's aid-policy facts are
unaffected. A derived share must never divide by a mis-extracted denominator.

**D7. Statuses keep RFC 170's discipline.** A cell the corpus failed to extract
(`parse_error`, and `H.503`/`H.504` carry a handful) lands as
`not_collected_by_us` — OUR gap, never spoken as the school's silence. A cell no
source carries gets NO row. A value exists exactly when the status bears one, as
the four fact tables' `value_iff_status` CHECKs already enforce.

**D8. What a family is told.** The coach names the cohort out loud — _the
students who graduated from this school in 2024-25_, not all undergraduates, not
this year's freshmen — gives the average cumulative amount at graduation and the
share who borrowed at all, and splits federal from private where the school
filed both. Brief 0003 binds without exception: **a loan is never subtracted
from any price, and a debt figure is never presented as a price.** Debt sits
beside the price conversation as an outcome of it, in its own sentence.

**D9. The Family Cost Report page changes too (Ian, at the gate).** The
parent-facing artifact is where the federal-only debt figure misleads most, so
`CostReportPage.debtBlock` grows the CDS half beside it. Two publishers with two
vintages share one section, and the rules that keeps honest are stated here: the
CDS sentence names the school as the claimant and carries its filing year; the
Scorecard sentence keeps saying "federal loans" and "the source publishes no
year for this figure"; the two are never summed, never differenced, and never
presented as one debt number. Where the school filed no private figure, the
section says so rather than leaving the impression that federal is the whole of
it. `blankFor` still owns the absence sentences.

**D10. A CDS figure is spoken as the school's OWN claim.** These are
self-reported survey answers, not administrative records: the CDS is filled in
by a school about itself with no audit, and 14 of 249 filings in our own corpus
report more borrowers than graduates. The store keeps the publisher out of the
KEY (measure, population, scope) and keeps it fully on the ROW (`source`,
`source_variable`, `publisher_flag`, `source_document_id`, `vintage`) — that
part is right and does not change. What changes is the VOICE: every borrowing
sentence attributes the figure ("Amherst College reports that…"), never states
it in the flat voice used for an administrative figure. A test asserts no
borrowing sentence states a CDS figure unattributed.

Modelling that softness as DATA — a `self_reported` attribute on the source, so
the hedge is derived rather than hand-written at each site — is the right
long-run shape, but it touches every source and every existing figure. It goes
to /chart as its own brief, not into this run (Ian, at the gate).

**D11. A zero average over zero borrowers is refused, but a zero COUNT is
kept.** Found at review, measured on our own seed: the CDS writes `0` into an
average-cumulative-principal cell when nobody of that loan type borrowed, and 91
of 138 reported state-loan averages and 81 of 148 institutional ones were that
placeholder. A mean over an empty set is not a number, and spoken aloud — "they
owed $0 on average by the time they graduated" — it is false in the direction
that flatters the school. It is refused at ingest. The borrower count's own zero
is a different fact and stands: "no student here borrowed a state loan" is true
and worth saying. The coverage floors follow the honest counts down rather than
guarding placeholders (§2).

## Detailed Design

### 1. Schema — no new table, four CHECK lists extended (migration 0089)

The whole data-model change is vocabulary. `cohort_money_stats` gains five
measures and three aid scopes; both cohort tables gain six populations (their
population lists are identical and are always re-added together).

```sql
-- Loan type is part of the MEASURE (D1): the grant-mix rule from 0085, applied
-- to borrowing. 'debt' here is the CDS's cumulative principal borrowed by the
-- time a student graduated -- not an annual amount (that is
-- student_loan_average_amount, IPEDS) and not the Scorecard's federal-only
-- median (median_debt_at_completion, a different cohort and a different stat).
ALTER TABLE cohort_money_stats
    DROP CONSTRAINT cohort_money_stats_measure_check,
    ADD CONSTRAINT cohort_money_stats_measure_check
        CHECK (measure IN ('avg_net_price', 'published_cost_blend', 'pell_share',
                           'median_debt_at_completion', 'median_earnings_10y',
                           'pell_average_award', 'federal_grant_share',
                           'federal_grant_average_award', 'state_local_grant_share',
                           'state_local_grant_average_award',
                           'institutional_grant_share',
                           'institutional_grant_average_award',
                           'student_loan_share', 'student_loan_average_amount',
                           'avg_need_based_grant', 'avg_need_met_share',
                           'any_loan_debt_average', 'federal_loan_debt_average',
                           'institutional_loan_debt_average',
                           'state_loan_debt_average', 'private_loan_debt_average'));

-- The aid scope follows the DENOMINATOR (0085). Every new measure is an average
-- over the borrowers OF ITS OWN LOAN TYPE, so it carries that type's receiving
-- scope; 'loan_receiving' (any kind) and 'federal_loan_borrowing' already name
-- two of the five sets exactly, and are reused rather than duplicated (D4).
ALTER TABLE cohort_money_stats
    DROP CONSTRAINT cohort_money_stats_aid_scope_check,
    ADD CONSTRAINT cohort_money_stats_aid_scope_check
        CHECK (aid_scope IN ('federal_aid_receiving', 'federal_loan_borrowing',
                             'all', 'grant_aided', 'pell_receiving',
                             'federal_grant_receiving',
                             'state_local_grant_receiving',
                             'institutional_grant_receiving', 'loan_receiving',
                             'need_based_aid_receiving',
                             'institutional_loan_borrowing',
                             'state_loan_borrowing', 'private_loan_borrowing'));

-- Six populations, on BOTH cohort tables (their lists are one list). The
-- graduating class is the cohort every H4/H5 figure is reported over; the five
-- borrower cohorts carry the loan type in the slug because
-- cohort_population_counts' natural key has no measure column (D1).
ALTER TABLE cohort_money_stats
    DROP CONSTRAINT cohort_money_stats_population_check,
    ADD CONSTRAINT cohort_money_stats_population_check
        CHECK (population IN (<the nine existing>, 'graduating_class',
                              'graduating_class_borrowers_any_loan',
                              'graduating_class_borrowers_federal_loan',
                              'graduating_class_borrowers_institutional_loan',
                              'graduating_class_borrowers_state_loan',
                              'graduating_class_borrowers_private_loan'));
ALTER TABLE cohort_population_counts ... -- the identical list
```

`cohort_money_stats_share_range_check` is **unchanged**: no new measure is a
share, because the share is derived (D2). The migration adds a
`COMMENT ON CONSTRAINT`-style header comment in house style stating that, so the
next reader does not "fix" the omission.

Every stat row: `residency_scope = 'all'`, `income_band = NULL`,
`population = 'graduating_class'`, `vintage` = the filing year,
`source = 'common_data_set'`, `source_variable` = the field id (`H.511`…),
`source_document_id` = the filing's row (the existing
`..._cds_cites_document_check` makes that mandatory). Every count row:
`residency_basis = 'not_applicable'`, `arrangement = 'not_applicable'`,
`population` = one of the six, same vintage/source/document.

Kotlin mirrors land in the same change (the CHECK↔enum pin is a set equality
both ways): five `MoneyMeasure` members (`USD_PER_YEAR`), three `CohortAidScope`
members, six `CohortPopulation` members, each with a KDoc naming its CDS field
id and its denominator.

### 2. Ingest — `bin/fetch-cds-seed` (D5, D6, D7)

`FIELD_IDS` gains 11 ids (`H.401`, `H.501`–`H.505`, `H.511`–`H.515`).
`H.506`–`H.510` are deliberately absent, and a test names them (D2).

```python
AID_POLICY_FIGURES += [
    ("graduating_class_count",                  "H.401", read_int_cell),
    ("any_loan_borrower_count",                 "H.501", read_int_cell),
    ("federal_loan_borrower_count",             "H.502", read_int_cell),
    ("institutional_loan_borrower_count",       "H.503", read_int_cell),
    ("state_loan_borrower_count",               "H.504", read_int_cell),
    ("private_loan_borrower_count",             "H.505", read_int_cell),
    ("any_loan_debt_avg_usd",                   "H.511", read_money_cell),
    ("federal_loan_debt_avg_usd",               "H.512", read_money_cell),
    ("institutional_loan_debt_avg_usd",         "H.513", read_money_cell),
    ("state_loan_debt_avg_usd",                 "H.514", read_money_cell),
    ("private_loan_debt_avg_usd",               "H.515", read_money_cell),
]
```

The averages go through `read_money_cell` / `get_decimal`, never `get_int`: the
cells are text-typed and fractional (`'38217.2331'`), and `get_int`'s
`\d+(\.0+)?` would silently reject them — "an integer reader that rejects
`28,193.50` is the same silent gap in a different costume".

**Regenerating the seed rewrites all four CDS files, and that is the fetcher's
contract, not a spill.** `bin/fetch-cds-seed` selects documents and writes
`merit-aid.csv`, `admission-factors.csv`, `deadlines.csv`, `aid_policy.csv` and
`PROVENANCE.json` in one pass; there is no per-fact-group output. The
non-borrowing deltas this run picks up are LIVE CORPUS DRIFT, proven by
experiment rather than argued: the pre-change fetcher run today produces those
three files byte-identical to the post-change fetcher run today, and both differ
from the committed seed. Measured across the run's two regenerations:
`deadlines.csv` 1033 -> 846 rows (documents withdrawn upstream, and filings
falling back to an older cycle), `merit-aid.csv` 372 -> 368 (e.g. ipeds 110644,
whose `H.201` was re-extracted upstream from 31464 — plainly a
total-undergraduate figure — to 6615), `admission-factors.csv` 377 -> 373, plus
rotating signed CDN urls. `documents_selected` 454 -> 448. Drift is live and
ongoing, so a re-run at land time will not reproduce this seed byte for byte;
that is the corpus, not this change. Reverting those three files would make
`PROVENANCE.json` describe a crawl that never happened, so they land, and this
paragraph is the sign-off.

**A zero average is the source's sentinel for "nobody borrowed", and it is
refused (D11).** The CDS publishes `0` in an average-cumulative-principal cell
when the matching borrower count is `0`: a mean over an empty set. Measured on
the seed before this rule, 91 of 138 reported state-loan averages and 81 of 148
institutional averages were that placeholder, every one of them beside a zero
borrower count — so two thirds of the state-loan "coverage" was not data. The
coach would have said "students here owed $0 on average by the time they
graduated", which is false in the way that matters most: it reads as good news.
`drop_averages_over_no_borrowers` refuses a reported `0` average whose own loan
type's borrower count is zero or absent. **The COUNT's own zero always stands**
— "no student here borrowed a state loan" is true, useful, and a different
sentence.

`AID_POLICY_COVERAGE_FLOOR` gains all eleven (`require_aid_policy_coverage`
fatals on an unfloored fact). Floors sit below the measured intersection with
our seeded college set, at 75–88% of measured as the file's existing floors do:
250 / 190 / 190 / 130 / 120 / 190 for the counts, and 190 / 190 / **50** /
**35** / 190 for the averages. The two low ones are low BECAUSE of the sentinel
rule — institutional averages measure 65 and state 46 once the placeholders are
gone, against 148 and 138 before. A floor that still read 130 would be guarding
placeholder rows.

`h5_is_contradictory(rows, drops)` mirrors `h2_is_contradictory` (D6) and
`aid_policy_h5_contradictory` joins `STICKY_DROPS`.

### 3. Load — `CdsSeedLoader`

Eleven new `AID_POLICY_FACTS` entries, no new machinery, and none of them
hand-written: they are DERIVED from `LoanType.entries`, because the seed column
names are exactly the loan type's own value plus `_borrower_count` /
`_debt_avg_usd`. `LoanType` is the one place the measure, the denominator scope
and the borrower cohort are tied together, so the write side cannot drift from
what `BorrowingDao` refuses at read time; a hand-written second copy would
compile with a swapped constant and fault only in production.

`AidPolicyFact.Stat` today hard-codes `aidScope = NEED_BASED_AID_RECEIVING`; it
gains a REQUIRED `aidScope` field — no default. A denominator inherited from a
default is the RFC 162 defect this brief has now hit three times ("the scope did
not follow the measure"), and a default hides half the routing table's decision,
so the two H2 need rows name their scope as loudly as the five borrowing rows
do. The wholesale delete-by-source stays derived from the routing table, so no
new teardown code.

### 4. Read — `college_cost_profile`, the existing door

- **The address lives in one new enum, `db/models/LoanType.kt`, NOT on
  `CostField`/`CollegeFigures`.** Each loan type declares its measure, its aid
  scope (its denominator) and its borrower population in one place, so the
  measure-narrowed read and the population-narrowed read cannot disagree, and
  `BorrowingDao` REFUSES a stored average whose aid scope is not that loan
  type's denominator — the D1 pin fires again at read time. `CostField` is the
  wrong home for two reasons, both structural: a `CostField` carrying no amount
  is fed into `data_availability` (`CollegeCostChatTool`), which the
  `putBorrowing` bullet below forbids for a CDS silence; and `CollegeFigures`
  addresses only `price_figures`/`cohort_money_stats`, so it can hold neither
  the six `cohort_population_counts` reads nor the filing citation. The read is
  a sibling `BorrowingDao`, not a widening of `AidPolicyDao`, whose query is
  narrowed to the ONE aid scope its two averages share.
- `CollegeCost` gains a `borrowing: BorrowingAtGraduation?` section (the cohort
  belongs in the type name — D8) built like RFC 170's `aidPolicy`: the derived
  share is held as ONE value with both its counts
  (`BorrowerCounts(borrowers, graduatingClass)`), so a share can never be
  emitted without its denominator; a count with no `H.401` yields the average
  and no share (acceptance criterion (c)).
- `BorrowingWire` mirrors `AidPolicyWire`: per loan type a `*_average_debt_usd`,
  `*_borrower_count`, `*_share_who_borrowed_percent` (every numeric key names
  its unit, unit last — the house rule `MeritAidWire`/`AidPolicyWire` state), a
  `borrowing_note` carrying the standing instruction (the `FORMS_NOTE`
  precedent), plus `graduating_class_count`, a `cohort_label` naming the class
  in words with its year, and the CDS citation via the existing `putCitation`.
  Keys join `NUMBERS_BY_CONTRACT`.
- `putBorrowing` mirrors `putAidPolicy`, and there are **three** silences, not
  two, because D7's owner split is the point: **no filing** → "we don't hold a
  Common Data Set for this school"; **filing, no borrowing block** → "this
  school's CDS doesn't report borrowing"; **filing, a cell we could not read**
  (`not_collected_by_us` rows, value-less) → OUR gap, said as ours and never as
  the school's silence; **section** → the figures. Each has its own constant and
  its own sentence, and none of them enters `data_availability` (that list is
  for Scorecard/IPEDS fields).
- Tool description gains one paragraph: name the cohort, say the year, keep loan
  types separate, never sum them, never subtract a loan from a price.

### 5. Coach prompt v21 (migration 0090)

v20 byte-identical prefix plus one appended paragraph — the additive-only rule.
It says: borrowing is about the students who GRADUATED in a named year; say
federal and private separately; say plainly when the private figure is not in
the school's filing; never add loan types together; never subtract a loan from a
price; a debt figure is never a price. `service.conf`'s `systemPromptVersion`
moves to `"v21"`; rollback is `COACHING_SYSTEM_PROMPT_VERSION=v20`, rows being
immutable.

## Files Modified

| File                                                                         | Change                                                                      |
| ---------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| `db/schema/0089.extend-money-vocabulary-for-borrowing.sql`                   | NEW — four CHECK lists re-added (measure, aid_scope, population ×2)         |
| `db/schema/0090.seed-coach-system-prompt-v21.sql`                            | NEW — v20 + one appended paragraph                                          |
| `db/src/main/kotlin/ed/unicoach/db/models/MoneyMeasure.kt`                   | +5 members                                                                  |
| `db/src/main/kotlin/ed/unicoach/db/models/CohortScopes.kt`                   | +3 aid scopes, +6 populations                                               |
| `bin/fetch-cds-seed`                                                         | +11 field ids, +11 figures, +11 floors, H5 contradiction guard, sticky drop |
| `college/src/main/kotlin/ed/unicoach/college/CdsSeedLoader.kt`               | +11 routing entries; `Stat` carries its aid scope                           |
| `db/src/main/kotlin/ed/unicoach/db/models/LoanType.kt`                       | NEW — the address per loan type, declared once                              |
| `db/src/main/kotlin/ed/unicoach/db/models/CollegeBorrowing.kt`               | NEW — read shape; `BorrowerCounts` holds the pair                           |
| `db/src/main/kotlin/ed/unicoach/db/dao/BorrowingDao.kt`                      | NEW — one batched read; refuses a wrong-denominator row                     |
| `service/.../coaching/costs/BorrowingAtGraduation.kt`                        | NEW — the service-side section                                              |
| `service/.../coaching/costs/CollegeCostService.kt`                           | borrowing section, derived share with its counts                            |
| `service/.../coaching/costs/CollegeCostChatTool.kt`                          | `putBorrowing`, wire keys, description paragraph                            |
| `service/.../coaching/costs/BorrowingWire.kt`                                | NEW — wire names + `NUMERIC_KEYS`                                           |
| `service/src/main/resources/service.conf`                                    | prompt pin v20 → v21                                                        |
| `public-web/.../render/CostReportPage.kt`                                    | `debtBlock` gains the CDS half, attributed and dated (D9)                   |
| `db/seed/cds/aid_policy.csv`                                                 | regenerated seed carrying the borrowing facts                               |
| `db/seed/cds/{deadlines,merit-aid,admission-factors}.csv`, `PROVENANCE.json` | same fetcher pass; corpus drift, measured in §2                             |
| `service/.../coaching/report/ShareCostReportChatTool.kt`                     | description follows the page it offers (D9)                                 |
| tests                                                                        | see below                                                                   |

Not touched: `money-vocabulary.json` (D1), `price_figures`, the legacy
`colleges.median_debt_at_completion_usd` path (`shape/08`).

## Implementation Plan

1. Migration 0089 + the Kotlin enum members, together (the pin is set equality
   both ways, so they cannot land apart). `bin/db-reset` green.
2. `bin/fetch-cds-seed`: field ids, figures, floors, contradiction guard; run it
   and regenerate `db/seed/cds/aid_policy.csv`; check the summary's drop counts.
3. `CdsSeedLoader` routing + `Stat.aidScope`; `bin/ingest-colleges` end-to-end
   on the regenerated seed; confirm row counts per measure/population.
4. Read path: addresses → service section → wire → tool description.
5. Prompt v21 (0090) + config pin.
6. Full `nix develop -c bin/test check`.

## Tests

- **Measure↔denominator pinned.** `MoneyVocabularyLoaderTest`'s existing set
  equality covers the CHECK↔enum agreement; a new test asserts every borrowing
  measure is written with its own loan type's receiving scope, and every
  borrower count with its own population — the fourth guard against the defect
  RFC 148, 162 and 170 each hit.
- **Partial reporting** (`CollegeCostChatToolTest`): a fixture school with a
  federal figure and no private one emits the federal fact, no private key, and
  a sentence saying the private figure is not in this school's filing.
- **No summing**: a test asserts no code path adds two loan types, and that the
  `any` value is the school's own `H.501`/`H.511`.
- **`H.506`–`H.510` proven unread**: a shell test in `bin/scripts-tests` names
  all five and asserts they appear in no seed row.
- **Decimal reader**: a fixture cell `'38217.2331'` survives ingest; `get_int`
  would have dropped it.
- **Contradiction, BOTH arms**: a fixture filing with `H.401=39, H.501=2588`
  drops its borrowing block with the sticky reason and keeps its other
  aid-policy facts; a second fixture with `H.401=800, H.501=300, H.503=500`
  fires the per-type arm (a type count over the any-loan count but UNDER the
  class), which the class arm alone would never reach. The per-type value must
  sit under the graduating class, or the class arm — which is evaluated first —
  fires instead and the test proves nothing.
- **Share only with both counts**: borrower count present, `H.401` absent → the
  average is emitted, the share key is absent.
- **Three silences, three sentences**: no filing / filing without a borrowing
  block / a cell we could not read each produce their OWN constant, and the
  unread case is seeded with real value-less `not_collected_by_us` rows rather
  than with an empty block. Asserting the same sentence for two of them is the
  D7 defect in test clothing. None of the three enters `data_availability`.
- **The read-time refusals are tested, not asserted in prose.** `BorrowingDao`
  refuses an average whose `aid_scope` is not its loan type's denominator, and a
  borrower count exceeding its graduating class. That refusal is the whole
  justification for holding the address in `LoanType` rather than in
  `CostField`, so a test corrupts each case and pins the exception.
- **Attribution (D10)**: no borrowing sentence — tool copy or report page —
  states a CDS figure without naming the school as the claimant; a test asserts
  it over every borrowing string the payload and page can emit.
- **Report page (D9)**: with both figures present the section says the Scorecard
  federal figure undated and the CDS figure with its year; with the private
  figure absent it says so; a test asserts the two are never summed or compared.
- **Prompt**: `SystemPromptCatalogTest` pins v21 as "v20 + one appended
  paragraph" and the standing bans still hold (no bare source code, every
  "subtract" preceded by "never ", no `H.\d` in prompt text).
- Full `bin/test check` is the gate.
