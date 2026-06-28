# INVARIANTS — db/schema

The append-only SQL migration layer. Files are applied once, in lexicographical
order, by `bin/db-migrate`; the database is the primary enforcement layer for
application invariants.

## Invariants

### Migrations are append-only and never edited after application

**Rule:** A committed migration file MUST NOT be edited, renumbered, or deleted,
and there are NO down-migrations. A schema change is always a new,
higher-numbered `NNNN.kebab-case-name.sql` file; reversion is a full `db-reset`
(drop → create → migrate), never an in-place rollback.

**Why:** `bin/db-migrate` tracks applied files by `version_id` in
`schema_migrations` and skips any already-applied version. Editing an applied
file silently diverges deployed databases (which never re-run it) from a
freshly-migrated one, so the schema stops being reproducible from this directory
— the single fact migration tracking depends on.

### Append-only log and immutable-entity tables keep their write guards

**Rule:** Every table designated a **log** (`convo_requests`, `convo_responses`,
`convo_responses_raw`, `email_sends`, `observations`, `claim_support`,
`extraction_runs`) MUST carry the `prevent_log_update` + `prevent_log_delete`
triggers; the immutable-entity `system_prompts` MUST carry
`prevent_immutable_entity_update` + `prevent_immutable_entity_delete`; and
`claims` MUST carry `prevent_physical_delete` + `prevent_immutable_updates` +
`prevent_physical_timestamp_update` (trigger_00b). A new log or immutable table
MUST attach the same guards; if it carries `row_created_at`, it MUST also attach
`prevent_physical_timestamp_update`.

**Why:** These tables are the audit trail and provenance ledger — observation
evidence, the token-spend record, the exact prompt that produced each turn or
claim. An in-place UPDATE or DELETE destroys the very record the table exists to
preserve, and (for `extraction_runs`/`observations`) corrupts the
watermark/provenance the extraction pass reads back. The guarantee is a DB-level
trigger, not a type, so a future migration adding a table or dropping a trigger
can silently violate it. As of migration 0023, `prevent_immutable_updates()`
guards `id` + `created_at` only; the `row_created_at` guarantee is carried
separately by `prevent_physical_timestamp_update()` on the five tables that have
that column (`users`, `sessions`, `students`, `convos`, `claims`).

## History

- [x] [RFC-05: Database Scripts](../../rfc/05-db-scripts.md)
- [x] [RFC-66: Extraction](../../rfc/66-extraction.md)
- [x] [RFC-82: Versioned Colleges](../../rfc/82-versioned-colleges.md)
