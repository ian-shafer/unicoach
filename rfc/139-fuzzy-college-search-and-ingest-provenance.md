# RFC 139: Fuzzy college name search and ingest provenance

**Status: Proposed**

Product brief 0004 ("college search index"), slice S1. Gate decisions in
`product/0004-college-search-index/{brief,spec}.md` are binding context; the
schema shapes below were approved by Ian at that brief's gate 2.

## Motivation

Three defects in today's college search, each user-visible:

1. **Name lookup is exact-substring only.** `CollegesDao.searchByName` is a
   leading-wildcard `ILIKE` over `colleges.name` — unindexed, and blind to typos
   ("Amhurst"), fragments, and nicknames ("Mizzou", "UMass Amherst"). This
   serves the iOS add-college picker (`GET /api/v1/colleges?q=`, RFC 137) — a
   surface aimed at teenagers typing on phones.
2. **The coach cannot state a true match count.** `search_colleges` reports
   `count` = the returned slice (≤ 25), so "how many small public schools in
   Maine?" cannot be answered honestly; and results are always ordered
   `undergrad_enrollment DESC` — the biggest school wins, with no alternative.
3. **Ingest is silently unobservable.** `bin/ingest-colleges` records no
   provenance (no snapshot URL/sha/date anywhere in the repo), does not fail
   when a source CSV lacks a column it reads (the column silently loads as
   NULL), and prints no change summary — a stale jar and a real load are
   indistinguishable (the "silent no-op ingest" scar, `product/STATUS.md`).

## Detailed Design

### Migration 0051: `pg_trgm` + `colleges.aliases`

The repo's first `CREATE EXTENSION`. `pg_trgm` is contrib, present in both the
nix dev Postgres 18 and RDS 18 (verified in nix; on AWS's supported-extension
list — the implementer verifies `CREATE EXTENSION` succeeds against RDS-parity
config before land).

```sql
CREATE EXTENSION pg_trgm;

ALTER TABLE colleges
    ADD COLUMN aliases TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE colleges_versions
    ADD COLUMN aliases TEXT[] NOT NULL DEFAULT '{}';
-- log_college_version() redefined to carry aliases (the 0045 pattern).

-- array_to_string() is STABLE, not IMMUTABLE, so it cannot appear inline in an
-- index expression (verified against PG 18: "functions in index expression must
-- be marked IMMUTABLE"). The expression lives in an IMMUTABLE SQL wrapper —
-- honest for TEXT[] concatenation — that queries must call verbatim to hit the
-- index.
CREATE FUNCTION college_search_text(name TEXT, aliases TEXT[])
RETURNS TEXT
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN name || ' ' || array_to_string(aliases, ' ');

CREATE INDEX colleges_search_text_trgm_idx ON colleges
    USING gin (college_search_text(name, aliases) gin_trgm_ops);
```

Aliases are **curated repo data**, not Scorecard data: a committed file
`db/data/college-aliases.json` (deno-fmt'd; entries
`{ "unit_id": 178396, "aliases": ["Mizzou", "University of Missouri"] }`, seeded
with ~50 well-known nicknames). Ingest loads it after the Scorecard upsert
phase: per-row
`UPDATE ... SET aliases = ARRAY(SELECT
jsonb_array_elements_text(?::jsonb)), version = version + 1 WHERE unit_id = ?
AND aliases IS DISTINCT FROM ARRAY(SELECT jsonb_array_elements_text(?::jsonb))`
— change-suppressed like the Scorecard upsert, so an unchanged alias file writes
nothing and bumps nothing. (The alias set rides as one bound `jsonb` parameter
that Postgres expands to `text[]`: the `SqlSession` boundary deliberately
withholds the pooled `Connection`, so the DAO builds no client-side
`java.sql.Array` and has no handle to `free()`.) Unknown `unit_id`s are reported
**by value** — the ids, not a count, in both the summary and the build row — and
are never fatal. Every shape violation is fatal at parse, before any write, as a
typed `InvalidAliasFileException` naming the file and the offending entry: a
**duplicate** `unit_id` (applying both entries would be last-writer-wins by file
order), an entry whose keys are not exactly `{unit_id, aliases}` (a mistyped key
is a typo, never surplus data), a non-integer `unit_id`, or a non-string alias.

### Migration 0052: `college_index_build`

Provenance for every ingest run (shape approved at brief 0004 gate 2; the
`index_rows` column arrives with the S3 derived table and is NULL until then):

```sql
CREATE TABLE college_index_build (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    started_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ NOT NULL,
    sources        JSONB       NOT NULL,  -- [{file, sha256, bytes, source_arg}]
    rows_ingested  JSONB       NOT NULL,  -- per table: inserted/changed/unchanged/skipped
    index_rows     INTEGER     NULL,      -- S3+
    change_summary JSONB       NOT NULL,  -- per column: non-null before/after; version bumps
    method_version INTEGER     NOT NULL,
    CONSTRAINT college_index_build_finished_after_started_check
        CHECK (finished_at >= started_at),
    CONSTRAINT college_index_build_method_version_positive_check
        CHECK (method_version > 0),
    CONSTRAINT college_index_build_index_rows_nonneg_check
        CHECK (index_rows IS NULL OR index_rows >= 0)
);
```

The two value CHECKs tighten the gate-2 shape without changing it (columns and
semantics as approved).

Derived/operational table: no versioning, no soft delete, append-only by
convention (each run inserts exactly one row at the end, success paths only — a
failed ingest writes no row and exits non-zero).

### Fuzzy `searchByName`

`CollegesDao.searchByName` becomes a two-signal ranked query over the indexed
expression `search_text = name || ' ' || array_to_string(aliases, ' ')`:

- **Match arms (OR):** `search_text % ?` (whole-string trigram at
  `pg_trgm.similarity_threshold` 0.3 — catches typos of full-ish names) ∨
  `? <% search_text` (word similarity at `pg_trgm.word_similarity_threshold` 0.6
  — catches fragments and nicknames: verified empirically against the real
  dataset, "Mizzou" scores far below 0.3 on whole-string similarity and 1.0 on
  word similarity, so `%` alone cannot serve the alias case) ∨
  `search_text ILIKE '%'||?||'%'` (escaped — kept on merit for short fragments,
  per the approval-gate discussion; over the search text, so a short alias
  fragment also matches). All three arms range over the indexed expression, so
  the whole OR is served by `colleges_search_text_trgm_idx` (`gin_trgm_ops`
  supports `%`, `<%`, and `ILIKE`) — no arm forces a seq scan.
- **Thresholds are owned by this code, not inherited.** Both bounds are named
  DAO constants (`SIMILARITY_THRESHOLD` / `WORD_SIMILARITY_THRESHOLD`) applied
  with `SET LOCAL` in the same transaction as the query, so what search returns
  cannot drift with a server- or role-level `pg_trgm` default between dev, CI
  and RDS. `SET LOCAL` reverts at commit (nothing leaks onto the pooled
  connection) and the operators stay index-backed: the thresholds are read by
  the same `gin_trgm_ops` operators, never written into the predicate.
- **Ranking:** exact-prefix-of-name first (RFC 137 behaviour preserved), then
  `word_similarity(?, search_text)` DESC (chosen over `similarity()` for the
  same fragment reason), then
  `undergrad_enrollment DESC NULLS
  LAST, name, unit_id` as the deterministic
  tail.

Service contract (`CollegeSearchService.searchByName`) is unchanged: same
trim/blank/length boundary, same clamp, same `CollegeSummary` rows — the REST
route and iOS picker get fuzziness with zero API change.

### Honest counts, sorting, credential level

`CollegeQuery` gains three fields; `CollegesDao.search` implements them:

- `sortBy: SortBy = ENROLLMENT_DESC` — enum: `ENROLLMENT_DESC` (today's order,
  stays default), `ADMISSION_RATE_ASC`, `NET_PRICE_ASC`, `GRADUATION_RATE_DESC`,
  `NAME_ASC`. NULLS LAST always; `unit_id ASC` tiebreak always. (A sort never
  filters: rows NULL on the sort key sink, they do not vanish — brief 0004 D11.)
- `credentialLevel: CredentialLevel? = null` — a named enum
  (`CERTIFICATE`/`ASSOCIATE`/`BACHELORS`/`MASTERS`/`DOCTORAL`) owning its
  Scorecard CREDLEV code, like its sibling `sortBy`; the code appears only at
  the DAO bind (`p.credential_level = ?`), so no caller writes a bare `3` and an
  out-of-domain level is unrepresentable. It expresses "has a bachelor's
  literature program"; only meaningful with `cipPrefix`, validated accordingly
  (the rejection names the level that was passed).
- **`totalMatches`**: a companion `SELECT COUNT(DISTINCT c.id)` with the same
  FROM/WHERE (no LIMIT). Two statements on one connection; at ~6k rows the
  second costs microseconds and keeps the main query untouched.

`search` returns `CollegeSearchPage(matches, totalMatches)`; `CollegeMatch` is
unchanged. Fit-lens callers adapt trivially (`.matches`).

### Tool and REST surface

`search_colleges` (CollegeSearchTool):

- input gains `sort_by` (word enum:
  `"enrollment" | "admission_rate" |
  "net_price" | "graduation_rate" | "name"`)
  and `credential_level` (word enum:
  `"certificate" | "associate" | "bachelors" | "masters" | "doctoral"` →
  `CredentialLevel` at the boundary, which owns codes 1/2/3/5/7 — raw codes
  reach neither the LLM nor the tool, brief 0004 amendment). The `sort_by`
  vocabulary is derived from an exhaustive `when` over `SortBy`, so a new sort
  cannot be silently unmapped; either word enum rejects an unrecognized value by
  naming both the vocabulary and the word received.
- output: `count` (returned rows) is joined by **`total_matches`** (unclamped),
  so the coach can say "312 match; showing 25".

REST `GET /api/v1/colleges?q=` response shape unchanged.

**Interaction with RFC 143** (landed while this branch was open): that RFC's
guard flags any tool-result field carrying a bare number unless the field is
allowlisted as a number _by contract_, so that a leaked Scorecard code cannot
reach the model. `total_matches` is such a number — a count of colleges, like
the existing `count` — so this RFC extends that allowlist deliberately. The
guard firing on a new numeric field is the mechanism working, not a defect:
every future numeric field must be justified the same way.

### Ingest: header assertion, provenance, change summary

`CollegeScorecardLoader` per source file:

1. **Header assertion (fatal, before any write):** the required column set for
   each file is asserted against the CSV header; a missing column aborts the run
   with the missing names, exit non-zero, nothing written. The failure names the
   source **the caller gave** (the `--*-source` argument, e.g. the `s3://` URL),
   not the scratch basename it was downloaded to.
2. **sha256 + bytes** computed while streaming each file; recorded in
   `college_index_build.sources` with the original argument (path or `s3://`).
3. **Counts:** per-table inserted / changed / unchanged / skipped (the existing
   skip taxonomy), plus per-column non-null counts on `colleges` before and
   after — the change summary. A count the run did not measure is **omitted**
   from the row, never written as `0`: a provenance row must not report an
   absent measurement as a real one. The counted columns are checked against a
   closed DAO allowlist, since they are interpolated as SQL identifiers rather
   than bound.

`IngestApplication` wraps the run: on success inserts the `college_index_build`
row (`method_version = 1`) and prints a human summary, e.g.:

    ingest complete in 41.2s
    colleges: 6273 seen — 0 inserted, 12 changed, 6261 unchanged (12 version bumps)
    programs: 215059 seen — 214880 upserted, 179 skipped (…)
    aliases:  52 entries — 52 applied, 0 unknown unit_id
    non-null deltas: admission_rate 1904→1907, sat_avg 1035→1035, …
    sources: institution.csv sha256=ab12… (143 MB), fields.csv sha256=cd34… (17 MB)
    build row: 0198c0de-…

An unchanged snapshot re-ingest prints `0 changed` loudly — the silent no-op
becomes a visible no-op. Elapsed time carries a decimal, so a fast run reads as
`0.4s`, never `0s`; any unknown alias `unit_id`s are listed by value after their
count.

The run is four sequential transactions, so a failure in a later phase cannot
roll back an earlier one. That partial state is **reported**, not inferred: the
loader raises `PartialIngestException` naming the phases that committed, and
`IngestApplication` logs it as
`PARTIAL INGEST — phases […] COMMITTED, no
college_index_build row was written, provenance was NOT recorded`.
Row-level ingest stays per-row best-effort (RFC 67); this is reporting only.

`bin/ingest-colleges` gains a third optional arg for the aliases file,
defaulting to the repo's `db/data/college-aliases.json`; the JVM entry point
takes all three resolved paths as required positionals. Each source's ORIGINAL
caller argument (path or `s3://` URL) rides into the JVM as an explicit
`--institution-source=/--fields-source=/--aliases-source=` flag — argv, not an
environment side channel — so the provenance row records what the caller
actually named even when the script downloads a remote arg to scratch first. A
repeated flag, a flag with an empty value, or an explicitly-empty aliases
argument to `bin/ingest-colleges` is a usage error (non-zero exit), never
last-wins or silently defaulted: the provenance row would otherwise record
something the caller never asked for.

## Files Modified

- `db/schema/0051.enable-pg-trgm-and-college-aliases.sql` — new
- `db/schema/0052.create-college-index-build.sql` — new
- `db/data/college-aliases.json` — new (~50 curated entries)
- `db/src/main/kotlin/ed/unicoach/db/models/CollegeQuery.kt` — sortBy,
  credentialLevel
- `db/src/main/kotlin/ed/unicoach/db/models/CredentialLevel.kt` — new (the
  CREDLEV enum)
- `db/src/main/kotlin/ed/unicoach/db/models/NewCollegeIndexBuild.kt` — new (the
  build-row input; carries structured `JsonObject`/`JsonArray` payloads, which
  the DAO serializes at the JDBC edge)
- `db/src/main/kotlin/ed/unicoach/db/models/CollegeSearchPage.kt` — new
- `db/src/main/kotlin/ed/unicoach/db/models/College.kt` (+ mapper) — aliases
- `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt` — searchByName rewrite;
  search sortBy/credentialLevel/count; alias update; build-row insert
- `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt` —
  header assertion, sha256, counts, alias loading
- `college/src/main/kotlin/ed/unicoach/college/IngestApplication.kt` — summary,
  build row, aliases arg
- `college/src/main/kotlin/ed/unicoach/college/CollegeSearchService.kt` — page
  passthrough (boundaries unchanged)
- `college/src/main/kotlin/ed/unicoach/college/CollegeSearchTool.kt` — schema +
  total_matches + word-enum mapping
- `service/src/main/kotlin/ed/unicoach/coaching/fitlens/FitLensService.kt` —
  adapt to CollegeSearchPage
- `bin/ingest-colleges` — aliases arg
- Tests alongside each (see Tests)

## Implementation Plan

1. Migrations 0051 + 0052; regenerate local schema; verify `CREATE EXTENSION`
   against RDS-parity settings.
2. `db` model/DAO changes + DAO tests (trigram ranking, sortBy, count,
   credential level, alias update suppression).
3. Loader/ingest changes + tests (header assertion fatal path, sha256, summary,
   build row, aliases).
4. Tool schema + service passthrough + fit-lens adaptation + tests.
5. `bin/ingest-colleges` arg + `bin/` shell-test coverage where the harness
   already covers the script.
6. Full `nix develop -c bin/test`; real-data smoke via the pinned local CSVs if
   present.

## Tests

- **DAO:** "Amhurst Colege" finds Amherst College; "Mizzou" finds
  Missouri-Columbia via alias; exact-substring queries that matched before still
  match; prefix-first ranking preserved; sortBy orderings each verified with
  NULLS LAST; `credentialLevel` narrows the program join; `totalMatches` exceeds
  the returned slice when > limit matches; alias re-apply of an unchanged file
  writes no version bump; `searchByName` still finds a typo'd name with hostile
  session-level `pg_trgm` GUCs (its own thresholds win); `nonNullCounts` refuses
  a column outside the allowlist.
- **Loader:** CSV missing a required header → fatal (naming the caller's source
  argument), no rows written; sha256 recorded matches an independently computed
  digest; unchanged re-ingest yields all-unchanged counts and a build row whose
  `rows_ingested` says so; unknown alias unit_id named in both the build row and
  the summary, not fatal; every alias-file shape violation (unknown key, missing
  key, non-integer `unit_id`, non-string alias, non-array root, invalid JSON)
  refused by name and located; a failure after a phase has committed reports the
  committed phases and writes no build row; elapsed time prints with sub-second
  precision.
- **Tool:** `sort_by`/`credential_level` word-enum mapping; `total_matches`
  present and unclamped; existing fit-lens forced-tool tests pass unchanged.
- **REST:** `GET /api/v1/colleges?q=Amhurst` returns Amherst (route contract
  otherwise byte-identical).
