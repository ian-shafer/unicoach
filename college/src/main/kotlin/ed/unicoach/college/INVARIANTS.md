# INVARIANTS — college

The college-knowledge capability layer (RFC 67): a connection-owning search
service, the Anthropic tool contract over it, and the re-runnable Scorecard
ingester. All SQL lives in the `db` module's `CollegesDao`; this layer
orchestrates connections, clamps the result cap, and adapts to the tool wire
shape.

## Invariants

### `CollegeSearchTool.execute` is total — it NEVER throws

**Rule:** `execute` MUST return a `JsonObject` for every input — including
malformed input, a search-time DAO failure, and a zero-match query. It MUST NOT
let an exception escape: bad input returns `{ "error": "<reason>" }`, a DAO
failure returns the structured `{ "error": { "kind": "search_failed", ... } }`,
and zero matches return `{ "colleges": [], "count": 0 }`. A zero-match result is
a success outcome, never an error.

**Why:** This tool is built to be registered, unchanged, into a future agentic
tool-use loop where the executor's return value is fed straight back to the
model as the tool result. A thrown exception there aborts the whole chat turn
instead of letting the model see "no matches" or "bad arguments" and recover.
The totality is the contract that makes the deferred wiring pure wiring; an edit
that lets `parseQuery`, the service call, or serialization throw silently breaks
the consumer that does not yet exist to catch it.

### Each loader row upserts inside its own SAVEPOINT

**Rule:** Every per-row upsert in `CollegeScorecardLoader` MUST be wrapped in a
SQL `SAVEPOINT` (see `upsertWithSavepoint`), rolled back to on a failed `Result`
and released on success. A failing row MUST NOT be allowed to abort the
enclosing per-file transaction.

**Why:** All rows of a file load inside one `withConnection` transaction. Once
any statement fails, PostgreSQL aborts the whole transaction (SQLSTATE `25P02`)
and every subsequent statement errors until rollback. Without the per-row
savepoint the first malformed/duplicate row would turn every following row into
a false "skip" and the terminal commit would discard all the good rows already
applied — converting a best-effort ingest into all-or-nothing data loss. The
savepoint scopes the blast radius to the one bad row, which is the entire point
of the best-effort design.

### An out-of-domain optional field is coerced to NULL — a row is NEVER dropped for a bad optional cell

**Rule:** When an _optional_ field's value falls outside its valid domain, that
field MUST be coerced to `NULL` and the row MUST still be persisted. Only a
missing or invalid _required_ field, or an out-of-domain _key_ field, may skip
the whole row. Which fields are optional versus required/key is the loader's own
partition (described in `SPEC.md`); the invariant is that the partition is
honored — an optional field's validity failure MUST NOT escalate to dropping the
row.

**Why:** A parent row anchors its dependent rows by a shared key (an institution
anchors its field-of-study programs by `unit_id`); dropping the parent for one
junk optional cell silently cascades to every dependent beneath it — the exact
failure mode the per-field granularity exists to prevent. Any change that routes
an optional field's domain check through the row-skip path, or reclassifies an
optional field as required, converts a single-cell data-quality quirk into
silent loss of an entire entity and everything keyed to it.

## History

- [x] [RFC-67: College Knowledge](../../../../../../../rfc/67-college-knowledge.md)
- [x] [RFC-78: College Scorecard Real-Data Hardening](../../../../../../../rfc/78-college-scorecard-real-data-hardening.md)
