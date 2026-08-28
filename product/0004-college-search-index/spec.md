# 0004 spec — the college search index

Status: **GATE 2 APPROVED** (Ian, 2026-08-27: "approve" — D14–D22 as written,
including the in-thread rulings recorded in D20 and S5). EXECUTE: S1 is next.
Gate 1 decisions and amendments: see `brief.md`. Slices are /ship instructions;
each /ship run's RFC may refine constraint details, but **table shape and
semantics as approved here are binding** (a change comes back as a new numbered
decision).

## DISCOVER preamble — corrections to assumptions

Grounded against the repo (see `research/repo-foundation.md`):

- `college_programs` is **4-digit CIP only** in practice (Scorecard FoS source),
  despite the relaxed 2/4/6 format check (migration 0021). 6-digit truth
  requires the new IPEDS Completions table (S2).
- Unchanged re-ingest rows write nothing and log no history — a rebuild after an
  ingest that changed 0 rows produces an identical index; the build stamp makes
  that visible rather than silent.
- No snapshot provenance exists anywhere in the repo today; S1 introduces it.
- Only `plpgsql` is installed; S1's `CREATE EXTENSION pg_trgm` is the repo's
  first extension (available in both nix dev PG 18 and RDS 18 — verified in nix,
  on AWS's supported list for RDS; the /ship run must verify on RDS before
  landing).
- Next migration file: `0049.`; next RFC: 139 (numbers move; /ship claims the
  actual next free ones).

## Architecture (fixed by gate 1)

Postgres-only. Two-phase ingest, no queue, no triggers into derived state:

    bin/ingest-colleges  (one command, one run)
      PHASE 1  upsert normalised source tables, row-by-row, versioned:
               colleges, college_programs            (Scorecard — exists)
               college_ipeds, college_programs_census (IPEDS — new, S2)
      PHASE 2  one transaction, at the end:
               TRUNCATE college_search_index; INSERT ... SELECT <joins/rollups>;
               compute percentile-rank columns (facts, not judgements);
               INSERT college_index_build (provenance + change summary);
               COMMIT.

Derived tables are never source of truth: droppable, rebuilt whole on every
ingest. Physical indexes (GIN/btree) self-maintain; at ~6k rows no index
gymnastics are needed. Raw source codes in schema; human-readable enums only at
the tool/API boundary.

## Proposed DDL (gate-2 artifact — Ian signs this)

Abbreviated to the binding shape; CHECK constraints follow house patterns
(`0015`/`0045`) and are finalised in each /ship RFC.

```sql
-- S1 (migration 0049-ish): the repo's first extension
CREATE EXTENSION pg_trgm;

-- S1: curated aliases for fuzzy name resolution ("Mizzou", "UMass Amherst").
-- Loaded from a repo-committed file (D16), not hand-edited in the DB.
ALTER TABLE colleges ADD COLUMN aliases TEXT[] NOT NULL DEFAULT '{}';
CREATE INDEX colleges_name_trgm_idx ON colleges
    USING gin ((name || ' ' || array_to_string(aliases, ' ')) gin_trgm_ops);

-- S2: IPEDS institutional attributes (reference table, RFC 84 composition:
-- versioned like colleges; upsert on unit_id; no soft delete).
CREATE TABLE college_ipeds (
    id                 UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    unit_id            INTEGER     NOT NULL,             -- joins colleges.unit_id
    survey_year        SMALLINT    NOT NULL,             -- e.g. 2023
    cy_active          BOOLEAN     NOT NULL,             -- HD.CYACTIVE
    death_year         SMALLINT    NULL,                 -- HD.DEATHYR (sentinel-cleaned)
    closed_at          DATE        NULL,                 -- HD.CLOSEDAT
    new_unit_id        INTEGER     NULL,                 -- HD.NEWID (merger successor)
    inst_level         SMALLINT    NULL,                 -- HD.ICLEVEL 1/2/3
    ug_offer           BOOLEAN     NULL,                 -- HD.UGOFFER
    carnegie_basic     SMALLINT    NULL,                 -- HD.C21BASIC
    carnegie_size      SMALLINT    NULL,                 -- HD.C21SZSET
    cbsa               INTEGER     NULL,                 -- HD.CBSA
    rel_affil          SMALLINT    NULL,                 -- IC.RELAFFIL (code; -2 => explicit none)
    has_rotc           BOOLEAN     NULL,                 -- IC.SLO5   (NULL = unreported)
    has_study_abroad   BOOLEAN     NULL,                 -- IC.SLO6
    has_disability_svc BOOLEAN     NULL,                 -- IC.DISAB
    disability_pct     DOUBLE PRECISION NULL,            -- IC.DISABPCT
    has_housing        BOOLEAN     NULL,                 -- IC.ROOM
    housing_capacity   INTEGER     NULL,                 -- IC.ROOMCAP
    application_fee    INTEGER     NULL,                 -- IC.APPLFEEU
    athletic_assoc     SMALLINT[]  NOT NULL DEFAULT '{}',-- IC.ASSOC1..6 (codes)
    football_conf      SMALLINT    NULL,                 -- IC.CONFNO1
    test_policy        SMALLINT    NULL,                 -- ADM.ADMCON7 raw: 1 req / 5 optional / 3 blind
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX college_ipeds_unit_id_unique_idx ON college_ipeds (unit_id);

-- S2: 6-digit program census (IPEDS C_A). Reference table, unversioned like
-- college_programs; upsert on the natural key.
CREATE TABLE college_programs_census (
    id           UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id   UUID        NOT NULL REFERENCES colleges(id) ON DELETE CASCADE,
    cip_code     TEXT        NOT NULL,     -- 6-digit, '^[0-9]{6}$'
    award_level  SMALLINT    NOT NULL,     -- IPEDS AWLEVEL (5 = bachelor's)
    awards_total INTEGER     NOT NULL,     -- CTOTALT, most recent year
    survey_year  SMALLINT    NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX college_programs_census_unique_idx
    ON college_programs_census (college_id, cip_code, award_level);
CREATE INDEX college_programs_census_cip_idx ON college_programs_census (cip_code);

-- S3: the subject taxonomy (authored, repo-committed source file; these tables
-- are its loaded form). ~60-100 subjects; Ian reviews the file content itself.
CREATE TABLE subjects (
    slug       TEXT        NOT NULL PRIMARY KEY,   -- 'literature'
    name       TEXT        NOT NULL,               -- 'Literature'
    synonyms   TEXT[]      NOT NULL DEFAULT '{}',  -- {'lit','english literature',...}
    cip_codes  TEXT[]      NOT NULL,               -- {'2301','2313','2314','160104'} (2/4/6-digit prefixes)
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- S3: THE derived search table. Rebuilt whole in phase 2; never hand-written.
CREATE TABLE college_search_index (
    college_id           UUID    NOT NULL PRIMARY KEY,   -- = colleges.id (no FK: derived, rebuilt)
    unit_id              INTEGER NOT NULL,
    name                 TEXT    NOT NULL,
    search_text          TEXT    NOT NULL,               -- name + aliases, trigram target
    city                 TEXT    NOT NULL,
    state                TEXT    NOT NULL,
    region               SMALLINT NULL,
    locale               SMALLINT NULL,                  -- raw code (D-amendment)
    control              SMALLINT NOT NULL,              -- raw code (D-amendment)
    -- universe flags (D2): searchable-by-default is derived, source stays raw
    is_active            BOOLEAN NOT NULL,               -- from CYACTIVE/DEATHYR; TRUE when IPEDS row absent
    is_four_year         BOOLEAN NULL,                   -- ICLEVEL=1; NULL = unreported
    is_degree_granting   BOOLEAN NULL,                   -- UGOFFER
    -- size / selectivity / outcomes / money (raw values, tri-state semantics per D11)
    undergrad_enrollment INTEGER NULL,
    admission_rate       DOUBLE PRECISION NULL,
    sat_avg              INTEGER NULL,
    test_policy          SMALLINT NULL,
    graduation_rate      DOUBLE PRECISION NULL,
    median_earnings      INTEGER NULL,
    median_debt          INTEGER NULL,
    pct_pell             DOUBLE PRECISION NULL,
    net_price            INTEGER NULL,
    net_price_q1         INTEGER NULL, net_price_q2 INTEGER NULL,
    net_price_q3         INTEGER NULL, net_price_q4 INTEGER NULL,
    net_price_q5         INTEGER NULL,
    cost_attendance      INTEGER NULL,
    tuition_in_state     INTEGER NULL,
    tuition_out_state    INTEGER NULL,
    -- IPEDS attributes (raw codes / tri-state booleans)
    rel_affil            SMALLINT NULL,
    has_rotc             BOOLEAN NULL,
    has_study_abroad     BOOLEAN NULL,
    has_disability_svc   BOOLEAN NULL,
    has_housing          BOOLEAN NULL,
    application_fee      INTEGER NULL,
    athletic_assoc       SMALLINT[] NOT NULL DEFAULT '{}',
    football_conf        SMALLINT NULL,
    carnegie_basic       SMALLINT NULL,
    carnegie_size        SMALLINT NULL,
    -- program rollups (from college_programs_census, bachelor's by default)
    -- percentile ranks (facts, corpus-relative, computed per build; the
    -- query-time similarity substrate — D8 as amended, no judgement baked in)
    -- four only, by Ian's gate-2 ruling: percentiles describe school
    -- CHARACTER (size, selectivity, price). Outcome percentiles (earnings,
    -- graduation) were dropped — institution-wide outcome rankings encode
    -- major-mix/demographic confounds and invite ranking-think; outcomes stay
    -- as raw, cited dollars/rates only, never as a distance axis.
    enrollment_pct       DOUBLE PRECISION NULL,
    admission_rate_pct   DOUBLE PRECISION NULL,
    sat_avg_pct          DOUBLE PRECISION NULL,
    net_price_pct        DOUBLE PRECISION NULL,
    cip_codes            TEXT[]  NOT NULL DEFAULT '{}',  -- 6-digit, AWLEVEL=5
    subject_slugs        TEXT[]  NOT NULL DEFAULT '{}',  -- taxonomy expansion
    build_id             UUID    NOT NULL                -- -> college_index_build.id
);
CREATE INDEX csi_search_text_trgm_idx ON college_search_index USING gin (search_text gin_trgm_ops);
CREATE INDEX csi_subject_slugs_idx    ON college_search_index USING gin (subject_slugs);
CREATE INDEX csi_cip_codes_idx        ON college_search_index USING gin (cip_codes);
CREATE INDEX csi_state_idx            ON college_search_index (state);
CREATE INDEX csi_control_idx          ON college_search_index (control);
CREATE INDEX csi_enrollment_idx       ON college_search_index (undergrad_enrollment);
CREATE INDEX csi_admission_rate_idx   ON college_search_index (admission_rate);
CREATE INDEX csi_net_price_idx        ON college_search_index (net_price);

-- S1 (provenance) / S3 (index stats): every ingest writes exactly one row.
CREATE TABLE college_index_build (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    started_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ NOT NULL,
    sources        JSONB       NOT NULL,  -- [{file, url?, sha256, bytes, release?}]
    rows_ingested  JSONB       NOT NULL,  -- per-table: upserted / changed / unchanged
    index_rows     INTEGER     NULL,      -- college_search_index rows written (S3+)
    change_summary JSONB       NOT NULL,  -- per-column non-null before/after, version bumps
    method_version INTEGER     NOT NULL   -- bumped when derivation logic changes
);
```

## Slices (each one a /ship instruction)

### S1 — Honest name search + provenance (Option A; one /ship run)

**Intent:** a student's typo or nickname finds the school, and the coach can
state a true match count; every ingest leaves a provenance trail.

Build: `CREATE EXTENSION pg_trgm` migration; `colleges.aliases` + the trigram
index over name+aliases; rewrite `CollegesDao.searchByName` to trigram
similarity with threshold + prefix boost (keep exact-substring as a fallback
arm); a repo-committed alias seed file (~50 entries: "Mizzou", "UMass", "Cal",
"Ole Miss"...) loaded by ingest; add `totalMatches` (unclamped COUNT(*)) and
`sortBy` (enrollment | admission_rate | net_price | graduation_rate | name) to
`CollegeQuery`/`search_colleges`, and a `credential_level` filter for the
program join; `college_index_build` table + header assertion (fatal on missing
source column) + change summary printed by `bin/ingest-colleges`.

Acceptance: "Amhurst" finds Amherst College; "Mizzou" finds Missouri-Columbia;
the tool reports `totalMatches` distinct from the returned slice; an ingest of
an unchanged snapshot prints "0 rows changed" loudly; an ingest of a CSV missing
a read column FAILS before writing. Doors: existing `search_colleges` chat tool

- `GET /api/v1/colleges?q=` (iOS picker) — both get the fuzzy upgrade free. No
  new user-facing surface. RDS pg_trgm availability verified before land.

### S2 — IPEDS: the attribute ingest (one /ship run)

**Intent:** the attributes Ian named (religion, ROTC, study abroad, disability,
housing, athletics, test policy, closure) become queryable source data.

Build: `college_ipeds` + `college_programs_census` tables (DDL above);
`bin/ingest-ipeds` (or extend `bin/ingest-colleges`) loading HD+IC+ADM+C_A from
local/`s3://` files with the same resolve/assert/summarise contract as S1;
sentinel cleaning (IPEDS -1/-2 codes → NULL vs explicit-no, per column — the
RELAFFIL -2 = "explicitly none" case is the pattern); provenance rows. Unmatched
`unit_id`s (IPEDS has schools Scorecard lacks) are counted and skipped, not
invented.

Acceptance: post-ingest counts match the research's measured 2023 figures within
tolerance (e.g. ~954 ROTC-yes, ~1,080 test-optional); UMaine System Central
Office is loadable but flagged not-degree-granting; no change to any existing
search surface yet.

### S3 — The index: derived table + subject taxonomy + rebuilt tool (the aha)

**Intent:** "small public schools in Maine with a literature program" answered
end-to-end, honestly, from one derived table.

Build: `subjects` + the authored taxonomy file (~60–100 subjects — **Ian reviews
the file itself in the /ship gate**, incl. literature = 23.01/23.13/
23.14/16.0104); `college_search_index` + phase-2 rebuild inside
`bin/ingest-colleges` (one transaction, build stamp, `index_rows`); repoint
`CollegesDao.search`/`search_colleges` at the index table with new filters
(subject, test_policy, rel_affil, has_rotc/abroad/housing, carnegie, athletics)

- D2 default universe (is_active, is_four_year default-on, overridable) + D11
  excluded-unknown counts in the tool response ("142 matched; 61 unjudgeable —
  admission rate unreported"). Fit-lens keeps working (same service interface).

Acceptance: the Maine query returns UMaine Orono/Farmington/USM/Presque Isle/
Augusta and NOT the System Central Office; every response carries totalMatches +
excluded-unknown counts + source year; dropping and re-ingesting reproduces the
index byte-identically (same snapshot, same method_version). Door: the coach —
`search_colleges` in chat is the surface; prompt guidance updated so the coach
uses subjects and reports counts.

### S4 — "Similar colleges to X", query-time (one /ship run)

**Intent:** the second of Ian's two founding queries — with "similar" decided
per conversation, not pre-baked (gate-1 D8 as amended).

Build: a `similar_colleges` chat tool — input: an anchor college (name or id,
fuzzy-resolved via S1) plus **optional axes** (size, selectivity, price, subject
mix — not outcomes; see D20 note), optional per-axis weights, and optional hard
constraints (state, control, max net price, "more likely to admit me"); defaults
for the bare "schools like X" ask are spelled out in the tool definition (size +
selectivity + setting, same control, active 4-years). Execution is one query:
weighted distance over the S3 percentile columns, `ORDER BY`, LIMIT ≤ 10 — no
new tables, no precompute, no method registry. The response names the axes and
constraints actually used so the coach's explanation is literally the query it
ran. Reproducibility is explicitly traded away (Ian, gate 2: "This is okay to
give up"); if a future surface needs a stable peer list, it pins a preset then.

Acceptance: bare "schools like Bowdoin" returns small selective private
four-years, not ASU; "like Bowdoin but cheaper" shifts the price constraint and
says so; "like Bowdoin but where I'd likely get in" relaxes selectivity
_downward_ deliberately; every response names its axes/constraints. Door: the
coach in chat.

### S5 — Consumer sweep: every search-shaped workflow uses the index

**Intent:** the index is only THE index if everything searches through it (Ian,
gate 2: "go and update all workflows that should use the search index to use
it").

Build: audit every call site that searches, filters, or name-resolves colleges —
the `search_colleges` chat tool, fit-lens query construction,
`GET /api/v1/colleges?q=` / the iOS picker, `update_college_list`'s name→id
resolution (RFC 136), admin-web college browsing, and any direct `colleges`
query outside the ingest/versioning path — and move each search- shaped one onto
`CollegeSearchService` over `college_search_index` (fuzzy resolution included).
Point-reads by id/unit_id stay on `colleges` (correct — the index is for
finding, the source tables for facts). Delete every bypassed legacy query path;
leave a one-line module convention stating that college _search_ goes through
the service, so future callers have a named rule.

Acceptance: a repo-wide sweep shows zero search-shaped SQL against `colleges`
outside `CollegesDao`'s index-backed paths and the ingest; "add Mizzou to my
list" in chat resolves through the same fuzzy path as the picker; fit-lens still
passes its suite unchanged. Mostly a deletion/verification slice — small by
design.

### S6 (optional, deferred) — Unattended refresh

`periodic_jobs` quarterly cron enqueueing the ingest, seeded `enabled = FALSE`.
Only if manual quarterly runs prove annoying. Not part of the core design
(gate-1 amendment).

## Gate 2 — decisions

**D14 — DDL sign-off.** The tables above: names, columns, raw-code semantics,
tri-state rules, index set. Default: as written.

**D15 — where derived tables live.** Default: ordinary migrations create them
(same `db/schema` flow), but no versioning/history triggers — they are derived;
`college_index_build` is their history.

**D16 — taxonomy + aliases as repo files.** Default: both are committed,
reviewable data files (deno-fmt'd JSON) loaded by ingest — not seed migrations
(they'll be edited over time; migrations are append-only), not DB-only rows
(they'd be invisible to review). Ian owns their content.

**D17 — search serving switches to the index table in S3.** Default: yes —
`search` and `searchByName` both re-point; the old query paths are deleted, not
kept in parallel. One search path.

**D18 — default universe values (D2 concretised).** Default:
`is_active = TRUE
AND is_four_year IS NOT FALSE` (unknown level is included,
inactive is not), both overridable per call.

**D19 — S2 ingest command shape.** Default: one `bin/ingest-colleges` grows
optional IPEDS file args (all-or-nothing per run: phase 2 always runs at the
end); no second CLI.

**D20 — similarity tool defaults.** Default axes for a bare "like X": size +
selectivity + setting, same control, active 4-year universe; exact defaults and
weight bounds proposed at S4's /ship gate. **Ruled (2026-08-27): outcome
percentiles (`median_earnings_pct`, `graduation_rate_pct`) are dropped — Ian:
"drop both outcome percentiles". Outcomes (earnings, debt, graduation) remain in
the index as raw, cited values for filtering and the cost tools, but are never a
similarity distance axis. Field-level earnings (Scorecard FoS, kept by D5) is
the honest future path for earnings questions.**

**D21 — tool response contract.** Default: every `search_colleges` response
carries `totalMatches`, `excludedUnknown` (per-filter counts), `sourceYear`s,
and results ≤25 with `sortBy`; enums are words ("public"), mapped at the
boundary.

**D22 — sequencing.** Default: S1 → S2 → S3 → S4 → S5, strictly ordered (each
builds on the last; S5 is the consumer sweep, added by Ian's gate-2 ruling);
lands before brief 0001's S4 (gate-1 D13). S1 can start immediately on approval.

Gate-2 outcome (2026-08-27, verbatim): "approve". All defaults D14–D22 stand,
plus the in-thread rulings already recorded above: D8-amended similarity
(query-time, axes-with-defaults), outcome percentiles dropped (D20), and the S5
consumer sweep with the finders-vs-readers boundary.
