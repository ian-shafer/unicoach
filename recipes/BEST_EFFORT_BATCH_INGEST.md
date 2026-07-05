# Recipe: Best-effort batch ingest

Load a large external dataset row by row where individual rows may be malformed,
duplicated, or out of domain, and one bad row must **not** lose the good ones.
"Best-effort" means: apply every row that can be applied, skip or repair the
rest, and account for what was skipped.

Best-effort is one of only **two** sanctioned processing contexts — the other is
**all-or-nothing** (atomic: any bad row aborts and rolls back the whole load).
The forbidden third is **partial processing**: halting mid-way on a non-fatal
error, leaving some rows applied and the rest unattempted in an inconsistent
state. Best-effort's non-negotiable half is **explicit reporting** — every
skipped or repaired row is counted and surfaced, never silently dropped. This is
the `design-review-best-effort-vs-all-or-nothing` review lens; the recipe below
is how to satisfy it for a bulk load.

The worked example is `college/.../CollegeScorecardLoader.kt` (the College
Scorecard ingester). Use this pattern for any similar bulk load from an
untrusted source; use all-or-nothing instead when a partial load would leave the
data inconsistent.

## How it works

A file loads inside one `database.withConnection { … }` transaction. Two
independent mechanisms keep a single bad row from poisoning that transaction:

1. **A pure mapper partitions each row's fields.** `mapRow` validates one record
   and returns either `Skipped(reason)` or `Mapped(value, coercedCells)` — it
   never touches the DB or the accumulator. The partition is the crux:
   - **Required / key fields** (e.g. `unit_id`, `name`): missing or
     out-of-domain → **skip the whole row** (`MapResult.Skipped`). A key field
     anchors dependents, so a bad key must drop the row, not persist a dangling
     one.
   - **Optional fields** (e.g. `region`, `locale`): out of domain → **coerce to
     `NULL` and keep the row** (`intInDomainOrNull(…, coercions)`). An optional
     field's bad cell must never escalate to dropping the row and everything
     keyed to it.

2. **Each upsert runs in its own SQL `SAVEPOINT`.** Even a well-mapped row can
   fail at write time (a CHECK or unique violation the mapper can't foresee).
   `upsertWithSavepoint(session) { CollegesDao.upsert(session, row) }` wraps the
   write: `SAVEPOINT` before, `RELEASE` on success, `ROLLBACK TO` on a failed
   `Result`. This is not optional bookkeeping — without it, PostgreSQL aborts
   the **entire** transaction on the first failed statement (SQLSTATE `25P02`),
   every later row falsely "skips", and the terminal commit discards all the
   good rows already applied.

Every skip and coercion is tallied in the per-file accumulator (`LoadCount` →
`LoadResult`) and DEBUG-logged once, so the load reports exactly what it dropped
or repaired — silent loss is the failure mode this whole pattern exists to
prevent.

## Read these first

- `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt` — the
  reference loader: `load`, the per-file `loadInstitutions`/`loadFields`,
  `upsertWithSavepoint`, the pure `mapInstitution`/`mapField`, and the
  `MapResult` / `LoadCount` types.
- `db/src/main/kotlin/ed/unicoach/db/dao/` — the DAO `upsert` the write step
  calls; it returns a `Result`, never throws for an expected constraint failure.

## Build one

1. **Open one transaction per file** with `database.withConnection`. All rows of
   the file share it.
2. **Write a pure mapper** `record -> MapResult`. Decide the required/key vs
   optional partition up front: a bad required/key field returns `Skipped`; a
   bad optional field is coerced (`…OrNull` into a `coercions` map) and the row
   stays `Mapped`. The mapper must not read the DB or mutate the accumulator.
3. **Wrap every write in `upsertWithSavepoint`.** Never issue a bare upsert
   inside the shared transaction.
4. **Tally skips and coercions** in the accumulator and DEBUG-log each once,
   then fold the accumulator into the returned result so callers see the counts.

## When not to use

If a partial load would leave the data set internally inconsistent — rows that
only make sense as a complete unit — use an all-or-nothing load (one
transaction, any failure rolls back the whole thing) instead. Best-effort is for
independent rows from an untrusted source where applying the good ones is
strictly better than applying none.
