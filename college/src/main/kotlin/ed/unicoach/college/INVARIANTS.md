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

## History

- [x] [RFC-67: College Knowledge](../../../../../../../rfc/67-college-knowledge.md)
