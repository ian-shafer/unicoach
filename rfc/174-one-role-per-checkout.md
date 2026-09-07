# RFC 174 — one server, one port, one role per checkout

## Summary

Every `bin/db-*` command in a dev checkout connects to the shared local
PostgreSQL cluster **as the cluster superuser**. `bin/db-run` — the single psql
chokepoint every other db script funnels through — hardcodes
`psql -U "$POSTGRES_USER"`, and `.env.dev` sets `POSTGRES_USER=postgres`. A
superuser owns nothing and may drop everything, so the only thing standing
between one checkout and another checkout's data is that the two derived
database _names_ differ.

On 2026-09-05 that turned out to be nothing at all. Run `rfc-170`'s pre-commit
gate failed with

    ❌ could not seed the marker row in both dev databases:
       FATAL: terminating connection due to administrator command

`bin/db-drop` runs `DROP DATABASE ... WITH (FORCE)`, which terminates every
other session connected to that database. `bin/scripts-tests`' cross-checkout
isolation section built its two fake checkouts with **hardcoded** directory
names (`unicoach-isotest-a`, `unicoach-isotest-b`), so two real checkouts
running the harness at once derived the _same_ database names on the _same_
shared cluster, and one force-dropped the other's connections mid-run.

The fixture names are being fixed separately, in a quick-lane run already in
flight. This RFC is the structural half. Names prevented nothing, because a
superuser is not asked for permission. **Privileges are asked for permission.**

This RFC gives each checkout its own non-superuser PostgreSQL role, makes that
role the OWNER of that checkout's dev, test and fuzz databases, and makes it the
identity every ordinary `bin/` connection authenticates as. `DROP DATABASE`
requires ownership, so a checkout that derives a wrong or colliding name now
gets a loud permission error instead of destroying another checkout's data.

This is deliberately **not** instance-per-checkout. One PostgreSQL server, one
port, one data directory, exactly as today (`POSTGRES_DATA_DIR` in `.env.dev` is
a shared absolute path by design). Nothing here adds an instance, changes
`POSTGRES_PORT`, or touches the per-checkout port blocks of RFC 156. Every
`bin/` command stays zero-argument: an operator never types a database name, a
port, or a role.

## Where the identities are today

Two identity variables exist. (`PGUSER` has zero occurrences repo-wide.)

| Variable        | Meaning today                                  | dev        | test / fuzz | cloud (SSM)      |
| --------------- | ---------------------------------------------- | ---------- | ----------- | ---------------- |
| `POSTGRES_USER` | cluster admin; what **every** `bin/` psql uses | `postgres` | `postgres`  | `unicoach_admin` |
| `DATABASE_USER` | the login role the **JVM** connects as         | `unicoach` | `postgres`  | `unicoach`       |

No shell script ever connects as `DATABASE_USER`; it is only ever the object of
a `CREATE ROLE` or a `GRANT`. Every shell DDL and DML path is the superuser.

The exhaustive per-script answer the instruction asks for:

| Path                    | Identity today                                                                                                                                                                                                  | Evidence                                                                 |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| `bin/db-run`            | `-U <user>` option; `CONNECT_USER` defaults to `$POSTGRES_USER` and is used at both psql branches                                                                                                               |                                                                          |
| `bin/db-write`          | `POSTGRES_USER`                                                                                                                                                                                                 | `:39` delegates to `db-run rw`                                           |
| `bin/db-query`          | `POSTGRES_USER`                                                                                                                                                                                                 | `:55` delegates to `db-run ro`                                           |
| `bin/db-create`         | ensure roles first (only when the checkout role differs from the cluster admin); call `db-adopt` when the existing database has another owner                                                                   |                                                                          |
| `bin/db-drop`           | ensure the checkout role, so a fresh worktree's zero-argument `bin/db-reset` works — `db-drop` is its first step (D4)                                                                                           |                                                                          |
| `bin/db-reset`          | help text only: it no longer "does no role work"                                                                                                                                                                |                                                                          |
| `bin/ios-scripts-tests` | one throwaway dotenv fixture value, `POSTGRES_DB=unicoach-dev-fixture` → `fixture-db`. It is never used as a database name; renaming it keeps guard test 1's exclusion list at the one entry this RFC specifies |                                                                          |
| `bin/db-drop`           | `POSTGRES_USER`                                                                                                                                                                                                 | `:40` `DROP DATABASE ... WITH (FORCE)` on the admin DB                   |
| `bin/db-reset`          | `POSTGRES_USER`                                                                                                                                                                                                 | `:35-37` = db-drop / db-create / db-migrate                              |
| `bin/db-migrate`        | `POSTGRES_USER`                                                                                                                                                                                                 | `:53` pipes each migration to `bin/db-write`                             |
| `bin/db-create-role`    | `POSTGRES_USER`                                                                                                                                                                                                 | `:39` writes to the admin DB; creates `DATABASE_USER`                    |
| `bin/db-bootstrap`      | `POSTGRES_USER`                                                                                                                                                                                                 | `:48` `initdb -U "$POSTGRES_USER"` — the cluster superuser IS this value |
| `bin/db-dump`           | `POSTGRES_USER`                                                                                                                                                                                                 | `:207` `pg_dump -U "$POSTGRES_USER"`                                     |
| `bin/db-restore`        | `POSTGRES_USER`                                                                                                                                                                                                 | `:297` `pg_restore --no-owner -U "$POSTGRES_USER"`                       |
| `bin/db-status`         | `POSTGRES_USER`                                                                                                                                                                                                 | `:54` via `db-query`                                                     |
| `bin/db-repl`           | `POSTGRES_USER`                                                                                                                                                                                                 | `:33` inline `psql -U "$POSTGRES_USER"`                                  |
| the JVM                 | `DATABASE_USER`                                                                                                                                                                                                 | `db.conf:5` `user = ${?DATABASE_USER}`                                   |

So: **ownership buys nothing today**, and the slice's own success criterion is
that the everyday path stops being the superuser. That is decision D2 below.

## Detailed Design

### D1 — The role name: `owner-<checkout_name>`, verbatim

The role is derived from `PROJECT_ROOT` by the same function the database name
uses, `checkout_name` (`bin/functions:190`), which is `basename "$PROJECT_ROOT"`
with a byte-length guard.

The basename is used **verbatim**, hyphens and all, always as a quoted
identifier (`"..."` in hand-written SQL, `format('%I', ...)` inside a DO block).
Hyphenated role names were tested end to end and work everywhere this design
needs them: `CREATE ROLE`, `psql -U`, `PGUSER`, a `postgresql://` URI,
`ALTER DATABASE ... OWNER TO`, `DROP DATABASE ... WITH (FORCE)`, and
`quote_ident` inside a DO block. The name never begins with `-`, so no shell or
`getopts` grammar is disturbed.

**The name is also character-checked, not merely length-checked.**
`checkout_name` now refuses anything outside `[A-Za-z0-9._-]` with a usage
error. It rejects, it does not sanitise — sanitising is the lossy collapse D1
rejects above. Every checkout directory on this machine already satisfies it.
This is defence in depth behind the escaping rules, not a replacement for them:
a checkout named `a$$b` terminated the `DO $$ ... $$` body outright, which no
amount of quote-doubling would have caught.

Two escaping rules follow, and they are different. A derived name used as an
**identifier** is quoted (`"..."`, or `format('%I', ...)` in a DO block). A
derived name used as a string **literal** — the role name compared against
`pg_roles.rolname`, or `DATABASE_PASSWORD` — never enters the SQL text at all.
It travels as a session GUC (`SET unicoach.owner_role TO ...`) and is read back
inside the block with `current_setting()`, so the server does the quoting via
`format('%I' / '%L')`. Quote-doubling alone was not enough: the value lands
inside a `DO $$ ... $$` body, where a `$$` in the input ends the block before
any quoting is consulted.

This closes a **pre-existing production defect** that RFC 174 did not introduce
but did rewrite the code around: `bin/db-create-role` interpolated
`DATABASE_PASSWORD` raw into `CREATE ROLE ... PASSWORD '...'`, inside dollar
quoting. On an instance that value comes from SSM, so a password containing `'`
or `$$` broke role creation or injected. `checkout_name` guards byte length only
and does no character filtering, so neither rule may be skipped on the grounds
that the input "looks safe".

**Sanitising to `[a-z0-9_]` was rejected.** It is lossy: `unicoach-rfc-174` and
`unicoach_rfc_174` would collapse onto one role, silently re-creating the exact
collision this RFC exists to stop. The cluster already carries hyphenated
identifiers (`unicoach-dev-unicoach-rfc-174`), so nothing is gained.

**The prefix is not optional.** The original checkout is `.../Work/unicoach`, so
a bare `checkout_name` role would be literally `unicoach` — which is already the
name of the shared application login role (`DATABASE_USER`). That checkout would
silently hand the app role `CREATEDB` and ownership of every database. The
prefix keeps the two namespaces apart.

**The prefix is `owner-`, six bytes, and its shortness is load-bearing.** The
database prefixes `unicoach-test-` and `unicoach-fuzz-` are 14 bytes, so any
role prefix of 14 bytes or fewer can never become the binding length constraint
— the budget stays exactly where the databases already put it. A longer, more
decorative prefix such as `unicoach-owner-` (15 bytes) would have made the role
the constraint and cost a byte of every checkout's name for nothing.
`owner-unicoach-rfc-174` still reads at a glance in `\du`.

### D1a — The 63-byte budget, which is already wrong

PostgreSQL truncates identifiers **silently** at 63 bytes. `bin/functions:181`
caps the checkout basename at `CHECKOUT_NAME_MAX_BYTES=50`, and its comment
justifies 50 as "63 minus the 13-byte prefix `unicoach-dev-`".

The comment undercounts. `unicoach-test-` and `unicoach-fuzz-` are **14** bytes,
so a 50-byte basename already yields a 64-byte test database name today — one
byte past the limit. This is not theoretical. The longest checkout name in the
project's history is

    unicoach-ship-test-port-fallback-below-block-floor    (50 bytes)

whose test database was `unicoach-test-` + 50 = **64 bytes**, silently truncated
by the server. That run shipped against a truncated database name and nothing
said so. Two checkouts agreeing in their first 49 bytes would likewise share one
test database while `cksum` correctly gave them different port blocks — the
quiet failure `bin/functions:172-180` describes, present in the tree right now.

This RFC makes the budget derived rather than asserted:
`CHECKOUT_NAME_MAX_BYTES` becomes **49**, stated as `63 - <longest prefix>` with
the prefixes listed beside it. The move is one byte, and it is entirely the
pre-existing off-by-one; the new role prefix (`owner-`, 6 bytes) deliberately
does not participate.

The consequence is that a 50-byte checkout name is now **refused loudly** rather
than truncated silently. One historical naming pattern —
`unicoach-ship-<long-slug>` — must therefore use a shorter slug. That is the
intended trade: `checkout_name` fails closed, as it was designed to.

An alternative was considered and rejected: returning
`<first 40 bytes>-<8 hex of cksum>` instead of failing, so that no name is ever
refused. It would keep long slugs working, but it costs `checkout_name` its
single deliberate property — loud failure — to serve one name in the project's
entire history.

### D2 — Split the cluster admin out of the everyday identity

One new key, and one changed value:

| Variable             | Meaning                                                             | dev / test / fuzz                             | cloud                  |
| -------------------- | ------------------------------------------------------------------- | --------------------------------------------- | ---------------------- |
| `POSTGRES_SUPERUSER` | **new.** Cluster admin. `initdb`, and role/ownership bootstrap ONLY | `postgres`                                    | _unset_ → falls back   |
| `POSTGRES_USER`      | unchanged meaning: the identity `bin/` connects as                  | `owner-<checkout>`                            | `unicoach_admin` (SSM) |
| `DATABASE_USER`      | unchanged meaning: the role the JVM pool connects as                | dev `unicoach`; test/fuzz → the checkout role | `unicoach` (SSM)       |

Everywhere `POSTGRES_SUPERUSER` is read it is read as
`${POSTGRES_SUPERUSER:-$POSTGRES_USER}`. In every cloud environment the key is
unset, so it collapses to the RDS master role — which is precisely the identity
that creates roles on the instance today
(`infra/files/deploy-on-instance.sh:40-45`). **The deployed behaviour is
byte-for-byte unchanged.**

The derivation itself lives **only in `.env.dev`**, never in a script.
`bin/functions`, `bin/db-create-role`, `bin/db-create`, `bin/db-run` and
`bin/db-migrate` are all shipped to the instance by `bin/deploy:124-138`; a
default computed inside any of them would compute a role name from the release
directory on RDS. `.env.dev` is never layered by the deploy path
(`bin/common:51-63`), which is the barrier that keeps this local.

`bin/db-bootstrap:48` switches to `initdb -U "$POSTGRES_SUPERUSER"`, so the
cluster superuser stays `postgres` and does not become whichever checkout
happened to bootstrap the machine.

`bin/postgres-check:30` passes `-U` to `pg_isready`, which never completes
authentication and is user-agnostic — verified with a role that does not exist.
Changing `POSTGRES_USER` does not affect it. (It also cannot be used to check
that the checkout role exists.)

### D3 — `bin/db-run` gains `-U`

`bin/db-run` is the single psql chokepoint; `db-query` and `db-write` forward
only `-d` to it, and `-U` is hardcoded at `:85` and `:88`. There is today **no
way at all** to connect as another role through the bounded interface, which is
why no test in the tree asserts a privilege.

`-U <user>` is added to `db-run`, `db-query` and `db-write`, forwarded exactly
as `-d` already is (an array appended to the psql argv), defaulting to
`$POSTGRES_USER`. Short option, `getopts`, per `bin/` rules.

This is what lets the two superuser touchpoints below exist, and what makes the
negative acceptance test expressible through the real interface rather than a
raw `psql` that bypasses it.

### D4 — The superuser touchpoints are few, named, and counted by a test

**`bin/db-create-role`** keeps its job as the single source of truth for role
creation, and gains the checkout owner role. Run as the superuser against
`POSTGRES_ADMIN_DB`, idempotent, and race-tolerant across concurrent checkouts:

```sql
DO $$
DECLARE r CONSTANT text := 'owner-<checkout>';
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
    EXECUTE format('CREATE ROLE %I LOGIN CREATEDB NOSUPERUSER NOCREATEROLE', r);
  END IF;
  EXECUTE format('ALTER ROLE %I LOGIN CREATEDB', r);
EXCEPTION WHEN duplicate_object OR unique_violation THEN
  RAISE NOTICE 'role % already exists, skipping', r;
END $$;
```

The unconditional `ALTER` sits outside the `IF` on purpose: it repairs a role
that already exists but lacks `LOGIN` or `CREATEDB`, so a half-made role is
healed rather than diagnosed. Verified: a seeded `NOLOGIN NOCREATEDB` role
becomes `LOGIN CREATEDB` on the first run, run two is a no-op, and after a
`DROP ROLE` run three recreates it.

`NOSUPERUSER` is the whole guarantee — a superuser bypasses every ownership
check. `NOCREATEROLE` is not required but blocks the lateral move.

The block is **skipped entirely** when the owner role equals the superuser,
which is the cloud case; the app-role half (`DATABASE_USER`) is unchanged.

**`bin/db-adopt`** — new, zero-argument, idempotent — makes this checkout's role
the owner of `POSTGRES_DB` and of every object inside it. It is the transition
for databases that already exist (D5), and `bin/db-create` calls it when it
finds the database owned by anyone else.

`bin/db-create` therefore becomes: ensure roles → create the database if missing
(as the checkout role, which becomes owner automatically) → adopt it if it
existed under another owner → grants → `schema_migrations`.

**How many superuser touchpoints there are is pinned by a test, not by this
sentence.** `bin/db-run`'s `-U` (D3) makes a new touchpoint a single
review-invisible line, so a guard test in `bin/scripts-tests` fixes the set of
`bin/` files that may connect as the cluster admin: `db-create-role`,
`db-adopt`, `db-bootstrap`, and the two harnesses. The pattern matches the
`$(cluster_superuser)` spelling too, so moving to the helper is not a way around
the count. A prose claim about a count that nothing measures is a claim that
drifts.

**`bin/db-drop` needs the role AND the ownership**, and must. `bin/db-reset` is
`db-drop → db-create → db-migrate`, and `db-drop` connects as the checkout role;
in a brand-new worktree that role does not exist yet, so `db-reset` would die at
step 1 with `FATAL: role "owner-..." does not exist` before `db-create` ever got
the chance to create it. Worse, ensuring the role is not enough: a pre-RFC-174
database is owned by `postgres`, and `DROP DATABASE` is checked against the
owner — `IF EXISTS` does not help — so `db-reset` in any existing checkout died
at step 1 with `must be owner of database`. With roughly 190 postgres-owned
databases on the cluster, that is every existing checkout.

So `db-create` and `db-drop` share one helper,
`set_checkout_database_ownership`, which names the single idea "make sure this
checkout owns what it is about to operate on": ensure the role, then adopt the
database if anyone else owns it. It is called **unconditionally** on the
non-admin path rather than gated on the database owner, because `db-adopt` is
two non-atomic statements — gating on `datdba` would let an interrupted run
leave a database owned but its tables not, which `db-create` would then skip
forever while `db-migrate` kept failing. `db-adopt` is idempotent, so the
unconditional call repairs that state on every run.

The helper's FIRST statement is the cloud guard, before any reference to
`bin/db-create-role` or `bin/db-adopt`. Neither script is in `bin/deploy`'s
`REPO_PATHS`, so on an instance the predicate is false and the function returns
before naming a path that does not exist there — unreachable by construction,
not by luck. `bin/db-drop` is likewise not in the deploy bundle.

Every ensure site — `db-create-role`, `db-adopt`, `db-create`, `db-drop` — is
guarded by `POSTGRES_USER != ${POSTGRES_SUPERUSER:-$POSTGRES_USER}`, so in a
cloud environment, where the two collapse to the RDS master, **no role work
happens at all**. That guard is the whole of the "deployed behaviour is
unchanged" claim, so it is tested directly rather than assumed.

No manual step, no documented "run this once", no new flag. A fresh checkout's
first `bin/db-create`, `bin/db-drop` or `bin/db-reset` does all of it.

### D5 — Adopting an existing dev database

Test and fuzz databases are recreated on every run and need nothing. Dev
databases already exist and are owned by `postgres`. Three findings, all
measured on the real cluster (PostgreSQL 18.3):

1. **`ALTER DATABASE ... OWNER TO` alone is not enough.** After it the role can
   `CREATE TABLE`, `DROP TABLE` and `DROP DATABASE` — so `bin/db-drop` already
   works — but `bin/db-migrate` does not:
   `ALTER TABLE public.legacy_t ADD COLUMN ...` →
   `42501: must be owner of table`.
2. **`REASSIGN OWNED BY postgres` is refused outright**:
   `2BP01: cannot reassign ownership of objects owned by role postgres because
   they are required by the database system`.
   The statement aborts and nothing is reassigned. It is unusable here.
3. **A serial/identity sequence cannot be re-owned directly**:
   `0A000: cannot change owner of sequence ... is linked to table`. It must be
   skipped; its owner follows the table. Indexes and TOAST tables likewise.

So `bin/db-adopt` does two things as the superuser:

```sql
-- from the admin database
ALTER DATABASE "unicoach-dev-<checkout>" OWNER TO "owner-<checkout>";
```

```sql
-- inside POSTGRES_DB: re-own every object the role must later modify.
-- Skips indexes ('i') and TOAST ('t'), which follow their table, and skips
-- sequences owned by a serial/identity column (pg_depend deptype = 'a').
DO $$
DECLARE r CONSTANT text := :'owner_role'; obj record;
BEGIN
  FOR obj IN SELECT ... LOOP
    EXECUTE format('ALTER %s %s OWNER TO %I', obj.kind, obj.ident, r);
  END LOOP;
END $$;
```

covering schemas, tables, views, materialized views, foreign tables, standalone
sequences, routines and types, each filtered on
`pg_get_userbyid(rel.relowner) <> r`.

**Extension members are excluded** (`NOT EXISTS` against `pg_depend` with
`deptype = 'e'`), for the same reason serial sequences are: they follow
something else. A migration creates `fuzzystrmatch` as the superuser, so its
eleven functions in `public` are `postgres`-owned; without the filter every
`db-reset` re-owned all eleven and the next `db-migrate` re-created them
`postgres`-owned again — a permanent non-no-op that quietly falsified the
idempotence claim below, and one that can affect `DROP EXTENSION` and
dump/restore fidelity.

`schema public` is excluded (`nspname <> 'public'`). On PostgreSQL 18 it is
owned by `pg_database_owner`, so it already follows the database owner that
`ALTER DATABASE ... OWNER TO` just set; re-owning it to the role directly would
replace that indirection with a fixed owner for no gain.

Both statements are idempotent by construction, and idempotence here means what
it says: on a migrated, already-adopted database a second run reports
`re-owned 0 object(s)` and prints no adoption line at all. A second
`ALTER DATABASE` is a successful no-op; a second DO block executes zero
statements because the `<> r` predicates match nothing. They are safe to run
unconditionally, so `db-adopt` needs no state and no flag.

An existing checkout keeps its dev data: nothing is dropped or recreated.

### D6 — `DATABASE_USER` in test and fuzz

`.env.test:35` and `.env.fuzz:37` override `DATABASE_USER=postgres`, so the JVM
connection pool is the **cluster superuser** during every test run. That
bypasses every ownership check from inside the application, and it is the same
hole in a different layer.

Both become the checkout owner role. Dev is left alone: `DATABASE_USER=unicoach`
there is a genuinely unprivileged app role that exercises least privilege, and
`bin/db-create` already grants it what it needs. A freshly created database
needs no grant fix-up — as a non-superuser owner, the checkout role can run both
`GRANT ALL ON SCHEMA public` and `ALTER DEFAULT PRIVILEGES` on PostgreSQL 18,
where `schema public` is owned by `pg_database_owner`.

### D7 — What this does NOT protect

Recorded so the next person meets these as known costs rather than surprises.

- **Connection-limit contention.** One `max_connections` is shared by every
  checkout. A checkout running a full suite can starve another of connections,
  and the failure will read as an unrelated flake.
- **Autovacuum and I/O competition.** One autovacuum worker pool, one shared
  buffer cache, one disk. A large ingest in one checkout slows every other.
- **A crash stops everyone.** One server means one failure domain.
  `bin/postgres-down` in any checkout stops the cluster for all of them
  (`README.md:575` already says so).
- **This is not a security boundary.** Local auth is `trust`, so any local
  process may connect _as_ `postgres` and do anything. RFC 174 protects a
  checkout's own tooling from misfiring; it does not defend against a hostile
  local user.
- **Ownership is not connection isolation.** `PUBLIC` retains `CONNECT`, so
  checkout A can still open a connection to checkout B's database. Every
  `SELECT`, `CREATE` and `DROP` inside it is refused with `42501`.

These are the accepted price of one server on one port, which stays the design.

## Files Modified

| File                            | Change                                                                                                                                                                                                                                                                                              |
| ------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `bin/functions`                 | `CHECKOUT_NAME_MAX_BYTES` 50 → 49, derived from the longest prefix and documented as such; new `cluster_superuser`, `checkout_owns_its_databases`, `set_checkout_database_ownership`, `sql_literal`, `sql_ident`. All read env only and derive no per-checkout NAME, so the shipped-file rule holds |
| `bin/tests-common`              | `list_contains`, so the allowlist-membership helper has one definition rather than four                                                                                                                                                                                                             |
| `.env.dev`                      | new `POSTGRES_SUPERUSER=postgres`; `POSTGRES_USER` becomes `owner-$(checkout_name "$PROJECT_ROOT")`                                                                                                                                                                                                 |
| `.env.test`                     | `DATABASE_USER` = the checkout role; `POSTGRES_DB` uses `checkout_name`, not bare `basename`                                                                                                                                                                                                        |
| `.env.fuzz`                     | same two changes                                                                                                                                                                                                                                                                                    |
| `.env.template`                 | document `POSTGRES_SUPERUSER`                                                                                                                                                                                                                                                                       |
| `bin/db-run`                    | `-U <user>` option; `psql -U "${CONNECT_USER:-$POSTGRES_USER}"` at both branches                                                                                                                                                                                                                    |
| `bin/db-query`, `bin/db-write`  | forward `-U` exactly as `-d` is forwarded                                                                                                                                                                                                                                                           |
| `bin/db-create-role`            | also ensure the checkout owner role (`LOGIN CREATEDB NOSUPERUSER NOCREATEROLE`); connect as `${POSTGRES_SUPERUSER:-$POSTGRES_USER}`; skip when they are equal                                                                                                                                       |
| `bin/db-adopt`                  | **new.** Zero-argument, idempotent ownership transition (D5)                                                                                                                                                                                                                                        |
| `bin/db-create`                 | ensure roles first; call `db-adopt` when the existing database has another owner                                                                                                                                                                                                                    |
| `bin/db-bootstrap`              | `initdb -U "$POSTGRES_SUPERUSER"`                                                                                                                                                                                                                                                                   |
| `bin/scripts-tests`             | the negative isolation test; the two guard tests; `CHECKOUT_NAME_MAX_BYTES` boundary test moves 50 → 49                                                                                                                                                                                             |
| `bin/db-scripts-tests`          | private-cluster env gains `POSTGRES_SUPERUSER`; role/ownership coverage                                                                                                                                                                                                                             |
| `README.md`, `CONFIGURATION.md` | `POSTGRES_SUPERUSER`, and the guarantee                                                                                                                                                                                                                                                             |

Not modified, deliberately: `db.conf`, any Kotlin, `infra/**`, `bin/deploy`,
`POSTGRES_PORT`, `bin/checkout-port`, and the RFC 156 port blocks.

## Implementation Plan

1. `bin/functions`: the byte budget (D1a) and its comment. Update the existing
   boundary test in `bin/scripts-tests` in the same step.
2. `bin/db-run` / `db-query` / `db-write`: the `-U` option, with the usage-error
   exit codes and stderr logging the `bin/` rules require.
3. `bin/db-create-role`: the owner-role DO block, the superuser connection, the
   equal-roles skip.
4. `bin/db-adopt`: new script.
5. `bin/db-create`: wire 3 and 4 in.
6. `bin/db-bootstrap`: `initdb -U "$POSTGRES_SUPERUSER"`.
7. The dotenvs (D2, D6).
8. Tests: the negative isolation test, then the two guard tests.
9. Docs.

Steps 1–7 leave the tree green at each point except that a checkout whose dev
database predates the change is adopted on its next `bin/db-create`.

## Tests

**The negative test is the point of the RFC** (`bin/scripts-tests`, the
cross-checkout isolation section). Two fake checkouts already exist there as
symlink roots under a `mktemp -d` parent; each now derives its own role from its
own basename, so a command run from root A authenticates as role A.

1. `bin/db-drop` from checkout A against checkout **B's** database is refused —
   asserted through the bounded interface as
   `iso_dev "$iso_root_a" db-write -d postgres 'DROP DATABASE "<B>" WITH (FORCE)'`,
   expecting failure and the text `must be owner of database`. The privilege
   check runs _before_ the FORCE termination step, so this is refused with or
   without `WITH (FORCE)`.
2. B's held connection **survives** the refused drop: a background psql session
   on B is still answering afterwards, and B's marker row still reads 1.
3. The existing positive test stays: `db-reset` in A drops A's own marker and
   leaves B's untouched.
4. The two roles are distinct and neither is a superuser (`pg_roles`:
   `rolsuper=f`, `rolcreatedb=t`, `rolcanlogin=t`).

**Guard test 1 — no literal database name in `bin/` or a fixture.** Loop over
`bin/*`, comment-stripped, asserting no line contains `unicoach-dev-`,
`unicoach-test-` or `unicoach-fuzz-` followed by a literal rather than
`$(checkout_name`. Exclusion list of one, with its reason recorded in the style
of the existing `HARNESS_EXCLUSIONS` guard: `bin/db-scripts-tests`, whose
`POSTGRES_DB=unicoach-db-scripts-test` names a database on a **private**
throwaway cluster with its own port and data directory, which cannot collide.
Paired with a positive assertion that each of the three dotenv derivation lines
calls `checkout_name "$PROJECT_ROOT"`.

Both guards derive their patterns from the dotenvs' own
`<prefix>$(checkout_name "$PROJECT_ROOT")` lines rather than hand-copying
`unicoach-test-` or the prefix alternation. A hand-copied prefix would leave the
budget silently wrong AND the guard silently matching nothing the moment a
prefix changed — the very failure this RFC removes, reintroduced inside the
tests meant to prevent it. Deriving them also makes the new `owner-` role prefix
participate for free.

**Guard test 3 — no unlisted `bin/` script connects as the cluster admin.** D4's
count, made mechanical.

**Guard test 2 — nothing stops or restarts the shared server.** Loop over
`bin/*`, comment-stripped, asserting no `pg_ctl stop|restart`, no
`brew services stop|restart`, no `pkill`/`killall` naming postgres, and no call
to `bin/postgres-down` or `bin/postgres-bounce`. Allowlist, each with its
reason: `postgres-down` itself, `postgres-bounce` (its only caller),
`bin/db-scripts-tests` (private cluster, trap-based teardown), and
`bin/scripts-tests:1304`, which names `bin/postgres-bounce` only inside an
argument-rejection assertion that never starts the server. The repo satisfies
this rule today — `pg_ctl stop`, `pg_ctl restart` and `brew services` have zero
hits — so the test pins a property rather than fixing a defect.

**The cloud equal-roles skip is tested, not assumed.** A second private-cluster
environment sets `POSTGRES_USER` to the cluster admin and leaves
`POSTGRES_SUPERUSER` unset, reproducing the deployed shape, and asserts that
`bin/db-create` creates **no** `owner-%` role and leaves the database owner
alone. Untested, an inverted guard would create an `owner-<release-directory>`
role on RDS.

**The fresh-worktree path is driven through `db-reset`, not `db-create`**, so
`bin/db-drop`'s role bootstrap runs with the role genuinely absent — that is the
path `bin/test` takes in a new checkout.

**`bin/db-adopt` gets the usage grammar every sibling script has**: unknown
option → 10, stray positional → 20, `-h` succeeds. `bin/db-run -U` gets a
valueless-option → 11 test and a forwarding test asserting
`db-query -U <role> -r 'SELECT session_user'` really returns that role — without
it, deleting the forwarding array from `bin/db-query` would pass the whole
suite.

**`bin/db-scripts-tests`** gains the first role coverage in the tree: the
bootstrap creates a `NOSUPERUSER CREATEDB` owner role, `db-create-role` is
idempotent and repairs a deficient role, and `db-adopt` run twice is a no-op.

**Full gate:** `nix develop -c bin/test check`, plus two worktrees running
`nix develop -c bin/shell-tests` simultaneously with both exit codes reported.

## Open items

- **~190 stale `unicoach-dev-*` / `-test-*` / `-fuzz-*` databases** owned by
  `postgres` are sitting on the shared cluster from long-deleted worktrees. Not
  in scope; they are dead weight, and adopting them is pointless. A separate
  sweep should drop them.
- The rejected instance-per-checkout alternative and the original diagnosis were
  cited as living in `.scratch/ship-archive/rfc-170/report.md`. That file does
  not contain them, so the diagnosis is reconstructed above from the failure
  text and the code.
