# SPEC: `college/src/main/kotlin/ed/unicoach/college`

## I. Overview

This package is the **college-knowledge capability layer** (RFC 67): grounded
retrieval over a curated subset of the U.S. Department of Education's College
Scorecard dataset, plus the operational ingester that loads it. It exists so the
coach reasons over schools that actually exist rather than inventing them.

It owns four deliverables, all built on the `db` module's `colleges` /
`college_programs` reference tables and `CollegesDao`:

| Type                       | Role                                                                            |
| -------------------------- | ------------------------------------------------------------------------------- |
| `CollegeSearchService`     | Connection-owning orchestrator: clamps the result cap and delegates to the DAO. |
| `CollegeSearchTool`        | The Anthropic tool contract — a JSON `definition` plus a total `execute`.       |
| `CollegeScorecardLoader`   | Re-runnable, best-effort CSV → DB upsert ingester.                              |
| `IngestApplication` (main) | The `fun main` operational entry that wires config + DB + loader.               |

The module is a Gradle library + `application` entry depending only on `:common`
and `:db`. **All SQL lives in the `db` module's `CollegesDao`**; this module
orchestrates connections, applies the result-cap clamp, and adapts to the chat
tool wire shape. It carries no Ktor/HTTP/REST dependency and no chat-module
dependency.

### Scope boundary: tool contract now, chat tool-use loop deferred

This module produces a **registerable** tool contract (`CollegeSearchTool`), but
nothing wires it into a chat turn — the repository has no tool-use loop to
extend. `execute` is deliberately pure and total so a follow-up agentic-loop RFC
is pure wiring. Nothing in this package touches the convo turn path.

---

## II. Behavioral Contracts

### `CollegeSearchService` — [`CollegeSearchService.kt`](./CollegeSearchService.kt)

`class CollegeSearchService(private val database: Database)` — constructor-DI
sibling of `CoachingService`.

#### `suspend search(query: CollegeQuery): Result<List<CollegeMatch>>`

- **Side Effects**: Read only — clamps `query.limit` into `MIN_LIMIT..MAX_LIMIT`
  via `coerceIn` (rewriting the query with `copy`), then runs
  `database.withConnection { CollegesDao.search(it, clamped) }`.
- **Error Handling**: Propagates the DAO `Result`. A zero-match query is
  `success(emptyList())`, not a failure.
- **Idempotency**: Yes.
- **Constants**: `MIN_LIMIT = 1`, `MAX_LIMIT = 25`.

---

### `CollegeSearchTool` — [`CollegeSearchTool.kt`](./CollegeSearchTool.kt)

`class CollegeSearchTool(private val service: CollegeSearchService)` — the chat
tool contract. Mirrors the `definition` + total-`call` shape an MCP server's
`list_tools`/`call_tool` expose, deliberately, so a later RFC could wrap this
exact `execute` in an MCP server with no rework — but it is a **native,
in-process tool, not an MCP server**.

#### `val definition: JsonObject`

- The Anthropic tool spec: `name = "search_colleges"`, a `description`
  enumerating the supported filters and explicitly stating it CANNOT reason
  about geographic distance/coastline, and an `input_schema` (JSON Schema
  `object`) whose `properties` mirror `CollegeQuery` (`cipPrefix`, `states`,
  `region`, `locales`, `control`, `minUndergradEnrollment`,
  `maxUndergradEnrollment`, `minAdmissionRate`, `maxAdmissionRate`,
  `maxNetPrice`, `minGraduationRate`, `limit`). `required` is empty — every
  field is optional, and the result cap is enforced server-side.

#### `suspend execute(input: JsonObject): JsonObject`

- **Side Effects**: Parses `input` into a `CollegeQuery` (via the total
  `parseQuery`), runs `service.search`, and serializes the matches.
- **Behavior**: On success returns
  `{ "colleges": [ { name, city, state, control, undergrad_enrollment,
  admission_rate, net_price, graduation_rate, median_earnings, pct_pell,
  programs: [cip_title...] } ], "count": n }`.
  Absent numeric fields serialize as JSON `null` (via `putOrNull`). The query's
  surfaced fields are the curated context columns plus the matched program
  titles — never the full `College`.
- **Input parsing** (total — never throws):
  - Unknown keys (outside `KNOWN_FIELDS`) → error.
  - Per-field type checks: a string-typed JSON value where an int/number is
    expected is rejected (and vice versa).
  - `cipPrefix` MUST match `^([0-9]{2}|[0-9]{4}|[0-9]{6})$` (2/4/6 digits).
  - `states` MUST be 2-letter codes; they are UPPERCASED before binding (the
    stored `STABBR` is uppercase and the SQL `IN` is case-sensitive, so a lower-
    case `"ca"` from the LLM still matches a `"CA"` row).
  - Range guards: `region` 0–9; `locales` 11–43; `control` 1–3;
    `min/maxUndergradEnrollment` ≥ 0; `min/maxAdmissionRate` and
    `minGraduationRate` 0.0–1.0; `maxNetPrice` ≥ 0.
  - An omitted `limit` defaults to `DEFAULT_LIMIT` (10); the service still
    clamps the final value.
- **Error Handling**:
  - Malformed input → `{ "error": "<reason>" }` (a precise validation string).
  - A search-time DAO failure → a structured object
    `{ "error": { "kind": "search_failed", "category": <transient|permanent|
    unknown>, "transient": <bool>, "detail": ..., "cause"?: ... } }`
    built using `errorCategory()` from `ed.unicoach.error`, so the consumer can
    branch on retryability rather than re-parse a flat string.
  - Zero matches → `{ "colleges": [], "count": 0 }`.
- **Idempotency**: Yes (the underlying search is read-only).
- **Constants**: `TOOL_NAME = "search_colleges"`, `DEFAULT_LIMIT = 10`,
  `MIN_LIMIT`/`MAX_LIMIT` re-exported from `CollegeSearchService`.

---

### `CollegeScorecardLoader` — [`CollegeScorecardLoader.kt`](./CollegeScorecardLoader.kt)

`class CollegeScorecardLoader(private val database: Database)` — the re-runnable
ingester for a version-pinned College Scorecard CSV pair (the institution-level
file and the field-of-study file). Uses Apache Commons CSV.

The loader applies three disposition mechanisms to each row before any DB work:

- **Mechanism A** — per-field coercion for optional metrics: a parsed value that
  falls outside the column's expected domain is coerced to `NULL` and the
  coercion is counted by column name; the row is otherwise kept and the DB CHECK
  is the backstop for anything the coercion misses. Applies to `region`,
  `locale`, `undergrad_enrollment`, `admission_rate`, `sat_avg`,
  `cost_attendance`, `tuition_in_state`, `tuition_out_state`, `graduation_rate`,
  `median_earnings`, and `pct_pell`.
- **Mechanism B** — pre-filter for known source sentinels in required/key
  columns: `UNITID=NA` (non-IPEDS field-of-study rows) and `CREDLEV` outside
  `1..8` (including the `99` "Non-Credential Program" sentinel) are skipped
  before any DB round-trip and bucketed under their own `SkipReason`.
- **Mechanism C** — post-upsert failure bucketing: `TransientError` →
  `SkipReason.Transient`; `ConstraintViolationException` (keyed by constraint
  name) → `SkipReason.ConstraintViolation`; other `PermanentError` → unnamed
  `SkipReason.ConstraintViolation`; null or unclassifiable →
  `SkipReason.UnknownFailure`.

#### `sealed interface SkipReason`

The structured skip taxonomy. All skip counts flow through these buckets so no
row is silently lost:

| Bucket                                     | Meaning                                                                           |
| ------------------------------------------ | --------------------------------------------------------------------------------- |
| `MissingRequiredField(missingFields)`      | One or more required/key columns were absent or blank; `missingFields` names them |
| `NoCollegeForUnitId`                       | Program row's `UNITID` resolved to no loaded college                              |
| `UnitIdNa`                                 | Field-of-study `UNITID=NA` sentinel (non-IPEDS institution)                       |
| `CredentialLevelOutOfDomain`               | `CREDLEV` outside `1..8` (including `99`)                                         |
| `ConstraintViolation(constraint: String?)` | DB CHECK/unique violation, keyed by the violated constraint name (null if absent) |
| `Transient`                                | Retryable DB fault                                                                |
| `UnknownFailure`                           | Null or otherwise-unclassifiable upsert failure                                   |

#### `data class LoadResult`

Per-load summary returned by `load`:

```
LoadResult(
  collegesLoaded: Int,
  programsLoaded: Int,
  skipsByReason: Map<SkipReason, Int>,   // structured skip counts
  fieldsCoercedToNull: Map<String, Int>, // mechanism-A coercion counts by column name
)
```

`transientSkips` and `permanentSkips` are derived transient properties for
backward compatibility: `transientSkips = skipsByReason[Transient] ?: 0`;
`permanentSkips` = sum of all non-Transient skip counts.

Coercions are tallied for every mapped row whose value reaches the upsert (not
for rows that are skipped at the mapping stage). A DB upsert failure discards
the coercions for that row — the count reflects successfully-persisted rows
only.

#### `sealed interface MapResult<T>` (private)

The pure-mapper return type. `Mapped(value, coercions)` carries the domain value
to upsert and the mechanism-A cells coerced on this row; `Skipped(reason)`
carries the skip bucket. The load loop — not the mapper — folds this into the
accumulator, so mappers contain no side effects.

#### `suspend load(institutionCsv: File, fieldsCsv: File): LoadResult`

- **Side Effects**: Upserts institutions first, then programs (a program
  resolves its owning college by `UNITID`, so colleges must exist first). Each
  file's rows are processed inside a single `database.withConnection`
  transaction with a per-row savepoint.
- **Behavior**: Institution rows go through `mapInstitution`; field-of-study
  rows go through `mapField`. Required/key fields for institutions are `UNITID`,
  `INSTNM`, `CITY`, `STABBR`, `CONTROL`; for programs, `UNITID`, `CIPCODE`,
  `CIPDESC`, `CREDLEV`. A row missing any of these returns
  `MapResult.Skipped(MissingRequiredField(...))`. The `OPEID` column is read
  directly (column name `OPEID`, not `OPEID8`; prior to RFC 78, the nonexistent
  `OPEID8` was read, causing `opeid` to always be null).
  - **Net-price coalesce**: `net_price` reads `NPT4_PUB` when `control == 1`,
    else `NPT4_PRIV`; both blank → `null`. Net price is excluded from
    mechanism-A domain coercion because negative values are legitimate
    (migration `0022`).
  - **Program-file sentinel handling (Mechanism B)**: `UNITID=NA` rows are
    detected by string comparison before integer parsing and bucketed as
    `UnitIdNa`. `CREDLEV` outside `1..8` (including `99`) is bucketed as
    `CredentialLevelOutOfDomain` after a successful integer parse, skipping the
    row before the owning-college DB read.
  - A program's owning college is resolved by `resolveCollege`, which calls
    `CollegesDao.findByUnitId` before the savepoint. A DB fault on that read is
    classified and logged like a mechanism-C upsert failure (never mislabeled
    `NoCollegeForUnitId`). An absent college is counted as `NoCollegeForUnitId`.
- **Error Handling** (Mechanism C): A row whose upsert returns a failed `Result`
  is rolled back to its savepoint, classified by `classifyUpsertFailure`, and
  counted. `classifyUpsertFailure` is `internal` and pure (no side effects):
  - `null` error → `UnknownFailure`
  - `TransientError` → `Transient`
  - `ConstraintViolationException` → `ConstraintViolation(error.constraint)`
    (the constraint name from `PSQLException.serverErrorMessage`, or null)
  - other `PermanentError` → `ConstraintViolation(null)` (permanent but no
    constraint name — covers non-PSQL permanent exceptions)
  - everything else → `UnknownFailure`

  Per-row skip logging uses DEBUG (non-transient) or WARN (transient). The log
  line carries the row's natural key, line number, constraint name, and server
  DETAIL from `ConstraintViolationException.constraint`/`.detail` when present.
- **Returns**: `LoadResult` aggregated across both files via `mergeCounts`.
- **Idempotency**: Yes — re-running upserts the same snapshot with no
  duplicates.

#### `upsertWithSavepoint(session, upsert): Result<T>` (private)

Issues `SAVEPOINT scorecard_row`, runs the upsert, then `RELEASE SAVEPOINT` on
success or `ROLLBACK TO SAVEPOINT` on a failed `Result`, leaving the transaction
usable for the next row. Without this savepoint discipline, the first failed
statement aborts the enclosing transaction (SQLSTATE `25P02`), every subsequent
row would falsely "skip", and the terminal commit would discard the good rows.

#### Log summaries

`logSummary` emits an INFO line per file with the total loaded count, the full
`skipsByReason` map, and the full `fieldsCoercedToNull` map, giving operators
visibility into real-data quirks without requiring DEBUG logging.

---

### `IngestApplication` — [`IngestApplication.kt`](./IngestApplication.kt)

`fun main(args: Array<String>)` — the operational entry, invoked via
`bin/ingest-colleges <institution.csv> <fields.csv>`.

- **Behavior**: Requires exactly two positional CSV path arguments (else exits
  `2`); validates both are existing files (else exits `2`); loads config via
  `AppConfig.load("common.conf", "db.conf")` (the two `.conf` files from the
  `:common`/`:db` classpath deps — no new `college.conf`); builds a `Database`,
  runs the loader in `runBlocking`, and logs the counts and skip/coercion maps.
  A non-zero `transientSkips` emits a follow-up warning that re-running may
  recover them.
- **Lifecycle**: Closes the `Database` in a `finally`.
- **Logging**: Ships no `logback.xml` of its own — inherits Logback's default
  console config (like `queue-worker`'s application entry), so the summaries
  print to the console.

---

## III. Infrastructure & Environment

- **Module**: `college` Gradle module (`college/build.gradle.kts`) —
  `kotlin.jvm`
  - `ktlint` + `application` plugins.
    `mainClass = "ed.unicoach.college.IngestApplicationKt"`.
- **Dependencies**: `:common` (config, `errorCategory()`), `:db` (`Database`,
  `SqlSession`, `CollegesDao`, the college models),
  `org.apache.commons:commons-csv` (loader only), `kotlinx-coroutines-core`,
  `slf4j-api`; `logback-classic` at runtime; `kotlin-test-junit5` +
  `kotlinx-coroutines-test` for tests. Reuses `kotlinx-serialization-json`
  (transitively, for the tool JSON).
- **No new runtime config keys**: the loader takes CSV paths as CLI args and
  reuses the existing DB config; the search service needs only `Database`.
- **Operational command**: `bin/ingest-colleges <institution.csv> <fields.csv>`
  forwards to `:college:run --args`. It runs inside the nix dev shell
  (`nix develop -c`); a bare invocation fails at `gradlew` with no JVM on PATH.
- **Tests**: DB-backed JUnit 5 under `nix develop -c bin/test college`, with CSV
  fixtures under `college/src/test/resources/`. Two test suites share a base
  class `CollegeScorecardTestBase` (one pooled `Database`, per-test
  `TRUNCATE colleges, college_programs CASCADE`):
  - `CollegeScorecardLoaderTest` — synthetic fixtures exercising skip/coercion
    paths.
  - `CollegeScorecardRealDataTest` — verbatim real Scorecard rows exercising the
    specific quirks RFC 78 hardens against: negative net price, out-of-domain
    `LOCALE`, the real `OPEID` column, 4-digit and 6-digit CIP codes, quoted
    embedded commas in `CIPDESC`, `CREDLEV=99`, and `UNITID=NA`.

---

## IV. History

- [x] [RFC-67: College Knowledge](../../../../../../../rfc/67-college-knowledge.md)
      — Introduced the `college` module: `CollegeSearchService` (clamp +
      delegate), `CollegeSearchTool` (Anthropic `definition` + total `execute`),
      the re-runnable best-effort `CollegeScorecardLoader` (per-row savepoint,
      transient/permanent skip split), and the `IngestApplication` entry. Backed
      by the new `colleges`/`college_programs` reference tables and
      `CollegesDao` in the `db` module.
- [x] [RFC-78: College Scorecard Real-Data Hardening](../../../../../../../rfc/78-college-scorecard-real-data-hardening.md)
      — Refactored `CollegeScorecardLoader` substantially: introduced the
      `SkipReason` sealed taxonomy (including `UnitIdNa`,
      `CredentialLevelOutOfDomain`, `ConstraintViolation(constraint)`,
      `UnknownFailure`, and `MissingRequiredField(missingFields)`); the
      `MapResult` sealed type and pure `mapInstitution`/`mapField` row mappers
      that return `Mapped`/`Skipped` without mutating the accumulator;
      mechanism-A per-field coercion via `intInDomainOrNull` /
      `doubleInDomainOrNull` + `logCoercion`; mechanism-B pre-filters for
      `UNITID=NA` and `CREDLEV` outside `1..8`; `classifyUpsertFailure` for
      structured mechanism-C bucketing; `resolveCollege` extracting the
      owning-college DB read into a named function; the `LoadResult` surface now
      carrying `skipsByReason` / `fieldsCoercedToNull` plus derived
      `transientSkips`/`permanentSkips`. Fixed `OPEID8` → `OPEID` column name.
      Added per-file `logSummary` and demoted per-row skip lines from WARN to
      DEBUG (transient stays WARN). Added `CollegeScorecardTestBase` shared test
      infrastructure and `CollegeScorecardRealDataTest` real-data test harness.
