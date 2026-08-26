# RFC 138: Declarative test state — `bin/state-apply` v1 (users)

## Executive Summary

A declarative tool for putting a unicoach dev/test database into a known state.
A **world file** (YAML) describes desired state; `bin/state-apply` makes it so.
v1 supports exactly one resource type — **users** (email, password, name,
email-verified, admin) — inside a skeleton designed to grow more resource types
later.

Two design commitments, agreed in discussion:

1. **Composition nests, association names.** The world file expresses ownership
   FK edges by nesting (a future `student:` block sits inside its user) and
   reference-data FK edges by natural key (a future `college: MIT`). The file
   never contains a database id.
2. **The unit of declaration is the whole world, not the row.** The schema
   deliberately blocks physical deletion (`prevent_physical_delete`, append-only
   log guards), and cascading soft-deletion of a user's graph is
   unbuilt/untested — that is product brief 0002's engine, not this tool's. So
   `state-apply` never deletes anything: the default mode is **create-only**
   (hard error if a declared user already exists), and `-f/--fresh` rebuilds the
   world from empty via the existing `bin/db-reset`. Worlds are replaced, not
   mutated. When brief 0002 lands its reset/delete engine, per-entity
   replacement can be added by calling it.

The applier runs at the **service-fidelity** level without HTTP: real signup
validation (`RegistrationValidator`), real argon2id hashing (`Argon2Hasher`),
DAO writes (`UsersDao.create`, `UsersDao.markEmailVerified`) in one
all-or-nothing transaction. No running `rest-server` is required.

## World file format (v1)

```yaml
users:
  - email: test1@example.com
    password: hunter2
    name: Test One # optional; default: email local part ("test1")
    verified: true # optional; default: true
    admin: false # optional; default: false
  - email: admin@example.com
    password: hunter2
    admin: true
```

Unknown keys are a hard error (strict deserialization) — a typo like `verifed:`
must not silently produce a different world.

## Detailed Design

### New module `:fixture`

Modeled on `:college` (RFC 67): a small module whose `main` is an operational
CLI over the shared config/db stack.

- `fixture/build.gradle.kts` — `application` plugin, mainClass
  `ed.unicoach.fixture.StateApplyApplicationKt`. Depends on `:common`, `:db`,
  `:service` (for `RegistrationValidator`; also positions the module to call
  services for future resource types). Parsing via `jackson-dataformat-yaml` +
  `jackson-module-kotlin` (dataformat is already in the version catalog; the
  kotlin module is added at the same pinned jackson version).
- `fixture/src/main/kotlin/ed/unicoach/fixture/WorldFile.kt` — data classes
  (`WorldFile`, `UserSpec`) + strict YAML loading (fail on unknown properties),
  applying the documented defaults.
- `fixture/src/main/kotlin/ed/unicoach/fixture/WorldApplier.kt` — the engine.
  `suspend fun apply(world: WorldFile): ApplyResult`. One
  `database.withConnection` transaction for the entire world; any failure rolls
  back everything (a world half-applied is worse than absent). Per user:
  1. Validate `(email, name, password)` with `RegistrationValidator` — the same
     validation real signup runs; a world file cannot create a user signup would
     reject.
  2. Hash the password with `Argon2Hasher` (registration parameters).
  3. `UsersDao.create(NewUser(email, name, displayName = null, passwordHash,
     isAdmin = spec.admin))`.
  4. If `verified` (the default): `UsersDao.markEmailVerified(session, id)`.
     `DuplicateEmailException` maps to a clear error naming the email and
     suggesting `-f`.
- `fixture/src/main/kotlin/ed/unicoach/fixture/StateApplyApplication.kt` —
  `fun main(args)`: usage check, load `common.conf` + `db.conf` (the
  `IngestApplication` pattern), parse the file, run the applier, log a per-user
  summary line
  (`created [test1@example.com] verified=true
  admin=false id=[...]`) and exit
  non-zero on any failure.

### `bin/` wrapper

- `bin/state-apply [-f] <world.yaml>` — sources `bin/common`; `-f` runs
  `bin/db-reset` first; then executes the prebuilt installDist launcher
  (`fixture/build/install/fixture/bin/fixture`), fataling if absent
  (pre-built-launcher rule), pointing at `bin/build-fixture`.
- `bin/build-fixture` — `installDist` for `:fixture` (clone of
  `bin/build-college`); registered in `bin/build`'s dependency-ordered list.

The tool is local-only by construction: `bin/deploy` does not ship the fixture
dist, and the wrapper talks to whatever DB the layered env resolves — the same
posture as `bin/db-reset` itself.

### Example world

`fixtures/dev-users.yaml` — a small checked-in canned world (a verified user, an
unverified user, an admin) that doubles as living documentation and as the smoke
input for manual use.

### Future growth (non-binding sketch)

A `student:` block nested under a user adds resource #2 with no format change;
reference data (colleges, system prompts) is named by natural key; intra-world
back-references (e.g. `superseded_by`) become sibling references scoped to the
enclosing entity. Per-entity replace (converge on a named user without a full
reset) arrives by delegating to brief 0002's reset/delete engine when it exists.
None of this is built now.

## Non-goals

- Deletion or convergence/diffing of any kind (brief 0002's territory).
- Resource types beyond users.
- Driving the REST API or requiring a running server.
- Production use of any kind.

## Files Modified

New:

- `fixture/build.gradle.kts`
- `fixture/src/main/kotlin/ed/unicoach/fixture/WorldFile.kt`
- `fixture/src/main/kotlin/ed/unicoach/fixture/WorldApplier.kt`
- `fixture/src/main/kotlin/ed/unicoach/fixture/StateApplyApplication.kt`
- `fixture/src/test/kotlin/ed/unicoach/fixture/WorldFileTest.kt`
- `fixture/src/test/kotlin/ed/unicoach/fixture/WorldApplierTest.kt`
- `bin/state-apply`
- `bin/build-fixture`
- `fixtures/dev-users.yaml`

Modified:

- `settings.gradle.kts` — `include("fixture")`
- `gradle/libs.versions.toml` — add `jackson-module-kotlin` at the pinned
  jackson version
- `bin/build` — add `fixture` to the ordered module list

## Implementation Plan

1. Module skeleton: `settings.gradle.kts`, `fixture/build.gradle.kts`, catalog
   entry.
2. `WorldFile` parsing + defaults + strict-mode tests.
3. `WorldApplier` + DB-backed tests.
4. `StateApplyApplication` main + `bin/build-fixture` + `bin/state-apply` +
   `fixtures/dev-users.yaml`.
5. Full-suite verification (`nix develop -c bin/test`), plus one manual
   end-to-end: `bin/state-apply -f fixtures/dev-users.yaml` against the dev DB
   and a login-shaped check of the argon2 hash.

## Tests

`WorldFileTest` (pure JVM):

- Parses the full documented format; defaults applied (`name` from local part,
  `verified=true`, `admin=false`).
- Unknown key → hard error. Missing `email`/`password` → hard error.
- Empty `users:` list and empty file → valid empty world.

`WorldApplierTest` (DB-backed, like the `:db` DAO tests):

- Happy path: two users created; rows carry expected email/name/admin;
  `email_verified_at` set for the verified user, NULL for `verified: false`.
- Password round-trip: created hash verifies with `Argon2Hasher.verify` against
  the plaintext.
- Real-validation parity: a password signup would reject (too short) is rejected
  here with field errors.
- Duplicate active email → error naming the email; **transaction rolled back**
  (a valid user earlier in the same world is absent afterwards).
- Re-apply after the same world → duplicate error (create-only semantics).

The `bin/state-apply` wrapper stays thin (arg parse, optional `db-reset`, exec)
and is exercised by the manual end-to-end in the implementation plan, not by a
shell harness — the `bin/ingest-colleges` precedent.
