# RFC 101: extraction/synthesis failure reason

## Executive Summary

`extraction_runs` (RFC 66) and `synthesis_runs` (RFC 93) each record a `failed`
row when the LLM's JSON output is unparseable, billing the tokens spent — but
the structured reason `ExtractionService`/`SynthesisService` already compute (a
private `ParseFailure`: `NotAnObject` / `MalformedJson(detail)` /
`BadField(field, value)`) is logged at `WARN` and discarded before it reaches
the write. This mirrors the gap `fit_lens_runs` had before RFC 98's follow-up
migration (0034): a `failed` row records that something went wrong, not what.

This RFC gives both tables the same `failure_category`/`failure_reason`
treatment `fit_lens_runs` got. One migration touches both tables (their
`ParseFailure` shapes are byte-for-byte identical), and `failure_category`'s
three values are a 1:1 mirror of `ParseFailure`'s three variants — the finer
`fit_lens_runs`-style split is a named deferred follow-up. A modeling gap in
`fit_lens_runs_failure_consistency_check` also surfaced (it does not forbid one
column set while the other is null); this RFC adds a pairing CHECK on all three
tables, backporting it onto `fit_lens_runs`.

The two `ParseFailure` definitions and their `category`/`toDisplay` mappings are
consolidated into one shared `JsonParseFailure`. The three coaching-memory run
models (`ExtractionRun`/`SynthesisRun`/`FitLensRun`) have their flat `outcome`
enum plus independently-nullable count and failure columns replaced by a sealed
outcome ADT that carries the outcome-discriminated payload, so an
`applied`-with-a-reason or `failed`-without cannot be constructed — extending
the refactor to RFC 98's landed `fit_lens_runs` so the three siblings stay
consistent. The DB stays flat columns + CHECK; the DAO is the sole mapping
boundary. Neither `ExtractionResult` nor `SynthesisResult` changes shape.

## Detailed Design

### Data model (DB)

The database schema is flat columns + CHECK on all three tables, unchanged in
shape from `fit_lens_runs` (RFC 98). The sealed outcome ADT below lives only in
the Kotlin domain model; the CHECK constraints are the DB-side guarantee
(defense in depth against a non-Kotlin writer), and the DAO is the single place
the flat row and the ADT are mapped to each other.

**Shared category enum**
(`db/src/main/kotlin/ed/unicoach/db/models/JsonParseFailureCategory.kt`): a
TEXT-backed enum, `NOT_A_JSON_OBJECT` / `MALFORMED_JSON` / `INVALID_FIELD`,
mirroring `FitLensFailureCategory`'s shape (`value: String`, `fromValue`
companion). One type, referenced by both `extraction_runs` and `synthesis_runs`
— their `ParseFailure` shapes are identical, so a shared persisted vocabulary
avoids two byte-for-byte-duplicate enums.

**Migration `db/schema/0035.add-extraction-and-synthesis-failure-reason.sql`** —
one file, both tables:

```sql
ALTER TABLE extraction_runs
  ADD COLUMN failure_category TEXT NULL,
  ADD COLUMN failure_reason   TEXT NULL;

ALTER TABLE extraction_runs ADD CONSTRAINT extraction_runs_failure_category_check
  CHECK (failure_category IN ('not_a_json_object','malformed_json','invalid_field'));

ALTER TABLE extraction_runs ADD CONSTRAINT extraction_runs_failure_reason_length_check
  CHECK (failure_reason IS NULL OR length(failure_reason) <= 2048);

-- Closes a gap the outcome-tie check alone leaves open: without this, a
-- non-failed row could carry one column set and the other null.
ALTER TABLE extraction_runs ADD CONSTRAINT extraction_runs_failure_pairing_check
  CHECK ((failure_category IS NULL) = (failure_reason IS NULL));

-- With pairing enforced above, this only needs to reference one column.
ALTER TABLE extraction_runs ADD CONSTRAINT extraction_runs_failure_consistency_check
  CHECK ((outcome = 'failed') = (failure_category IS NOT NULL));

ALTER TABLE synthesis_runs
  ADD COLUMN failure_category TEXT NULL,
  ADD COLUMN failure_reason   TEXT NULL;

ALTER TABLE synthesis_runs ADD CONSTRAINT synthesis_runs_failure_category_check
  CHECK (failure_category IN ('not_a_json_object','malformed_json','invalid_field'));

ALTER TABLE synthesis_runs ADD CONSTRAINT synthesis_runs_failure_reason_length_check
  CHECK (failure_reason IS NULL OR length(failure_reason) <= 2048);

ALTER TABLE synthesis_runs ADD CONSTRAINT synthesis_runs_failure_pairing_check
  CHECK ((failure_category IS NULL) = (failure_reason IS NULL));

ALTER TABLE synthesis_runs ADD CONSTRAINT synthesis_runs_failure_consistency_check
  CHECK ((outcome = 'failed') = (failure_category IS NOT NULL));
```

**Migration `db/schema/0036.add-fit-lens-failure-pairing-check.sql`** — a small
follow-up backporting the pairing CHECK onto the already-committed
`fit_lens_runs` (RFC 98/0034):

```sql
ALTER TABLE fit_lens_runs ADD CONSTRAINT fit_lens_runs_failure_pairing_check
  CHECK ((failure_category IS NULL) = (failure_reason IS NULL));
```

`fit_lens_runs_failure_consistency_check` (0034) is left as-is — still correct,
now redundant with the pairing check, not worth a DROP/ADD dance on a committed
constraint for a pure strengthening.

### Domain outcome model (sealed ADT)

The `outcome` of a run is a sealed interface whose variants carry the payload
that outcome — and only that outcome — has. This replaces the current shape,
where `outcome` is a two-value enum sitting beside independently-nullable count
columns (meaningful only on `applied`, forced to 0 on `failed` by a
`*_failed_counts_check`) and failure columns (null on `applied`, required on
`failed`). Under the flat shape an `applied` run with a failure reason, or a
`failed` run missing one, is constructable in Kotlin and rejected only at the DB
CHECK; the ADT makes both unrepresentable at the construction site.

The three existing enums are each converted in place from an `enum class` to a
`sealed interface` of the same name (`ExtractionOutcome`, `SynthesisOutcome`,
`FitLensOutcome`), so the `outcome` field name and type name are unchanged and
`row.outcome.value` (the persisted `'applied'`/`'failed'` string) still resolves
— each variant overrides `val value: String`. `fromValue` is dropped (the DAO
reconstructs a variant from several columns, not one string).

- `ExtractionOutcome`:
  `Applied(observationsWritten, claimsWritten,
  claimsSuperseded)` /
  `Failed(category: JsonParseFailureCategory,
  reason: String)`.
- `SynthesisOutcome`: `Applied(commitmentsWritten, commitmentsDropped)` /
  `Failed(category: JsonParseFailureCategory, reason: String)`.
- `FitLensOutcome`: `Applied(suggestionsWritten)` /
  `Failed(category:
  FitLensFailureCategory, reason: String)`.
  `FitLensOutcome.Failed` carries `FitLensFailureCategory` (RFC 98's
  `malformed_output`/`invalid_content` split), not `JsonParseFailureCategory` —
  the ADT is per-table, so each `Failed` carries its own table's category type.

Only outcome-discriminated payload moves into the ADT. Fields that legitimately
vary within an outcome stay flat top-level fields on the run model: the four
token columns (recorded on `applied` and `failed` alike) and, on `FitLensRun`,
`matchesConsidered` (nullable independent of outcome — 0 for a completed
zero-match retrieve, non-null on a `failed` pass that died at LLM call #2, null
only when the retrieve never ran; no `*_check` ties it to `outcome`).

**Kotlin models**: `NewExtractionRun`/`ExtractionRun`,
`NewSynthesisRun`/`SynthesisRun`, and `NewFitLensRun`/`FitLensRun` each drop
their flat `outcome` enum, count fields, and `failureCategory`/`failureReason`
fields, replacing them with a single `outcome: <table>Outcome` (the ADT). Token
fields, provenance, ids, and `matchesConsidered` are unchanged. The `New*`
insert types no longer default the counts/failure to `0`/`null` — the payload is
now required by whichever variant is constructed.

### Service changes

**Shared `JsonParseFailure`**
(`service/src/main/kotlin/ed/unicoach/coaching/JsonParseFailure.kt`, package
`ed.unicoach.coaching`): the sealed failure type `NotAnObject` /
`MalformedJson(detail)` / `BadField(field, value)`, plus
`val JsonParseFailure.category: JsonParseFailureCategory` and
`fun JsonParseFailure.toDisplay(): String`, lifted to one shared top-level type
consumed by both `ExtractionService` (`…coaching.extraction`) and
`SynthesisService` (`…coaching.synthesis`). This is the direct analog of RFC
98's `FailureReason` living in `FitLensResult.kt`. The two private
`ParseFailure` definitions — byte-for-byte identical variants, `category`
`when`, and `toDisplay` — are deleted from the two services and replaced by
references to the shared type; the parse sites (`parseOutput`) return
`JsonParseFailure` variants unchanged. `toDisplay` renders the free-text
diagnostic (`"root is not a JSON object"` / `"malformed JSON: [$detail]"` /
`"field [$field]=[$value]"`), the same text the deleted `toString()` overrides
produced; `category` is derived, never stored, so two constructions of the same
variant cannot carry different categories.

Both services thread the parse failure into the run write, unchanged in control
flow. `writeFailedRun` takes the `JsonParseFailure` and constructs
`<table>Outcome.Failed(failure.category, failure.toDisplay())`; the applied path
constructs `<table>Outcome.Applied(<counts>)`. `ExtractionResult`/
`SynthesisResult` remain `Success`/`TransientFailure` only, and the unparseable
path still returns `TransientFailure` after writing the `failed` row — this RFC
persists a reason already computed at an existing write site, it does not add a
dead-letter path or a new result variant. `FitLensService`'s applied/failed
`appendRun` call sites likewise construct `FitLensOutcome.Applied(...)` /
`FitLensOutcome.Failed(reason.category, reason.toDisplay())`; the `0`
`suggestionsWritten` a `failed` fit-lens run wrote explicitly is now implicit in
the `Failed` variant (the DAO writes `suggestions_written = 0`).

### DAO changes

`ExtractionRunsDao`/`SynthesisRunsDao`/`FitLensRunsDao` are the single mapping
boundary between the flat row and the ADT.

- `mapRun`: reads the `outcome` string and constructs the ADT — `'applied'` →
  `Applied(<count columns>)`, `'failed'` →
  `Failed(parseFailureCategory(
  failure_category), failure_reason)`, any other
  value → `SQLException`. `parseFailureCategory(value: String): <Category>`
  throws `SQLException` naming the offending value in brackets (`[$value]`, the
  project's serialization convention) on an unrecognized persisted category, the
  same shape as the existing
  `parseOutcome`/`FitLensRunsDao.parseFailureCategory` validators. The failure
  columns and `failure_category` are non-null on a `failed` row by CHECK, so the
  `Failed` construction is total; a corrupt row surfaces as the `SQLException`
  above rather than a Kotlin NPE.
- `create`: destructures the ADT into columns via a `when` on `input.outcome` —
  `Applied` sets the count columns and null failure columns; `Failed` sets zero
  counts, `failure_category`/`failure_reason`. The `outcome` column is
  `input.outcome.value`.

### Admin

`ExtractionRunsResource`/`SynthesisRunsResource`/`FitLensRunsResource` keep
their flat, column-per-field projection: the `AdminField` list is unchanged
(still `outcome`, `failureCategory`, the count fields, `failureReason` as
separate columns). Only `cells()` changes — it destructures `row.outcome` (a
`when` over the ADT) to fill the count cells (real values on `Applied`, `0` on
`Failed`, as today) and the `failureCategory`/`failureReason` cells (the values
on `Failed`, `""` on `Applied`). `failureCategory` stays `inList = true`
(triage-at-a-glance, next to `outcome`); `failureReason` stays `inList = false`,
detail-only. `ExtractionRunsResource`/`SynthesisRunsResource` gain the two
failure fields (new columns this RFC adds); `FitLensRunsResource` already
renders them and only its `cells()` destructuring changes.

### Error handling / edge cases

- **Unparseable output, both services.** Unchanged control flow: a `failed` row
  is written (as today), now carrying `category`/`toDisplay()` via
  `Outcome.Failed`. The pass still returns `TransientFailure` and is retried by
  the queue up to `maxAttempts`; a retry that also fails unparseably writes
  another `failed` row (pre-existing behavior, not a regression this RFC
  introduces).
- **A DB write failure inside `writeFailedRun` itself.** Unchanged: both
  services already catch this and log a warning (synthesis) or propagate
  `TransientFailure` (extraction) without crashing the caller; moving two bind
  parameters into the `Failed` variant does not change this path.
- **A corrupt persisted row reaching `mapRun`.** A `failed` row with a null or
  out-of-enum `failure_category` cannot exist under the CHECK constraints;
  should one appear (raw SQL bypassing the app), `mapRun` throws `SQLException`
  from `parseFailureCategory` or the outcome `when`, the same failure mode as
  the existing `parseOutcome` validator — never a silent misread.
- **Migration on existing data.** `failure_category`/`failure_reason` are
  `NULL`-defaulted `ADD COLUMN`s; every pre-existing row (`applied` or `failed`)
  has both `NULL`. Because the new consistency CHECK requires
  `failure_category IS NOT NULL` whenever `outcome = 'failed'`, `ADD CONSTRAINT`
  validates existing rows at migration time and a pre-existing `failed` row with
  a `NULL` `failure_category` would fail that validation. **This is a real risk
  if any `failed` rows already exist** — verified empty in the Implementation
  Plan's first step before the migration is trusted in any environment carrying
  data. (`extraction_runs`/`synthesis_runs` are append-only via
  `prevent_log_update`/`prevent_log_delete`, so no row is ever re-validated
  after migration time.)

### Dependencies

- No new Gradle module, no new third-party dependency, no RFC blocked-on.
- Reused, unchanged: `AdminTestSupport.kt`'s DB seeding helpers gain optional
  parameters only (additive), the queue (`JobType`, `JobHandler` — untouched),
  the RFC 77 admin engine.

## Tests

DB/DAO tests use the project harness (recreated test DB); service tests use a
real `Database` with a fake `ChatProvider`. Run
`nix develop -c bin/test <module> -f`, verifying executed-vs-declared counts
(block-body tests only).

**Migration (`db`).**

- `0035`/`0036` apply on a fresh DB. `extraction_runs`/`synthesis_runs` have the
  two new columns and four new CHECKs (category, length, pairing, consistency);
  `fit_lens_runs` has the new pairing CHECK alongside its existing (now
  redundant) consistency check.
- Per table (`extraction_runs`, `synthesis_runs`): an invalid `failure_category`
  value is rejected; a `failure_reason` over 2048 chars is rejected; one column
  set with the other `NULL` is rejected (the pairing CHECK) regardless of
  `outcome`; a `failed` row with `failure_category NULL` is rejected; an
  `applied` row with `failure_category` set is rejected.
- `fit_lens_runs`: one column set with the other `NULL` is rejected (the new
  0036 pairing CHECK) on a row that would have passed the pre-0036 schema.

**`ExtractionRunsDaoTest`/`SynthesisRunsDaoTest`/`FitLensRunsDaoTest` (`db`).**

- Each file's local test-data helper (`run()`/`append()`) is updated to take the
  outcome ADT (or an `applied`/`failed` discriminant plus payload) rather than a
  flat enum + nullable failure params, so every existing call constructs a valid
  variant; existing `applied`/`failed` cases are mechanically ported, not
  behaviorally changed.
- New (extraction/synthesis):
  `append persists a failed run's failure_category
  and failure_reason` — a
  `Failed(...)` outcome round-trips through `mapRun` back to an equal
  `Failed(...)` (category and reason preserved).
- New (extraction/synthesis): the existing `applied`-outcome round-trip case
  asserts `mapRun` yields an `Applied(...)` carrying the counts and no failure
  payload (there is no failure field to read).
- New (all three):
  `a persisted failure_category outside the enum surfaces as a
  mapping error`
  — asserts `parseFailureCategory`'s `SQLException` path, **only added if an
  equivalent test exists for `parseOutcome` today**; otherwise dropped as
  untestable through the DAO's public surface (the CHECK makes the persisted
  value always valid).
- New (`FitLensRunsDaoTest`):
  `a row with failure_category set and failure_reason
  null (or vice versa) is rejected`
  — exercises the new 0036 pairing CHECK.
- `FitLensRunsDaoTest` existing `applied`/`failed` round-trips are ported to the
  ADT and assert `Applied(suggestionsWritten)` / `Failed(category, reason)` with
  `matchesConsidered` still read as a flat field on both.

**`ExtractionServiceTest`/`SynthesisServiceTest` (`service`).**

- Extends the existing unparseable-`Completed` cases: asserts the persisted
  run's `outcome` is `Failed` with `failureCategory` `MALFORMED_JSON` (a
  malformed-JSON-string fixture) and `INVALID_FIELD` (a wrong-field-type
  fixture), and that `failureReason` contains the offending field/value.
- Extends the token-accounting-across-an-applied-and-a-failed-pass case to also
  assert the failed run's `Failed` category/reason alongside the token sum, and
  that the applied run is `Applied(<counts>)`.
- No new test class: `ExtractionResult`/`SynthesisResult` are unchanged.

**`FitLensServiceTest` (`service`).**

- Existing `applied`/`failed`/`skipped` assertions are ported to read
  `FitLensOutcome.Applied(...)` / `FitLensOutcome.Failed(...)` off the persisted
  run; the failed-pass case (already asserting `failureCategory`/`failureReason`
  from RFC 98) now reads them off the `Failed` variant. Behavior is unchanged;
  only the shape the assertions read changes.

**Admin (`admin-web`).**

- `AdminTestSupport.kt`'s
  `seedExtractionRun`/`seedSynthesisRun`/`seedFitLensRun` are updated to build
  the outcome ADT (defaulting to `Applied` unless a failure payload is
  supplied); existing seed calls are ported without behavioral change.
- `ExtractionRunsResourceTest`/`SynthesisRunsResourceTest`: a new case seeds a
  `Failed(...)` run and asserts the list view renders `failureCategory` and the
  detail view renders `failureReason`.
- `FitLensRunsResourceTest`: existing cases are ported to the ADT-shaped seed;
  its already-present failure-rendering assertions are unchanged.

## Invariants

None. The failure/count consistency (paired nullability, populated exactly when
`outcome = 'failed'`, counts zero on `failed`) is enforced two ways — the
migration's CHECK constraints (DB) and the sealed outcome ADT (Kotlin, at every
construction site). Both are mechanical (type- and constraint-enforced), not a
human-gated behavioral rule, so neither qualifies for `INVARIANTS.md`. The ADT
now models all three coaching-memory run log tables identically; that
consistency is a convention for future tables (a candidate note for
`postgres-log-table-design`), not a directory-specific durable guarantee.

## Implementation Plan

1. **Migrations.** Add `0035.add-extraction-and-synthesis-failure-reason.sql`
   and `0036.add-fit-lens-failure-pairing-check.sql`. Before applying in any
   environment carrying data, confirm no pre-existing `failed` row exists in
   `extraction_runs`/`synthesis_runs`
   (`SELECT count(*) FROM extraction_runs WHERE outcome = 'failed'` / same for
   `synthesis_runs`) — a nonzero count means
   `ADD CONSTRAINT ... failure_consistency_check` fails at migration time and
   the migration needs a data backfill first (out of scope; flag to the
   architect). Verify: `nix develop -c bin/test db -f`;
   `psql "$POSTGRES_DB" -c '\d+ extraction_runs' -c '\d+ synthesis_runs' -c '\d+ fit_lens_runs'`.

2. **Shared category enum + outcome ADTs + domain models.** Add
   `JsonParseFailureCategory.kt`. Convert `ExtractionOutcome`,
   `SynthesisOutcome`, `FitLensOutcome` from `enum class` to `sealed interface`
   with `Applied`/ `Failed` variants (per Domain outcome model). Replace the
   flat count/failure fields on `NewExtractionRun`/`ExtractionRun`,
   `NewSynthesisRun`/`SynthesisRun`, `NewFitLensRun`/`FitLensRun` with the
   single `outcome` ADT field. Verify:
   `nix develop -c ./gradlew :db:compileKotlin`.

3. **DAOs + DAO tests.**
   `ExtractionRunsDao`/`SynthesisRunsDao`/`FitLensRunsDao`: `mapRun`
   reconstructs the ADT, `create` destructures it, `parseFailureCategory`
   validators (bracket-wrapped offending value). Port the `run()`/`append()`
   test helpers to the ADT and add the new cases from Tests, including the
   `FitLensRunsDaoTest` pairing-check case. Verify:
   `nix develop -c bin/test db -f`.

4. **Shared `JsonParseFailure` + services.** Add `JsonParseFailure.kt` (sealed
   type + `category` + `toDisplay`) in `ed.unicoach.coaching`; delete the
   private `ParseFailure` from `ExtractionService`/`SynthesisService` and point
   their parse/write sites at it. Update all three services' run-write call
   sites (`writeFailedRun`/applied path/`appendRun`) to construct the outcome
   ADT. Extend `ExtractionServiceTest`/`SynthesisServiceTest`; port
   `FitLensServiceTest`. Verify: `nix develop -c bin/test service -f`.

5. **Admin test support.** Port `AdminTestSupport.kt`'s
   `seedExtractionRun`/`seedSynthesisRun`/`seedFitLensRun` to build the outcome
   ADT (defaulting to `Applied`). Verify:
   `nix develop -c bin/test admin-web -f`.

6. **Admin resources.** Update `cells()` in `ExtractionRunsResource`/
   `SynthesisRunsResource`/`FitLensRunsResource` to destructure the ADT; add the
   two failure `AdminField`s + cells to the extraction/synthesis resources (the
   fit-lens resource already has them). Add the new
   `ExtractionRunsResourceTest`/`SynthesisRunsResourceTest` cases; port
   `FitLensRunsResourceTest`. Verify: `nix develop -c bin/test admin-web -f`.

7. **Full gate.** Verify:
   `nix develop -c bin/format -c && nix develop -c bin/test check -f`.

## Files Modified

**Created**

- `db/schema/0035.add-extraction-and-synthesis-failure-reason.sql`
- `db/schema/0036.add-fit-lens-failure-pairing-check.sql`
- `db/src/main/kotlin/ed/unicoach/db/models/JsonParseFailureCategory.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/JsonParseFailure.kt` — shared
  parse-failure ADT + `category`/`toDisplay` (F2), lifted from the two services.

**Modified**

- `db/src/main/kotlin/ed/unicoach/db/models/ExtractionOutcome.kt` — enum →
  sealed interface (`Applied`/`Failed`).
- `db/src/main/kotlin/ed/unicoach/db/models/SynthesisOutcome.kt` — enum → sealed
  interface.
- `db/src/main/kotlin/ed/unicoach/db/models/FitLensOutcome.kt` — enum → sealed
  interface.
- `db/src/main/kotlin/ed/unicoach/db/models/NewExtractionRun.kt` — flat
  count/failure fields → `outcome` ADT.
- `db/src/main/kotlin/ed/unicoach/db/models/ExtractionRun.kt` — same.
- `db/src/main/kotlin/ed/unicoach/db/models/NewSynthesisRun.kt` — same.
- `db/src/main/kotlin/ed/unicoach/db/models/SynthesisRun.kt` — same.
- `db/src/main/kotlin/ed/unicoach/db/models/NewFitLensRun.kt` — flat
  count/failure fields → `outcome` ADT (`matchesConsidered` stays flat).
- `db/src/main/kotlin/ed/unicoach/db/models/FitLensRun.kt` — same.
- `db/src/main/kotlin/ed/unicoach/db/dao/ExtractionRunsDao.kt` —
  `mapRun`/`create` ADT mapping, `parseFailureCategory` validator (bracketed
  value, F1).
- `db/src/main/kotlin/ed/unicoach/db/dao/SynthesisRunsDao.kt` — same.
- `db/src/main/kotlin/ed/unicoach/db/dao/FitLensRunsDao.kt` — `mapRun`/`create`
  ADT mapping.
- `db/src/test/kotlin/ed/unicoach/db/dao/ExtractionRunsDaoTest.kt` — ADT
  helper + new cases.
- `db/src/test/kotlin/ed/unicoach/db/dao/SynthesisRunsDaoTest.kt` — same.
- `db/src/test/kotlin/ed/unicoach/db/dao/FitLensRunsDaoTest.kt` — ADT helper,
  pairing-check case, ported round-trips.
- `service/src/main/kotlin/ed/unicoach/coaching/extraction/ExtractionService.kt`
  — delete private `ParseFailure`, use shared `JsonParseFailure`; build outcome
  ADT at write sites.
- `service/src/main/kotlin/ed/unicoach/coaching/synthesis/SynthesisService.kt` —
  same.
- `service/src/main/kotlin/ed/unicoach/coaching/fitlens/FitLensService.kt` —
  build `FitLensOutcome` ADT at `appendRun` call sites.
- `service/src/test/kotlin/ed/unicoach/coaching/extraction/ExtractionServiceTest.kt`
  — extend unparseable-output cases (ADT shape).
- `service/src/test/kotlin/ed/unicoach/coaching/synthesis/SynthesisServiceTest.kt`
  — same.
- `service/src/test/kotlin/ed/unicoach/coaching/fitlens/FitLensServiceTest.kt` —
  port assertions to the `FitLensOutcome` ADT.
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/ExtractionRunsResource.kt`
  — `failureCategory`/`failureReason` fields; `cells()` destructures the ADT.
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/SynthesisRunsResource.kt`
  — same.
- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/FitLensRunsResource.kt`
  — `cells()` destructures the ADT (fields already present).
- `admin-web/src/test/kotlin/ed/unicoach/admin/AdminTestSupport.kt` —
  `seedExtractionRun`/`seedSynthesisRun`/`seedFitLensRun` build the ADT.
- `admin-web/src/test/kotlin/ed/unicoach/admin/resources/ExtractionRunsResourceTest.kt`
  — new case.
- `admin-web/src/test/kotlin/ed/unicoach/admin/resources/SynthesisRunsResourceTest.kt`
  — new case.
- `admin-web/src/test/kotlin/ed/unicoach/admin/resources/FitLensRunsResourceTest.kt`
  — port to ADT-shaped seed.
