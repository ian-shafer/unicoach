# INVARIANTS — college

The college-knowledge capability layer (RFC 67): a connection-owning search
service, the Anthropic tool contract over it, and the re-runnable Scorecard
ingester. All SQL lives in the `db` module's `CollegesDao`; this layer
orchestrates connections, clamps the result cap, and adapts to the tool wire
shape.

## Invariants

### An out-of-domain optional field is coerced to NULL — a row is NEVER dropped for a bad optional cell

**Rule:** When an _optional_ field's value falls outside its valid domain, that
field MUST be coerced to `NULL` and the row MUST still be persisted. Only a
missing or invalid _required_ field, or an out-of-domain _key_ field, may skip
the whole row. Which fields are optional versus required/key is the loader's own
partition (defined in the loader code); the invariant is that the partition is
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
