# RFC 140: Admissions Intelligence Layer v0 — CDS reference tables + seed ingest

**Status: Approved** (Ian, 2026-08-28: split D-A, sequencing D-B — land after
rfc-139, launch set D-C, seed shape D-D, and the DDL as amended:
`freshmen_ft_total` rename; ratings and rounds on the house TEXT-enum pattern)

Product brief 0001, slice S4 (Admissions Intelligence Layer v0). Standing
context: gate-2 decisions D7–D12 in `product/0001-v1-differentiator/spec.md`; D8
fixes the launch set at ~300–500 schools; D10 requires Ian to approve every new
table's DDL explicitly at the /ship gate; brief 0004's D13 sequences its
search-index work against this slice (addressed under **Sequencing** below).

**Split (pre-authorised by the spec).** S4 is the largest slice in Beat 1 and
the spec allows splitting in design. The honest read is TWO slices, not one and
not three:

- **S4a (this RFC):** the three CDS reference tables, the seed fetched from the
  collegedata.fyi corpus, the ingest that loads it, and the launch-set coverage
  report. Schema without a proven seed is unverifiable, so they are one slice.
- **S4b (next RFC):** the cited `college_admissions_profile` chat tool, the
  merit-aid feed into `college_cost_profile` ("X% of freshmen without need got
  merit here, avg $Y"), and the coach-prompt update. AC: cited merit answers in
  chat.

## Motivation

The coach can already cite federal cost truth (Scorecard: net price by income
band, debt, earnings — RFCs 133/135). What it cannot cite is the school-authored
layer families actually ask about: does this school give merit to families who
don't qualify for need-based aid, and how much; what does admission actually
weigh; when is everything due. That data exists — every selective school
publishes a Common Data Set — but no consumer AI product exposes it as
structured, cited tool data. This slice builds the reference tables and the
seed; the follow-on slice puts them in the coach's hands.

**Source.** The open-source collegedata.fyi corpus
(github.com/bolewood/collegedata-fyi; MIT-licensed code, schema, and archive;
the values themselves are school-published facts, not copyrightable). Research
findings (fetched live, 2026-08-28; full detail in the run archive):

- Free, no-auth API + raw PostgREST + bulk JSONL snapshots. Schools keyed by
  `ipeds_id` = zero-padded IPEDS UNITID → direct join to `colleges.unit_id`.
- **H2A merit aid**: field `H.2A01` (# no-need first-time full-time freshmen
  awarded non-need merit), `H.2A02` (average award $), denominator `H.201` (#
  degree-seeking FT freshmen). ~371 distinct schools. Verified live:
  Northeastern 358 / $16,112 (13.0%), Tulane 518 / $19,636, Alabama 3,206 /
  $16,895.
- **C7 factor grid**: `C.701`–`C.718`, literal rating strings ("Very Important"
  … "Not Considered"), ~402 distinct schools; ~5% extraction junk →
  whitelist-normalise, drop non-matching (unreported, not guessed).
- **Deadlines** (C14/C16/C21/C22): offered-flags are decent (ED `C.2101` in ~287
  docs, EA `C.2201` in ~282); concrete date rows are sparse and the field
  numbering shifts between the 2024-25 and 2025-26 schemas (disambiguate via
  each row's `schema_version`). v0 treats deadlines as **flags + best-effort
  dates**; a missing date is "not reported", never interpolated. A month without
  a day is real CDS reporting and is stored; a day without a month is junk and
  is refused at every layer (fetcher, loader, DB CHECK), and only a complete
  month+day counts as a concrete date in the coverage report. One declared
  inference: a round row with a reported date but no extracted offered-flag (and
  the flag-less rounds, priority/ED2) carries `offered = true` — a reported
  deadline logically entails the round exists; dates themselves are never
  inferred.

**Explicitly out of scope:** any net-price-calculator automation (brief 0001
D2); scraping CDS PDFs ourselves (0004 D10 declined it; the corpus's already-
extracted values are what we ingest); the chat tool and prompt work (S4b).

## Detailed Design

### Launch set (D8)

The launch set is the intersection of the corpus's usable coverage with our
`colleges` table: every school whose UNITID matches a `colleges.unit_id` and
that has at least one of the three fact groups in its latest extracted CDS
document (~400 schools; within D8's 300–500 band). This _is_ the
popularity-ranked set in practice — CDS publication skews precisely toward the
selective 4-years students list — plus the coverage report calls out any
student-listed school (`college_list`) that the corpus misses, so college-list
popularity is monitored explicitly rather than assumed.

### Seed artifact: repo-committed, fetched by script

`bin/fetch-cds-seed` (python3, stdlib-only, dev-shell) pulls the corpus via
PostgREST (batched, `X-CollegeData-Client: unicoach` header), normalises, and
writes a reviewable, diffable seed under `db/seed/cds/`:

    db/seed/cds/merit-aid.csv        # unit_id, source_year, freshmen_ft_total, no_need_merit_count, no_need_merit_avg, source_url, archive_url
    db/seed/cds/admission-factors.csv# unit_id, source_year, rigor..applicant_interest (18 rating codes), source_url, archive_url
    db/seed/cds/deadlines.csv        # unit_id, source_year, round, offered, closing_month, closing_day, notification_month, notification_day, source_url, archive_url
    db/seed/cds/PROVENANCE.json      # fetched_at, corpus counts, per-file sha256

All interpretation lives in the fetcher: the C7 rating whitelist ("Very
Important"→'very_important', "Important"→'important', "Considered"→'considered',
"Not Considered"→'not_considered'; anything else → empty cell), the
per-`schema_version` deadline field mapping, and the document selection.

**Document selection is per fact group, not per school.** For each school, each
of the three groups independently takes the newest extracted, non-removed
document that actually _reports that group_ (`cds_manifest.canonical_year`
desc). Newest-document-only selection would discard a group a school reported
last cycle but omitted this one — measured at ~11% of available coverage
(factors 356 of ~375 reachable). Per-group `fell_back_to_older_doc` /
`documents_skipped` / `unreported_in_every_doc` counters land in
`PROVENANCE.json`, so a fallback is visible rather than inferred.

**The `schema_version` → field-numbering mapping is an explicit table**
(`2025-26` → split month/day fields, `2024-25` → free-text), never sniffed with
a default arm. A document whose `schema_version` is absent, internally
inconsistent, or not in the table has its _deadlines_ refused and counted; its
other fact groups still load. This is the difference between a future CDS
template being reported and it silently fabricating `early_decision_2` dates,
since the same field numbers carry different meanings across templates.

**Corpus access is pinnable**: `--anon-key`, else `$COLLEGEDATA_ANON_KEY`, else
the published-key scrape, with the source recorded in `PROVENANCE.json`. The
Kotlin loader stays a dumb typed CSV reader with a header assertion (the 0004 S1
pattern), so a stale or malformed seed fails loudly instead of loading NULLs.

Refresh cadence: re-run the fetcher, review the diff, commit — same rhythm as
the Scorecard snapshot, no cron.

### Proposed DDL (D10 — Ian signs this)

One migration (`0054`). Three reference tables, all: `college_id` FK to
`colleges(id)` resolved from UNITID at load (unmatched seed rows are logged,
skipped, and counted in the coverage report); **unversioned** — `source_year` in
the natural key makes history explicit (a new CDS cycle is a new row), and a
re-ingest of the same cycle is a correction that overwrites (upsert on the
natural key). Raw source codes in schema; human-readable labels only at the
tool/API boundary (house rule). `source_year` is the CDS cycle start year (2024
= the 2024-25 CDS); `source_url` is the school's own CDS document, `archive_url`
the corpus's archived copy — S4b's per-fact citations render from these columns.

```sql
-- CDS H2 section H2A: institutional non-need ("merit") aid to first-time
-- full-time freshmen with no financial need. The user-facing % is DERIVED at
-- read time as no_need_merit_count / freshmen_ft and labeled as such.
CREATE TABLE college_merit_aid (
    id                   UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id           UUID        NOT NULL REFERENCES colleges(id) ON DELETE RESTRICT,
    source_year          SMALLINT    NOT NULL,  -- CDS cycle start year, e.g. 2024
    -- H.201 FRSH_FT_N: TOTAL degree-seeking first-time full-time freshmen
    -- enrolled (all of them, needy or not) -- the denominator for the derived
    -- share, which is therefore "share of ALL FT freshmen" and must be
    -- labeled that way (the % of specifically-no-need freshmen is NOT
    -- computable: CDS does not report that denominator).
    freshmen_ft_total    INTEGER     NULL,
    -- H.2A01 FRESH_FT_NN_NONEED_N: # of those freshmen who had NO financial
    -- need and were awarded institutional non-need ("merit") aid.
    no_need_merit_count  INTEGER     NULL,
    -- H.2A02 FRESH_FT_NN_NONEED_D: school-reported AVERAGE award in whole
    -- dollars. Source data, not derivable: no total-dollars column exists.
    no_need_merit_avg    INTEGER     NULL,
    source_url           TEXT        NOT NULL,
    archive_url          TEXT        NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (college_id, source_year),
    CONSTRAINT college_merit_aid_year_check
        CHECK (source_year BETWEEN 2015 AND 2100),
    CONSTRAINT college_merit_aid_nonneg_check
        CHECK ((freshmen_ft_total   IS NULL OR freshmen_ft_total   >= 0) AND
               (no_need_merit_count IS NULL OR no_need_merit_count >= 0) AND
               (no_need_merit_avg   IS NULL OR no_need_merit_avg   >= 0)),
    CONSTRAINT college_merit_aid_count_le_total_check
        CHECK (freshmen_ft_total IS NULL OR no_need_merit_count IS NULL
               OR no_need_merit_count <= freshmen_ft_total)
);

-- CDS C7 factor grid, wide: one TEXT rating per factor, the house
-- own-enumeration pattern (TEXT + CHECK IN, backed by a Kotlin enum with
-- value/fromValue -- CollegeListEntryStatus/IncomeBand precedent). NULL = not
-- reported (incl. extraction junk dropped by the fetcher's whitelist).
-- Vocabulary: 'very_important' | 'important' | 'considered' | 'not_considered'.
CREATE TABLE college_admission_factors (
    id                    UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id            UUID        NOT NULL REFERENCES colleges(id) ON DELETE RESTRICT,
    source_year           SMALLINT    NOT NULL,
    rigor                 TEXT        NULL,  -- C.701
    class_rank            TEXT        NULL,  -- C.702
    gpa                   TEXT        NULL,  -- C.703
    test_scores           TEXT        NULL,  -- C.704
    essay                 TEXT        NULL,  -- C.705
    recommendations       TEXT        NULL,  -- C.706
    interview             TEXT        NULL,  -- C.707
    extracurriculars      TEXT        NULL,  -- C.708
    talent                TEXT        NULL,  -- C.709
    character_qualities   TEXT        NULL,  -- C.710
    first_generation      TEXT        NULL,  -- C.711
    alumni_relation       TEXT        NULL,  -- C.712
    geography             TEXT        NULL,  -- C.713
    state_residency       TEXT        NULL,  -- C.714
    religious_affiliation TEXT        NULL,  -- C.715
    volunteer_work        TEXT        NULL,  -- C.716
    work_experience       TEXT        NULL,  -- C.717
    applicant_interest    TEXT        NULL,  -- C.718
    source_url            TEXT        NOT NULL,
    archive_url           TEXT        NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (college_id, source_year),
    CONSTRAINT college_admission_factors_year_check
        CHECK (source_year BETWEEN 2015 AND 2100),
    -- one per-column CHECK, pattern:
    --   (rigor IS NULL OR rigor IN ('very_important','important','considered','not_considered'))
    -- repeated verbatim for all 18 factor columns in the migration.
    CONSTRAINT college_admission_factors_rating_check
        CHECK ( ... all 18 columns as above ... )
);

-- Application rounds, long: one row per (college, cycle, round). CDS gives
-- month/day WITHOUT a year (cycle-relative), so the raw MM/DD is stored and
-- the render layer says "Jan 15" against the cycle — never a fabricated DATE.
-- round: our own taxonomy, same TEXT-enum pattern:
-- 'regular' | 'priority' | 'early_decision_1' | 'early_decision_2' |
-- 'early_action' | 'rolling'.
CREATE TABLE college_deadlines (
    id                 UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id         UUID        NOT NULL REFERENCES colleges(id) ON DELETE RESTRICT,
    source_year        SMALLINT    NOT NULL,
    round              TEXT        NOT NULL,
    offered            BOOLEAN     NOT NULL,  -- the reliable bit (C.2101/C.2201/C.1601/C.1401)
    closing_month      SMALLINT    NULL,      -- best-effort (sparse in corpus)
    closing_day        SMALLINT    NULL,
    notification_month SMALLINT    NULL,
    notification_day   SMALLINT    NULL,
    source_url         TEXT        NOT NULL,
    archive_url        TEXT        NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (college_id, source_year, round),
    CONSTRAINT college_deadlines_year_check
        CHECK (source_year BETWEEN 2015 AND 2100),
    CONSTRAINT college_deadlines_round_check
        CHECK (round IN ('regular','priority','early_decision_1',
                         'early_decision_2','early_action','rolling')),
    CONSTRAINT college_deadlines_month_day_check
        CHECK ((closing_month      IS NULL OR closing_month      BETWEEN 1 AND 12) AND
               (closing_day        IS NULL OR closing_day        BETWEEN 1 AND 31) AND
               (notification_month IS NULL OR notification_month BETWEEN 1 AND 12) AND
               (notification_day   IS NULL OR notification_day   BETWEEN 1 AND 31))
);
```

No physical indexes beyond the UNIQUE constraints: every read is by `college_id`
(leftmost prefix of the natural key), row counts are ~400–800 per table.
Standard `updated_at` trigger per house pattern; no delete guard — these are
droppable reference tables, rebuilt from the seed at will (the 0004 "derived
tables are never source of truth" stance).

### Ingest

`bin/ingest-colleges` grows three optional CDS seed-file arguments (the 0004 D19
shape: one CLI, optional per-source args; all-or-nothing per source group — pass
all three CDS files or none). Operator-facing `-m/-a/-d` map to **named**
`--cds-merit/--cds-factors/--cds-deadlines` launcher flags, so no seed file is
ever bound to a table by argv position. `IngestApplication` gains a CDS loader:
header assertion first (any missing/renamed column is fatal), then UNITID →
`colleges.id` resolution, then upsert per table on the natural key. The run
summary prints per-table upserted/changed/unchanged/skipped counts and the
**launch-set coverage report**:

    CDS coverage: 403 launch-set colleges
      merit aid          371/403 (32 without)
      admission factors  402/403
      deadlines (flags)  289/403, with >=1 concrete date 212/403
      student-listed schools missing from corpus: 2 (Foo College, Bar U)

If brief 0004's S1 (rfc-139, in flight) has landed by implementation time, the
ingest also writes its provenance row through S1's mechanism (sources sha256s
from `PROVENANCE.json`); if not, `PROVENANCE.json` itself is the provenance
until the rebase, and wiring in the row is a follow-up inside S4b.

**Verification discipline (from the slice instruction):** the dist is rebuilt
(`bin/build-college`) before any load; the seed is proven landed with direct
`SELECT`s on all three tables plus the coverage report — a successful-looking
load is not evidence.

### Sequencing vs brief 0004 D13

0004 D13 defaults its S1–S3 before this slice. The technical read: S4a's only
genuine dependency is S1's ingest-provenance/header-assertion pattern (in flight
as rfc-139); S2 (IPEDS attributes) and S3 (the search index) share no tables, no
columns, and no code paths with these CDS tables, which join `colleges` on
`unit_id` directly. Proposed: land S4a after rfc-139 lands (rebase, adopt its
loader conventions), without waiting for 0004 S2/S3. S4b (the tool) is likewise
independent of the search index. This is a conscious narrowing of D13 for Ian to
approve, not a silent override.

## Files Modified

- `db/schema/0054.create-cds-admissions-tables.sql` — new (0049-0053 were taken
  by RFC 141/142/143/144 prompt seeds and RFC 139 while this run was open)
- `bin/fetch-cds-seed` — new (python3, stdlib-only)
- `db/seed/cds/merit-aid.csv`, `admission-factors.csv`, `deadlines.csv`,
  `PROVENANCE.json` — new, generated then committed
- `bin/ingest-colleges` — optional CDS seed args
- `college/src/main/kotlin/ed/unicoach/college/IngestApplication.kt` — CDS args
- `college/src/main/kotlin/ed/unicoach/college/CdsSeedLoader.kt` — new
- `db/src/main/kotlin/ed/unicoach/db/models/FactorRating.kt`,
  `ApplicationRound.kt` — new house-pattern enums
- `db/src/main/kotlin/ed/unicoach/db/...` — models + DAOs for the three tables
- `bin/scripts-tests` (or the module's test home) — CLI arg coverage
- `CLAUDE.md` — new "Schema conventions" section (the house TEXT-enum /
  raw-source-code / derived-at-read rules, approved at the gate)
- tests per below

## Implementation Plan

1. Migration 0049 + models/DAOs + Kotlin loader with header assertion (TDD
   against fixture CSVs).
2. `bin/fetch-cds-seed` + generate the real seed; commit it; record corpus
   counts in `PROVENANCE.json`.
3. Wire `bin/ingest-colleges` args; rebuild dist; run the real load; capture the
   coverage report and direct SELECTs as run artifacts.
4. Rebase onto rfc-139 if landed; adopt its provenance/header conventions.

## Tests

- Migration applies; constraints reject bad rating/round values, months,
  negative counts, count > denominator.
- Loader: header-assertion failure is fatal and named; unknown UNITID rows are
  skipped and counted; upsert is idempotent (re-run → all unchanged); a changed
  value updates and bumps `updated_at`.
- Fetcher: unit-testable normalisation (rating whitelist, schema-version
  deadline mapping, latest-doc selection) against recorded fixture JSON — no
  network in tests.
- Coverage report: counts computed from the DB, asserted against fixtures.
