# RFC 158 — The canonical money store

Slice: `shape/01/canonical-store` (brief 0006, gate 1 D1–D9, gate 2 D12–D16).
Substrate slice: **no consumer changes**. The door for this work opens at
`shape/04/cost-answers-from-canonical` and is named there.

## Motivation

Brief 0006's bet (D1): give unicoach a money store shaped like what money IS — a
price has a residency, an arrangement and a year; a statistic has a population;
an absence has a reason. Today `colleges` holds publisher blends and pairs as
sibling columns, `toIntOrNull` collapses `PrivacySuppressed` into the same NULL
as "not reported" (`CsvIngestSupport.kt:272-275`,
`CollegeScorecardLoader.kt:1235-1240`), and the four read-time repair RFCs
(149/151/152/157) rebuild the truth on every read. This RFC lands the tables,
their authored vocabulary, and a `canonical-money` ingest phase that fills them
from the pinned Scorecard snapshot. Nothing reads them yet.

## Decisions (physical layout — D13 leaves these to this RFC)

The logical model — keys, vocabularies, statuses, CHECK semantics — is
gate-1-fixed and restated in the DDL below. What follows is decided _here_,
argued against the audit's risk register (`repo-money-audit.md` §6).

**P1. One narrow figure-per-row `price_figures` table**, not wide per-(college ×
year) rows and not one-table-per-concept-family.

- Wide rows re-encode the vocabulary as column names — the exact publisher shape
  this brief exists to leave — and every new concept or source becomes a
  migration plus a `log_college_version()`-class column-list restatement (risk
  §6.1's whole family). Narrow rows make `shape/02` (IC_AY: in-district tier,
  `fees_only`, with-family housing) a **pure data change**: new rows, zero DDL.
- Per-concept-family tables would triple the DDL, the CHECKs, the DAO and the
  provenance plumbing for identical semantics; the CHECKs do not differ by
  family.
- Volume is a non-issue: ~6k colleges × ≤10 populated cells ≈ 60–100k rows for
  the Scorecard fill; low millions after IC_AY's four years. Standard b-tree
  territory.

**P2. Vocabulary tables are real tables with FKs from the fact tables**, seeded
from one authored file `db/data/money-vocabulary.json` (the `subjects.json`
pattern: committed, diffable, exact-key-set parse, fatal validation, one
transaction, upsert + delete-not-in). D6 fixes this direction (unicoach authors,
external maps in); `income_bands` in particular must be a table because D7 moves
the dollar ranges into data. The cost is risk §6.5 — FKs make the vocabulary
phase a write precondition and every canonical-table test fixture must seed the
vocabulary — accepted with eyes open, as 0067 accepted it for
`colleges.state`/`locale`; fixture builders are part of this RFC's test work.

**P3. Arrangement-invariance is an explicit `not_applicable` row, plus an
authored per-concept flag.** `arrangements` carries the three living
arrangements (`LivingArrangement`'s values) **plus `not_applicable`**, mirroring
`residency_bases` — inapplicability is an explicit choice, never a NULL default
(D4's philosophy applied to the arrangement axis). `price_concepts` carries an
authored `arrangement_varies` boolean (the RFC 149 rule as data:
tuition-and-fees and books do not vary by where you live; housing-and-food and
other-expenses do). A CHECK cannot span tables, so the pairing rule
(`arrangement = 'not_applicable'` IFF the concept is arrangement-invariant) is a
**loader fatal with a test** — the acceptance criteria's sanctioned second
mechanism.

**P4. `cohort_money_stats` keys on a structured population basis and uses
`UNIQUE NULLS NOT DISTINCT`.** The population basis is three columns —
`population`, `residency_scope`, `aid_scope` — each TEXT + CHECK (house enum
pattern; these are measure attributes, not authored taxonomy, so no vocabulary
table). `income_band` is a nullable FK (NULL = the overall figure; a sentinel
'overall' row would pollute D7's five owned bands), so the natural key is a
`UNIQUE NULLS NOT DISTINCT` index (Postgres 18) rather than a PK.

**P5. Vintages are stored honestly, `undated` included.** Both fact tables key
on a `TEXT` year: `price_figures.academic_year` is always a real `'YYYY-YY'`
(`'2022-23'` for the Scorecard published-price family —
`ScorecardVintage.PUBLISHED_PRICE`). `cohort_money_stats.vintage` is `'YYYY-YY'`
where the source dates the cohort (`'2021-22'` for COSTT4_A/NPT4* —
`BLENDED_AVERAGE`) and the literal `'undated'` where the source pools or does
not date it (median debt, median earnings, pell share — exactly the figures
`CostBreakdown` refuses to date today, CostBreakdown.kt:199-204). Fabricating a
year to satisfy a key would be the lie D15 exists to avoid; `'undated'` keeps
the key total and the honesty intact.

**P6. Blends are unrepresentable in `price_figures` by FK.** No blend concept
exists in `price_concepts`; COSTT4_A and the NPT4 family land in
`cohort_money_stats` as measures with their true population basis. The FK onto
`price_concepts` _is_ the "no blend may enter the price table" constraint — a
blend cannot be named there. A loader test additionally pins that no
`source_variable` in `price_figures` is ever `COSTT4_A`/`NPT4*`.

**P7. The fill writes rows only for cells in the active source mapping's
domain.** Within that domain every college gets a row with a value or a status
(`PrivacySuppressed` → `suppressed_by_publisher`; blank/`NULL` sentinel →
`not_reported_by_institution`). Cells no ingested source carries (with-family
housing-and-food before IC_AY, `fees_only`, `in_district`) get **no row** —
materializing every combination would fabricate millions of
`not_collected_by_us` rows that say nothing. The `not_collected_by_us` status
exists in the vocabulary (D3 fixes six values) and is reserved for a source we
deliberately skip a cell of; the v1 Scorecard fill emits it never. Absence of a
row reads as "we ingest no source for this cell".

**P8. Upstream-wins (D5) is an ordered source list in the loader.** The fill
iterates sources in priority order (v1: `scorecard` only) and first write wins
per key; `source` + `source_variable` + `academic_year` on the row record the
winner. When shape/02/03 add IPEDS sources ahead of Scorecard in the list, the
rule is already load-bearing.

**P9. New Kotlin code lives in the ingest module (`college/`), not
`service/.../costs/`.** `ForbiddenCostArithmeticTest` scans only
`service/src/main/kotlin/ed/unicoach/coaching/costs` (risk §6.3 says the
placement is a decision either way): the canonical fill maps and copies, it
never does money arithmetic, and placing loader code in the service cost domain
would drag ingest into the coach's dependency surface. Enums shared with the DB
live in `db/.../models` beside `IncomeBand`/`LivingArrangement`.

**P10. `IncomeBand.kt` keeps its bracket text until a consumer cuts over.** D7
moves the dollar ranges into data — the seed carries
`min_usd`/`max_usd`/`bracket_label` — but `IncomeBand.bracket` feeds live wire
labels today and this slice may not touch consumers. A test pins the DB rows and
the Kotlin enum to byte-equality in both directions; the Kotlin text is deleted
at shape/04, not here.

**P11. Provenance gets two dedicated columns plus one JSONB summary.**
`college_index_build` gains `price_figure_rows` and `cohort_money_stat_rows`
(the 0064 `search_index_rows` precedent: one column, one meaning) and
`canonical_money_summary JSONB` carrying the per-status breakdown per table —
the operator-visible fact the slice's first-session test names. Keys inside the
JSONB are our vocabulary slugs, not column names, so risk §6.6's frozen-key seam
does not widen. `METHOD_VERSION` bumps 5 → 6.

**P12. The phase is a wholesale rebuild in one transaction.** `canonical-money`
runs in derived phase 2 after `search-index` and before `provenance`
(`CollegeScorecardLoader.kt:675-680`'s "rows first, derived state second"):
DELETE + re-fill of `price_figures` and `cohort_money_stats` from a re-parse of
the pinned institution CSV with status-preserving readers. Idempotent by
construction; `PartialIngestException` semantics unchanged. `aid_policy_facts`
is created empty and the phase does not touch it (D9; shape/07 fills it).

## Detailed Design

### Migration `0083.create-canonical-money-tables.sql`

(house header comment citing this RFC; `0077` and `0080` were claimed by RFC
159's and RFC 160's migrations while this run was open, so the number was
recomputed at land time — the claim-numbers-live rule earning its keep twice in
one run)

Slug columns are `TEXT` with an underscore-slug format CHECK
(`^[a-z0-9]+(_[a-z0-9]+)*$`), **not** the 0060 `slug` DOMAIN: that DOMAIN allows
hyphens only, and the gate-fixed vocabulary values (`not_applicable`,
`under_30k`, ...) must byte-agree with `IncomeBand`/`LivingArrangement`/
`money_profiles`' underscore values (P10). Each vocabulary table carries its own
named format CHECK.

```sql
-- Vocabulary tables (D6): unicoach-authored, seeded from
-- db/data/money-vocabulary.json by the money-vocabulary ingest phase.
-- Reference data: unversioned, no history trigger, house updated_at trigger,
-- reloaded by the ingest (the 0060/0064 reference-table shape).

CREATE TABLE residency_bases (
    slug        TEXT        NOT NULL PRIMARY KEY,
    description TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- rows: in_district, in_state, out_of_state, not_applicable

CREATE TABLE arrangements (
    slug                  TEXT        NOT NULL PRIMARY KEY,
    description           TEXT        NOT NULL,
    is_living_arrangement BOOLEAN     NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- rows: on_campus, off_campus, with_family (is_living_arrangement TRUE;
--       matches LivingArrangement.kt, pinned by test), and not_applicable
--       (FALSE): the explicit key for arrangement-invariant concepts (P3).

CREATE TABLE figure_statuses (
    slug          TEXT        NOT NULL PRIMARY KEY,
    description   TEXT        NOT NULL,
    value_bearing BOOLEAN     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- rows (D3): reported (TRUE), imputed_by_publisher (TRUE),
--   not_reported_by_institution, not_applicable, suppressed_by_publisher,
--   not_collected_by_us (all FALSE)

CREATE TABLE price_concepts (
    slug               TEXT        NOT NULL PRIMARY KEY,
    description        TEXT        NOT NULL,
    arrangement_varies BOOLEAN     NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- rows: tuition_and_fees (FALSE), fees_only (FALSE),
--   housing_and_food (TRUE), books_and_supplies (FALSE),
--   other_expenses (TRUE), published_price (TRUE).
-- No blend concept exists here: the FK below IS the no-blends rule (P6).
-- published_price is reserved for a source-published all-in price; a SUM of
-- components is derived and is never stored (CLAUDE.md schema conventions).

CREATE TABLE income_bands (
    slug          TEXT        NOT NULL PRIMARY KEY,
    min_usd       INTEGER     NOT NULL,
    max_usd       INTEGER     NULL,             -- NULL = open-ended top band
    bracket_label TEXT        NOT NULL,          -- e.g. "$30,001 to $48,000"
    sort_order    SMALLINT    NOT NULL UNIQUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT income_bands_min_nonneg_check CHECK (min_usd >= 0),
    CONSTRAINT income_bands_range_check
        CHECK (max_usd IS NULL OR max_usd > min_usd)
);
-- rows (D7, the five cut-points as data): under_30k, 30k_to_48k, 48k_to_75k,
--   75k_to_110k, over_110k — slugs identical to money_profiles.income_band's
--   CHECK list and IncomeBand.kt values, pinned by test (P10).

-- The price facts table (D2): one published figure per row.
CREATE TABLE price_figures (
    id              UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id      UUID        NOT NULL REFERENCES colleges(id),
    price_concept   TEXT        NOT NULL REFERENCES price_concepts(slug),
    residency_basis TEXT        NOT NULL REFERENCES residency_bases(slug),
    arrangement     TEXT        NOT NULL REFERENCES arrangements(slug),
    academic_year   TEXT        NOT NULL,
    amount_usd      INTEGER     NULL,
    status          TEXT        NOT NULL REFERENCES figure_statuses(slug),
    source          TEXT        NOT NULL,
    source_variable TEXT        NOT NULL,   -- e.g. 'TUITIONFEE_IN'
    publisher_flag  TEXT        NULL,       -- raw IPEDS X-code, when one exists
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT price_figures_natural_key
        UNIQUE (college_id, price_concept, residency_basis, arrangement,
                academic_year),
    CONSTRAINT price_figures_academic_year_format_check
        CHECK (academic_year ~ '^[0-9]{4}-[0-9]{2}$'),
    -- D3: a value exists exactly when the status bears one. The two
    -- value-bearing statuses are named literally (a CHECK cannot subquery
    -- figure_statuses.value_bearing); a test pins the two lists together.
    CONSTRAINT price_figures_value_iff_status_check
        CHECK ((amount_usd IS NOT NULL) =
               (status IN ('reported', 'imputed_by_publisher'))),
    -- Published gross prices are nonnegative (0062's nonneg family). Net
    -- prices can go negative but net prices are cohort statistics and cannot
    -- enter this table (P6).
    CONSTRAINT price_figures_amount_nonneg_check
        CHECK (amount_usd IS NULL OR amount_usd >= 0),
    CONSTRAINT price_figures_source_nonempty_check CHECK (source <> ''),
    CONSTRAINT price_figures_source_variable_nonempty_check
        CHECK (source_variable <> '')
);

CREATE INDEX price_figures_college_idx ON price_figures (college_id);

-- The cohort statistics table (D2): a number about a population, never a
-- price anyone is quoted.
CREATE TABLE cohort_money_stats (
    id              UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id      UUID        NOT NULL REFERENCES colleges(id),
    measure         TEXT        NOT NULL,
    population      TEXT        NOT NULL,
    residency_scope TEXT        NOT NULL,
    aid_scope       TEXT        NOT NULL,
    income_band     TEXT        NULL REFERENCES income_bands(slug),
    vintage         TEXT        NOT NULL,
    value           NUMERIC     NULL,
    status          TEXT        NOT NULL REFERENCES figure_statuses(slug),
    source          TEXT        NOT NULL,
    source_variable TEXT        NOT NULL,
    publisher_flag  TEXT        NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT cohort_money_stats_measure_check
        CHECK (measure IN ('avg_net_price', 'published_cost_blend',
                           'pell_share', 'median_debt_at_completion',
                           'median_earnings_10y')),
    CONSTRAINT cohort_money_stats_population_check
        CHECK (population IN ('title_iv_aided_undergraduates',
                              'undergraduates',
                              'federal_loan_borrowing_completers',
                              'employed_not_enrolled_10y_after_entry')),
    CONSTRAINT cohort_money_stats_residency_scope_check
        CHECK (residency_scope IN ('in_state_rate_paying', 'all')),
    CONSTRAINT cohort_money_stats_aid_scope_check
        CHECK (aid_scope IN ('federal_aid_receiving',
                             'federal_loan_borrowing', 'all')),
    CONSTRAINT cohort_money_stats_vintage_format_check
        CHECK (vintage ~ '^[0-9]{4}-[0-9]{2}$' OR vintage = 'undated'),
    CONSTRAINT cohort_money_stats_value_iff_status_check
        CHECK ((value IS NOT NULL) =
               (status IN ('reported', 'imputed_by_publisher'))),
    CONSTRAINT cohort_money_stats_source_nonempty_check CHECK (source <> ''),
    CONSTRAINT cohort_money_stats_source_variable_nonempty_check
        CHECK (source_variable <> '')
);
-- Natural key: income_band NULL means "the overall figure", and two overall
-- rows for the same cell must collide — hence NULLS NOT DISTINCT.
CREATE UNIQUE INDEX cohort_money_stats_natural_key
    ON cohort_money_stats (college_id, measure, population, residency_scope,
                           aid_scope, income_band, vintage)
    NULLS NOT DISTINCT;

CREATE INDEX cohort_money_stats_college_idx ON cohort_money_stats (college_id);

-- Modeled ahead (D9): empty until shape/07 fills it from the CDS corpus.
CREATE TABLE aid_policy_facts (
    id            UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id    UUID        NOT NULL REFERENCES colleges(id),
    policy        TEXT        NOT NULL,
    academic_year TEXT        NOT NULL,
    value_boolean BOOLEAN     NULL,
    value_number  NUMERIC     NULL,
    status        TEXT        NOT NULL REFERENCES figure_statuses(slug),
    source        TEXT        NOT NULL,
    source_url    TEXT        NULL,
    archive_url   TEXT        NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT aid_policy_facts_natural_key
        UNIQUE (college_id, policy, academic_year),
    CONSTRAINT aid_policy_facts_policy_check
        CHECK (policy IN ('css_profile_required', 'noncustodial_css_required',
                          'fafsa_required', 'meets_full_need',
                          'need_fully_met_share', 'avg_need_based_grant_usd')),
    CONSTRAINT aid_policy_facts_academic_year_format_check
        CHECK (academic_year ~ '^[0-9]{4}-[0-9]{2}$'),
    CONSTRAINT aid_policy_facts_one_value_shape_check
        CHECK (NOT (value_boolean IS NOT NULL AND value_number IS NOT NULL)),
    CONSTRAINT aid_policy_facts_value_iff_status_check
        CHECK (((value_boolean IS NOT NULL) OR (value_number IS NOT NULL)) =
               (status IN ('reported', 'imputed_by_publisher')))
);

-- Provenance (P11): the 0064 one-column-one-meaning precedent.
ALTER TABLE college_index_build
    ADD COLUMN price_figure_rows INTEGER NULL,
    ADD COLUMN cohort_money_stat_rows INTEGER NULL,
    ADD COLUMN canonical_money_summary JSONB NULL,
    ADD CONSTRAINT college_index_build_price_figure_rows_nonneg_check
        CHECK (price_figure_rows IS NULL OR price_figure_rows >= 0),
    ADD CONSTRAINT college_index_build_cohort_money_stat_rows_nonneg_chk
        CHECK (cohort_money_stat_rows IS NULL OR cohort_money_stat_rows >= 0);
```

Every table takes the house `trigger_03_enforce_<table>_updated_at` trigger.
Constraint names are all under Postgres's 63-char identifier limit (risk §6.9 —
checked by eye and by test). `COMMENT ON` statements follow the 0060/0062 house
style, naming the vocabulary owner and citing this RFC; the year is never
restated in a comment (0062:121-127 precedent).

### Authored seed: `db/data/money-vocabulary.json`

One JSON object with five arrays (`residency_bases`, `arrangements`,
`figure_statuses`, `price_concepts`, `income_bands`), entries carrying exactly
the columns above (exact-key-set parse; unknown or missing key = fatal, the
`SubjectLoader.parseEntry` discipline). The rows are fixed by gate 1 and listed
in the DDL comments above.

### Kotlin

- `db/.../models`: `ResidencyBasis`, `FigureArrangement`, `FigureStatus` (with
  `valueBearing`), `PriceConcept` (with `arrangementVaries`), `MoneyMeasure`,
  `PopulationBasis`/`ResidencyScope`/`AidScope` enums — each the house
  `enum class Foo(val value: String)` + `fromValue` shape. Row types
  `NewPriceFigure`, `NewCohortMoneyStat`, vocabulary row types.
- `db/.../dao/CanonicalMoneyDao.kt`: vocabulary upserts (three-way
  `UpsertOutcome`, delete-not-in — the `CodebooksDao`/`SubjectLoader` shape),
  wholesale `deleteAll` + batch insert for the two fact tables, count-by-status
  queries for provenance.
- `college/.../MoneyVocabularyLoader.kt`: parse (fatal on shape errors,
  duplicate slugs, empty sections) + load in one transaction; validates the
  parsed vocabulary against the Kotlin enums both ways (a seed row with no enum
  value, or an enum value with no seed row, is fatal — the mapping has one home,
  disagreement is a build error, not drift).
- `college/.../CanonicalMoneyLoader.kt`: the fill. Re-parses the institution CSV
  with **status-preserving readers** (new `CsvIngestSupport` helpers that return
  value-or-status instead of `Int?`): `'PrivacySuppressed'` →
  `suppressed_by_publisher`; blank/`'NULL'` → `not_reported_by_institution`;
  parseable value → `reported` (domain-coerced values keep mechanism A's tally
  and land as `not_reported_by_institution` with the coercion counted). Mapping
  (P7's domain):
  - `TUITIONFEE_IN`/`TUITIONFEE_OUT` → `price_figures` × (`tuition_and_fees`,
    `in_state`/`out_of_state`, `not_applicable` arrangement, `2022-23`) — the
    pair lands as two rows.
  - `ROOMBOARD_ON/OFF` → `housing_and_food` × `on_campus`/`off_campus`,
    residency `not_applicable`.
  - `BOOKSUPPLY` → `books_and_supplies`, arrangement + residency
    `not_applicable`.
  - `OTHEREXPENSE_ON/OFF/FAM` → `other_expenses` ×
    `on_campus`/`off_campus`/`with_family`, residency `not_applicable`.
  - `COSTT4_A` → `cohort_money_stats` `published_cost_blend`, population
    `title_iv_aided_undergraduates`, residency_scope `in_state_rate_paying`
    (public) / `all` (private), aid_scope `all`, vintage `2021-22`.
  - `NPT4_PUB/PRIV` + `NPT41-45_*` (control-keyed, as today) → `avg_net_price`,
    population `title_iv_aided_undergraduates`, residency_scope
    `in_state_rate_paying` (public) / `all` (private), aid_scope
    `federal_aid_receiving`, income_band NULL / the five bands, vintage
    `2021-22`.
  - `PCTPELL` → `pell_share`, population `undergraduates`, scopes `all`, vintage
    `undated`.
  - `GRAD_DEBT_MDN` → `median_debt_at_completion`, population
    `federal_loan_borrowing_completers`, aid_scope `federal_loan_borrowing`,
    residency_scope `all`, vintage `undated`.
  - `MD_EARN_WNE_P10` → `median_earnings_10y`, population
    `employed_not_enrolled_10y_after_entry`, scopes `all`, vintage `undated`.
    Upstream-wins (P8): sources iterated in priority order, first write wins per
    natural key; v1 list is `[scorecard]`. Loader fatals: an
    arrangement-invariant concept paired with a living arrangement (or vice
    versa, P3); a blend variable routed at `price_figures`; a banded row for a
    measure that does not band.
- `CollegeScorecardLoader.ingest`: two new phases — `money-vocabulary` after
  `subjects` (line ~645), `canonical-money` after `search-index` (line ~680) —
  each its own `phase(...)` transaction; provenance call gains the three new
  fields; `METHOD_VERSION` 5 → 6 with a docstring line.
- `IngestApplication` + `bin/ingest-colleges`: new optional `-v` (getopts, short
  option, non-empty-value guard) defaulting to
  `$PROJECT_ROOT/db/data/money-vocabulary.json`, threaded as
  `--money-vocabulary=`/`--money-vocabulary-source=` like `--subjects`. The
  canonical row counts and per-status breakdown reach the operator on stderr
  through the loader/entry-point INFO log lines (bin/ conventions: stdout stays
  empty on a normal run); the provenance row carries the same facts.

## Files Modified

New:

- `db/schema/0083.create-canonical-money-tables.sql` (number recomputed at
  commit)
- `db/data/money-vocabulary.json`
- `db/src/main/kotlin/ed/unicoach/db/models/{ResidencyBasis,FigureArrangement,FigureStatus,PriceConcept,MoneyMeasure,CohortScopes}.kt`
  (+ row types, exact file split at implementer's discretion)
- `db/src/main/kotlin/ed/unicoach/db/dao/CanonicalMoneyDao.kt` (delete-not-in is
  one allowlisted function over the five vocabulary tables)
- `college/src/main/kotlin/ed/unicoach/college/MoneyVocabularyLoader.kt`
- `college/src/main/kotlin/ed/unicoach/college/CanonicalMoneyLoader.kt`
- tests listed below

Modified:

- `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt` (two
  phases, provenance fields, METHOD_VERSION 6)
- `college/src/main/kotlin/ed/unicoach/college/CsvIngestSupport.kt`
  (status-preserving readers beside `intOrNull`, no behavior change to existing
  readers)
- `college/src/main/kotlin/ed/unicoach/college/IngestApplication.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt`
  (`NewCollegeIndexBuild` + `insertIndexBuild` gain the three fields)
- `bin/ingest-colleges` (`-v`, summary lines)

Untouched on purpose: every consumer (`CollegeCostService`, chat tools, cost
report, search index, admin-web), `colleges`' columns,
`ForbiddenCostArithmeticTest`'s scanned package, `IncomeBand.kt` (P10).

## Implementation Plan

1. Migration + seed file; `bin/db-reset` green.
2. db models + `CanonicalMoneyDao` + DAO tests (constraint tests included).
3. `MoneyVocabularyLoader` + tests (SubjectLoader test shape).
4. `CsvIngestSupport` status-preserving readers + tests.
5. `CanonicalMoneyLoader` + loader tests over fixture CSVs (PrivacySuppressed
   fixtures included).
6. Phase wiring, provenance, `bin/ingest-colleges` `-v` + summary; end-to-end
   ingest test + idempotency test.
7. `nix develop -c bin/test`; shell-harness check for the `bin/` edit
   (`nix develop -c bin/shell-tests` while iterating).

## Tests

Acceptance criteria, each mechanized:

- **DB constraint tests** (`CanonicalMoneyDaoTest`): NOT NULL residency refused;
  value-without-value-bearing-status and status-without-value refused (both
  tables); `NULLS NOT DISTINCT` collides two overall rows; FK to vocabulary
  refused for an unknown slug; `academic_year`/`vintage` format CHECKs;
  `aid_policy_facts` one-value-shape CHECK.
- **Vocabulary agreement tests**: `figure_statuses.value_bearing` rows agree
  with the literal status list inside both `*_value_iff_status_check` CHECKs;
  seed rows ↔ Kotlin enums byte-agree both ways; `income_bands` slugs + labels
  agree with `IncomeBand.kt` and `money_profiles`' CHECK list (P10);
  `arrangements`' living rows agree with `LivingArrangement`.
- **Loader unit tests**: PrivacySuppressed → `suppressed_by_publisher` row;
  blank/NULL → `not_reported_by_institution`; tuition pair → exactly two rows;
  blend variables never reach `price_figures` (assert zero rows with
  `source_variable` in the blend set — P6's test half); arrangement-invariance
  fatal (P3); mixed-vintage: assert the fill never writes a `published_price`
  row and every row carries exactly one year in its key — the stored-shape
  successor of `CostBreakdown`'s Kotlin rule, which stays in force for the read
  side.
- **End-to-end ingest test** (existing loader-test shape, fixture CSV with a
  PrivacySuppressed cell): after one ingest — tuition pairs as two rows each;
  NPT4/COSTT4_A/debt/earnings/pell in `cohort_money_stats` with their true
  population basis; `suppressed_by_publisher` count > 0 asserted; provenance row
  carries `price_figure_rows`, `cohort_money_stat_rows` and the per-status
  `canonical_money_summary`.
- **Idempotency test**: second ingest of the same snapshot — identical row
  counts, `UNCHANGED` outcomes, byte-identical provenance summary.
- **`bin/` conventions**: `-v` getopts behavior (unknown option / missing value
  exit codes); summary on stderr, stdout empty on a normal run (`scripts-tests`
  shape).
