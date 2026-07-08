# INVARIANTS — db/schema

The append-only SQL migration layer. Files are applied once, in lexicographical
order, by `bin/db-migrate`; the database is the primary enforcement layer for
application invariants.

## Invariants

### Migrations are append-only and never edited after application

**Rule:** A successfully applied migration file MUST NOT be edited, renumbered,
or deleted, and the schema only ever moves forward: every change is a new,
higher-numbered `NNNN.kebab-case-name.sql` file. There is no rollback or reverse
migration — to undo a change you add a new, compensating one. (`db-reset` — drop
→ create → migrate — is a **dev-only** rebuild from scratch; it destroys all
data and MUST NEVER be run against a deployed database.) The one exception to
immutability: a migration that _failed_ to apply in a deployed environment may
be edited to fix the failure — its transaction (including the
`schema_migrations` insert) rolled back, so it was never recorded as applied.

**Why:** `bin/db-migrate` tracks applied files by `version_id` in
`schema_migrations` and skips any already-applied version. Editing an applied
file silently diverges deployed databases (which never re-run it) from a
freshly-migrated one, so the schema stops being reproducible from this directory
— the single fact migration tracking depends on.

## History

- [x] [RFC-05: Database Scripts](../../rfc/05-db-scripts.md)
- [x] [RFC-66: Extraction](../../rfc/66-extraction.md)
- [x] [RFC-82: Versioned Colleges](../../rfc/82-versioned-colleges.md)
- [x] [RFC-91: College List](../../rfc/91-college-list.md)
- [x] [RFC-93: Synthesis](../../rfc/93-synthesis.md)
