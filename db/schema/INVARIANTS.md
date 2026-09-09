# INVARIANTS — db/schema

The append-only SQL migration layer. Files are applied once, in the order
declared by `db/schema/ORDER`, by `bin/db-migrate`; the database is the primary
enforcement layer for application invariants.

`ORDER` is the single source of apply order (RFC 180): one filename per line, no
numbers, no comments, no blank lines. A new migration is `<kebab-slug>.sql` with
no numeric prefix — uniqueness is the slug, order is the line — and its line is
appended at the end. The numbered `NNNN.<slug>.sql` files are historical: they
are listed in `ORDER` in the lexicographic order they were applied in, and they
are **never renamed**. `bin/db-migrate` refuses, before applying anything, when
`ORDER` and the directory disagree; `bin/db-status` reports the same
disagreements instead of refusing, because a reporting command must still
report.

**`ORDER` is merged by git, not by you.** `.gitattributes` marks it
`merge=union`, so a rebase or a merge never stops on it: git keeps **both**
sides' lines — the newly landed lines first, this branch's line last — and
reports no conflict. That is deliberate: two runs appending one line each at the
same end of the file have nothing interesting to resolve.

The cost is that union can never fail, so it can also produce a **duplicate**
line — the same filename appended by two runs, or an append replayed onto a base
that already has it. Nothing in git will tell you. The validators are what catch
it: `bin/db-migrate` refuses a duplicated line, and `/ship`'s
`scripts/ship-order` refuses one at land time and rewrites `ORDER` as the base
branch's copy plus this run's own lines, so the landed order is derived rather
than merge-order-dependent. When you edit `ORDER` by hand after a merge, read
the whole file: never reorder or delete a landed line, and never leave a
filename on two lines.

Applied migrations are recorded in `schema_migrations`, keyed on `filename`;
there is no `version_id` column and no stored ordinal — the order is `ORDER`'s
to hold.

## Invariants

### Migrations are append-only and never edited after application

**Rule:** A successfully applied migration file MUST NOT be edited, renamed, or
deleted, and neither may its line in `db/schema/ORDER` be removed or moved. The
schema only ever moves forward: every change is a new `kebab-case-name.sql` file
whose line is appended to `ORDER`. There is no rollback or reverse migration —
to undo a change you add a new, compensating one. (`db-reset` — drop → create →
migrate — is a **dev-only** rebuild from scratch; it destroys all data and MUST
NEVER be run against a deployed database.) The one exception to immutability: a
migration that _failed_ to apply in a deployed environment may be edited to fix
the failure — its transaction (including the `schema_migrations` insert) rolled
back, so it was never recorded as applied.

**Why:** `bin/db-migrate` tracks applied files by `filename` in
`schema_migrations` and skips any already-applied file. Editing an applied file
silently diverges deployed databases (which never re-run it) from a
freshly-migrated one, so the schema stops being reproducible from this directory
— the single fact migration tracking depends on.

## History

- [x] [RFC-05: Database Scripts](../../rfc/05-db-scripts.md)
- [x] [RFC-66: Extraction](../../rfc/66-extraction.md)
- [x] [RFC-82: Versioned Colleges](../../rfc/82-versioned-colleges.md)
- [x] [RFC-91: College List](../../rfc/91-college-list.md)
- [x] [RFC-93: Synthesis](../../rfc/93-synthesis.md)
- [x] [RFC-106: Provider-agnostic LLM call log](../../rfc/106-provider-agnostic-llm-call-log.md)
