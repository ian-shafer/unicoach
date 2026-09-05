# RFC 161 — IPEDS IC_AY: published charges as a canonical source

Slice: `shape/02/ipeds-ic-ay` (brief 0006, gate 2 approved 2026-09-02).\
Needs: BLOCKS `shape/01/canonical-store` — LANDED as RFC 158. CONFLICTS
`shape/03/ipeds-sfa` — no live run at claim time; rebase risk only.

## Summary

Pin IPEDS `IC2023_AY.csv` — the charges file — as a second canonical money
source, load it into a new staging table, and map it into `price_figures` ahead
of the College Scorecard under RFC 158's existing upstream-wins rule.

The payoff is correctness, not plumbing. Measured over the whole 2023 file
against the Scorecard snapshot this repo serves from:
`CHG3AY3`/`TUITIONFEE_OUT`, `CHG5AY3`/`ROOMBOARD_ON`, `CHG7AY3`/`ROOMBOARD_OFF`,
`CHG4AY3`/`BOOKSUPPLY` and `CHG9AY3`/`OTHEREXPENSE_FAM` agree **100.0%**, but
`CHG2AY3`/`TUITIONFEE_IN` agrees only **92.1%** — and **all 269 mismatches equal
`CHG1AY3`, the in-district figure, exactly**. The Scorecard collapses
in-district into "in", so unicoach today tells a family that Austin Community
College's in-state price is
**$2,550** when the real in-state price is **$8,580** and $2,550 is the
in-district price. IC_AY carries the three residency tiers as separate
first-class variables, so the fix is to read the tiers from the source that has
them.

Substrate by design, exactly as RFC 158 was: no consumer reads the new rows. The
door is `shape/04/cost-answers-from-canonical`.

## Two corrections to the slice text (findings, not decisions taken here)

1. **There is no with-family food-and-housing figure in IC_AY.** The slice and
   brief 0006's DISCOVER preamble both say `CHG7/8AY` is
   "off-campus-with-family". The pinned codebook says otherwise: `CHG7AY` is
   _off campus (not with family) food and housing_ and `CHG8AY` is _off campus
   (not with family) other expenses_. The with-family arrangement has exactly
   one variable, `CHG9AY` (other expenses) — IPEDS assumes zero food and housing
   for a student living at home. Unicoach already ingests the same fact from the
   Scorecard's `OTHEREXPENSE_FAM`, which agrees with `CHG9AY3` on 100.0% of
   rows. **No source anywhere publishes with-family food and housing.** This RFC
   therefore does not add that figure and does not fabricate a zero for it: the
   absence stays an absence, and `shape/04` says it as our gap. The slice's
   third user-visible payoff is withdrawn; the first two (a real in-district
   tier, fees split from tuition) stand and are the reason to run the slice.
2. **IC_AY covers 3,825 institutions, not ~6,100.** Institutions that report on
   a program-year calendar are in `IC_PY`, a different file. So IC_AY refines
   roughly 63% of the universe and the Scorecard remains the only source for the
   rest — which upstream-wins already handles per key, but which the ingest
   summary must state so nobody reads a smaller row count as data loss.

## Detailed Design

### 1. The pinned artifact

`bin/fetch-ipeds` gains a fifth artifact in its `ARTIFACTS` table:
`IC2023_AY.zip` (309,736 bytes, sha256
`42d3ee39a107d69b6da02df2ffa934ebe3bb76657568fb9efb7027b6ce0d69ee`), single
member `ic2023_ay.csv` (3,426,048 bytes), written to `db/seed/ipeds/`. It joins
the existing **all-or-nothing** fetch group and inherits every guard unchanged:
the `_rv` refusal (`IC2023_AY_RV.zip` is a 404 today and must stay a fatal, not
a fallback), the shrink and member-drift guards, `SEED_SHRINK_FLOOR`, the
`require_survey_year` year-bump fatal, and one atomic `write_files_atomically`
over all CSVs plus `PROVENANCE.json`. `-F` fixture replay writes `null` digests
as it does now. The Stata codebook `IC2023_AY_Stata.zip` is pinned through the
existing `bin/fetch-codebooks` mirror (RFC 147), as evidence for the flag
mapping below.

Two file facts the parser must absorb rather than "clean up": the final header
is `CHG9AY3` **with a trailing space**, and missing values are a literal `.`.

### 2. Staging: `college_ipeds_charges` (migration 0084)

Staging stays source-shaped (the `college_ipeds` precedent, RFC 84 / RFC 144),
but IC_AY's shape is a 235-column wide table whose columns are a _year × tier ×
component_ cross-product. Mirroring that as 114 value columns plus 114 flag
columns would encode the cross-product in identifiers and force a migration for
every future year. So the staging table is **narrow**: one row per UNITID ×
charge variable stem × academic year, carrying the value and its imputation flag
verbatim as published.

```sql
CREATE TABLE college_ipeds_charges (
  id                 UUID PRIMARY KEY DEFAULT uuidv7(),
  college_id         UUID NOT NULL REFERENCES colleges (id) ON DELETE CASCADE,
  charge_variable    TEXT NOT NULL,   -- IPEDS stem without the year suffix: 'CHG2AY'
  academic_year      TEXT NOT NULL,   -- 'YYYY-YY', decoded from the 0-3 suffix
  amount_usd         INTEGER,         -- NULL when the flag bears no value
  imputation_flag    TEXT NOT NULL,   -- the raw published X-code: R/A/C/Z/B/D/...
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT college_ipeds_charges_variable_format_check
    CHECK (charge_variable ~ '^[A-Z0-9]+$'),
  CONSTRAINT college_ipeds_charges_academic_year_format_check
    CHECK (academic_year ~ '^[0-9]{4}-[0-9]{2}$'),
  CONSTRAINT college_ipeds_charges_flag_domain_check
    CHECK (imputation_flag IN ('R','C','G','J','K','L','N','P','Z','B','D','H','A')),
  CONSTRAINT college_ipeds_charges_amount_nonneg_check
    CHECK (amount_usd IS NULL OR amount_usd >= 0),
  CONSTRAINT college_ipeds_charges_value_iff_flag_check
    CHECK ((amount_usd IS NOT NULL) = (imputation_flag IN ('R','C','G','J','K','L','N','P','Z')))
);
CREATE UNIQUE INDEX college_ipeds_charges_natural_key_idx
  ON college_ipeds_charges (college_id, charge_variable, academic_year);
CREATE TRIGGER trigger_03_enforce_college_ipeds_charges_updated_at
  BEFORE UPDATE ON college_ipeds_charges
  FOR EACH ROW EXECUTE PROCEDURE update_colleges_timestamp();
```

`college_ipeds_charges_value_iff_flag_check` is the value-IFF-flag rule where
the rows live: an amount exists exactly when the raw code is one of the
value-bearing ones. It adds no new failure mode — the loader already fatals on
either bad pairing at parse — but it makes that claim true of ANY writer, and it
is what the loader's "the DB CHECK backstops this" comment refers to. The codes
are spelled out because SQL cannot reference the Kotlin enum; their COUNT is
not, because `ImputationFlag` owns it.

`college_ipeds_charges_flag_domain_check` states the same 13-value set as the
column's DOMAIN rather than a `^[A-Z]$` shape. A shape admits 26 letters, so `Y`
(published, but never on a loaded variable) and never-published letters such as
`Q` would store cleanly, read as valueless through the CHECK above, and then
fatal in the canonical-money fill 180,000 rows later. `charge_variable` keeps a
shape and loses its length bound: nothing derives `16`, so `{1,16}` defended
nothing while being free to refuse a longer stem IPEDS may publish.

There is **no** separate `(college_id)` index. It would be a strict prefix of
the natural-key unique index above, so it can never win a plan that index
cannot, and it would cost a write on every one of the ~180,000 rows an ingest
touches.

Unversioned, like every ingest-reloaded reference table — no
`log_college_version()` change, because no column is added to `colleges`. Every
identifier above is under the 63-byte Postgres truncation limit (longest:
`trigger_03_enforce_college_ipeds_charges_updated_at`, 51; then
`college_ipeds_charges_academic_year_format_check`, 48).

**The load is a snapshot, not an accumulation.** Staging is upsert-only, so the
`ipeds-charges` phase ends by deleting every row outside the vocabulary it just
staged (`CollegeIpedsChargesDao.deleteNotIn`, the
`CodebooksDao.deleteSubjectsNotIn` precedent). The keep-set is the exact natural
keys the run WROTE — matched as whole
`(college_id, charge_variable,
academic_year)` triples by an `unnest`, never as
a cross-product of three independent axes. That difference is a real row: a
charge row whose own upsert failed is not in the set, so it is retired here
instead of surviving with the previous file's amount for the canonical fill to
serve as current. Without it two silent failures follow — a survey-year bump
leaves the old window's rows behind for the canonical fill to meet as an
undecodable year, and an institution that STOPS reporting into IC_AY keeps its
last rows forever, where upstream-wins makes them beat the Scorecard and a
family is shown last year's price indefinitely.

No `price_figures` **structural** migration is needed: RFC 158 already seeded
`in_district`, `fees_only` and `with_family`, and `source` is a plain column
outside the natural key.

### 2b. The money source becomes an owned enumeration (decision 6)

`price_figures.source` is `TEXT` with only a non-empty CHECK, and one Kotlin
constant `SOURCE_SCORECARD = "scorecard"`. That was harmless while one source
existed and the column was decorative. This RFC makes it load-bearing —
`ORDERED_SOURCES` decides which of two conflicting prices a family is shown — so
a mistyped source string would silently change a price with nothing in the
schema to notice. The repo's own rule applies: an owned enumeration is `TEXT` +
`CHECK IN (...)` plus exactly one Kotlin `enum class` with a `fromValue`
companion.

Migration 0084 therefore also adds, to both fact tables:

```sql
ALTER TABLE price_figures
  ADD CONSTRAINT price_figures_source_domain_check
  CHECK (source IN ('ipeds_ic_ay', 'scorecard'));
ALTER TABLE cohort_money_stats
  ADD CONSTRAINT cohort_money_stats_source_domain_check
  CHECK (source IN ('ipeds_ic_ay', 'scorecard'));
```

with `enum class MoneySource(val value: String)` beside the other money enums,
replacing the two bare constants. Deliberately **not** a sixth vocabulary table:
the five RFC 158 seeded are unicoach _concepts_, where D6's "unicoach authors,
external maps in" applies; a source is external identity and provenance, not a
concept. Precedence stays `ORDERED_SOURCES` in code, guarded by the existing
unmapped-source fatal, rather than becoming operator-editable data.

### 3. Which variables are loaded, and which are deliberately not

Loaded, all four carried years (`0`=2020-21 … `3`=2023-24 — D15: store history,
serve latest):

| IPEDS       | price concept        | residency        | arrangement      |
| ----------- | -------------------- | ---------------- | ---------------- |
| `CHG1AY0-3` | `tuition_and_fees`   | `in_district`    | `not_applicable` |
| `CHG2AY0-3` | `tuition_and_fees`   | `in_state`       | `not_applicable` |
| `CHG3AY0-3` | `tuition_and_fees`   | `out_of_state`   | `not_applicable` |
| `CHG1AF0-3` | `fees_only`          | `in_district`    | `not_applicable` |
| `CHG2AF0-3` | `fees_only`          | `in_state`       | `not_applicable` |
| `CHG3AF0-3` | `fees_only`          | `out_of_state`   | `not_applicable` |
| `CHG4AY0-3` | `books_and_supplies` | `not_applicable` | `not_applicable` |
| `CHG5AY0-3` | `housing_and_food`   | `not_applicable` | `on_campus`      |
| `CHG6AY0-3` | `other_expenses`     | `not_applicable` | `on_campus`      |
| `CHG7AY0-3` | `housing_and_food`   | `not_applicable` | `off_campus`     |
| `CHG8AY0-3` | `other_expenses`     | `not_applicable` | `off_campus`     |
| `CHG9AY0-3` | `other_expenses`     | `not_applicable` | `with_family`    |

Not loaded, each for a stated reason:

- **`CHG*AT` (tuition only)** — `tuition_and_fees` minus `fees_only` is exactly
  it, and a derived figure is never stored (schema conventions; RFC 158 P6).
  Loading `AT` as well would let a rounding disagreement between three stored
  columns contradict itself.
- **`TUITION1-3`, `FEE1-3`, `HRCHG1-3`** — these are _average per-student_
  charges, a different concept from a published price; they belong to a cohort
  layer, not `price_figures`. Out of scope, named here so a later slice does not
  think they were missed.
- **`CHG*TGTD`/`CHG*FGTD`** (guaranteed-increase percents) — a policy fact, not
  a price; they have no X-flag partner at all.
- **`published_price`** — a total, therefore derived, therefore never stored.

### 4. Missingness: the X-flag mapping (D3)

Every loaded variable has a paired imputation column. The partner name is `X` +
the stem for the `CHG*` family (`CHG2AY3` → `XCHG2AY3`); the truncated-stem
pattern (`TUITION1` → `XTUIT1`) affects only variables this RFC does not load.
The codebook ships the 14 codes as a **comment block with no `label define`**,
so the loader carries its own enum and treats an unknown code as fatal.

| X-code                                     | `figure_statuses` slug                    | carries a value |
| ------------------------------------------ | ----------------------------------------- | --------------- |
| `R` reported, `C` analyst-corrected        | `reported`                                | yes             |
| `G`, `J`, `K`, `L`, `N`, `P`, `Z`          | `imputed_by_publisher`                    | yes             |
| `B` blank, `D` do-not-know, `H` not-usable | `not_reported_by_institution`             | no              |
| `A` not applicable                         | `not_applicable`                          | no              |
| `Y` professional-practice program          | fatal — never occurs on a loaded variable |                 |

This is the mapping brief 0006 decided; it is applied unchanged. Measured
reality in the 2023 file: only `R`, `A`, `C` and `Z` occur at all, so no charge
figure unicoach serves is imputed today — the branch exists so that a future
year's imputed value cannot arrive dressed as reported.

`Z` (_implied zero_) is the one code where the decided mapping and the source's
own meaning pull apart: it is a real zero, not an estimate, and it appears on
263 `XFEE2` rows. Under the decided mapping it lands as `imputed_by_publisher`
with value `0`, which is honest (the value survives, the status is not
`reported`) but understates it. Gate decision 3 below.

A value present with a valueless flag, or a flag bearing a value with an empty
cell, is a **fatal** — the same value-IFF-status rule the `price_figures` CHECK
and `college_ipeds_charges_value_iff_flag_check` enforce, caught at parse. The
rule has ONE implementation, `ImputationFlag.mapReading()`: the parse calls it
on the way in and the canonical fill on the way out, so neither can drift from
the other.

**The survey year is checked, not assumed.** IC_AY's `0`-`3` suffix is a
POSITION in the file's own four-year window, not a year, so the same column
`CHG2AY3` is 2023-24 in `IC2023_AY.csv` and 2024-25 in `IC2024_AY.csv`. The
window is therefore DERIVED from a named `IpedsChargeVocabulary.SURVEY_YEAR`
beside the mapping, and the run's own `--survey-year` is checked against it —
the guard `bin/fetch-ipeds` already applies on the download side. A disagreement
is fatal at header-assertion time, before any phase commits; without it an
`IC2024_AY.csv` would stamp every staged row and every derived price one year
stale and the run would exit green.

### 5. Canonical fill: the source parameter and precedence

`CanonicalMoneyLoader` today hard-codes `source = SOURCE_SCORECARD` in
`priceFigure()` and `cohortStat()`. This RFC threads `source` through as a
parameter and adds the IPEDS branch to the dispatch RFC 158 left for it. The
`when` over `MoneySource` is EXHAUSTIVE, with no catch-all: a member added
without a branch is a COMPILE error, which is a better extension point than the
runtime one a leftover `else` offered. What no branch could ever catch — a
member that HAS a branch but was never RANKED, whose rows would simply stop
being written — is a companion `init` check that `ORDERED_SOURCES` names every
`MoneySource`:

```kotlin
internal val ORDERED_SOURCES = listOf(MoneySource.IPEDS_IC_AY, MoneySource.SCORECARD)
```

Upstream-wins is then the existing `putIfAbsent` on `PriceKey`, unchanged and
now actually load-bearing: IPEDS writes first, the Scorecard fills only keys
IPEDS left empty (the 2,300 IC_PY institutions, and every cohort statistic,
which IC_AY does not carry). Nothing is averaged; each row keeps its own
`source` and `source_variable`.

The fill DECLARES its second input: IC_AY arrives as the staged rows, read in
the fill's own transaction, not as a second file. It is declared as an
`internal` CONSTRUCTOR collaborator (`StagedChargesReader`, with a stated
lifetime contract) rather than as a parameter of the public `fill(csv)` — the
input stays visible and substitutable in a test, while the live pooled
`SqlSession` the reader is handed stops escaping to public callers. The fill
reads staging (not the CSV) so the canonical phase keeps its "rows first,
derived state second" position after `search-index`, and `METHOD_VERSION` goes 6
→ 7.

Because the prune lives in the `ipeds-charges` phase, a run with no `--ic-ay`
never runs it — so the fill must survive whatever the last IC_AY load left
behind. A staged variable or academic year the mapping does not name is
therefore **counted and passed over, never fatal**: refusing it would take the
whole money rebuild down over rows nothing asked it to serve. The count reaches
the stderr summary and `college_index_build.canonical_money_summary` keyed by
the drift itself — a typed `ChargeDrift` axis with the offending value nested
under it, `{"charge_variable": {"CHG2AT": 1}}`, so a consumer reads which
variable or which year without splitting a packed key on `=`. Each ignored row
is also DEBUG-logged with its identity (row id, college, stored key), because a
bare count names one row out of ~180,000 and no college. So the drift stays loud
instead of becoming a silently missing price (brief 0006 D6).

**The Scorecard's own `TUITIONFEE_IN` mapping is left as it is.** For the 3,825
IC_AY institutions the IPEDS row wins and the mislabel is gone; for the IC_PY
remainder the Scorecard figure is all we have, and re-labelling it in-district
on a guess would trade one wrong label for another. Recorded as an open item for
`shape/04`, which decides what a family is told.

### 6. Ingest wiring

`bin/ingest-colleges` gains the IC_AY file to its existing all-or-nothing IPEDS
group (`getopts`, short option, `EXIT_MISSING_REQUIRED_ARG` when the group is
half-supplied), `IngestApplication`'s `IPEDS_FILE_FLAGS` and `IpedsSources`
extend by one, and a new `ipeds-charges` phase loads staging **before**
`canonical-money` (an ingest-loaded FK is a write precondition). Row counts
reach `college_index_build.rows_ingested` and the stderr load summary under the
existing omit-vs-zero rule; the canonical summary additionally breaks the price
rows down by source, so an operator sees the IPEDS/Scorecard split and the
per-status counts in one place.

## Files Modified

- `db/schema/0084.create-college-ipeds-charges.sql` — new: the staging table
  plus the two `source` domain CHECKs (decision 6).
- `bin/fetch-ipeds` — fifth artifact, `ARTIFACTS` row, ingest-command summary.
- `bin/fetch-codebooks` — `IC2023_AY_Stata.zip` mirror.
- `bin/ingest-colleges` — IPEDS group option, help text, all-or-nothing check.
- `db/seed/ipeds/PROVENANCE.json`, `db/seed/codebooks/PROVENANCE.json` — pinned
  digests (regenerated, not hand-edited).
- `college/src/main/kotlin/ed/unicoach/college/IpedsChargesLoader.kt` — new:
  header assertion, narrow reshape, X-flag enum, value-IFF-status fatals.
- `college/src/main/kotlin/ed/unicoach/college/CanonicalMoneyLoader.kt` —
  `source` parameter typed as `MoneySource`, `ORDERED_SOURCES`, the IPEDS
  mapping branch, per-source counts in `FillResult`.
- `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt` —
  `ipeds-charges` phase, summary lines, `METHOD_VERSION` 7.
- `db/src/main/kotlin/ed/unicoach/db/models/…`, `…/dao/…` — the staging model
  and DAO, mirror-named on `CollegeIpeds*`, plus `MoneySource` beside the other
  money enums.
- `IngestApplication` argv wiring.
- Fixtures under `college/src/test/resources/` — a trimmed `ic2023_ay.csv`
  carrying Austin CC (222992), a public university and a private.
- Tests below.

## Implementation Plan

1. Migration 0084 + model + DAO + `CollegeIpedsChargesDaoTest`.
2. `bin/fetch-ipeds` artifact + codebook mirror; run the real fetch; commit the
   regenerated `PROVENANCE.json` digests.
3. `IpedsChargesLoader` + fixtures + `IpedsChargesLoaderTest`.
4. Ingest phase + argv + `bin/ingest-colleges` option + shell tests.
5. `CanonicalMoneyLoader`: `source` parameter first (a pure refactor, tests
   still green), then the IPEDS branch and `ORDERED_SOURCES`.
6. Summary/provenance per-source counts; `METHOD_VERSION` 7.
7. `MoneySource` enum + the two domain CHECKs; delete the bare source constants.
8. Full `nix develop -c bin/test` and `bin/shell-tests`.

## Tests

- **The disagreement is pinned** (slice acceptance criterion): Austin Community
  College District, UNITID 222992 — Scorecard `TUITIONFEE_IN` 2,550 vs IPEDS
  `CHG2AY3` 8,580 for 2023-24. Asserts the canonical `in_state` row is 8,580
  with `source = ipeds_ic_ay`, and that a distinct `in_district` row carries
  2,550. This is the whole slice in one test.
- **A community college gains a real `in_district` tier** distinct from
  `in_state` — the same fixture, asserted as two rows, not one.
- **Fees split**: `fees_only` rows exist for all three tiers and never equal the
  `tuition_and_fees` row.
- **Upstream-wins**: for a key both sources carry, the IPEDS row is stored and
  the Scorecard value appears nowhere; for an IC_PY institution absent from
  IC_AY, the Scorecard row survives. Neither is averaged.
- **Status carried, counts asserted**: per-status counts over the fixture; an
  imputed code stores its value under `imputed_by_publisher` and never
  `reported`; a `B`/`D`/`H` row stores no value; an unknown code is fatal, and
  so is the published `Y`; value-IFF-status is fatal in BOTH directions — a
  value under a valueless flag, and a value-bearing flag over an empty cell.
- **All four years land**, and the natural key keeps them distinct.
- **`_RV` refusal** extends to the new artifact (shell test, `bin/scripts-tests`
  IPEDS block); half-supplied IPEDS group still exits
  `EXIT_MISSING_REQUIRED_ARG`.
- **Ingest**: `IpedsChargesIngestTest` for row counts, the build row, and the
  PERSISTED `price_figures_by_source` block — including that a source which won
  no row is omitted rather than written as `0`.
- **The fill is unchanged where IC_AY is absent**: with an empty
  `college_ipeds_charges` staging table, `CanonicalMoneyIpedsTest` asserts the
  Scorecard-only result the fill produced before this RFC.
- **`MoneySource` is closed**: an unknown source string is rejected by the DB
  CHECK, and `fromValue` returns `null` for it rather than inventing a member —
  the `PriceConcept` precedent — asserted both ways.
- No user-visible first-session test: substrate. The operator-visible evidence
  is the per-tier row counts and flag distribution on stderr.
