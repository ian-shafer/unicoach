# RFC 67: College Knowledge

## Executive Summary

The coach currently has no grounding dataset: asked for "small schools near the
ocean with marine biology I can afford," it invents institutions. This RFC adds
a real college dataset and structured retrieval over it so the coach reasons
over schools that exist.

Three deliverables, designed as a standalone capability consumed by both live
chat and the later reflection lenses (per `features/coaching-memory.md`):

1. **Dataset** — two new reference tables, `colleges` and `college_programs`,
   holding a curated subset of College Scorecard institution-level and
   field-of-study data (identity, location, size, selectivity, cost, outcomes,
   CIP program offerings), loaded by a re-runnable JVM ingester from a pinned
   snapshot.
2. **Retrieval** — `CollegesDao.search` builds parameterized SQL over typed
   columns from a `CollegeQuery` (program, size band, geography, selectivity,
   budget, control), wrapped by a `CollegeSearchService` in a new `college`
   module.
3. **Tool contract** — `CollegeSearchTool` packages the retrieval as an
   Anthropic tool definition (JSON `input_schema`) plus a pure
   `execute(input) → result` adapter, ready to register into a chat turn.

The chat turn path is **not** modified here: the repository has no tool-use loop
to extend, and building one is deferred to a follow-up RFC (see Detailed
Design). This RFC produces the registerable tool contract so that follow-up is
pure wiring.

## Detailed Design

### Scope: tool contract now, chat tool-use loop deferred

This RFC delivers `CollegeSearchTool` (definition + `execute`) and the full
retrieval capability, but does **not** wire the tool into a chat turn; the
generic agentic loop lands in a separate follow-up RFC that imports this tool
and rewires the turn path. The repository has no tool-calling mechanism to fit,
verified against current code: `chat/ChatMessage.kt` is `(role, text: String)`
and `AnthropicChatProvider.requestBody` serializes `content = message.text` (a
JSON string, not a content-block array), so a `tool_result` block cannot be
transmitted; `ChatRequest` has no typed `tools` field (only the opaque
`params: JsonObject?` passthrough, which nothing consumes as a tool call); and
`CoachingService.collectTurn`/`buildReplyFlow` runs one provider call and
persists exactly one `convo_requests` + `convo_responses` pair per turn, with no
`stop_reason == "tool_use"` detection, tool dispatch, or second call. A real
loop requires structured content blocks on `ChatMessage`, typed `tools` on
`ChatRequest`, a capped iteration loop in `CoachingService`, and a
persistence/visibility model for the intermediate round-trips (each maps to one
request/response pair but must replay to the model while staying out of
`CoachingService.visibleHistory`). That work is deferred because it is generic
infrastructure every future tool needs (extraction's inline actions per
`features/coaching-memory.md`) and reworks the load-bearing turn path; folding
it in here would roughly double the blast radius (`chat`, `service`,
`rest-server`, `ConvoContent`, the visible-history projection) and couple two
unrelated risk surfaces. The retrieval and tool contract are identical
regardless, so the follow-up is pure wiring.

### Data model

Two reference tables. Both hold externally-sourced federal data, mutated only by
re-ingestion (upsert), never by application request flow. They are therefore
**reference tables**, not student entities: no OCC versioning, no version
history, no soft-delete, and no append-only log guards. The institution's stable
federal `UNITID` is the natural upsert key; a surface `id UUID` follows the
project convention that external references use a DB-generated UUID and DAO
inserts do not accept an id.

#### Entity Configuration — `colleges` and `college_programs`

| Setting        | Selection    | Implementation Requirement                                                                          |
| :------------- | :----------- | :-------------------------------------------------------------------------------------------------- |
| **ID Type**    | `UUIDv7`     | `id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7()`; natural key in a separate `UNIQUE` column.         |
| **Mutability** | Mutable      | `created_at` + `updated_at` (logical only); `update_timestamp`-style trigger on UPDATE.             |
| **Timestamps** | Logical Only | Omit `row_created_at`/`row_updated_at`; bulk refresh does not need the physical/logical split.      |
| **Versioning** | Disabled     | No `version`, no `_versions` table; the pinned snapshot is the archive, history is not a guarantee. |
| **Deletions**  | Physical     | No `deleted_at`; rows are bulk-managed reference data, re-derivable from the snapshot.              |

These omit the soft-delete/versioning guards standard for student-owned entities
(`students`, `convos`) because the data carries no per-row provenance or audit
obligation — it is a re-loadable projection of a public file. Stale-row removal
on a _future_ refresh (institutions leaving the dataset) is out of scope.

#### `colleges`

Curated institution-level columns from the College Scorecard "Most Recent
Institution-Level Data" file. Column names below are the project's snake_case;
the source Scorecard field is noted.

| Column                 | Type                                   | Source / Notes                                                                                                |
| :--------------------- | :------------------------------------- | :------------------------------------------------------------------------------------------------------------ |
| `id`                   | `UUID` PK                              | `uuidv7()`                                                                                                    |
| `unit_id`              | `INTEGER` UNIQUE                       | `UNITID` — federal natural key, upsert target                                                                 |
| `opeid`                | `TEXT` NULL                            | `OPEID8`                                                                                                      |
| `name`                 | `TEXT` NOT NULL                        | `INSTNM`                                                                                                      |
| `city`                 | `TEXT` NOT NULL                        | `CITY`                                                                                                        |
| `state`                | `TEXT` NOT NULL                        | `STABBR` — 2-letter postal code                                                                               |
| `region`               | `SMALLINT` NULL                        | `REGION` — IPEDS region code 0–9                                                                              |
| `locale`               | `SMALLINT` NULL                        | `LOCALE` — urbanization 11–43 (city/suburb/town/rural)                                                        |
| `latitude`             | `DOUBLE PRECISION` NULL                | `LATITUDE`                                                                                                    |
| `longitude`            | `DOUBLE PRECISION` NULL                | `LONGITUDE`                                                                                                   |
| `control`              | `SMALLINT` NOT NULL                    | `CONTROL` — 1 public, 2 private nonprofit, 3 private for-profit                                               |
| `undergrad_enrollment` | `INTEGER` NULL                         | `UGDS` — degree-seeking undergrad headcount; the size axis                                                    |
| `admission_rate`       | `DOUBLE PRECISION` NULL                | `ADM_RATE` — 0–1; the selectivity axis                                                                        |
| `sat_avg`              | `INTEGER` NULL                         | `SAT_AVG`                                                                                                     |
| `cost_attendance`      | `INTEGER` NULL                         | `COSTT4_A` — annual cost of attendance, USD                                                                   |
| `net_price`            | `INTEGER` NULL                         | `NPT4_PUB` for public, else `NPT4_PRIV` — single average-net-price column, coalesced at load; the budget axis |
| `tuition_in_state`     | `INTEGER` NULL                         | `TUITIONFEE_IN`                                                                                               |
| `tuition_out_state`    | `INTEGER` NULL                         | `TUITIONFEE_OUT`                                                                                              |
| `graduation_rate`      | `DOUBLE PRECISION` NULL                | `C150_4` — 6-yr completion rate, 0–1; outcome signal                                                          |
| `median_earnings`      | `INTEGER` NULL                         | `MD_EARN_WNE_P10` — median earnings ~10yr post-entry, USD; ROI context                                        |
| `pct_pell`             | `DOUBLE PRECISION` NULL                | `PCTPELL` — Pell-recipient share of undergrads, 0–1; access/affordability signal                              |
| `website`              | `TEXT` NULL                            | `INSTURL`                                                                                                     |
| `created_at`           | `TIMESTAMPTZ` NOT NULL DEFAULT `NOW()` | logical insert time                                                                                           |
| `updated_at`           | `TIMESTAMPTZ` NOT NULL DEFAULT `NOW()` | advanced on upsert by the update trigger                                                                      |

Constraints (named, per the TEXT/CHECK convention):
`colleges_unit_id_positive_check (unit_id > 0)`;
`colleges_name_length_check (length(name) <= 255)` and the trimmed/not-empty
pair for `name`, `city`; `colleges_state_length_check (length(state) = 2)`;
`colleges_control_valid_check (control IN (1,2,3))`;
`colleges_region_range_check (region IS NULL OR region BETWEEN 0 AND 9)`;
`colleges_locale_range_check (locale IS NULL OR locale BETWEEN 11 AND 43)`
(symmetry with `region`: both are coded categoricals);
`colleges_admission_rate_range_check (admission_rate IS NULL OR admission_rate
BETWEEN 0 AND 1)`;
non-negative checks for `undergrad_enrollment`, `sat_avg`, `cost_attendance`,
`net_price`, `tuition_in_state`, `tuition_out_state`, `median_earnings`;
`colleges_graduation_rate_range_check` and `colleges_pct_pell_range_check` (each
`IS NULL OR BETWEEN 0 AND 1`); `colleges_website_length_check` and
`colleges_opeid_length_check` (≤255 when present). Indexes:
`colleges_unit_id_unique_idx` (UNIQUE on `unit_id`);
`colleges_state_idx (state)`; `colleges_control_idx (control)`;
`colleges_undergrad_enrollment_idx`, `colleges_admission_rate_idx`,
`colleges_net_price_idx`, `colleges_graduation_rate_idx` to support range
filters.

Trigger: a single `BEFORE UPDATE` trigger named
`trigger_03_enforce_colleges_updated_at` advancing `updated_at`, mirroring
`update_jobs_timestamp()`'s plain (no logical/physical split) shape in
`0003.create-queue.sql`. The `trigger_NN` prefix is a fixed reserved-slot
convention (per `db/schema/SPEC.md`), not a dense per-table sequence: the
enforce-`updated_at` guard always occupies `_03`, even when it is the sole
trigger on the table — `0003.create-queue.sql` names the analogous lone trigger
on `jobs` `trigger_03_enforce_jobs_updated_at`. The table is not append-only and
not OCC-versioned, so it reuses no entity guard; it gets a plain
`update_colleges_timestamp()` function defined in this migration, mirroring
`update_jobs_timestamp()`.

#### `college_programs`

CIP program offerings per institution, from the Scorecard "Most Recent
Field-of-Study" file, enabling "offers marine biology" filtering at full CIP
granularity (the institution-level `PCIPxx` fields are only 2-digit families and
too coarse).

| Column             | Type                                   | Source / Notes                                                           |
| :----------------- | :------------------------------------- | :----------------------------------------------------------------------- |
| `id`               | `UUID` PK                              | `uuidv7()`                                                               |
| `college_id`       | `UUID` NOT NULL                        | `REFERENCES colleges(id) ON DELETE CASCADE`                              |
| `cip_code`         | `TEXT` NOT NULL                        | `CIPCODE` — 6-digit CIP, stored as a 6-char digit string (e.g. `260702`) |
| `cip_title`        | `TEXT` NOT NULL                        | `CIPDESC`                                                                |
| `credential_level` | `SMALLINT` NULL                        | `CREDLEV` — 3 = bachelor's, etc.                                         |
| `created_at`       | `TIMESTAMPTZ` NOT NULL DEFAULT `NOW()` |                                                                          |
| `updated_at`       | `TIMESTAMPTZ` NOT NULL DEFAULT `NOW()` |                                                                          |

Constraints: `college_programs_cip_code_format_check (cip_code ~ '^[0-9]{6}$')`;
`college_programs_cip_title_length_check (length(cip_title) <= 255)` plus
not-empty;
`college_programs_credential_level_range_check (credential_level IS
NULL OR credential_level BETWEEN 1 AND 8)`.
Upsert key:
`college_programs_unique_idx UNIQUE (college_id, cip_code,
credential_level)` —
note `credential_level` is NULLABLE, so the unique index must use
`COALESCE(credential_level, -1)` or the table forbids NULL on this column; the
loader populates `credential_level` from `CREDLEV` which is always present in
the field-of-study file, so the column is declared `NOT NULL` and the unique
index is plain `(college_id, cip_code, credential_level)`. Index
`college_programs_cip_code_idx (cip_code)` for program-prefix lookups, and
`college_programs_college_id_idx (college_id)` for the join from a matched
college.

Trigger: a `BEFORE UPDATE` trigger named
`trigger_03_enforce_college_programs_updated_at` advancing `updated_at`, reusing
the same `update_colleges_timestamp()` function (it touches only `updated_at`);
`_03` is the reserved enforce-`updated_at` slot (same convention as `colleges`).
`created_at`/`updated_at` + this trigger are carried for uniformity with
`colleges` (negligible cost), not because any reader consumes them — the table
is bulk-upserted only.

CIP prefix matching: queries match `cip_code LIKE :prefix || '%'` so a 2-digit
(`26` biology), 4-digit (`2613` ecology/marine), or 6-digit (`260702` marine
biology) prefix all resolve. The LLM supplies the prefix at the granularity it
infers from the student's words.

### Ingestion: one-time pinned snapshot via a re-runnable loader

The dataset enters Postgres through a JVM loader that upserts a downloaded,
version-pinned College Scorecard CSV pair (institution-level + field-of-study)
on the natural keys (`unit_id`; `(college_id, cip_code, credential_level)`). The
loader is idempotent — re-running re-applies the same snapshot with no
duplicates — so a future refresh RFC adds a download+cadence wrapper with zero
loader rework; periodic auto-refresh is YAGNI now because Scorecard updates
roughly annually and the fields used here drift slowly. (A scheduled queue job
pulling the Scorecard API would add a `jobs` integration, pagination, and
stale-row reconciliation before any evidence the freshness matters.)

The schema DDL lives in the migration (`db/schema/0013.create-colleges.sql`);
the **data** does not — a multi-thousand-row institution set is far past the
seed-row scale `db/schema/SPEC.md` permits in a migration, and would re-run
nowhere. The dataset file is **not committed** (multi-MB, public); the loader
takes the CSV path as an argument and tests run against a small committed
fixture. Stale-row removal on re-ingest is out of scope (no second snapshot
exists to diverge).

### Retrieval: structured SQL filtering over typed columns

Retrieval is structured SQL filtering over the typed columns, not semantic
search: the LLM translates natural language into typed `CollegeQuery` fields via
the tool `input_schema`, and Postgres does the filtering and ranking. Every
constraint students express in the motivating example maps to a structured field
(_small_ → enrollment band; _marine biology_ → CIP prefix; _budget_ →
`net_price` ceiling; _reach/safety_ → `admission_rate` band; _public/private_ →
`control`; _urban/rural_ → `locale`), so no new infrastructure is needed.
Embedding/pgvector search is rejected because Scorecard is numeric/categorical
with no descriptive corpus to embed — reconsider only if free-text "vibe"
queries become common.

Limitation, stated honestly: "near the ocean" has **no** Scorecard field. The
LLM maps the phrase to a coastal-`state` set in the `states` filter;
`latitude`/`longitude` are stored so a future RFC can add true coast-distance
ranking. The tool description must not claim a geographic-proximity capability
the data lacks.

Ranking is deterministic and simple: hard-filter on all supplied constraints,
then `ORDER BY undergrad_enrollment DESC NULLS LAST, unit_id ASC`, `LIMIT`
(default 10, clamped ≤25). Relevance ranking beyond hard filters is deferred to
the fit-lens RFC, which owns "I found a school you'd love."

### Retained columns: curated typed columns, no raw retention

Only curated, typed columns are retained — no raw JSONB and no full-dataset
copy. The pinned snapshot file is the archive; if a later RFC needs a Scorecard
column not curated here, it adds the typed column and re-ingests. The Scorecard
institution file is ~3,000 columns, but that is ~100 base metrics each exploded
by demographic subgroup, income bracket, cohort year, and field of study — finer
cuts than coaching needs, plus policymaker-oriented repayment/research fields.
The curated set keeps the headline value of each coaching-relevant metric;
beyond the structural/selectivity/cost columns it includes three outcome signals
chosen for coaching value: `graduation_rate` (`C150_4`, also the only outcome
filter via `minGraduationRate`), `median_earnings` (`MD_EARN_WNE_P10`), and
`pct_pell` (`PCTPELL`, the access signal) — the latter two returned as context
only. A raw JSONB blob is rejected because it invites unconstrained reads that
bypass the typed columns and their checks. The accepted cost is a schema
migration + re-ingest whenever a new field is wanted, which is the right price
for keeping every queryable column typed and constrained.

Explicitly **not** modeled, because the source lacks it: **application
deadlines.** College Scorecard / IPEDS carry no per-cycle application deadlines;
the brief's "deadlines" is not satisfiable from this source and is deferred to a
later RFC with a deadline-bearing source (e.g. Common App / CDS), or to the
`college-list` entity's per-student key dates.

### Retrieval and tool contract (the `college` module)

A new Gradle module `college` (library + ingestion `application` entry),
depending on `:common` and `:db`. It holds the retrieval orchestration, the tool
adapter, and the loader. SQL lives in the `db` module's DAO per the project's
layering (the `db` module owns all SQL); the `college` module orchestrates
connections and adapts to the tool wire shape.

**`db` module additions** (models + DAO):

- `db/models/CollegeId.kt`, `College.kt`, `NewCollege.kt`, `CollegeProgram.kt`,
  `NewCollegeProgram.kt` — value/data classes mirroring the columns, following
  `Convo.kt`/`NewConvo.kt` style (`College : Identifiable<CollegeId>`).
- `db/models/CollegeQuery.kt` — the typed filter. Every field except `limit` is
  nullable (absent = unconstrained); `limit` is mandatory and clamped to `1..25`
  by the service:

  | Field                    | Type            | Notes                               |
  | :----------------------- | :-------------- | :---------------------------------- |
  | `cipPrefix`              | `String?`       | 2/4/6-digit CIP prefix              |
  | `states`                 | `List<String>?` | 2-letter codes; OR-set              |
  | `region`                 | `Int?`          |                                     |
  | `locales`                | `List<Int>?`    |                                     |
  | `control`                | `List<Int>?`    | subset of {1,2,3}                   |
  | `minUndergradEnrollment` | `Int?`          |                                     |
  | `maxUndergradEnrollment` | `Int?`          |                                     |
  | `minAdmissionRate`       | `Double?`       |                                     |
  | `maxAdmissionRate`       | `Double?`       |                                     |
  | `maxNetPrice`            | `Int?`          |                                     |
  | `minGraduationRate`      | `Double?`       | 0..1; the only outcome filter       |
  | `limit`                  | `Int`           | mandatory; clamped 1..25 by service |

- `db/models/CollegeMatch.kt` — a result row: the curated college fields
  (including the outcome signals `graduation_rate`, `median_earnings`,
  `pct_pell`) plus the `cip_title`s matched by `cipPrefix` (empty when no
  program filter). `graduation_rate`, `median_earnings`, and `pct_pell` are
  returned context — only `graduation_rate` is also a filter
  (`minGraduationRate`); earnings and Pell share are surfaced for the coach to
  reason over in prose, not filtered on, because thresholding on them is
  value-laden.
- `db/dao/CollegesDao.kt` — a stateless `object` (per `ConvosDao` precedent for
  the object/`Database` shape; the upsert methods are new hand-rolled SQL — no
  `ON CONFLICT`/upsert exists anywhere in the codebase today, where DAOs use
  typed `Creatable`/`insertReturning` helpers):
  - `upsert(session, NewCollege): Result<College>` — hand-rolled
    `INSERT ... ON CONFLICT (unit_id) DO UPDATE`.
  - `upsertProgram(session, NewCollegeProgram): Result<CollegeProgram>` —
    hand-rolled
    `ON CONFLICT (college_id, cip_code, credential_level) DO UPDATE`.
  - `findByUnitId(session, Int): Result<College?>`.
  - `search(session, CollegeQuery): Result<List<CollegeMatch>>` — builds a
    parameterized `SELECT` appending one `AND` clause per non-null filter; joins
    `college_programs` only when `cipPrefix` is set; applies the deterministic
    `ORDER BY` + `LIMIT`. All values bound as parameters (no string
    interpolation of filter values).

**`college` module:**

- `college/CollegeSearchService.kt` —
  `class CollegeSearchService(private val
  database: Database)`;
  `suspend fun search(query: CollegeQuery): Result<List<
  CollegeMatch>>`
  clamps `limit` to `1..25` and runs
  `database.withConnection {
  CollegesDao.search(it, query) }`. Constructor DI,
  sibling to `CoachingService`.
- `college/CollegeSearchTool.kt` — the chat tool contract, with no chat-module
  dependency (it emits/consumes plain `JsonObject`):
  - `val definition: JsonObject` — the Anthropic tool spec:
    `name =
    "search_colleges"`, a `description` that enumerates the
    supported filters and explicitly states it cannot reason about
    distance/coastline, and an `input_schema` (JSON Schema object) whose
    properties mirror `CollegeQuery` (all optional except an implicit result
    cap).
  - `suspend fun execute(input: JsonObject): JsonObject` — parses `input` into a
    `CollegeQuery` (rejecting unknown/ill-typed fields into a structured
    `{ "error": ... }` result rather than throwing), calls
    `CollegeSearchService.search`, and serializes matches into a compact result
    object
    `{ "colleges": [ { name, city, state, control, undergrad_enrollment,
    admission_rate, net_price, graduation_rate, median_earnings, pct_pell,
    programs: [cip_title...] } ], "count": n }`.
    A zero-match query returns `{ "colleges": [], "count": 0 }` — an empty
    result is a valid domain outcome, not an error.

  This is a **native, in-process tool, not an MCP server.** College search is a
  SQL query against the project's own Postgres in the same JVM; there is no
  external provider, cross-process boundary, or third-party/cross-host reuse
  requirement, so MCP's transport and server lifecycle would be unjustified
  overhead. MCP is orthogonal to the `tool_use`/`tool_result` model protocol —
  it is a way to _source_ tools into the loop, not a different model contract —
  so deferring the chat tool-use loop forecloses nothing. The `definition` +
  total-`execute` shape here is deliberately the same shape MCP's
  `list_tools`/`call_tool` expose, so a later RFC that wanted to publish college
  search to other hosts could wrap this exact `execute` in an MCP server with no
  rework.
- `college/CollegeScorecardLoader.kt` — parses the two CSVs (Apache Commons CSV)
  into `NewCollege` / `NewCollegeProgram` and upserts via `CollegesDao` inside
  `database.withConnection`. Missing/blank optional fields map to `null`;
  malformed required fields (no `UNITID`/`INSTNM`/`STABBR`/`CONTROL`) skip the
  row with a logged, bracketed warning rather than aborting the load. A row
  whose upsert fails with any `DaoException` (e.g. a CHECK violation from dirty
  source data such as `admission_rate > 1`) is likewise logged-and-skipped.
  `mapDatabaseError` in `db/dao/DaoExceptions.kt` maps SQLState `23514`
  (check_violation) to the generic `DatabaseException`, never to
  `ConstraintViolationException`, while transient/connection faults (SQLState
  `08*`/`40001`/`40P01`/`53*`/`57P*`) map to `TransientDatabaseException`. Both
  are `DaoException` subtypes, and the loader skips on **any** `DaoException`,
  so a CHECK violation and a transient fault are both swallowed despite mapping
  to different subtypes. Skip-on-any-`DaoException` accepts that conflation
  rather than expanding scope into `DaoExceptions.kt`; the per-row blast radius
  is one row either way.
- `college/IngestApplication.kt` — `fun main(args)`: loads config via
  `AppConfig.load("common.conf", "db.conf")` (the two `.conf` files contributed
  by the `:common` and `:db` classpath dependencies — no new `college.conf` is
  introduced, the loader reads only the DB config), reads the institution-CSV
  and field-of-study-CSV paths from `args`, runs the loader, and logs counts.
  Like `queue-worker`'s `application` entry, it ships **no** `logback.xml` of
  its own and inherits Logback's default console configuration for the bracketed
  warnings (only the long-lived servers carry a logback.xml).

### API Contracts

No REST surface is added or changed. The only external contract is the
`search_colleges` tool `input_schema` (above), which is internal to a future
chat turn, plus the `bin/ingest-colleges <institution.csv> <fields.csv>`
operational command. The buffered/streaming convo endpoints in `ConvoRoutes.kt`
are untouched.

### Error Handling / Edge Cases

- **Empty results** are a successful outcome (`count: 0`), never an error — the
  tool returns the empty set so the LLM can relax constraints.
- **Malformed tool input** (unknown field, wrong type, `cipPrefix` not digits)
  returns a structured `{ "error": "<reason>" }` result object; the executor
  never throws into the (future) turn loop.
- **Limit abuse** is clamped at the service boundary (`1..25`); the
  `input_schema` advertises the cap.
- **Loader, malformed row**: skipped with a bracketed warning; the load
  continues (best-effort over the dataset, not all-or-nothing — one corrupt CSV
  line must not lose the other 6,000 institutions).
- **Loader, idempotency**: re-running upserts; `unit_id` and
  `(college_id,
  cip_code, credential_level)` uniqueness prevent duplicates.
- **Net-price coalesce**: `net_price` takes `NPT4_PUB` when `control = 1` else
  `NPT4_PRIV`; both blank → `null`.
- **CHECK violations** (e.g. `admission_rate > 1` from dirty source data)
  surface as a `DaoException` — specifically the generic `DatabaseException`,
  since `mapDatabaseError` does not route SQLState `23514` to
  `ConstraintViolationException` — and the loader logs and skips the offending
  row. Transient/connection faults instead map to `TransientDatabaseException`,
  but because the loader skips on **any** `DaoException`, a CHECK violation and
  a transient fault are both swallowed despite mapping to different subtypes;
  this RFC does not change the DAO error mapping.

### Dependencies

- New library: Apache Commons CSV pinned at
  `org.apache.commons:commons-csv:1.14.1` (current latest stable) added to
  `gradle/libs.versions.toml`, used only by the `college` module loader.
- Reuses `kotlinx-serialization-json` (already present) for the tool JSON, and
  the `db` module's `Database`/`SqlSession`/DAO machinery.
- No new runtime config keys: the loader takes CSV paths as CLI args and reuses
  the existing DB config; the search service needs only `Database`.

## Tests

DB-backed JVM tests under JUnit 5, run by `nix develop -c bin/test`. The
`college` module is added to `bin/test`'s `MODULES`. Schema constraints are
verified through the `db`-module DAO test (DB-backed), not a new bash SQL
harness.

**`db/src/test/.../dao/CollegesDaoTest.kt`** (module `db`):

- `upsert inserts a new college and returns it with a generated id`.
- `upsert on existing unit_id updates in place and advances updated_at` — no
  duplicate row; `id` stable.
- `upsertProgram enforces (college_id, cip_code, credential_level) uniqueness` —
  second upsert updates `cip_title`, does not duplicate.
- `control outside {1,2,3} is rejected` — `colleges_control_valid_check`.
- `admission_rate above 1 is rejected` — range check.
- `negative undergrad_enrollment is rejected`.
- `state of length != 2 is rejected`.
- `cip_code not six digits is rejected` —
  `college_programs_cip_code_format_check`.
- `search with no filters returns all rows ordered by enrollment desc, unit_id asc`.
- `search by cipPrefix joins programs and matches 2/4/6-digit prefixes` — seed a
  marine-biology program (`260702`); assert `26`, `2607`, `260702` all hit and
  `27` misses; `cip_title` populated in the match.
- `search by maxNetPrice / size band / states / control / admission-rate band /
  minGraduationRate`
  — one case per filter axis asserting inclusion and exclusion.
- `search returns the outcome columns` — `graduation_rate`, `median_earnings`,
  and `pct_pell` are populated in `CollegeMatch` even though only
  `graduation_rate` is filterable.
- `search combines filters conjunctively` — the motivating example: small +
  coastal-state set + marine-biology CIP + net-price ceiling.
- `search applies limit and the limit is honored at the SQL level`.

**`college/src/test/.../CollegeScorecardLoaderTest.kt`** (module `college`,
DB-backed):

- `loads institutions and programs from fixture CSVs` — counts match the
  fixture; spot-check coalesced `net_price` (public vs private row) and the
  outcome columns (`graduation_rate`, `median_earnings`, `pct_pell`).
- `re-running the loader is idempotent` — row counts unchanged after a second
  run.
- `a row missing UNITID/INSTNM/STABBR/CONTROL is skipped, others load` — fixture
  includes one malformed line.
- `blank optional fields become null` — e.g. a row with empty `ADM_RATE`.

**`college/src/test/.../CollegeSearchServiceTest.kt`** (module `college`,
DB-backed):

- `clamps limit to 1..25` — request 100 → at most 25; request 0 → at least 1.
- `delegates filtering to the DAO and returns matches` — seed via DAO, assert a
  filtered search.
- `zero matches returns an empty list, not a failure`.

**`college/src/test/.../CollegeSearchToolTest.kt`** (module `college`):

- `definition exposes a valid input_schema with all CollegeQuery fields optional`.
- `definition description states no geographic-distance capability` — guards
  against over-claiming "near the ocean".
- `execute maps tool input to a CollegeQuery and returns the result object`
  (DB-backed): a populated input yields `{ colleges, count }`.
- `execute on malformed input returns an error object, not an exception` —
  non-digit `cipPrefix`, wrong-typed `maxNetPrice`.
- `execute on a zero-match query returns count 0`.

Test resources: `college/src/test/resources/scorecard-institutions-fixture.csv`
and `scorecard-fields-fixture.csv` — ~6 institutions (public + private, a range
of size/selectivity/cost/state, one malformed row) and their programs (including
a marine-biology CIP).

## Implementation Plan

Each step is independently verifiable. Commands assume the dev shell
(`nix develop -c ...`). Do not modify `SPEC.md` files (out-of-band sync).

1. **Schema migration.** Add `db/schema/0013.create-colleges.sql` defining
   `colleges`, `college_programs`, their indexes, CHECK constraints, the
   `BEFORE UPDATE` `updated_at` trigger function and triggers.
   - Verify: `nix develop -c bin/db-reset` applies cleanly through `0013`;
     `nix develop -c bin/db-status` shows `0013` applied.
   - Verify: `nix develop -c bin/db-query "\\d+ colleges"` and
     `\\d+
     college_programs` show the columns, constraints, and indexes.

2. **DB models + DAO.** Add `CollegeId`, `College`, `NewCollege`,
   `CollegeProgram`, `NewCollegeProgram`, `CollegeQuery`, `CollegeMatch` under
   `db/models/`, and `CollegesDao` under `db/dao/` (upsert, upsertProgram,
   findByUnitId, search). Add `CollegesDaoTest`.
   - Verify:
     `nix develop -c bin/test db --tests
     "ed.unicoach.db.dao.CollegesDaoTest"`
     passes; confirm executed test count matches declared (block-body tests).

3. **`college` module skeleton + dependency.** Add `commons-csv` to
   `gradle/libs.versions.toml`; create `college/build.gradle.kts` (kotlin.jvm +
   ktlint + application plugins, deps `:common`, `:db`, commons-csv, coroutines,
   serialization-json, logback for the app, kotlin-test + coroutines-test for
   tests); `include("college")` in `settings.gradle.kts`; add `college` to
   `bin/test`'s `MODULES`.
   - Verify: `nix develop -c ./gradlew :college:compileKotlin` succeeds;
     `nix develop -c bin/test college` runs (zero tests at this point) without a
     "unknown module" error.

4. **Retrieval service.** Add `CollegeSearchService` (clamp + delegate) and
   `CollegeSearchServiceTest`.
   - Verify:
     `nix develop -c bin/test college --tests
     "ed.unicoach.college.CollegeSearchServiceTest"`
     passes.

5. **Tool contract.** Add `CollegeSearchTool` (definition + execute) and
   `CollegeSearchToolTest`.
   - Verify:
     `nix develop -c bin/test college --tests
     "ed.unicoach.college.CollegeSearchToolTest"`
     passes.

6. **Loader + ingestion entry.** Add `CollegeScorecardLoader`,
   `IngestApplication`, the two fixture CSVs, `CollegeScorecardLoaderTest`, and
   a new `bin/ingest-colleges` script. No existing `bin/` script invokes
   `./gradlew :module:run --args` (the queue-worker runs as a daemon from a
   pre-built installDist binary), so this wrapper is written fresh, following
   the `bin/build-queue-worker` shape: `source "$(dirname "$0")/common"` (which
   sets `PROJECT_ROOT` and sources `bin/functions` + `.env` — it does NOT source
   the nix dev shell; the dev shell is supplied by the caller via
   `nix develop -c`, which is why the verify lines wrap with it, and a bare
   invocation outside the shell fails at the `gradlew` call because there is no
   JVM on PATH), require two positional CSV-path arguments, and forward them via
   `exec "$PROJECT_ROOT/gradlew" :college:run --args="<institution.csv> <fields.csv>"`.
   - Verify:
     `nix develop -c bin/test college --tests
     "ed.unicoach.college.CollegeScorecardLoaderTest"`
     passes.
   - Verify:
     `nix develop -c bin/ingest-colleges
     college/src/test/resources/scorecard-institutions-fixture.csv
     college/src/test/resources/scorecard-fields-fixture.csv`
     loads the fixture against the local DB and logs non-zero counts;
     `nix develop -c bin/db-query "SELECT count(*) FROM colleges"` is non-zero.

7. **Full gate.** Run the Kotlin verification gate.
   - Verify: `nix develop -c bin/test check db college` passes (ktlint + tests,
     `--force` to defeat the cache, JUnit XML counts confirmed).

## Files Modified

New files:

- `db/schema/0013.create-colleges.sql`
- `db/src/main/kotlin/ed/unicoach/db/models/CollegeId.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/College.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewCollege.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/CollegeProgram.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewCollegeProgram.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/CollegeQuery.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/CollegeMatch.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/CollegesDaoTest.kt`
- `college/build.gradle.kts`
- `college/src/main/kotlin/ed/unicoach/college/CollegeSearchService.kt`
- `college/src/main/kotlin/ed/unicoach/college/CollegeSearchTool.kt`
- `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt`
- `college/src/main/kotlin/ed/unicoach/college/IngestApplication.kt`
- `college/src/test/kotlin/ed/unicoach/college/CollegeSearchServiceTest.kt`
- `college/src/test/kotlin/ed/unicoach/college/CollegeSearchToolTest.kt`
- `college/src/test/kotlin/ed/unicoach/college/CollegeScorecardLoaderTest.kt`
- `college/src/test/resources/scorecard-institutions-fixture.csv`
- `college/src/test/resources/scorecard-fields-fixture.csv`
- `bin/ingest-colleges`

Modified files:

- `settings.gradle.kts` — `include("college")`.
- `gradle/libs.versions.toml` — add the `commons-csv` library (and version).
- `bin/test` — add `college` to the `MODULES` array (kept in sync with
  `settings.gradle.kts`).

The chat tool-use loop (deferred to a follow-up RFC; see Detailed Design) will
touch the `chat`, `service`, and `rest-server` turn path, but those files are
out of scope here and are deliberately omitted from this whitelist.
