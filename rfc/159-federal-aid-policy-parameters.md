# RFC 159 — Federal Aid Policy Parameters: Pell and Loans in the Coach

Status: proposed\
Slice: `shape/06/pell-and-loans` (brief 0006, wave 1)\
Base: `main@d41af119` · Branch: `pipeline/rfc-159`

## Summary

Two of the most consequential questions a family asks — "do we qualify for a
Pell Grant, roughly?" and "what would loans look like?" — need **zero college
data** to answer honestly. They need a handful of federal policy parameters (the
Pell maximum and minimum award, the SAI floor, the max-Pell AGI tests, the
Direct Loan annual and aggregate limits) that change by award year and are
published by Federal Student Aid. Today the coach has none of them, so it either
stays silent or hard-codes numbers in prose — brief 0006's D8 calls the
parameter store "the cheapest high-trust win in the whole brief".

This RFC lands that store and its door: a new `policy_parameters` reference
table keyed `(award_year, parameter)`, hand-authored seed rows verified against
primary FSA sources **this run** (fetched live from fsapartners.ed.gov,
2026-09-03; quotes and URLs in `.scratch`-archived `verified-facts.md` and
inline below), a new `FederalAidPolicyChatTool` that serves those parameters
with per-source citations and the family's dependency answer, and coach prompt
v17 telling the model how to narrate them. Computations — a family's estimated
Pell award, their SAI, a loan gap — are narrated by the coach at read time from
the served facts and are **never stored** (gate-1 never-store list).

One deliberate honesty mechanism shapes the whole design: today's date is inside
award year 2026-27, and FSA has published the 2026-27 Pell figures (DCL
GEN-26-01) but **not yet the 2026-27 Direct Loan volume** (Handbook Vol 8 is
absent from the 2026-27 edition as of this run, and the 2025 reconciliation law
changes graduate/PLUS lending from July 1, 2026). So the store carries what is
verifiable per award year, the tool serves the latest row per parameter group,
and **when the latest verified year is behind the current award year the answer
says so plainly** — the slice's stale-award-year acceptance criterion is
exercised by real data on day one.

## Decisions

### D-A. One narrow table, parameter names carry their unit

`policy_parameters` is a narrow reference table: one row per
`(award_year, parameter)`, one `INTEGER` value column. The alternative — wide
typed columns per concept — would need a new migration for every statutory
change and would leave most cells NULL in years where a source is not yet
published.

The house naming rule (measure_qualifier_unit; dollars end `_usd`) lives in the
**parameter name**, not the value column: `pell_max_award_usd`,
`direct_loan_annual_dependent_y1_total_usd`,
`pell_max_agi_dependent_single_parent_pct`, `sai_floor` (a dimensionless index,
commented as such). The vocabulary is a closed `CHECK IN (...)` plus one Kotlin
`enum class PolicyParameter(val value:
String)` with a `fromValue` companion in
`db/models` — the IncomeBand/AnswerStatus precedent. 24 parameters at launch (2
Pell awards, 2 AGI-test percents, 1 SAI floor, 19 loan limits).

Values are integers. Every launch figure is a whole number of dollars, percent
points, or index points; a future fractional parameter gets its own `_x100`-
style name or a schema change argued then, not a speculative NUMERIC now.

### D-B. Rows carry their citation; the wire shape is CdsCitation's

The slice's first acceptance criterion — every row carries award_year + source
URL — lands as three NOT NULL columns: `source_name` (the spoken name, e.g.
"Federal Student Aid Dear Colleague Letter GEN-26-01"), `source_url`, and the
key's `award_year`. The tool renders citations exactly the way CDS merit-aid
citations render today (`CdsCitation.putCitation`: `{cited_as, url}`), grouped
per source document rather than repeated per row.

### D-C. Seed rows are authored in the migration, verified against primary sources

Prompt-seed precedent (0044-0076): architect-approved data INSERTed by an
immutable migration. A wrong or superseded figure is corrected by a **new
migration** (reference-table upsert on the natural key), never by editing a
landed one. This run verified every seeded figure against fsapartners.ed.gov
directly:

- **AY 2026-27** (current): Pell max **$7,395**, min **$740** (GEN-26-01,
  confirmed effective by P.L. 119-75); SAI floor **-1,500**; max-Pell AGI tests
  **225%** (dependent, single parent) / **175%** (dependent, non-single parent)
  (2026-27 AVG Ch 3).
- **AY 2025-26** (prior): the same five (GEN-25-02, 2025-26 AVG Ch 3), plus all
  Direct Loan limits from 2025-26 Handbook Vol 8 Ch 4 Tables 1A/1B/1C/4:
  dependent undergrad annual 5,500/6,500/7,500 (sub caps 3,500/4,500/5,500);
  independent undergrad annual 9,500/10,500/12,500 (same sub caps); grad annual
  20,500 unsub-only; aggregates dependent 31,000/23,000, independent
  57,500/23,000, grad 138,500/65,500.
- **AY 2026-27 loan limits are deliberately absent**: Vol 8 of the 2026-27
  Handbook is not yet published, and OBBBA changes graduate/PLUS lending from
  July 1, 2026. Seeding last year's numbers under this year's key would be a
  fabricated fact.

### D-D. The tool serves facts; the model narrates; Kotlin computes nothing

`FederalAidPolicyChatTool` takes no required input and returns: the current
award year (computed from the clock, July 1 boundary, via `AcademicYear`
labels), the latest available parameter group per topic — the latest award year
at or before the current one, so a future-published year is never served as
current — with its award year and citation, an explicit `award_year_status`
(`current` / `prior_year`) **with a statement sentence** per group, and the
money-profile dependency echo (D-F). There is no arithmetic in Kotlin — no SAI
estimate, no Pell amount, no loan-minus-anything — so nothing computed can be
stored, and the ForbiddenCostArithmetic rule has nothing to forbid. The prompt
(v17) instructs the coach to frame Pell as **eligibility and range, never a
promised award**, to state loan limits without ever subtracting them from any
price (brief 0003), and to name the award year it is citing.

### D-E. New package `coaching/aid`, and the arithmetic scan extends to cover it

The surface lives in `service/.../coaching/aid/` (the `coaching/admissions/`
precedent), not in `costs/`. `ForbiddenCostArithmeticTest` currently scans only
`coaching/costs/`; this RFC extends its scanned-directory list to include
`coaching/aid/` (with the same package-is-read guard naming an expected file),
so the money-vocabulary subtraction ban covers the new code from day one instead
of silently not applying. (Brief 0006 audit §6 flagged exactly this trap.)

### D-F. One `money_profiles` addition: `dependency`. Family size is deliberately not asked

Dependency (dependent vs independent for federal aid) selects which loan table
applies and which max-Pell test applies — an answer that visibly changes the
narration. It lands as the 0070 tri-state shape: `dependency` TEXT
(`dependent`/`independent`) + `dependency_status`
(`unanswered`/`answered`/`declined`) + value-IFF-answered CHECK, mirrored on the
history table, `log_money_profile_version()` re-listed; a `FieldUpdate` in
`MoneyProfileService` and an input on `MoneyProfileChatTool`, invited in-flow
with the value named, declinable, resumable (0001 D11/D12).

Family size is **not** added this slice, although the spec names it: the
max-Pell AGI tests key off the federal poverty guideline for family size, and
this slice stores no poverty-guideline table — so a family-size answer would
change nothing the product says. Asking for it would be an ask with no value,
which value-before-ask forbids. When a later slice seeds poverty guidelines (≈30
rows/year, same store), family size becomes a real input and is added then, with
its value nameable. The Pell answer today instead cites the AGI percents and the
guideline source so a family can check themselves.

### D-G. Stale award year is a first-class, per-group statement

"Current" is computed from the clock (award year = July-June). Each parameter
group in the payload carries `award_year`, `award_year_status`, and a
house-style statement key: for `prior_year`, e.g. "These loan limits are the
2025-26 award year figures; Federal Student Aid has not yet published the
2026-27 Direct Loan volume, and 2025 legislation changes graduate and parent
lending from July 2026." Never silently served, per the slice's acceptance
criterion — and, at launch, actually exercised by the loan group.

## Detailed Design

### Schema (migration 0077 — create + seed)

```sql
CREATE TABLE policy_parameters (
    award_year INTEGER NOT NULL
        CONSTRAINT policy_parameters_award_year_check
        CHECK (award_year BETWEEN 2024 AND 2100),
    parameter TEXT NOT NULL
        CONSTRAINT policy_parameters_parameter_check
        CHECK (parameter IN (
            'pell_max_award_usd',
            'pell_min_award_usd',
            'pell_max_agi_dependent_single_parent_pct',
            'pell_max_agi_dependent_non_single_parent_pct',
            'sai_floor',
            'direct_loan_annual_dependent_y1_total_usd',
            'direct_loan_annual_dependent_y1_subsidized_usd',
            'direct_loan_annual_dependent_y2_total_usd',
            'direct_loan_annual_dependent_y2_subsidized_usd',
            'direct_loan_annual_dependent_y3_plus_total_usd',
            'direct_loan_annual_dependent_y3_plus_subsidized_usd',
            'direct_loan_annual_independent_y1_total_usd',
            'direct_loan_annual_independent_y1_subsidized_usd',
            'direct_loan_annual_independent_y2_total_usd',
            'direct_loan_annual_independent_y2_subsidized_usd',
            'direct_loan_annual_independent_y3_plus_total_usd',
            'direct_loan_annual_independent_y3_plus_subsidized_usd',
            'direct_loan_annual_grad_unsubsidized_usd',
            'direct_loan_aggregate_dependent_total_usd',
            'direct_loan_aggregate_dependent_subsidized_usd',
            'direct_loan_aggregate_independent_total_usd',
            'direct_loan_aggregate_independent_subsidized_usd',
            'direct_loan_aggregate_grad_total_usd',
            'direct_loan_aggregate_grad_subsidized_usd'
        )),
    value INTEGER NOT NULL,
    source_name TEXT NOT NULL CHECK (source_name <> ''),
    source_url TEXT NOT NULL CHECK (source_url <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (award_year, parameter)
);
```

Reference-table shape (0060 precedent): unversioned, no soft delete, no OCC,
`update_colleges_timestamp()` trigger for `updated_at`. Award year is the
**starting** calendar year (2026 = "2026-27"); rendering goes through
`AcademicYear`. `sai_floor` gets a `COMMENT ON` noting it is a dimensionless
index (value -1500). All constraint names counted well under 63 chars; the
longest parameter value is a value, not an identifier, so the 63-char trap does
not apply to the vocabulary itself.

Seed: 5 rows for award_year 2026, 24 rows for award_year 2025, INSERTed in the
same migration with the source names/URLs from `verified-facts.md` (quoted in
D-C). Total 29 rows.

### Kotlin models and DAO

- `db/models/PolicyParameter.kt` — the 24-entry enum with `fromValue`.
- `db/models/DependencyStatus.kt` — `DEPENDENT`/`INDEPENDENT` with `fromValue`.
- `db/dao/PolicyParametersDao.kt` — stateless object, `listAll(session)` /
  `listForAwardYear` via `queryList`, row mapper parsing the enum through
  `CorruptPersistedValueException` (SystemPromptsDao/MoneyProfilesDao
  precedent). Read-only: no insert path outside migrations.

### Service and tool (`service/.../coaching/aid/`)

- `FederalAidPolicyService(database)` — one `withConnection` reading
  `policy_parameters` and the student's money profile (`MoneyProfilesDao`),
  `readInSession` extracted for statement-count tests. Groups rows into three
  topics — `pell` (awards + AGI tests + SAI floor), `undergrad_loans`,
  `grad_loans` — each topic resolved to its latest award_year at or before the
  clock-derived current award year (a future-published year is never served as
  current), with `current`/`prior_year` status. Returns a plain data class; no
  arithmetic.
- `FederalAidPolicyChatTool` — `StudentScopedChatTool` subclass, thin adapter.
  Payload (house conventions: absent-never-empty, code+statement pairs,
  tri-state echo):

```json
{
  "current_award_year": "2026-27",
  "pell": {
    "award_year": "2026-27",
    "award_year_status": "current",
    "max_award_usd": 7395,
    "min_award_usd": 740,
    "sai_floor": -1500,
    "max_pell_agi_tests": {
      "dependent_single_parent_pct_of_poverty_guideline": 225,
      "dependent_non_single_parent_pct_of_poverty_guideline": 175,
      "statement": "A dependent student qualifies for the maximum Pell Grant if the parents were not required to file a federal tax return, or if the parents' income is at or below 225% (single parent) or 175% (other parents) of the federal poverty guideline for the family's size and state."
    },
    "framing_statement": "Pell eligibility is set by the FAFSA's Student Aid Index and income tests; the coach can describe the range, never promise an award.",
    "sources": [ { "cited_as": "Federal Student Aid's 2026-27 Pell Grant maximum and minimum award letter (GEN-26-01)", "url": "..." }, { "cited_as": "Federal Student Aid Handbook, 2026-2027, Application and Verification Guide Chapter 3", "url": "..." } ]
  },
  "undergrad_loans": {
    "award_year": "2025-26",
    "award_year_status": "prior_year",
    "award_year_statement": "These are the 2025-26 award year limits; Federal Student Aid has not yet published the 2026-27 Direct Loan volume.",
    "dependent": { "annual": [ {"year_level": "first_year", "total_usd": 5500, "subsidized_max_usd": 3500}, ... ], "aggregate_total_usd": 31000, "aggregate_subsidized_max_usd": 23000 },
    "independent": { ... },
    "loan_framing_statement": "A loan is money the family repays with interest; loan limits are never subtracted from any price.",
    "sources": [ { "cited_as": "Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4", "url": "..." } ]
  },
  "grad_loans": { ... },
  "money_profile": {
    "dependency_status": "unanswered" | "answered" | "declined",
    "dependency": "dependent", "dependency_label": "Dependent for federal aid" (only when answered)
  },
  "source_note": "Figures are federal policy for the named award year; they are the same at every college."
}
```

DESCRIPTION built from wire-key consts (CollegeCostChatTool precedent), telling
the model: cite the award year; Pell is a range/eligibility framing; never
subtract loans from a price; if `dependency_status` is `unanswered`, it MAY
invite the dependency answer by naming its value ("most students applying from
high school are dependent for federal aid; knowing which applies picks the right
loan limits"), and MUST still answer fully without it by presenting both tables.

- Registered in `Application.kt`'s `ToolRegistry` list.

### money_profiles addition (migration 0078)

0070 shape verbatim: `ALTER TABLE money_profiles ADD COLUMN dependency TEXT` +
`dependency_status TEXT NOT NULL DEFAULT 'unanswered'` + three CHECKs
(vocabulary, status vocabulary, value-IFF-answered); same pair on
`money_profiles_versions` (status column with DEFAULT);
`CREATE OR REPLACE
FUNCTION log_money_profile_version()` re-listing all columns.
Kotlin: `MoneyProfile`/`MoneyProfileEdit`/`NewMoneyProfile` fields,
`mapProfile`, `MoneyProfileService.FieldUpdate` arm, `MoneyProfileChatTool`
input + DESCRIPTION line.

### Prompt v17 (migration 0079) and pin

v16 verbatim plus one appended paragraph: the coach has a federal-aid-policy
tool for Pell and loan questions; answers cite the award year and source; Pell
is framed as eligibility/range, never a promised amount; loan limits are stated
and never subtracted from a price; if parameters are from a prior award year the
coach says so; the dependency question is invited with its value named and
declinable. Pin bumped to v17 in `service.conf`. Rollback:
`COACHING_SYSTEM_PROMPT_VERSION=v16`.

## Files Modified

New:

- `db/schema/0077.create-and-seed-policy-parameters.sql`
- `db/schema/0078.add-money-profile-dependency-status.sql`
- `db/schema/0079.seed-coach-system-prompt-v17.sql`
- `db/src/main/kotlin/ed/unicoach/db/models/PolicyParameter.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/DependencyStatus.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/PolicyParametersDao.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/aid/FederalAidPolicyService.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/aid/FederalAidPolicyChatTool.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/PolicyParametersDaoTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/aid/FederalAidPolicyServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/aid/FederalAidPolicyChatToolTest.kt`

Modified:

- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — register tool
- `service/src/main/resources/service.conf` — prompt pin v17
- `db/src/main/kotlin/ed/unicoach/db/models/MoneyProfile*.kt` (models),
  `db/src/main/kotlin/ed/unicoach/db/dao/MoneyProfilesDao.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/moneyprofile/MoneyProfileService.kt`,
  `.../MoneyProfileChatTool.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/costs/ForbiddenCostArithmeticTest.kt`
  — scan `coaching/aid/` too
- `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt` —
  v17 prefix+append test
- money-profile test files touched by the new field

(The implementer finalizes the vocabulary list from `PolicyParameter.kt` as the
single source, mirrored into the CHECK.)

## Implementation Plan

1. Migration 0077 (table + trigger + 30 seed rows with citations) +
   `PolicyParameter`/`DependencyStatus` enums + `PolicyParametersDao` + DAO
   test.
2. Migration 0078 (money_profiles dependency_status, full 0070 shape) + model/
   DAO/service/tool threading + tests.
3. `FederalAidPolicyService` + `FederalAidPolicyChatTool` + registration +
   service/tool tests; extend `ForbiddenCostArithmeticTest`.
4. Migration 0079 (prompt v17) + pin bump + `SystemPromptCatalogTest` update.

## Tests

- **PolicyParametersDaoTest**: seed rows present for both award years; every row
  has non-empty source_name/source_url; enum round-trips; unknown parameter in
  DB raises `CorruptPersistedValueException`.
- **FederalAidPolicyServiceTest**: latest-year resolution (pell → 2026, loans →
  2025 from the real seed); `current` vs `prior_year` status derivation across
  the July 1 boundary (clock injected); grouping completeness; one
  statement-count/batching test over `readInSession`.
- **FederalAidPolicyChatToolTest**: first-session shape (no profile → tri-state
  `unanswered`, full Pell + both loan tables, prior-year statement present on
  loans, citations with URLs); answered/declined dependency echo; no empty
  lists; every group carries `award_year`; framing statements verbatim.
- **ForbiddenCostArithmeticTest**: extended directory scanned, guard asserts
  `FederalAidPolicyService.kt` is read; existing positive control still fires.
- **SystemPromptCatalogTest**: v17 = v16 verbatim + one paragraph; pinned
  version exists; served-body guards still pass.
- **Money-profile tests**: tri-state CHECK enforcement for the new field,
  history row logging, MoneyProfileChatTool update path.

## Open items

- Poverty-guideline rows (family size input becomes valuable) — later slice.
- 2026-27 Direct Loan limits: seed via a new migration when FSA publishes Vol 8
  (or GEN-26-xx equivalent); the prior-year statement retires itself.
- Interest rates / origination fees are out of scope (not in the slice's
  parameter list).
