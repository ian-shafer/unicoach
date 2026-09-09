# RFC 180 — `db/schema/ORDER`: the apply order becomes a declared file

Lane A. Fixes the migration ordering hole: the four-digit filename prefix is a
claim about apply order that nothing enforces and no run can keep.

## Summary

`bin/db-status` computes pending migrations as a set difference — every
`db/schema/*.sql` not present in `schema_migrations` — and emits them in **shell
glob order**. `bin/db-migrate` applies that list in the order it arrives. The
total order over migrations is therefore emergent from a glob, declared nowhere,
tested nowhere, and derived from a filename prefix that runs must renumber.

Concretely: a migration authored as `0086` and landed after `0087..0090` had to
be renamed four times (RFC 169: `0086 → 0087 → 0089 → 0091`), and the worktree
twice held two files claiming the same number, caught by a human. A run that
skipped the rename would have applied its migration _before_ migrations that
landed _before_ it — silently, since a lower prefix simply sorts earlier.

The prefix cannot be kept honest, because a run claims a number hours before it
lands and cannot know what lands in between. This RFC stops trying. Apply order
becomes an explicit file, `db/schema/ORDER`, written at land time — when the
order is finally a fact rather than a prediction.

Three consequences follow. New migration filenames drop the number and carry
only a slug, so two runs choosing the same name collide in git as an add/add
instead of silently sharing a position. `schema_migrations` keys on `filename`
and `version_id` is dropped. The existing files are never renamed: `ORDER` is
generated from their current lexicographic order, which is exactly the order in
which they were applied.

## Decisions

**D1. `ORDER` is a plain list of filenames, one per line, in apply order.** No
numbers, no comments, no blank lines, no header. Every non-empty line is a
migration filename that must exist in `$DB_SCHEMA_DIR`; the file ends with a
newline.

The format is chosen for the merge, not for the reader. A run appends exactly
one line at the end of the file, so two concurrent runs produce two appends at
the same EOF hunk — the one case a `merge=union` driver resolves correctly and
in the right direction (see D6). A comment, a section header, or a
number-per-line would all put non-append edits in the same hunk and break that
property. Reading it needs nothing: `cat db/schema/ORDER` _is_ the apply order.

`ORDER` lives inside `$DB_SCHEMA_DIR` (`bin/common:95`), not at a hard-coded
repo path, because both shell harnesses override that variable to run against
scratch schema directories. It is not matched by the `*.sql` glob, so no
enumeration picks it up as a migration.

**D2. `db-migrate` walks `ORDER`, and refuses on any disagreement with disk.**
Pending = the `ORDER` lines not yet in `schema_migrations`, applied top to
bottom. Before applying anything, `db-migrate` fatals when:

- a `db/schema/*.sql` file on disk is absent from `ORDER` (an unordered
  migration — the RFC 169 defect, now loud);
- an `ORDER` line names a file not on disk;
- `ORDER` lists the same filename twice;
- `ORDER` contains a blank line (the refusal names the line number);
- `ORDER` is missing or empty while `*.sql` files exist.

A filename that matches neither grammar (D5) is a sixth refusal, checked in the
same pass rather than inside the apply loop — an illegal name at position 12
must not abort after eleven migrations have already committed.

The refusal names every offending file of every kind in one message, and it
happens **before the first `BEGIN`**, so a corpus that disagrees with itself
never half-applies. Corpus drift exits `EXIT_SCHEMA_ORDER_DRIFT`
(`bin/functions`), distinct from status 1, so a caller can tell a disagreeing
corpus from a missing schema directory or a migration that failed. Two
migrations can no longer share a position because a position _is_ a line number
in one file, and a duplicate line is one of the four refusals.

**D3. `db-status` enumerates through `ORDER` too, and reports drift instead of
refusing it.** It is the reporting face of the same corpus; leaving it on the
glob would make it a second, disagreeing source of truth. The checks live in one
place in `bin/common` — `schema_order_scan` records every disagreement and never
fails, `schema_order_assert_clean` is the single fatal — so the writer and the
reader compute drift identically and only differ in what they do about it.

`db-migrate` refuses. `db-status` does not: an unordered file is an everyday
authoring state, and a read-only reporting tool that dies on it prints nothing
about the other 94 migrations — while `db-restore` tells operators to diagnose
with `db-status`. So drift becomes payload: `[UNORDERED]` for a file on disk no
`ORDER` line names, `[MISSING]` for an `ORDER` line with no file, `[UNKNOWN]`
for an applied row neither knows, alongside the usual `[APPLIED]`/`[PENDING]`
rows and a warning on stderr. The machine formats (`-f applied-only`,
`-f unapplied-only`) keep drift out of stdout entirely.

**D4. `version_id` is dropped; `schema_migrations` keys on `filename`.**

The alternative — store the `ORDER` ordinal — is rejected on the repo's own
schema convention: _derived figures, anything computable from stored columns,
are computed at read time and never stored_. An ordinal is a copy of a fact that
`ORDER` already holds, and a copy that goes stale the moment `ORDER` is appended
to by another run. It also re-creates the defect this RFC removes: a number in
the database claiming an order the file actually owns.

Nothing needs it. `filename` is the natural key (a migration is applied exactly
once), the two readers that ordered by `version_id` do not need a total order —
`bin/db-status:54` uses the result as a membership set, and `bin/db-restore:344`
wants the most recently applied row, which is `applied_at DESC` and always was.
The historical apply order of an existing database is preserved in `applied_at`.

`version_id VARCHAR(4)` is also a hard blocker: it cannot hold a slug filename,
so it must be reshaped or dropped in any case.

**D5. New migrations are `<kebab-slug>.sql`; existing files are untouched
forever.** `db-migrate` accepts two grammars — legacy `^[0-9]{4}\.<slug>\.sql$`
(the committed numbered files, immutable) and new `^<slug>\.sql$`, where
`<slug>` is `[a-z0-9]([a-z0-9-]*[a-z0-9])?`. Both remain strict enough to keep
the filename safe to interpolate into SQL, which is what the existing regex was
silently doing. No timestamps: they are the same predicted-order mistake with
more digits.

The number was serving two jobs — order, and uniqueness. `ORDER` takes the
first. The slug takes the second, and takes it better: two runs that both add
`add-users-timezone.sql` collide in git as an add/add conflict that a human must
read, where two runs both choosing `0094` merged clean into a corrupt corpus.

**D6. A landing run's `ORDER` line is placed at land, under the land lock, after
the final rebase.** The line cannot only be written at land — the run's own
tests must pass during implementation, so the implementing run appends its line
when it adds the migration. What land does is **re-place** it: a new
`scripts/ship-order -s <rs>` rewrites `ORDER` as _the base branch's `ORDER`
verbatim, followed by this run's own lines in the run's own order_, then any
`*.sql` on disk that neither names.

That is derived, idempotent, and correct in both directions: recorded order
equals landed order, because the base copy is read under the lock after the
final rebase and the lock is only released at the fast-forward. A run with no
migration rewrites nothing. A phase-6 fix loop re-runs it safely.

It is called between `ship-rebase` and `ship-squash`, so the line joins the
run's staged diff and lands **inside the hook-verified tree**. Appending it in
`ship-land` instead would need a `--no-verify` commit and a doc-glob exemption
for `db/schema/ORDER` — a hole in the one gate this design exists to protect.

**D7. `.gitattributes` marks `db/schema/ORDER` `merge=union`.** This is the
"keep both lines, mine last" rule item 5 of the instruction asks for, and git
already implements it: during a rebase, _ours_ is the newly landed upstream and
_theirs_ is the replayed run, so union emits the landed lines first and this
run's line last. Verified on git 2.55.0 — the same rebase without the attribute
produces `CONFLICT (content)` and markers.

Union never _fails_, which is precisely why it is not the whole answer: two runs
could silently produce a duplicate line. `db-migrate`'s duplicate refusal (D2)
is the validator that union cannot be, and `ship-order` (D6) is what makes the
final order deterministic rather than merge-order-dependent. The attribute's job
is narrower: keep a mid-run rebase from stopping on a file whose conflicts are
never interesting. It is the repo's first `.gitattributes`, one line, one
purpose.

**D8. `db-create` gains an idempotent reshape for an existing
`schema_migrations`.** Today it is a bare `CREATE TABLE IF NOT EXISTS`, so on
any database that already has the table — production, every long-lived dev DB —
new DDL is a **no-op** and the old shape survives. `bin/test` runs `db-reset` on
every invocation, so the local suite is always cold and would prove nothing
about production. That silence is the largest hazard in this change, and the
deploy path (`infra/files/deploy-on-instance.sh` → `db-create` → `db-migrate`)
is exactly where it would surface.

So `db-create` runs, after the `CREATE TABLE IF NOT EXISTS` (now in the new
shape), a guarded `DO` block that drops the primary key and the `version_id`
column and makes `filename` the primary key — only when `version_id` still
exists. It is idempotent, safe to re-run, and fails loudly rather than silently
if `filename` somehow holds duplicates.

## Out of scope

- **No migration is renamed.** The 95 committed files keep their prefixes
  permanently; `ORDER` records the order they already have.
- **No timestamped filenames.**
- **No migration changes what it does.** No SQL inside any existing file is
  edited, including the two stale `version_id` comments in `0006` and `0007` —
  they are inside applied, immutable migrations and stay as committed.
- **No new ordering policy for the future**: `ORDER` records the order runs
  landed in, it does not let an author choose a position ahead of others.
- **`db-dump` / `db-restore` round-trip semantics are unchanged** beyond the one
  ordering column in the level probe.

## Detailed Design

### `db/schema/ORDER`

95 lines at landing, generated from the current corpus:

```
0000.shared-functions.sql
0001.create-users.sql
...
0094.drop-publisher-money-columns.sql
```

Generation is `ls db/schema/*.sql | sort` — their lexicographic order, which is
their true applied order, since every one of them was applied by the glob-order
loop this RFC replaces. The generated file is committed as part of this run, and
thereafter only ever appended to.

### `bin/common` — one integrity check, two callers

```sh
# Emits the ordered migration filenames, one per line, or fatals with a
# message naming every disagreement between ORDER and $DB_SCHEMA_DIR.
schema_order_files() { ... }
```

Checks, in this order: `ORDER` exists; no blank line; no duplicate line; every
listed file exists on disk; every `*.sql` on disk is listed. All disagreements
of a kind are collected and reported together — a corpus with three unordered
files should say so once, not three runs later. Exit is `fatal` (status 1): this
is a corrupt corpus, not a usage error, and the reserved codes in
`bin/functions` are documented as usage-error codes.

### `bin/db-migrate`

Consumes `schema_order_files` directly instead of `db-status -f
unapplied-only`,
skips filenames already in `schema_migrations`, and applies the rest in listed
order. It therefore calls `bin/db-create` itself (stdout redirected to stderr):
provisioning used to happen transitively through the `db-status` call this
change removes, and dropping that call without replacing it would silently
un-provision the deploy path. Behaviour is unchanged, and `db-migrate`'s
`help()` now says so, as `db-status`'s already did.

Per file, unchanged in shape:

```sh
BEGIN;
<file contents>
INSERT INTO schema_migrations (filename) VALUES ('<base>');
COMMIT;
```

The filename regex accepts both grammars (D5) and still runs before
interpolation.

### `bin/db-status`

`applied_raw` becomes
`SELECT filename FROM schema_migrations ORDER BY
filename ASC;` (membership set
— the ordering is immaterial and was always decorative). The enumeration loop
iterates `schema_order_files` instead of the `*.sql` glob.

### `bin/db-create`

```sql
CREATE TABLE IF NOT EXISTS schema_migrations (
  filename   TEXT PRIMARY KEY,
  applied_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema = current_schema()
               AND table_name = 'schema_migrations'
               AND column_name = 'version_id') THEN
    ALTER TABLE schema_migrations DROP CONSTRAINT IF EXISTS schema_migrations_pkey;
    ALTER TABLE schema_migrations DROP COLUMN version_id;
    ALTER TABLE schema_migrations ALTER COLUMN filename TYPE TEXT;
    -- RAISE a named exception listing duplicates before the key is added.
    ALTER TABLE schema_migrations ADD PRIMARY KEY (filename);
  END IF;
END $$;
```

`table_schema = current_schema()` is load-bearing, not defensive noise: without
it a legacy `schema_migrations` in **any other schema** keeps the guard true
forever while the `ALTER` resolves through `search_path` to a different table,
so `db-create` fails on every run — and `db-create` is called by `db-migrate`
and `db-status`. `filename` is `TEXT` rather than `VARCHAR(255)` because a
length bound stated only in the DDL is one the pre-`BEGIN` corpus gate does not
know about, so an over-long legal slug would pass validation and die mid-run.

### `bin/db-restore`

The level probe becomes
`SELECT filename FROM schema_migrations ORDER BY
applied_at DESC LIMIT 1;` — the
question it was always asking. The probe stays warning-only.

### `bin/deploy`

`REPO_PATHS` already bundles `db/schema`, so `ORDER` ships with it; no manifest
change is required, and this RFC asserts that rather than assuming it.

### `scripts/ship-order -s <rs>` (ship skill)

```
base_order  := git show "$BASE_SHA:db/schema/ORDER"   (empty if absent)
mine        := lines in worktree ORDER not present in base_order, in worktree order
unlisted    := *.sql on disk named by neither, sorted
write         base_order + mine + unlisted
```

Blank lines are stripped on the way through — D1 forbids them and
`schema_order_files` refuses them, so `ship-order` never writes one back.
Refuses when a line present in `base_order` has been deleted from the worktree
copy — that is history being rewritten, not an append. Prints what it appended.
`getopts`, short options only, all logging to stderr, per `bin/` conventions.

Phase 6 of the ship skill becomes:

```
ship-lock acquire
ship-rebase
ship-order          ← new
ship-squash
bin/format
git commit          (full hook)
...
```

## Files Modified

| File                                          | Change                                                    |
| --------------------------------------------- | --------------------------------------------------------- |
| `db/schema/ORDER`                             | **new** — 95 generated lines                              |
| `.gitattributes`                              | **new** — `db/schema/ORDER merge=union`                   |
| `bin/common`                                  | new `schema_order_files` helper                           |
| `bin/db-migrate`                              | walk `ORDER`; two-grammar regex; drop `version_id` insert |
| `bin/db-status`                               | enumerate via `ORDER`; `ORDER BY filename`                |
| `bin/db-create`                               | new table shape + idempotent reshape `DO` block           |
| `bin/db-restore`                              | level probe orders by `applied_at DESC NULLS LAST`        |
| `bin/functions`                               | new `EXIT_SCHEMA_ORDER_DRIFT`                             |
| `bin/db-scripts-tests`                        | rewrite filename-pinned tests; new refusal tests          |
| `.prime/agent/skills/ship/scripts/ship-order` | **new**                                                   |
| `.prime/agent/skills/ship/scripts/ship-land`  | refuses when `ship-order -n` says `ORDER` is stale        |
| `.prime/agent/skills/ship/SKILL.md`           | phase 6 sequence + the `ORDER` conflict rule              |
| `.prime/agent/skills/slice/SKILL.md`          | stop computing the next migration number                  |
| `.prime/agent/skills/chart/SKILL.md`          | one phrase: slice no longer claims a migration number     |
| `bin/ship-scripts-tests`                      | cases for `ship-order`                                    |
| `README.md`, `db/schema/INVARIANTS.md`        | describe `ORDER` and slug filenames                       |

Not modified: `bin/db-reset`, `bin/db-drop`, `bin/db-adopt`, `bin/db-dump`,
`bin/test`, `bin/deploy`, `infra/files/deploy-on-instance.sh` — each was read
and none is filename- or `version_id`-aware; `db-reset` is drop→create→migrate
and inherits the change.

## Implementation Plan

1. Generate `db/schema/ORDER`; add `.gitattributes`.
2. `bin/common`: `schema_order_files` with the four refusals.
3. `bin/db-create`: new shape + reshape block.
4. `bin/db-migrate`, `bin/db-status`, `bin/db-restore`.
5. `bin/db-scripts-tests`: rewrite the ~10 filename-pinned assertions, add the
   refusal and no-op cases.
6. `scripts/ship-order` + SKILL.md phase 6 and the conflict rule, with its cases
   in `bin/ship-scripts-tests`.
7. Docs: `README.md`, `db/schema/INVARIANTS.md`, `slice/SKILL.md`,
   `chart/SKILL.md`.

## Tests

In `bin/db-scripts-tests` (private cluster, scratch `DB_SCHEMA_DIR`):

- **No-op on a current DB** — migrate a corpus, re-run `db-migrate`, assert
  success, "fully migrated", and that `count(*)` of `schema_migrations` is
  unchanged. This is the acceptance criterion that an already-migrated database
  is unaffected.
- **Reshape** — create a DB with the OLD `schema_migrations` shape and rows, run
  `db-create`, assert `version_id` is gone, `filename` is the primary key, the
  rows survive, and a following `db-migrate` is a no-op.
- **File on disk missing from `ORDER`** — fatal, message names the file, nothing
  applied.
- **`ORDER` line missing from disk** — fatal, message names the line.
- **Duplicate line in `ORDER`** — fatal; this is the "two migrations cannot
  share a position" proof.
- **`ORDER` order beats lexicographic order** — a corpus whose `ORDER` lists
  `b-second.sql` before `a-first.sql`; assert the applied order (via
  `applied_at`, and via a table only the second migration can create).
- **Slug filenames apply** and are recorded verbatim in `schema_migrations`.
- **Bad filename** (uppercase, spaces, quote) is still refused.
- **Halt on error** keeps its meaning: a failing migration mid-`ORDER` halts and
  leaves later listed migrations unapplied.
- Existing `db-dump`/`db-restore` level assertions updated to the new probe.

`bin/scripts-tests` needs no change: its `make_fake_checkout` symlinks the real
`db` directory, so the fake checkout already carries `db/schema/ORDER`, and its
`DB_SCHEMA_DIR` resolution assertion keeps its meaning.

`bin/db-scripts-tests` and `bin/ship-scripts-tests` both unset `GIT_DIR`,
`GIT_WORK_TREE` and `GIT_INDEX_FILE`, and `ship-order` strips them for its own
git calls, because **`GIT_DIR` outranks `git -C`** and `bin/pre-commit` exports
it: a `git init` fixture run from inside the hook otherwise re-initialises the
real checkout. `ship-lock` already carried that scar; this run earned it again,
so a regression test now exports the hook's variables and asserts `ship-order`
still answers about the run's repository and leaves the real one untouched.

In `bin/ship-scripts-tests`, against a throwaway git fixture: `ship-order`
appends this run's line last, is idempotent, is a no-op for a run with no
migration, refuses a deletion of an already-landed line, appends an unlisted
`*.sql`, treats a missing `ORDER` at the base SHA as empty, and writes nothing
to stdout.

`ship-land` runs `ship-order -n` before its fast-forward, so a run that skips
the step is refused rather than landing a line in the wrong position — the
mechanism is enforced, not documented.

The whole suite runs as `nix develop -c bin/test check`, which is what the
pre-commit hook runs.
