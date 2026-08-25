# RFC 133: Income-band net price and median debt on colleges

## Executive Summary

`colleges` carries a single `net_price` — the overall average (NPT4_PUB/PRIV
coalesced at load, RFC 67/82). Product brief 0001 ("know your real price") needs
the price a _specific family_ is likely to pay, and the College Scorecard
institution file already publishes average net price by household income band
(NPT41..NPT45, in five brackets: $0–30k, $30,001–48k, $48,001–75k, $75,001–110k,
$110k+, each with `_PUB`/`_PRIV` variants) plus median cumulative federal debt
of completers (`GRAD_DEBT_MDN`). This RFC adds six nullable columns —
`net_price_q1..net_price_q5` and `median_debt` — through the whole
reference-data path: schema (+ version history + change-detection tuple),
Scorecard loader, domain models, and the college-search chat tool's result
objects, so cost answers in chat can cite a band-accurate number today and slice
S3 (the cost tool proper) has its data.

It also lands the product-ethos rider from brief 0001: a short "value before
ask" section in `PRODUCT.md`.

No new tables. No new tool parameters (filtering by band price is S3's concern);
the new fields appear in search results only.

## Detailed Design

### Schema (migration 0045)

Six nullable `INTEGER` columns on `colleges` and `colleges_versions`:

    ALTER TABLE colleges
        ADD COLUMN net_price_q1 INTEGER NULL,  -- NPT41_*, income $0-30k
        ADD COLUMN net_price_q2 INTEGER NULL,  -- NPT42_*, $30,001-48k
        ADD COLUMN net_price_q3 INTEGER NULL,  -- NPT43_*, $48,001-75k
        ADD COLUMN net_price_q4 INTEGER NULL,  -- NPT44_*, $75,001-110k
        ADD COLUMN net_price_q5 INTEGER NULL,  -- NPT45_*, $110k+
        ADD COLUMN median_debt  INTEGER NULL;  -- GRAD_DEBT_MDN, completers

Same on `colleges_versions`. Constraints mirror the house pattern: `median_debt`
gets a `>= 0` check; the five band columns get NO nonneg check, matching
`net_price`, whose check was dropped in 0022 because Scorecard publishes
legitimate negative net prices (aid exceeding cost). The version trigger
`log_college_version()` is CREATE OR REPLACEd to carry the six new columns; the
migration runs before any backfill so history rows written after it are
complete. Existing history rows show NULL for the new columns — correct: those
ingests never saw the fields.

### Change detection and upsert (CollegesDao)

The upsert's curated-column tuple (both the SET list and the `IS DISTINCT FROM`
row comparison) grows the six columns, so a re-ingest that only changes a band
price bumps `version` and writes history, and an unchanged re-ingest stays a
no-op.

### Loader (CollegeScorecardLoader)

`mapInstitution` reads the six fields. Band prices follow the exact `net_price`
precedent: `control == 1` reads `NPT4n_PUB`, else `NPT4n_PRIV`; excluded from
mechanism-A coercion because negatives are valid (0022). `median_debt` uses
`intInDomainOrNull(0, MAX)` like the other nonneg money fields; Scorecard's
`PrivacySuppressed`/`NULL` sentinels already map to null via the existing
`intOrNull` path.

### Models and search tool

`NewCollege` / `College` / `CollegeMatch` gain the six fields (nullable Ints).
`CollegeSearchTool.matchObject` serializes them (null → JsonNull, matching the
existing style), with the band bracket documented in the tool DESCRIPTION so the
coach can pick the right band conversationally until S2/S3 land. No new query
parameters.

### PRODUCT.md rider

A new "## Product ethos: value before ask" section: never force a user through a
step whose value they don't yet understand; invite, allow
start/stop/resume/later, degrade gracefully on decline, never gate on completion
(the chat-before-subscription flow is the existing instance).

## Files Modified

- `db/schema/0045.add-college-income-band-net-price.sql` — new migration
- `db/src/main/kotlin/ed/unicoach/db/models/College.kt` (or wherever
  `College`/`NewCollege`/`CollegeMatch` live) — six fields each
- `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt` — row mappers, upsert
  column lists, IS DISTINCT FROM tuple, search SELECT
- `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt` —
  mapInstitution
- `college/src/main/kotlin/ed/unicoach/college/CollegeSearchTool.kt` —
  matchObject + DESCRIPTION
- `college/src/test/resources/scorecard-institutions-*.csv` — header + cells for
  the six fields (all institution fixtures)
- Tests listed below; `PRODUCT.md`

## Implementation Plan

1. Migration 0045 (columns on both tables, trigger replace, median_debt check).
2. Models + DAO (mappers, upsert lists, distinct tuple, search SELECT).
3. Loader mapInstitution + fixture CSV columns.
4. CollegeSearchTool matchObject + description.
5. PRODUCT.md rider.
6. Tests.

## Tests

- `CollegesDaoTest`: upsert round-trips the six fields; a change in only
  `net_price_q3` bumps version and writes a history row carrying all six;
  unchanged re-upsert writes nothing.
- `CollegeScorecardLoaderTest`: PUB/PRIV band coalesce per control; blank and
  `PrivacySuppressed` cells load as null; negative band price loads (not
  coerced); negative `median_debt` coerces to null and is counted.
- `CollegeSearchToolTest` (or the real-data test): result objects carry the six
  fields; nulls serialize as JsonNull.
- Existing fixture-driven tests updated for the new CSV columns.
