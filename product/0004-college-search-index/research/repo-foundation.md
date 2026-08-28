# Research: repo foundation for college search

**Method.** Read the schema migrations, `:db` DAO, `:college` module,
REST/admin/iOS callers, `queue`/`cron`, `flake.nix`, `infra/`, and `rfc/` at
`HEAD` of `/Users/ian/Work/unicoach`. Row counts and null fractions are
**measured** against the local dev database via `nix develop -c bin/db-query`
(PostgreSQL 18.3). Caveat: that DB is a developer machine's ingest of an
unrecorded Scorecard snapshot — the repo pins no snapshot version anywhere I
could find, so treat the counts as representative of a full ingest, not as a
guarantee about production.

---

## 1. What college data exists today

Three tables. `colleges` (`db/schema/0015.create-colleges.sql:34`) + `version`
(`db/schema/0023.version-colleges.sql:103`) + six income-band columns
(`db/schema/0045.add-college-income-band-net-price.sql:23`):

`id, unit_id, opeid, name, city, state, region, locale, latitude, longitude, control,
undergrad_enrollment, admission_rate, sat_avg, cost_attendance, net_price,
tuition_in_state, tuition_out_state, graduation_rate, median_earnings, pct_pell,
website, created_at, updated_at, version, net_price_q1..q5, median_debt`.

`colleges_versions` (`db/schema/0023.version-colleges.sql:115`, extended at
`0045:33`) carries exactly the same curated columns plus `version`,
`created_at`, `updated_at`; PK is `(id, version)`, FK
`id → colleges(id) ON DELETE RESTRICT`, and there is no secondary index (the PK
prefix serves `WHERE id = ? ORDER BY version`). It holds **no** column
`colleges` lacks.

`college_programs` (`db/schema/0015.create-colleges.sql:105`):
`id, college_id,
cip_code, cip_title, credential_level, created_at, updated_at`.
Unique on `(college_id, cip_code, credential_level)` (`:122`).

**Measured (local dev DB):** 6,273 colleges; 6,273 `colleges_versions` rows
(i.e. every row still at `version = 1` — no second ingest has ever changed a row
here); 215,059 `college_programs` rows across 420 distinct CIP codes. By
control: 2,047 public / 1,901 private nonprofit / 2,325 for-profit. Programs by
`credential_level`: L1 41,357 (4,345 schools), L2 40,736, **L3 (bachelor's)
68,634 across 2,416 schools**, L4 1,181, L5 35,323, L6 11,902, L7 2,227, L8
13,699. Only 3,861 of 215,059 program rows end in `00`, so the CIP codes are
genuinely 6-digit, not 4-digit rollups.

**Non-null coverage of facet columns (n = 6,273):**

| column                                  | non-null | column            | non-null  |
| --------------------------------------- | -------- | ----------------- | --------- |
| `state`, `control`, `region`, `website` | 100.0%   | `net_price`       | 80.2%     |
| `locale`                                | 91.3%    | `net_price_q1`    | 78.8%     |
| `latitude`/`longitude`                  | 91.4%    | `median_debt`     | 76.2%     |
| `undergrad_enrollment`                  | 87.3%    | `cost_attendance` | 51.0%     |
| `pct_pell`                              | 86.8%    | `graduation_rate` | **36.1%** |
| `median_earnings`                       | 81.7%    | `admission_rate`  | **30.4%** |
|                                         |          | `sat_avg`         | **16.5%** |

That is the single most important number in this report: **selectivity is
missing for ~70% of rows and test scores for ~84%**. Any ranking that treats
NULL as "unknown but filterable" will silently drop most of the catalog; today's
filters do exactly that (`c.admission_rate >= ?` excludes NULLs).

## 2. Every existing search surface

| surface                     | file                                                                                     | what it does                                                                                                                              |
| --------------------------- | ---------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `CollegesDao.search`        | `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt:427`                               | The structured facet search                                                                                                               |
| `CollegesDao.searchByName`  | `…/CollegesDao.kt:534`                                                                   | Name substring search                                                                                                                     |
| `CollegeSearchService`      | `college/src/main/kotlin/ed/unicoach/college/CollegeSearchService.kt:25,39`              | Connection boundary + `limit` clamp `1..25` (`:55`)                                                                                       |
| `search_colleges` chat tool | `college/…/CollegeSearchTool.kt:36`                                                      | JSON-Schema tool over `search`; wired into chat by `service/…/CollegeChatTool.kt:14` and registered at `rest-server/…/Application.kt:312` |
| fit-lens                    | `service/…/fitlens/FitLensService.kt:268`                                                | Calls `collegeSearchService.search(...)` with an LLM-authored query                                                                       |
| `GET /api/v1/colleges?q=`   | `rest-server/…/routing/CollegeRoutes.kt:36,46`                                           | Auth'd, no profile gate; `q` required, ≤100 chars; `limit` default 20 (`:99`)                                                             |
| iOS picker                  | `ios-app/UnicoachiOS/CollegeListClient.swift:69`                                         | The only iOS caller — add-college name picker                                                                                             |
| admin                       | `admin-web/…/resources/CollegesResource.kt:29,119`                                       | Read-only list ordered by `name, unit_id` + version-history panel. **No search box, no filters**                                          |
| cost / list tools           | `service/…/costs/CollegeCostChatTool.kt`, `service/…/collegelist/CollegeListChatTool.kt` | Operate on already-chosen college ids; not search                                                                                         |

**Exactly what exists, and its limits.**

`search` (`CollegesDao.kt:427-522`) accepts `CollegeQuery`
(`db/…/models/CollegeQuery.kt:9-22`):
`cipPrefix, states, region, locales, control,
min/maxUndergradEnrollment, min/maxAdmissionRate, maxNetPrice, minGraduationRate,
limit`.
All hard AND-filters; list fields are OR-sets. Program filtering is a
`JOIN college_programs p … p.cip_code LIKE ? || '%'` (`:436`) — prefix only,
**no credential-level filter**, so "has a literature program" cannot be
restricted to bachelor's. There is **no relevance ranking at all**:
`ORDER BY
undergrad_enrollment DESC NULLS LAST, unit_id ASC` (`:509`) — the
biggest school matching the filters wins, always. No text search over
`name`/`cip_title`, no similarity, no scoring, no pagination (LIMIT only, ≤25),
no facet on `median_earnings`, `median_debt`, `sat_avg`, `pct_pell`,
`cost_attendance`, tuition, or the income bands (they are _returned_ but not
_filterable_).

`searchByName` (`:534-557`) is `name ILIKE '%' || ? || '%'` with LIKE
metacharacters escaped (`:566`), ordered prefix-first then enrollment then
`name, unit_id`. No index serves a leading-wildcard ILIKE — it is a **sequential
scan of 6,273 rows** every call. No trigram/fuzzy matching, so "UMass Amherst",
"Mizzou", or a typo returns nothing; no alias/nickname table exists.

## 3. The ingest pipeline

`bin/ingest-colleges <institution.csv> <fields.csv>`
(`bin/ingest-colleges:43-55`) resolves each arg through `resolve_file_arg`
(`bin/functions:219`) — local path **or `s3://` URI** — and execs the prebuilt
dist at `$COLLEGE_DIST/bin/college` (`bin/functions:217`), never Gradle.
`bin/build-college` produces that dist. `IngestApplication.main`
(`college/…/IngestApplication.kt:19`) validates both files, loads DB config,
runs `CollegeScorecardLoader(database).load(...)` and logs
`colleges/programs/transient_skips/permanent_skips/skips_by_reason/fields_coerced_to_null`.

Source: College Scorecard "Most Recent" institution-level + field-of-study CSVs
(https://collegescorecard.ed.gov/data/,
https://collegescorecard.ed.gov/data/data-documentation/). The dataset file is
deliberately **not committed** (RFC 67 `rfc/67-college-knowledge.md:213-217`);
tests run against small fixtures. **I could not find any recorded snapshot
version, URL, checksum, or date anywhere in the repo** — "version-pinned" is a
convention, not an artifact. That is a real gap for a durable index.

Semantics: per-row best-effort upsert, not all-or-nothing
(`CollegeScorecardLoader.kt:20-30`); one connection per file (`:159,:192`), no
transaction spanning the load. `CollegesDao.upsert` (`:185`) is
`ON CONFLICT (unit_id)
DO UPDATE … WHERE (<26 curated cols>, unit_id) IS DISTINCT FROM (EXCLUDED…)`
with `version = colleges.version + 1`, plus a `UNION ALL` arm returning the
unchanged row. So an unchanged re-ingest writes nothing, bumps nothing, and —
because Postgres does not fire AFTER triggers for a suppressed update — logs no
history row (`db/schema/0023.version-colleges.sql:183-189`). `upsertProgram`
(`:297`) overwrites `cip_title` only and is **unversioned**. Deletes are blocked
outright (`trigger_00_prevent_colleges_delete`, `0023:169`), and **stale rows
are never removed** (RFC 67: "stale-row removal on re-ingest is out of scope").
A closed school stays forever.

**Interaction with a derived index.** A re-ingest touches only _changed_ rows,
and each one leaves a marker: `updated_at` advances and `version` increments. A
denormalised index can therefore be refreshed **incrementally** off
`updated_at > watermark` — cheap and precise. Two traps: (a) `college_programs`
upserts do **not** bump the parent college's `updated_at`, so a program-derived
index needs `college_programs.updated_at` watched separately; (b) rows that
vanish from a newer snapshot are never deleted, so no "disappeared" signal
exists to invalidate an index entry.

## 4. Queue / periodic-jobs machinery

`jobs` + `job_attempts` (`db/schema/0003.create-queue.sql:1,25`): `job_type`
TEXT (app-validated), JSONB `payload` ≤64 KiB, statuses
`SCHEDULED/RUNNING/COMPLETED/
DEAD_LETTERED`, `locked_until`, per-job
`max_attempts` override. Per-type config — concurrency, `maxAttempts` (default
3), backoff, lock/execution timeouts — is `queue/…/JobTypeConfig.kt:9-10`,
applied at `QueueWorker.kt:247-252`. `JobType` is a Kotlin enum
(`queue/…/JobType.kt:3`), so a new kind is one enum entry + one `JobHandler`

- registration in `queue-worker/…/Application.kt`.

`periodic_jobs` (`db/schema/0029.create-periodic-jobs.sql`): `name` PK,
`job_type`, `payload`, 5-field cron `schedule`, `timezone`, `next_run_at` (the
claim key, partial index `WHERE enabled`), `last_run_at`, `enabled`. The `:cron`
process (`cron/…/PeriodicScheduler.kt`) claims due rows once a minute and
enqueues an ordinary job. Pattern to copy: synthesis `'0 3 * * *'` and fit-lens
`'0 4 * * 1'`, both seeded `enabled = FALSE` (`db/schema/0030…:13-17`,
`0033…:15-19`) so a fresh environment never fires them. Sweep handlers use
`maxAttempts = 1` because the next tick reproduces the sweep, and
`executionTimeout < lockDuration`
(`service/…/fitlens/FitLensSweepHandler.kt:23-43`). Idempotency is by
construction (an upsert / a re-derivable sweep), not by a dedupe key — the queue
is at-least-once.

`ASYNC_WORK.md` binds any maintenance job: async-capable work **defaults to the
queue** and must not run inline on a request coroutine; and a _required_ enqueue
must happen **inside the request's transaction**, with the request failing if
the enqueue fails. An index-refresh job triggered by a request would have to
obey the second rule; a cron-driven refresh only the first.

## 5. House schema conventions

- Migrations are append-only, `NNNN.kebab-case-name.sql`, applied in
  lexicographic order by `bin/db-migrate`, **never edited after applying**
  (`db/schema/INVARIANTS.md:9-18`). Next file is `0049.`.
- Two table classes. _Reference_ tables (externally sourced, mutated only by
  re-ingest): no OCC, no soft delete, logical `created_at`/`updated_at` only,
  plain updated_at trigger (`0015…:1-16`). _Entities_: `uuidv7()` PK,
  `version` + `enforce_versioning()`
  - `_versions` history, `row_created_at`/`row_updated_at` physical split, soft
    delete. `colleges` is the documented hybrid — a versioned reference entity
    composed à la carte (`0023…:1-20`, RFC 84).
- **Trigger slots by name**, since BEFORE triggers fire in name order: `_00`
  delete-guard, `_00a` immutable-guard, `_00b` physical-timestamp guard, `_01`
  versioning, `_03` enforce-`updated_at`, `_04` history writer
  (`0023…:163-190`).
- Naming: `<table>_<column>_<rule>_check` constraints, `<table>_<cols>_idx` /
  `_unique_idx` indexes, snake_case columns. Every nullable numeric carries an
  explicit range/nonneg CHECK unless the source legitimately publishes
  out-of-range values (`0022`, `0045:11-18`).
- Bulk data never lives in a migration; only small seed rows (RFC 67
  `:207-212`).
- **Postgres 18** locally (`flake.nix:25-27`, `pkgs.postgresql_18`; measured
  18.3) and in production (RDS `engine = "postgres"`, `db_engine_version`
  default `"18"` — `infra/rds.tf:20-21`, `infra/variables.tf:45-49`). `uuidv7()`
  is used unqualified, i.e. the PG18 built-in, no extension.
- **Extensions: only `plpgsql` is installed.** Available but not enabled in the
  dev shell's server: `pg_trgm 1.6`, `unaccent 1.1`, `fuzzystrmatch 1.2`,
  `btree_gin 1.3`, `cube 1.5`, `earthdistance 1.2`. **`pgvector` is NOT
  available** — it is not in the nix Postgres package, and RDS would need
  `shared_preload`/parameter-group work plus a `CREATE EXTENSION`. Postgres's
  built-in `tsvector`/GIN full-text search needs **no** extension and is the
  zero-dependency option; trigram fuzzy matching needs one
  `CREATE EXTENSION pg_trgm` in a migration (works on RDS, but it is a new class
  of dependency for this repo).

## 6. Relevant RFCs, and the next free number

| RFC          | one line                                                                                                                                                          |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 67           | College Knowledge — creates `colleges`/`college_programs`, the loader, `CollegeQuery`/`search_colleges`; explicitly rejects pgvector and defers relevance ranking |
| 78           | Hardens Scorecard ingestion against real-data quirks (`UNITID=NA`, `CREDLEV=99`, negative net price)                                                              |
| 82           | Versioned colleges + read-only admin browsing (`colleges_versions`, content-change-only version bump)                                                             |
| 84           | Entity version composition — the à-la-carte trigger/versioning rules a new table must follow                                                                      |
| 91           | College List — the per-student saved list that consumes college ids                                                                                               |
| 93 / 97 / 98 | Synthesis; periodic-task infrastructure (`periodic_jobs` + `:cron`); fit-lens — the three sweep/handler patterns to copy                                          |
| 133          | Income-band net price + median debt on `colleges`                                                                                                                 |
| 134 / 135    | Money profile; "Know your real price" — the cost read that joins colleges back to list entries                                                                    |
| 136 / 137    | `update_college_list` chat tool; iOS college-list screen, which added `GET /api/v1/colleges?q=` and `searchByName`                                                |
| 138          | `bin/state-apply` (declarative test state) — highest committed RFC                                                                                                |

I found **no** RFC covering full-text search, similarity/"colleges like X",
ranking, or index freshness. **Next free RFC number: 139.**

## Honest gaps

- Counts are from one developer machine's DB, not production; I did not query
  production.
- No snapshot provenance is recorded in-repo, so I cannot say _which_ Scorecard
  release the 6,273 rows are.
- I did not benchmark `searchByName`; "sequential scan" is inferred from the
  leading `%` and the absence of any text index in `0015`, not from an
  `EXPLAIN`.
- I did not verify that `pg_trgm` can actually be created on the deployed RDS
  instance (it is on the AWS-supported list, but I did not test it).
