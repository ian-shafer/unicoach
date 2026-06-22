# SPEC: `college/src/main/kotlin/ed/unicoach/college`

## I. Overview

This package is the **college-knowledge capability layer** (RFC 67): grounded
retrieval over a curated subset of the U.S. Department of Education's College
Scorecard dataset, plus the operational ingester that loads it. It exists so the
coach reasons over schools that actually exist rather than inventing them.

It owns three deliverables, all built on the `db` module's `colleges` /
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

## II. Invariants

### General

- This module MUST NOT contain SQL. Every read/write goes through the `db`
  module's `CollegesDao` via `Database.withConnection`; this layer owns no raw
  `java.sql.Connection`.
- This module MUST NOT depend on the `chat` module. The tool contract speaks
  plain `kotlinx.serialization.json.JsonObject` on both ends so it stays
  registerable without coupling to the (future) turn path.
- The tool description MUST NOT claim a geographic-proximity / distance / "near
  the ocean" capability the Scorecard data lacks. There is no coast-distance
  field; the LLM approximates proximity by passing a coastal-`state` set.

### Result cap

- `CollegeSearchService` is the SOLE clamp point for the result cap: it coerces
  the query's `limit` into `MIN_LIMIT..MAX_LIMIT` (`1..25`) before the DAO sees
  it. The DAO and tool layers trust the clamp; the tool only advertises the cap
  in its schema and supplies `DEFAULT_LIMIT` (10) when the caller omits `limit`.

### Tool totality

- `CollegeSearchTool.execute` MUST be total — it MUST NOT throw into the
  (future) turn loop. Malformed input (unknown field, wrong type, non-digit
  `cipPrefix`, out-of-range coded value) returns a structured `{ "error": ... }`
  object; a search-time DAO failure returns a structured error object carrying a
  retryability category; and a zero-match query returns
  `{ "colleges": [], "count": 0 }` — an empty result is a valid domain outcome,
  never an error.

### Loader best-effort

- The loader MUST be best-effort over the dataset, not all-or-nothing: a single
  malformed CSV row (missing required field, or whose upsert fails with any
  `DaoException`) MUST be logged with a bracketed warning and skipped so it
  never loses the rest of the snapshot.
- The loader MUST be idempotent: a re-run re-applies the same snapshot with no
  duplicates, relying on the DAO's upsert on the natural keys (`unit_id`;
  `(college_id, cip_code, credential_level)`).
- Each row's upsert MUST be wrapped in its own SQL `SAVEPOINT` (see
  `upsertWithSavepoint`). Without it, the first failed statement aborts the
  enclosing transaction (SQLSTATE `25P02`), every subsequent row would falsely
  "skip", and the terminal commit would discard the good rows. This savepoint
  discipline lives in the loader, not the DAO.

---

## III. Behavioral Contracts

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
    unknown>, "transient": <bool>, "detail": ..., "cause"?: ... } }`,
    so the consumer can branch on retryability rather than re-parse a flat
    string.
  - Zero matches → `{ "colleges": [], "count": 0 }`.
- **Idempotency**: Yes (the underlying search is read-only).
- **Constants**: `TOOL_NAME = "search_colleges"`, `DEFAULT_LIMIT = 10`,
  `MIN_LIMIT`/`MAX_LIMIT` re-exported from `CollegeSearchService`.

---

### `CollegeScorecardLoader` — [`CollegeScorecardLoader.kt`](./CollegeScorecardLoader.kt)

`class CollegeScorecardLoader(private val database: Database)` — the re-runnable
ingester for a version-pinned College Scorecard CSV pair (the institution-level
file and the field-of-study file). Uses Apache Commons CSV.

#### `suspend load(institutionCsv: File, fieldsCsv: File): LoadResult`

- **Side Effects**: Upserts institutions first, then programs (a program
  resolves its owning college by `UNITID`, so colleges must exist first). Each
  file's rows are processed inside a single `database.withConnection`
  transaction with a per-row savepoint.
- **Behavior**:
  - `mapInstitution` / `mapField` coerce CSV cells: a trimmed cell, or `null`
    when absent/blank (the Scorecard blank-cell idiom). A row missing a required
    field (institution: `UNITID`/`INSTNM`/`CITY`/`STABBR`/`CONTROL`; program:
    `UNITID`/`CIPCODE`/`CIPDESC`/`CREDLEV`) is logged and skipped pre-DB
    (returns `null`).
  - **Net-price coalesce**: `net_price` reads `NPT4_PUB` when `control == 1`,
    else `NPT4_PRIV`; both blank → `null`.
  - A program whose `UNITID` resolves to no loaded college (via
    `CollegesDao.findByUnitId`) is logged and skipped.
  - The `findByUnitId` read runs BEFORE the row's savepoint — safe because the
    savepoint discipline keeps the transaction unaborted.
- **Error Handling**: A row whose upsert returns a failed `Result` (any
  `DaoException`) is rolled back to its savepoint, counted as a skip, and logged
  with a bracketed cause description. Skips are split by category: a
  `TransientError`-bearing `DaoException` (connection/serialization fault) is a
  `transientSkip` (a re-run may recover it); everything else is a
  `permanentSkip` (e.g. a CHECK/unique violation from dirty source data — note
  `CollegesDao.mapCollegeError` maps `23514` to `ConstraintViolationException`,
  a `PermanentError`). The split lets a caller tell "retry the ingest" from
  "this row is permanently corrupt".
- **Returns**:
  `LoadResult(collegesLoaded, programsLoaded, transientSkips,
  permanentSkips)`
  — per-file counts summed across both files.
- **Idempotency**: Yes — re-running upserts the same snapshot with no
  duplicates.

#### `upsertWithSavepoint(session, upsert): Result<T>` (private)

- Issues `SAVEPOINT scorecard_row`, runs the upsert, then `RELEASE SAVEPOINT` on
  success or `ROLLBACK TO SAVEPOINT` on a failed `Result`, leaving the
  transaction usable for the next row. This is the loader's
  all-rows-survive-one- bad-row mechanism (see §II).

---

### `IngestApplication` — [`IngestApplication.kt`](./IngestApplication.kt)

`fun main(args: Array<String>)` — the operational entry, invoked via
`bin/ingest-colleges <institution.csv> <fields.csv>`.

- **Behavior**: Requires exactly two positional CSV path arguments (else exits
  `2`); validates both are existing files (else exits `2`); loads config via
  `AppConfig.load("common.conf", "db.conf")` (the two `.conf` files from the
  `:common`/`:db` classpath deps — no new `college.conf`); builds a `Database`,
  runs the loader in `runBlocking`, and logs the four counts. A non-zero
  `transientSkips` emits a follow-up warning that re-running may recover them.
- **Lifecycle**: Closes the `Database` in a `finally`.
- **Logging**: Ships no `logback.xml` of its own — inherits Logback's default
  console config (like `queue-worker`'s application entry), so the bracketed
  warnings print to the console.

---

## IV. Infrastructure & Environment

- **Module**: `college` Gradle module (`college/build.gradle.kts`) —
  `kotlin.jvm`
  - `ktlint` + `application` plugins.
    `mainClass = "ed.unicoach.college.
  IngestApplicationKt"`.
- **Dependencies**: `:common` (config), `:db` (`Database`, `SqlSession`,
  `CollegesDao`, the college models), `org.apache.commons:commons-csv` (loader
  only), `kotlinx-coroutines-core`, `slf4j-api`; `logback-classic` at runtime;
  `kotlin-test-junit5` + `kotlinx-coroutines-test` for tests. Reuses
  `kotlinx-serialization-json` (transitively, for the tool JSON).
- **No new runtime config keys**: the loader takes CSV paths as CLI args and
  reuses the existing DB config; the search service needs only `Database`.
- **Operational command**: `bin/ingest-colleges <institution.csv> <fields.csv>`
  forwards to `:college:run --args`. It runs inside the nix dev shell
  (`nix develop -c`); a bare invocation fails at `gradlew` with no JVM on PATH.
- **Tests**: DB-backed JUnit 5 under `nix develop -c bin/test college`, with
  small committed CSV fixtures under `college/src/test/resources/`. The full
  dataset file is NOT committed (multi-MB, public).

---

## V. History

- [x] [RFC-67: College Knowledge](../../../../../../../rfc/67-college-knowledge.md)
      — Introduced the `college` module: `CollegeSearchService` (clamp +
      delegate), `CollegeSearchTool` (Anthropic `definition` + total `execute`),
      the re-runnable best-effort `CollegeScorecardLoader` (per-row savepoint,
      transient/permanent skip split), and the `IngestApplication` entry. Backed
      by the new `colleges`/`college_programs` reference tables and
      `CollegesDao` in the `db` module.
