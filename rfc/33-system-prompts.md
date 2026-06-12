# 33 — System Prompts

## Executive Summary

This RFC introduces `system_prompts`: the persistence layer for the
team-authored system prompts that shape every coaching turn, and rewires the
coaching-conversation log (RFC #32) to pin each turn to a specific prompt by
foreign key.

`system_prompts` is an **immutable, insert-only entity**. A row is created once
and never updated or deleted. It is deliberately **not** the RFC #06 versioned
pattern (`users`, `students`): there is no `system_prompt_versions` table, no
`version` OCC column, and no `*_versions` log trigger, because no edits are ever
made. A "new version" of a prompt is simply a new immutable row with a new `id`.
It is governed by `postgres-entity-table-design` (immutable variant), not
`postgres-log-table-design`: it is an unordered, keyed catalog that a log FKs
**to** as authoritative reference state — the role of an entity, not an event
stream. Each row carries a `name` (logical family), a `version` (label, carried
as plain immutable data), and the exact prompt `body` sent to the model, with
`UNIQUE (name, version)`.

Part 2 rewires `convo_requests`. RFC #32 pinned the prompt with a loose
`system_prompt_version TEXT NOT NULL` column (verbatim string, no FK — its
Decision D-2). This RFC removes that column and its three CHECK constraints and
replaces them with a single
`system_prompt_id UUID NOT NULL REFERENCES system_prompts(id) ON DELETE
RESTRICT`.
Because `system_prompts` is immutable, the `id` is a complete and permanent pin
— it resolves forever to the exact `(name, version, body)` that was sent — and
is strictly stronger than the verbatim string (referential integrity guarantees
the prompt row exists and is unchanged).

The change ships as a new forward migration, `0007.create-system-prompts.sql`,
honoring the project's append-only migration convention (`bin/db-migrate` skips
already-recorded `version_id`s, so editing the committed `0006` would silently
never re-run on an already-migrated database). Scope is **schema-only**: one
migration plus DB-level shell tests. No DAO, service, REST, or prompt-assembly
layer.

## Detailed Design

### Entity Configuration — `system_prompts`

| Setting        | Selection                          | Implementation Requirement                                                                                                |
| :------------- | :--------------------------------- | :------------------------------------------------------------------------------------------------------------------------ |
| **ID Type**    | `UUIDv7`                           | `id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7()` — the stable FK pin; also the snapshot identity                           |
| **Mutability** | **Immutable** (insert-only)        | No `updated_at`. A `BEFORE UPDATE` trigger blocks every update (see D-3)                                                  |
| **Timestamps** | Creation only (2-timestamp)        | `created_at` (logical) + `row_created_at` (physical). No `updated_at`/`row_updated_at` — nothing ever updates (see D-2)   |
| **Versioning** | **Disabled** (NOT RFC #06 pattern) | No `version` OCC, no `system_prompt_versions`, no log trigger. A new prompt version is a new immutable row (see D-1)      |
| **Deletions**  | **None**                           | No `deleted_at`. Rows are FK'd-to by a permanent log and can never be deleted; physical `DELETE` blocked by trigger (D-4) |

### Data Model

The migration lives at `db/schema/0007.create-system-prompts.sql` (next free
number; `0006` is the last existing file). It defines, in order: the two
immutable-entity guard functions, then the `system_prompts` table with its
constraints, triggers, and the `UNIQUE (name, version)` constraint, then the
`ALTER TABLE` that rewires `convo_requests`.

#### Immutable-entity guard functions (new shared functions)

`db/schema/0000.shared-functions.sql` is an already-applied, append-only
migration; `bin/db-migrate` skips files whose `version_id` is already recorded,
so editing `0000` would **not** re-run on any existing database. The two new
guards are therefore defined at the top of the new `0007` migration (idempotent
`CREATE OR REPLACE`), exactly as RFC #32 introduced
`prevent_log_update`/`prevent_log_delete` in `0006`.

They are distinct from every existing guard, each of which has a message wrong
for an immutable entity: `prevent_immutable_updates()` blocks only
`id`/`created_at`/ `row_created_at` (partial — it would permit updating
`name`/`version`/`body`); `prevent_physical_delete()` says _"Use soft deletes by
setting deleted_at"_ (there is no `deleted_at`); and
`prevent_log_update`/`prevent_log_delete` mislabel an entity as a "log" and tell
the operator to _"prune by partition/retention"_ (this is permanent reference
data, never pruned). See Decision D-3.

```sql
CREATE OR REPLACE FUNCTION prevent_immutable_entity_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Immutable entity rows cannot be updated.'
    USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_immutable_entity_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Immutable entity rows cannot be deleted.'
    USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;
```

#### `system_prompts` — immutable entity

```sql
CREATE TABLE system_prompts (
  id             UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),

  -- Creation timestamps only (immutable: no update timestamps). created_at is the
  -- logical authoring time, row_created_at the physical insert time; they differ
  -- only when a prompt is backfilled carrying its original authoring date.
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  -- Domain data, all immutable.
  name    TEXT NOT NULL,   -- logical family/key, e.g. 'coach'
  version TEXT NOT NULL,   -- label carried as plain immutable data, e.g. 'v1' / '2026-06-01'
  body    TEXT NOT NULL,   -- the exact prompt text sent to the model

  -- A (family, version) pair maps to exactly one immutable body forever. This is
  -- what makes "a new version is a new row" safe: two different bodies cannot both
  -- claim to be coach/v1.
  CONSTRAINT system_prompts_name_version_unique UNIQUE (name, version),

  -- name/version: bounded, non-empty, canonical (trimmed) — the project-wide
  -- TEXT + named-CHECK text-column convention (postgres-entity-table-design,
  -- "String Types"; see D-5 for why body is exempt from the trimmed check).
  CONSTRAINT system_prompts_name_length_check     CHECK (length(name) <= 255),
  CONSTRAINT system_prompts_name_not_empty_check  CHECK (length(trim(name)) > 0),
  CONSTRAINT system_prompts_name_trimmed_check    CHECK (name = trim(name)),
  CONSTRAINT system_prompts_version_length_check    CHECK (length(version) <= 255),
  CONSTRAINT system_prompts_version_not_empty_check CHECK (length(trim(version)) > 0),
  CONSTRAINT system_prompts_version_trimmed_check   CHECK (version = trim(version)),

  -- body: non-empty and size-bounded, but NOT trimmed. The body is the verbatim
  -- artifact sent to the model and has no raw-payload backup table behind it;
  -- trailing whitespace/newlines may be intentional, so canonicalizing it would
  -- risk altering what reaches the LLM (see D-5).
  CONSTRAINT system_prompts_body_not_empty_check CHECK (length(trim(body)) > 0),
  CONSTRAINT system_prompts_body_size_check      CHECK (octet_length(body) <= 1048576)
);
```

No explicit secondary index: `UNIQUE (name, version)` creates a composite index
whose leading `name` column also serves "all versions of a family" lookups; the
PK serves `id` lookups (the FK join path).

Triggers (BEFORE triggers fire in alphabetical name order — preserving the
`0006` log convention of `trigger_00_*` for the UPDATE guard and `trigger_01_*`
for the DELETE guard; the `00a`/`03` suffixes `0006` uses on the `convos` entity
are unneeded here because `system_prompts` has only these two guard triggers):

```sql
CREATE TRIGGER trigger_00_prevent_system_prompts_update
BEFORE UPDATE ON system_prompts
FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_entity_update();

CREATE TRIGGER trigger_01_prevent_system_prompts_delete
BEFORE DELETE ON system_prompts
FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_entity_delete();
```

The blanket `BEFORE UPDATE` block makes `id`, `created_at`, and `row_created_at`
immutable for free — no separate `prevent_immutable_updates` guard is needed.

#### `convo_requests` rewire (`ALTER`)

```sql
ALTER TABLE convo_requests
  DROP COLUMN system_prompt_version,
  ADD COLUMN system_prompt_id UUID NOT NULL
    REFERENCES system_prompts(id) ON DELETE RESTRICT;
```

`DROP COLUMN system_prompt_version` automatically drops the three CHECK
constraints that depend on it
(`convo_requests_system_prompt_version_length_check`, `_not_empty_check`,
`_trimmed_check` — `0006` lines 123-128). The `ADD COLUMN … NOT
NULL` with no
default is valid because `convo_requests` is empty: RFC #32 is schema-only with
no DAO, so nothing writes the table yet.

`ON DELETE RESTRICT`, deliberately **not** `CASCADE`. Every other FK in `0006`
is `ON DELETE CASCADE` to pre-wire future student-erasure, which flows
child-ward (`students → convos → convo_requests → …`). `system_prompt_id` points
the opposite way, at a **shared parent**: cascading a prompt delete into
`convo_requests` would erase every turn that used that prompt across all
students. `RESTRICT` forbids deleting a prompt while any turn cites it.
Student-erasure is unaffected — it deletes `convo_requests` rows (the child
side); `system_prompts` (the parent) is untouched. In practice the prompt can
never be deleted at all (the `prevent_immutable_entity_delete` guard), so
`RESTRICT` is a second, declarative line of defense and a statement of intent.
See Decision D-6.

### Why an FK replaces the verbatim string

RFC #32 D-2 chose a verbatim `TEXT` pin precisely because there was no prompt
registry yet, and argued a verbatim pin survives even if a registry row is later
changed or dropped. This RFC supplies that registry and makes the pin an FK,
which is strictly stronger here:

- **Immutability** means the `id` permanently resolves to the exact prompt;
  there is no drift the verbatim string would have guarded against.
- The verbatim string's one advantage — surviving a dropped registry row — is
  preserved by construction: `system_prompts` rows can never be updated or
  deleted (guards) and the FK is `RESTRICT`, so the referenced row is guaranteed
  to exist unchanged for the life of the turn.

No companion `version` column is denormalized onto `convo_requests`: it is
reachable via the FK and never changes, so storing it again would be redundant.

### Error Handling / Edge Cases

- Insert `system_prompts` with NULL `name`, `version`, or `body` → NOT NULL
  violation `23502`.
- Duplicate `(name, version)` → unique violation `23505`
  (`system_prompts_name_version_unique`).
- `name`/`version` too long, blank, or untrimmed; `body` blank or over 1 MiB →
  CHECK violation `23514` (the named constraint).
- A `body` with leading/trailing whitespace is **accepted** (no trim check by
  design).
- `UPDATE` on `system_prompts` → `P0001` from
  `prevent_immutable_entity_update()` (_"Immutable entity rows cannot be
  updated."_).
- `DELETE` on `system_prompts` → `P0001` from
  `prevent_immutable_entity_delete()` (_"Immutable entity rows cannot be
  deleted."_).
- Insert `convo_requests` with NULL `system_prompt_id` → NOT NULL violation
  `23502`.
- Insert `convo_requests` with a non-existent `system_prompt_id` → FK violation
  `23503`.
- Reference the dropped `system_prompt_version` column on `convo_requests`
  (insert or update) → undefined-column error `42703` — the column and its three
  CHECKs no longer exist after `0007`.
- Attempt to delete a `system_prompts` row referenced by a `convo_requests` row
  → blocked at two layers: the immutable-entity delete guard (`P0001`) fires
  first; were the guard ever lifted, the `ON DELETE RESTRICT` FK would still
  reject it (`23503`).

### Dependencies

- Shared trigger infrastructure and `uuidv7()` in
  `db/schema/0000.shared-functions.sql` / the `pg_uuidv7` extension, PostgreSQL
  18, per existing schema infrastructure. No new external libraries or
  extensions.
- `convo_requests` table (RFC #32,
  `db/schema/0006.create-coaching-conversations.sql`), whose
  `system_prompt_version` column this migration replaces.
- `bin/db-migrate` (RFC #05) applies the new migration in lexicographical order.
- DB tests reuse the RFC #32 shell-harness infrastructure: `bin/common` (which
  sets `set -e`, the mechanism by which the `bin/db-tests` aggregator aborts on
  the first failing harness), `bin/tests-common`
  (`assert_success`/`assert_failure`, `pass_test`/`fail_test`, `end_tests`),
  `bin/db-run` / `bin/db-query`, and the aggregator `bin/db-tests`. The new
  harness owns no Postgres lifecycle (same contract as `bin/db-convos-tests`):
  no `EXIT`/`INT`/`TERM` trap, no
  `postgres-up`/`postgres-down`/`db-init`/`db-migrate`; it assumes the live,
  migrated DB the caller provides.

## Tests

Schema-only RFC → tests are shell harnesses, following the RFC #32 lifecycle
contract exactly: each harness assumes a live, already-migrated database
supplied by the caller (`bin/test` or a developer), owns no Postgres lifecycle,
and asserts schema behavior through SQL via `bin/db-run rw` / `bin/db-query`
with `assert_success` / `assert_failure`. `bin/db-run` runs psql with
`ON_ERROR_STOP=1`, so a statement rejected by a constraint or trigger exits
non-zero; where two rejection mechanisms must be distinguished, the harness
greps the error for the **named constraint or trigger message** (part of the
schema contract). Per-assertion writes run inside `BEGIN ...
ROLLBACK` so they
never persist, making cases order-independent.

### `bin/db-system-prompts-tests` (new)

`system_prompts` has no FK dependencies, so the harness needs no committed
fixture; every case is a self-contained `BEGIN/ROLLBACK` insert. Cases use a
namespaced `name` (e.g. `sp-test-coach`) to avoid colliding with the `convos`
harness fixture prompt.

Assertions (each an `assert_success` / `assert_failure` case):

- A minimal insert supplying `name`, `version`, `body` is accepted; `id`,
  `created_at`, and `row_created_at` take their defaults (all non-NULL).
- NULL `name` is rejected (NOT NULL, `23502`); NULL `version` is rejected; NULL
  `body` is rejected.
- A 256-char `name` is rejected (`system_prompts_name_length_check`); a
  whitespace-only `name` is rejected (`system_prompts_name_not_empty_check`); a
  `name` with leading or trailing whitespace is rejected
  (`system_prompts_name_trimmed_check`).
- A 256-char `version` is rejected (`system_prompts_version_length_check`); a
  whitespace-only `version` is rejected
  (`system_prompts_version_not_empty_check`); a `version` with leading or
  trailing whitespace is rejected (`system_prompts_version_trimmed_check`).
- A whitespace-only `body` is rejected (`system_prompts_body_not_empty_check`);
  a `body` over 1 MiB is rejected (`system_prompts_body_size_check`).
- A `body` with leading/trailing whitespace (e.g. `"...\n\n"`) is **accepted** —
  locks in the deliberate absence of a body trim check (D-5).
- `UNIQUE (name, version)`: a second insert with the same `(name, version)` is
  rejected (`system_prompts_name_version_unique`, `23505`); the same `name` with
  a different `version` is accepted; a different `name` with the same `version`
  is accepted.
- `UPDATE system_prompts SET body = ...` is rejected and the error matches
  `Immutable entity rows cannot be updated.`
  (`prevent_immutable_entity_update`).
- `DELETE FROM system_prompts` is rejected and the error matches
  `Immutable entity rows cannot be deleted.`
  (`prevent_immutable_entity_delete`).
- Backfill: an insert that sets `created_at` to a fixed past literal (e.g.
  `'2020-01-01T00:00:00Z'`) and leaves `row_created_at` defaulted (`NOW()`) is
  accepted, and the row reads back with `created_at < row_created_at` —
  confirming the two creation timestamps are independent (the reason
  `row_created_at` is carried).
- `prevent_immutable_entity_update()` and `prevent_immutable_entity_delete()`
  exist after migration (queried from `pg_proc`), confirming `0007` created the
  new guards.

### `bin/db-convos-tests` (modified for the rewire)

- The fixture inserts a committed `system_prompts` row (a distinct
  `name`/`version`, e.g. `convos-fixture-prompt`/`v1`) and captures
  `SYSTEM_PROMPT_ID`. This insert MUST precede the harness's first committed
  `convo_requests` insert (the `REQUEST_ID` fixture and, after it, the
  `CASCADE_*` subtree), because both now require a non-NULL `system_prompt_id`
  FK — `SYSTEM_PROMPT_ID` must already be defined when they run. Every
  `convo_requests` insert across the harness (fixture, `CASCADE_*` subtree, and
  every case) replaces `system_prompt_version, … 'v1'` with
  `system_prompt_id, … '$SYSTEM_PROMPT_ID'`. The committed fixture row carries
  the same re-run hazard as the existing committed `users`/`students`/`convos`
  fixtures (a fixed, `UNIQUE`-constrained key collides if the harness is run
  twice against a DB that was not recreated); it relies on `bin/test`'s
  `db-destroy → db-init → db-migrate` recreate, exactly as those fixtures
  already do.
- The two cases asserting the removed column's constraints are deleted:
  `test_convo_requests_blank_system_prompt_version` and
  `test_convo_requests_system_prompt_version_untrimmed`.
- `test_convo_requests_update_rejected` updates a still-existing column
  (`SET model_requested = 'x'`) instead of the dropped `system_prompt_version`;
  it still asserts the `prevent_log_update` message. The target column/value is
  immaterial: the `BEFORE UPDATE` log guard `RAISE`s unconditionally before any
  CHECK is evaluated, so any still-present column with any value triggers the
  same rejection.
- New: a regression case locking in the column drop — an
  `INSERT INTO convo_requests` (or `UPDATE`) that names `system_prompt_version`
  is rejected with `42703` (undefined column), proving `0007` removed the column
  and its three CHECKs rather than leaving them alongside the new FK.
- New: a `convo_requests` insert with NULL `system_prompt_id` is rejected (NOT
  NULL, `23502`); a `convo_requests` insert with a non-existent
  `system_prompt_id` is rejected (FK, `23503`). A valid `system_prompt_id` is
  exercised by every other case.
- `test_cascade_delete_rules` is **deleted**. The current test asserts that
  **every** FK on the four tables reports `delete_rule = CASCADE` (a blanket
  `grep -qv ':CASCADE$'` plus a `>= 5` count). After the rewire that blanket
  form **regresses**: `convo_requests` gains a sixth FK,
  `convo_requests_system_prompt_id_fkey` (Postgres' default
  `<table>_<column>_fkey` name for the unnamed inline FK on `system_prompt_id`),
  whose `delete_rule` is `RESTRICT`, which the blanket grep rejects. Rather than
  rewrite it as a scoped static-catalog assertion, the case is dropped: the
  cascade/RESTRICT topology is latent pre-wiring, masked on every table by a
  BEFORE-DELETE guard that rejects all physical deletes before any `ON DELETE`
  action can fire, so the rule is never exercised behaviorally; it becomes
  reachable — and is asserted on observed row effects — only when the RFC #31
  student-erasure path ships and lifts the guards (see D-8). The FK declarations
  are verified by `\d convo_requests` at implementation time (Plan step 1). The
  pre-existing behavioral guard test `test_cascade_inert_on_convos_delete` is
  **retained** — it asserts the BEFORE-DELETE guard blocks `DELETE FROM convos`
  and the committed subtree survives; its `CASCADE_*` fixture's `convo_requests`
  insert is rewired to carry `system_prompt_id` like every other insert in the
  harness. The `==== Cascade topology ... ====` section banner is kept (the
  retained behavioral guard test still lives under it); only the static
  `delete_rule` case is removed.

### `bin/db-tests` (modified)

The current aggregator is two lines of logic: `source common` (which sets
`set -e`), then a single `"$PROJECT_ROOT/bin/db-convos-tests"` call. A new line
`"$PROJECT_ROOT/bin/db-system-prompts-tests"` is inserted **before** that call.
No explicit exit-status guard is added: the inherited `set -e` already aborts
the aggregator the moment any harness exits non-zero (the same mechanism that
already guards `db-convos-tests`). No Postgres-lifecycle ownership is added
(unchanged contract).

## Implementation Plan

1. **Migration.** Create `db/schema/0007.create-system-prompts.sql` containing,
   in order: `prevent_immutable_entity_update()` and
   `prevent_immutable_entity_delete()` (`CREATE OR REPLACE`);
   `CREATE TABLE system_prompts` with all constraints, the
   `UNIQUE (name, version)` constraint, and the two
   `BEFORE UPDATE`/`BEFORE DELETE` triggers; then
   `ALTER TABLE convo_requests DROP COLUMN system_prompt_version, ADD
   COLUMN system_prompt_id UUID NOT NULL REFERENCES system_prompts(id) ON DELETE
   RESTRICT`.
   Verify:
   - `nix develop -c bin/postgres-up && nix develop -c bin/db-init && nix develop -c bin/db-migrate && nix develop -c bin/db-status`
   - `nix develop -c psql "$DATABASE_URL" -c '\d system_prompts' -c '\d convo_requests'`
     — `system_prompts` present with both triggers and the unique constraint;
     `convo_requests` shows `system_prompt_id` (NOT NULL, FK
     `ON DELETE RESTRICT`) and **no** `system_prompt_version` column or its
     CHECKs.
   - `nix develop -c psql "$DATABASE_URL" -c '\df prevent_immutable_entity_update' -c '\df prevent_immutable_entity_delete'`

2. **System-prompts schema harness.** Add `bin/db-system-prompts-tests`
   (executable; `chmod +x`), modeled on `bin/db-convos-tests` and owning no
   Postgres lifecycle (no `EXIT`/`INT`/`TERM` trap, no
   `postgres-up`/`postgres-down`/`db-init`/`db-migrate`). Implement every
   assertion in the Tests section with `assert_success` / `assert_failure` over
   `bin/db-run rw` / `bin/db-query`, ending with `end_tests`. Verify (against
   the already-migrated DB from step 1):
   - `nix develop -c bin/db-system-prompts-tests` — all assertions pass; the
     script exits `0` and prints the `N tests passed` summary.

3. **Rewire the convos harness.** Modify `bin/db-convos-tests`: add the
   committed `system_prompts` fixture row and `SYSTEM_PROMPT_ID` **before the
   first committed `convo_requests` insert** (the `REQUEST_ID` fixture and the
   `CASCADE_*` subtree both reference it); replace every `system_prompt_version`
   usage with `system_prompt_id`; delete the two obsolete
   `system_prompt_version` cases; repoint `test_convo_requests_update_rejected`
   to an existing column; add the NULL/FK `system_prompt_id` cases; **delete**
   `test_cascade_delete_rules` (its blanket-`CASCADE` introspection regresses
   under the new `RESTRICT` FK; topology coverage is deferred to a higher-level
   erasure test — see D-8 — and the retained
   `test_cascade_inert_on_convos_delete` guard test is unchanged). Verify:
   - `nix develop -c bin/db-convos-tests` — green against the migrated DB.

4. **Aggregate.** Modify `bin/db-tests` to invoke `bin/db-system-prompts-tests`
   (insert the line before the existing `bin/db-convos-tests` line). A failure
   aborts the run via the `set -e` inherited from `bin/common`; do not add a
   redundant exit-status guard. Verify:
   - `nix develop -c bin/db-tests` — both harnesses green against the migrated
     DB.
   - `nix develop -c bin/test` — runs the shell DB tests and the Gradle suite;
     both green; Postgres still up when Gradle runs.

## Files Modified

### New

- `db/schema/0007.create-system-prompts.sql` — the migration: two
  immutable-entity guard functions, the `system_prompts` table (constraints,
  unique, triggers), and the `convo_requests` rewire `ALTER` (discovered
  automatically by `bin/db-migrate`'s `DB_SCHEMA_DIR` scan; no registration
  needed).
- `bin/db-system-prompts-tests` — shell schema-test harness for `system_prompts`
  (executable).

### Modified

- `bin/db-convos-tests` — rewire every `convo_requests` insert from
  `system_prompt_version` to a `system_prompt_id` FK (new committed
  `system_prompts` fixture row); delete the two obsolete `system_prompt_version`
  constraint cases; repoint the UPDATE-rejected case to an existing column; add
  NULL/FK `system_prompt_id` cases; delete `test_cascade_delete_rules` (the
  blanket-`CASCADE` catalog assertion regresses under the new `RESTRICT` FK;
  topology coverage deferred — see D-8). `test_cascade_inert_on_convos_delete`
  is retained (its `CASCADE_*` fixture insert is rewired to `system_prompt_id`).
- `bin/db-tests` — insert a `bin/db-system-prompts-tests` invocation line before
  the existing `bin/db-convos-tests` line. Failure aborts the run via the
  `set -e` inherited from `bin/common` (no explicit exit-status guard is added —
  none exists today).

`db/schema/0006.create-coaching-conversations.sql` is **not** modified — the
append-only convention requires the change to ship as the new `0007` migration
(an in-place edit would never re-run on an already-migrated database). `rfc/32`
is left as the historical record; this RFC supersedes its Decision D-2.

## Decisions

Resolved with the architect during the design session.

### D-1 — `system_prompts` is an immutable, insert-only entity (NOT the RFC #06 versioned pattern)

A "new version" of a prompt is a new immutable row with a new `id`, not an OCC
bump on a mutable row. There is no `system_prompt_versions` table, no `version`
OCC column, and no `*_versions` log trigger — the entire versioned-entity
machinery is absent because nothing is ever edited. The `version` column is
plain immutable label data, not an OCC counter.

### D-2 — Governed by `postgres-entity-table-design` (immutable variant), not the log skill

Immutability is a write pattern, not a role. A log is an ordered event stream
belonging to a stream key, folded over by replay/analytics, and "rarely
FK'd-to." `system_prompts` is the opposite: an unordered, keyed catalog that a
log (`convo_requests`) FKs **to** as authoritative reference state. The log
skill itself warns against entities pointing at log rows as authoritative state
and recommends pinning to a versioned/snapshot table — an immutable
`system_prompts` row _is_ that snapshot. It therefore follows the entity skill's
immutable variant (the `devices` example): `created_at` only-class timestamps,
no `updated_at`, immutability enforced by trigger.

### D-3 — New `prevent_immutable_entity_*` guards, not reuse of existing guards

Two new functions block all `UPDATE`s and all `DELETE`s, defined in `0007` via
`CREATE OR REPLACE`. No existing guard fits: `prevent_immutable_updates()` is
partial (permits updating domain columns); `prevent_physical_delete()` advises a
non-existent `deleted_at`; and `prevent_log_update`/`prevent_log_delete`
mislabel an entity as a "log" and tell the operator to "prune by
partition/retention" (this is permanent reference data). This mirrors RFC #32's
own reasoning, which declined to reuse `prevent_physical_delete()` for its logs
precisely because the message was wrong — the same logic, applied in the other
direction, forbids reusing the log guards for an entity. Named to the codebase's
`prevent_*` family rather than the entity skill's `enforce_immutability()` for
consistency with the four existing `prevent_*` guards.

### D-4 — No soft-delete

No `deleted_at`. A `system_prompts` row is immutable and FK'd-to by a permanent,
append-only log, so it can never be deleted — there is no delete to soft-model.
"Active vs. retired" is mutable selection state that would contradict
immutability; it is an application/config concern (choose an `id`, or the latest
`version` of a `name`), not a column. The catalog is team-authored, not personal
data, so it is outside the RFC #31 student-erasure cascade.

### D-5 — `body` is non-empty and size-bounded but NOT trimmed

`name` and `version` carry the standard length/not-empty/trimmed checks (the
project-wide `TEXT` + named-`CHECK` convention from
`postgres-entity-table-design`, "String Types"). `body` is the verbatim artifact
sent to the model with no raw-payload backup table behind it (unlike
`convo_*.content`, backed by `convo_responses_raw`). Trailing whitespace or
newlines in a prompt may be intentional and are sent as-is, so a
`body = trim(body)` check would risk silently altering what reaches the LLM.
`body` keeps only a non-empty check (rejecting whitespace-only) and a 1 MiB size
bound.

### D-6 — `convo_requests` pins a single `system_prompt_id` FK, `ON DELETE RESTRICT`

The `system_prompt_version TEXT NOT NULL` column and its three CHECKs are
removed and replaced with
`system_prompt_id UUID NOT NULL REFERENCES system_prompts(id) ON DELETE
RESTRICT`.
Immutability makes the `id` a complete, permanent pin, so no companion `version`
column is denormalized (it is reachable via the FK and never drifts).
`RESTRICT`, not `CASCADE`: `system_prompt_id` points at a shared parent, and
cascading a prompt delete into `convo_requests` would erase every turn that used
the prompt across all students. The five existing `0006` FKs remain `CASCADE`
(the child-ward student-erasure chain); `system_prompt_id` is the deliberate
exception. The static cascade-topology test is dropped rather than extended to
cover the new `RESTRICT` FK; see D-8.

### D-7 — New `0007` forward migration, not an in-place edit of `0006`

`bin/db-migrate` records applied `version_id`s and skips them, so editing the
committed `0006` would never re-run on a database that already applied it,
silently drifting its schema. The change ships as
`0007.create-system-prompts.sql`, which creates the table and guards and then
`ALTER`s `convo_requests` (the `ADD COLUMN … NOT NULL` is safe on the empty
table). This honors the project's append-only migration convention and reaches
every already-migrated database.

### D-8 — Drop the static cascade-topology test rather than rewrite it (topology asserted at a higher level later)

`test_cascade_delete_rules` (RFC #32, `bin/db-convos-tests`) introspects
`information_schema.referential_constraints` and asserts every erasure-chain FK
declares `delete_rule = CASCADE` via a blanket grep. The `system_prompt_id`
rewire adds a `RESTRICT` FK that breaks that grep, forcing a choice: rewrite it
as a scoped static assertion (five named `CASCADE` FKs + one `RESTRICT`), or
remove it.

It is removed. Static catalog introspection asserts a DDL _declaration_, not
behavior — and here the declared cascade/RESTRICT topology is **inert by
construction**: every table in the chain (and `system_prompts`) carries a
BEFORE-DELETE guard that rejects all physical deletes before any `ON DELETE`
action can fire, so no real delete ever exercises the rule. The `CASCADE` half
is moreover Postgres-builtin behavior, not this schema's code. The topology is
forward-wiring for the RFC #31 student-erasure path; it becomes reachable — and
is then asserted behaviorally, on the observed row effects of a real cascading
delete — only when that path ships and lifts the guards. A static check here
would buy regression protection on latent wiring, but the architect's call is to
defer that to the higher-level erasure test rather than carry a catalog-shape
assertion in the schema suite. The FK rules are still verified by
`\d convo_requests` at implementation time (Plan step 1).

This drops only the static `delete_rule` assertion; behavioral guard coverage is
unchanged. `test_cascade_inert_on_convos_delete` still proves the BEFORE-DELETE
guard blocks `DELETE FROM convos` and the committed subtree survives, and the
new `system_prompts` harness proves `DELETE FROM system_prompts` is rejected by
the immutable-entity guard.
