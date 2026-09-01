# RFC 156 — Per-worktree dev isolation: the dev database, the dev ports, and a dump/restore pair

## Motivation

Every git worktree today shares one dev database named `unicoach` and one set of
dev ports (`8080` / `8081` / `8082`). Both are global mutable state in a
workflow that runs 7+ worktrees at once.

The database half has already caused real loss twice in one day: a
`bin/db-reset` run in any worktree drops the single shared `unicoach` database,
destroying a full college ingest that another worktree was relying on. The blast
radius of a routine, per-run command is every checkout on the machine.

The port half is quieter but the same shape. Two worktrees cannot run a dev
`rest-server` at the same time; the second fails to bind. Worse, whichever one
wins owns `8080` for the whole machine, so an iOS build made in worktree A talks
to whatever daemon happens to hold `8080` — possibly worktree B's server, backed
by a different database.

Neither half is a new design. `.env.dev` already states the intent in a comment
above `POSTGRES_DATA_DIR`:

> Shared absolute path so every git worktree uses the SAME cluster (one postgres
> on POSTGRES_PORT); isolation is per-database. Do NOT use a `$PROJECT_ROOT`
> path.

One cluster, many databases. `.env.test` has implemented exactly that since RFC
131 in a single line, and `.env.fuzz` copies it. `bin/checkout-port` (RFC 132)
has published a stable, 16-aligned, per-checkout port block with `+10..+15`
explicitly reserved and a stated rule that a new consumer takes the next
reserved offset. This RFC finishes both mechanisms by extending them to dev, and
adds the one piece that makes the change survivable in practice: a way to give a
fresh worktree a populated dev database without re-running the ingest.

## Detailed Design

### 1. Per-worktree dev database

Remove the flat `POSTGRES_DB=unicoach` from the base `.env` and set it per role.

`.env.dev` gains the derivation `.env.test` and `.env.fuzz` already use:

    POSTGRES_DB=unicoach-dev-$(checkout_name "$PROJECT_ROOT")

`checkout_name` (`bin/functions`) is that derivation's one home; it echoes
`basename "$PROJECT_ROOT"` and is also the sole input to the port derivation
below, so the checkout's IDENTITY is spelled once rather than in each half.
(`.env.test` and `.env.fuzz` keep their literal `$(basename "$PROJECT_ROOT")`:
routing them through the function would spread its "`bin/functions` first"
precondition to two more files for no functional gain.)

This works for the same reason the test derivation works, and the contract is
already documented at `.env.test:31`: `bin/common` sets `PROJECT_ROOT` at line 3
from its own `${BASH_SOURCE[0]}`, before `load_env_layers` (line 99) sources any
file, and the whole load runs under `set -a`, so the command substitution
expands at source time into a flat exported literal.

**The required consequence: `.env.prod` gains an explicit
`POSTGRES_DB=unicoach`.** `.env.prod` does not set the key today; it inherits
`unicoach` from the base. Removing it from the base makes prod's value unset,
which is the correct outcome under `CONFIGURATION.md`'s five-role model:

> A key omitted here is **unset**, not inherited from `.env.dev`, so
> `require_env_vars` fatals on the laptop before build.

This is not a theoretical concern. `bin/gen-deployed-env:61` carries
`POSTGRES_DB` in `DEPLOY_VAR_NAMES`, line 91 `unset`s every one of those names
before layering (so nothing can leak in from the ambient environment), and line
106 calls `require_env_vars`. Without the `.env.prod` addition,
`bin/deploy prod` fails on the laptop with
`required environment variable [POSTGRES_DB] is unset or
empty`. That is
fail-closed, not a silent wrong-database deploy — but it is still a break, so
**both edits must land in the same commit**.

The value must be exactly `unicoach`, because `infra/rds.tf:28` hardcodes
`db_name = "unicoach"` and nothing enforces the agreement mechanically.

`.env.deploy.prod` needs no change: it is deploy-control only (AWS account and
region) and carries no JVM value. `.env.template` does need updating — it is the
schema reference for the five roles, so `POSTGRES_DB` moves out of its base
block into the `.env.dev` block (derived form) and appears commented in the
cloud-env block, since it is a `DEPLOY_VAR_NAMES` key.

The base `.env`'s own header explains why the key must leave: the base holds
values "identical in dev and every cloud env". Once dev's value is per-worktree,
that is no longer true of `POSTGRES_DB`. RFC 95:40 cites `POSTGRES_DB` as an
example of exactly such an identical value; this RFC reverses that specific
classification and nothing else about RFC 95.

#### 1a. The 63-byte collision: the original bug, re-entering by truncation

A per-worktree database name is only isolation if the server keeps the name it
is given, and it does not. PostgreSQL's `name` type truncates **silently at 63
bytes**, and `unicoach-dev-` spends 13 of them. Two checkouts whose directory
names agree in their first 50 characters therefore land on **one dev database**
— while `cksum`, which sees the full untruncated name, hands them **different
port blocks**. Nothing binds twice. Nothing fails. And the remedy 2d documents
for the port half — rename a worktree — reads as inapplicable to an operator
looking at two names that plainly already differ.

That is the shared-`unicoach` data loss this RFC was written to remove, walking
back in through a side door, and it is invisible in exactly the way the original
was. So it is **guarded, not documented**: `checkout_name` refuses a checkout
basename longer than 50 bytes (counted in bytes, since the server's 63 are
bytes) and names the rename in its message. A derivation that cannot state its
own result is not allowed to produce one.

### 2. Per-worktree dev ports

`bin/checkout-port` reserves `+10..+15` and states that its offset registry is
the authority. Dev takes the next three:

    +10  rest-server  -- dev (PORT / SERVER_PORT)
    +11  admin-web    -- dev (ADMIN_WEB_PORT)
    +12  public-web   -- dev (PUBLIC_WEB_PORT)
    +13..+15  reserved

`.env.dev` derives the three ports from the block base, and **re-derives
`SERVER_PORT` afterwards**. That last point is not optional: `.env:30` assigns
`SERVER_PORT=$PORT` in the base layer, before any delta, so a delta that
overrides `PORT` without re-deriving `SERVER_PORT` leaves `SERVER_PORT` at 8080.
`.env.test:37-38` already documents and solves this exact trap.

#### 2a. The design question: dev must not advance

`bin/checkout-port` advances to the next whole block when **any** port in the
derived block is held. That behaviour is correct for a test run and wrong for
dev, and the two requirements are genuinely in conflict:

- A **test** run is ephemeral. It needs _a_ free block right now; which block is
  irrelevant. Advancing on contention is what lets two colliding checkouts run
  suites concurrently.
- A **dev** daemon is long-lived and its port is _baked into an iOS build_
  (`bin/build-ios:145` → `Info.plist` `UnicoachBackendURL`). A dev port that
  moves is not a dev port. It must be the same port tomorrow, and the same port
  after a reboot, or every previously-built app on the simulator and on the
  phone silently points somewhere wrong.

**Decision: the dev half uses the pure derivation with no advance, ever.**

The alternatives were considered and rejected:

- _Let dev advance too._ Fails the requirement outright. The port would change
  under the operator whenever any unrelated process touched the block, and the
  iOS build would go stale invisibly.
- _A pinned per-worktree override file_ (e.g. a gitignored `.env.dev.local` with
  a hand-chosen port). This restores stability but reintroduces exactly what RFC
  132 removed: a hand-maintained port assignment with no authority, which
  drifts, collides, and has to be reasoned about per checkout. It also does not
  survive `git worktree add` — a fresh worktree would have no port until someone
  wrote the file, which is the friction this RFC exists to delete.
- _Scan for a free port at daemon start._ Same failure as advancing, plus the
  port would differ between two runs in the same checkout.

The derivation is stable because it is a pure function of the checkout directory
name, which is exactly the property RFC 132 built it for. Nothing else is needed
to make dev stable — only the _removal_ of the advance.

#### 2b. The consequence nobody would notice until it bit: the free-check must skip the dev half

`block_is_free` probes all 16 offsets. Once a dev `rest-server` holds `base+10`,
that checkout's own `bin/test` sees its derived block as busy and advances to
`base+16` — a block it does not own, and one that may be another checkout's
derived block. Assigning dev to the reserved range without touching the
free-check would therefore break test-port attribution for every checkout that
runs a dev daemon, which is all of them.

**`block_is_free` is narrowed to the ephemeral half, `+0..+9`, and renamed
`ephemeral_half_is_free`** — the name is load-bearing, because widening this
function back to 16 offsets is the documented way to break the whole design.
Narrowing it is correct rather than a workaround: the dev ports are _supposed_
to be held by this checkout, so treating "my own dev server is running" as
evidence that my block is unavailable inverts the meaning of the check. The
ephemeral consumers only ever bind `+0..+9` (test `+0..+2`, scripts-tests
scratch `+3..+6`, fuzz `+7..+9`), so probing that range is both necessary and
sufficient for the advance decision. A block that advances still lands on a base
whose `+0..+9` are verified free; the dev daemons of whichever checkout derives
that block sit at `+10..+12` and are never contended.

The predicate also stops treating "the probe did not answer" as an answer. It
branches on `bin/check-port`'s **three documented exits** — `0` in use, `1`
free, `2` invalid argument — and **fatals on anything else**, naming the port
and the status. The old `if bin/check-port ...` form read every non-zero status
as _free_, and `if` suspends `errexit`, so a `check-port` that could not run at
all (`126`, `127`, a bad argument) reported the entire block free and
`bin/checkout-port` printed a base whose contention had never been established —
after which `bin/test` binds sixteen ports on it. A free-check that cannot fail
is not a check.

#### 2c. Where the derivation lives, and the recursion trap it avoids

`.env.dev` cannot call `bin/checkout-port`. `bin/checkout-port:2` sources
`bin/common`, and `bin/common` sources `.env` then `.env.dev` — so a command
substitution in `.env.dev` would re-enter `bin/common`, which would source
`.env.dev` again, which would fork another `bin/checkout-port`. That is an
unbounded fork bomb, not a slow path. The same applies to `bin/check-port`.

`.env.test` sidesteps this because a harness (`bin/scripts-tests`,
`bin/test-fuzz`) computes the base and exports `BASE_TEST_PORT` _before_
anything sources `bin/common`. Dev has no harness — `.env.dev` is the ambient
default layer, sourced by every script.

**The pure derivation moves into `bin/functions` as a shell function,
`checkout_block_base`.** This is the one location both consumers can reach with
no subprocess and no recursion:

| consumer        | sets `PROJECT_ROOT` | sources `bin/functions` | sources `.env.dev` |
| --------------- | ------------------- | ----------------------- | ------------------ |
| `bin/common`    | line 3              | line 5                  | line 99            |
| `bin/build-ios` | line 7              | line 8                  | line 113           |

`bin/functions` is already sourced, and `PROJECT_ROOT` is already set, before
the dotenvs are read on **both** paths. The second row matters as much as the
first: `bin/build-ios` deliberately does not source `bin/common` (it runs under
system Xcode, outside the Nix shell) and instead sources `.env` and `.env.dev`
directly, so any dev-port logic that lived in `bin/common` would be invisible to
the iOS build — the one consumer that most needs the right port.

`bin/checkout-port` then calls `checkout_block_base` for its derivation and
keeps its advance loop on top. The formula exists in exactly one place, and
`bin/checkout-port`'s help text remains the registry authority.

The block constants the formula is made of **move to `bin/functions` with it**,
as `CHECKOUT_BLOCK_FLOOR` / `CHECKOUT_BLOCK_SIZE` / `CHECKOUT_BLOCK_COUNT`. They
are not separable from the function: leaving them in `bin/checkout-port` and
re-declaring them beside `checkout_block_base` would put the same three numbers
in two files, which is the exact drift this section exists to remove. The
`CHECKOUT_` prefix is not decoration either. `bin/functions` is sourced by
**every** `bin/` script and the dotenvs are sourced **after** it, so a bare
`BLOCK_SIZE=` anywhere in the repo — or in an operator's environment — would
silently re-scale every dev port. A library global gets a namespaced name.

The half-boundary joins them, as `CHECKOUT_DEV_OFFSET_REST_SERVER` /
`_ADMIN_WEB` / `_PUBLIC_WEB` = `10` / `11` / `12`. `bin/checkout-port` derives
its `EPHEMERAL_SPAN` from `_REST` and `.env.dev` adds the three offsets by name,
so the "+10 is where dev begins" fact has one home and the free-check's boundary
cannot drift away from the ports it is protecting — which is the coupling 2b's
whole argument rests on. It also makes the dotenv legible: `+ 11` cannot be read
in isolation, because its meaning ("admin-web, dev") lives only in the registry.

#### 2c-i. How the value reaches `.env.dev`, and why it is not exported

`checkout_block_base` takes the checkout root as an **explicit argument**,
defaulting to `$PROJECT_ROOT`, and **fatals on an empty one**. An ambient
`PROJECT_ROOT` is silently satisfiable by the _wrong_ value — one exported by a
parent script, or a test fixture's root — which yields a valid-looking block for
another checkout with nothing visible at the call site. An empty one used to
produce `basename "" → "" →` a perfectly real `cksum` `→ 21120`: a plausible
number for no checkout at all.

`.env.dev` does not call the function. `bin/functions` publishes the **value**:

    if [ -n "${PROJECT_ROOT:-}" ]; then
      CHECKOUT_BLOCK_BASE="$(checkout_block_base "$PROJECT_ROOT")" || exit $?
    fi

and `.env.dev` / `.env.template` read `${CHECKOUT_BLOCK_BASE:?...}` and
`${CHECKOUT_DEV_OFFSET_*:?...}`. The `PROJECT_ROOT` guard is not optional —
`bin/functions` is sourced by scripts that never set it, and an unconditional
call to a now-fataling function would kill all of them. The assignment is
status-checked **separately** from any `export` or `local`, both of which return
their _own_ status and would mask a fataling derivation.

**The measured failure this closes.** Before, `.env.dev` computed
`$(( $(checkout_block_base) + 10 ))`. Source `.env` and `.env.dev` without
`bin/functions` and bash evaluates `$(( $(undefined_fn) + 10 ))` to **10**, with
`command not found` on stderr and **rc=0** — `set -e` does not fire on an
arithmetic expansion. The result is `PORT=SERVER_PORT=10`: a silently wrong,
privileged port that `bin/build-ios` then **bakes into an app bundle**, and
whose eventual bind failure reads as a permissions problem rather than a config
one. The two shipped consumers were safe by construction, but the precondition
was written only in a comment, and `.env.template` — the file a new env role is
copied from — carried the same lines. With `:?` the same command fails loudly
with **rc=127** and a message naming the rule.

`CHECKOUT_BLOCK_BASE` is a **plain shell variable, deliberately not exported**,
and that is the subtle part. Its only reader is `.env.dev`, sourced by the
_same_ shell on both paths, so an export would reach nothing that needs it —
only gradle, the JVM, `xcodebuild` and `psql`. Worse, it would **defeat the
fail-closed guard it exists to serve**: a child that sources `bin/functions`
_without_ `PROJECT_ROOT` (`bin/gen-deployed-env` is exactly such a site) would
**inherit** the value, so `.env.dev`'s `:?` could never fire — and what it
inherited would be the **parent checkout's base**, which is precisely the
wrong-block failure the explicit argument was added to prevent. `.env.test`'s
exported `BASE_TEST_PORT` looks like a precedent and is not: that contract
genuinely crosses a process boundary (a harness computes it, a later process
reads it), and this one does not.

#### 2d. Collision: it fails, and that is the whole behaviour

Two checkouts whose directory names hash to the same block (1 in 1900) get the
same dev ports. Since dev never advances, the second daemon fails to bind, and
`bin/daemon-up:167-171` already fatals with the port and the fact that it is
held. **That is sufficient, and no code is added for this case.**

An earlier draft had the fatal walk `git worktree list`, derive each sibling's
block, and name the colliding checkout. That is rejected on layering grounds:
`bin/daemon-up` is general daemon infrastructure that knows about services,
ports, and PID files. Teaching it to enumerate git worktrees would push a
dev-environment concept into a script that has no business holding one, to
improve the message of a 1-in-1900 event. Refusing to start, with the port
named, is already unambiguous and already correct.

The remedy is documented rather than automated: **rename one of the two worktree
directories.** The derivation is a pure function of that name, so a rename moves
the block.

The **database half has the same collision and none of the noise.** Two
checkouts with the same directory basename in different parent directories share
one dev database and overwrite each other's data — and there is no bind to fail,
so nothing announces it. The remedy is the same rename, which moves both halves
at once, and it is written into `.env.dev` beside the derivation rather than
left to be rediscovered. (The truncation variant, where the names differ and the
database still collides, is not left to a comment — see 1a.)

### 3. The iOS staleness problem

`bin/build-ios` derives the backend URL from
`http://$APP_DOMAIN:${SERVER_PORT:-8080}` and bakes it into the bundle's
`Info.plist` as `UnicoachBackendURL`. It therefore picks the new per-worktree
port up automatically, with no change — which is good, and is also the whole
problem.

An app built in worktree A carries A's port forever. Switch to worktree B,
launch the app, and one of two things happens:

1. Nothing is listening on A's port, and the app shows the generic **"No
   Connection"** screen (`InfrastructureError.swift:19`) — a message that blames
   the network for a build-staleness bug.
2. Worktree A's daemon _is_ still running, and the app talks to a **real backend
   backed by a different database**. Nothing is visibly wrong. This is the
   dangerous case, and per-worktree ports make it more likely rather than less,
   because two dev servers can now coexist.

`BackendURL.swift:7` falls back to `http://localhost:8080` when the baked value
is empty or unparseable. **That fallback is left unchanged.** It is only reached
when the `UNICOACH_BACKEND_URL` build setting was never passed — not on any path
this RFC creates, since `bin/build-ios` always passes it — and the install-time
check below covers the realistic failure. Changing Swift to harden a path that
the build cannot produce would be scope this change does not need.

**Decision: make it loud, at install time, by comparing two facts that both
already exist.** The `.app` bundle's expanded `Info.plist` holds the URL the app
was built with, at paths the repo already names (`bin/ios-functions:110`,
`bin/install-ios:83`), readable with system `plutil`/`PlistBuddy` outside the
Nix shell. The URL this checkout _would_ use now is the same three lines
`bin/build-ios:127-147` already computes. If they differ, refuse and say so.

Two supporting moves fall out:

- The URL derivation is **extracted from `bin/build-ios` into a shared helper**,
  `bin/ios-url-functions`, which also removes its existing duplicate in
  `bin/release-ios:135-147`. Three copies of a derivation that must agree is two
  too many.

  This makes one behavioural change, and it is a precondition rather than a side
  effect: on its **local** target `bin/release-ios` previously read `.env`
  alone, and now layers `.env` then `.env.dev` — the same list `bin/build-ios`
  reads. The guarantee asserted below, that the two scripts derive identical
  URLs from one dotenv fixture, cannot hold while they read different files, and
  after this RFC the per-worktree `SERVER_PORT` lives in `.env.dev`, so the
  narrower list would have given `release-ios` the wrong port. Keeping the old
  list behind a flag was considered and rejected: it reinstates precisely the
  drift the extraction removes, and no caller wants it. The **`prod`** target is
  unaffected — it sets `UNICOACH_DEPLOY` and reads `.env.prod` on both sides,
  before and after.
- The check goes in `ios_boot_install_launch` (`bin/ios-functions:191-227`), the
  single choke point both `bin/ios-simulator` and `bin/screenshot-ios` route
  through, and in `bin/install-ios` for the physical-device path, which does not
  pass through `ios-functions`. `bin/ios-simulator -B` — install without
  rebuilding — is precisely the case that needs it.

The check is a **refusal with a one-line fix instruction**, not a warning. A
warning in a build log is not louder than a wrong answer from a real server.

The refusal is a published interface, so it is named: **`IOS_EXIT_STALE_BUNDLE`
= exit `3`**, documented in the `Exit codes:` block of **every script that can
emit it** — `bin/install-ios`, `bin/ios-simulator` and `bin/screenshot-ios` —
and in `DEPLOY.md`, and asserted by the tests below. A published code documented
in only one of its three exits is not published. Callers get a code they can
branch on, not just a message.

The reader that supplies the baked half therefore has **three** outcomes, not
two, and the third is the one an earlier draft got wrong:

1. **The URL was read.** Compare, and refuse on a mismatch.
2. **The bundle states nothing** — no `UnicoachBackendURL` key, an old bundle
   shape. Install, and say so at info level. A bundle that cannot state what it
   was built for is not evidence of staleness, and refusing on absence would
   turn every bundle shape this check does not understand into a hard stop on
   the install path.
3. **The read itself failed** — no `plutil` on `PATH`, a corrupt or half-written
   `Info.plist`. Install, but **warn**, carrying `plutil -lint`'s own verdict.

Outcome 3 is not outcome 2. A tool failure is not evidence about the bundle, and
collapsing the two silently **disabled this entire check on both install paths**
— while the verified path logged "matches this checkout" and the unchecked path
printed nothing at all, so an operator could not tell a checked install from an
unchecked one. Proceeding loudly is defensible; proceeding silently is not, and
neither is refusing an install because a system tool is missing.

#### 3a. What the shared derivation may do, and what it must refuse

Extracting the derivation put a second consumer — the install-time comparison —
onto code that had been written for one, and three properties had to change
before it could be shared honestly.

**The bare-IP `APP_DOMAIN` rule is a build-time policy, and it lives in its own
function.** `ios_require_valid_app_domain` is called from `bin/build-ios` and
`bin/release-ios` only; `ios_derive_backend_url` produces a **value** and
nothing else. It had been inside the derivation, so the new install path
inherited a refusal it never asked for: a bundle baked for `http://10.0.0.5:…`
in a checkout whose `APP_DOMAIN` **is** `10.0.0.5` — the two agree, the check's
own subject is satisfied — aborted the install anyway, with **exit 1** instead
of the published `IOS_EXIT_STALE_BUNDLE`, and with a cookie-Domain message on a
path where nothing is being built. Moving the policy into the caller's shell
also fixes a quieter thing: a `fatal` inside `$( )` is a subshell, so whether it
stopped the program was decided by the caller's syntax (`local u="$(…)"` masks
it entirely), which is why the old code needed the same "plain assignment, never
`local`" warning in four separate comments. A safety property restated in four
comments is one the code does not hold.

**The rule is now an allowlist: the last DNS label must start with a letter.**
The denylist it replaces matched a dotted quad and a colon, and therefore
admitted `127.1`, `10.1`, `2130706433`, `0x7f000001` and `192.168.1` — every one
of them a bare IP literal that iOS resolves, every one an invalid cookie
`Domain` under RFC 6265, and so every one a bundle whose session cookie silently
never persists. The allowlist subsumes all of them and still admits `localhost`
and MagicDNS names, with no interpreter dependency added to a script that must
run under the Command Line Tools alone.

**`SERVER_PORT` is required on the local derive path, and validated with
`validate_port`.** It was `${SERVER_PORT:-8080}`. After section 2 **no dev
server binds 8080**, so that fallback bakes a dead port into a bundle — and here
is the sharp part: **the staleness check cannot catch it, because the expected
URL and the baked URL come from this same function.** They agree with each other
while both are wrong. A check that compares a value against itself is not a
check. The same reasoning rules out merely testing for non-emptiness: `abc`,
`"8080 "`, `0` and `99999` are all non-empty and none is a port, and each would
be baked and then confirmed by a comparison with itself. `bin/functions` already
owns that rule as `validate_port`, and every caller of the iOS library sources
it first.

**Loading the dotenvs is a shell load, not an export.** The loader had used
`set -a`, which made it a process-wide export: `bin/build-ios` and
`bin/release-ios` then ran `xcodebuild` — and every build phase it spawns — with
`POSTGRES_DB`, `DATABASE_PASSWORD`, `DATABASE_USER`, `PGHOST`,
`POSTGRES_DATA_DIR`, `SERVER_HOST` and `APP_STORE_ENVIRONMENT` in the
environment, and on the deploy path `.env.prod`'s client-ID secrets besides.
Nothing needs them: both callers pass what the build consumes as explicit build
**settings**. The values are sourced into the caller's shell and exported
nowhere; dotenv `$VAR` interpolation is unaffected, because plain shell
variables expand.

### 4. `bin/db-dump` and `bin/db-restore`

Per-worktree dev databases create a new chore: a fresh worktree's database does
not exist, and populating it means re-running the ingest — ~419 MB of federal
source data, about a minute of work plus JVM startup. That chore is the direct
cost of section 1, and this section pays it off.

    bin/db-dump    [-f] [-d <db>] [-o <file>]   # stdout by default, -f overwrites
    bin/db-restore [-y] [<file>]                # stdin by default

Both default to `$POSTGRES_DB` from the layered env, like every other `bin/db-*`
script. `pg_dump` and `pg_restore` 18.3 are already in the dev shell, and
`bin/common:94` exports `PGPORT`, so neither needs `-h`/`-p`.

**`db-dump` takes `-d`, and `db-restore` deliberately does not.** That asymmetry
is a decision, and `-d` exists for a reason this RFC originally missed. The
pair's very first job is section 5's migration — lifting the legacy shared
`unicoach` database into each worktree — and after this change **no checkout
points at `unicoach`**, so a plain `bin/db-dump` dumps a database that does not
exist. The obvious escape does not work either: `bin/common` sources the dotenvs
under `set -a` **after** the caller's environment, so
`POSTGRES_DB=unicoach
bin/db-dump` is overwritten by `.env.dev`. Section 5's
original "or by hand" was the hand-wave that hid this; `-d` is the fix,
read-only and mirroring `bin/db-query -d`. `db-restore` gets no counterpart,
because it **destroys** its target and a restore aimed at the wrong database is
the exact accident this RFC exists to prevent. The read-only half may name a
database; the destructive half may only ever act on the one its own environment
resolves.

#### 4a. The design question: why not `CREATE DATABASE ... TEMPLATE`

`CREATE DATABASE new TEMPLATE unicoach` is a file-level copy inside one cluster.
It parses no SQL and rebuilds no index, it needs no artifact on disk, and it
works here precisely because `.env.dev` already pins every worktree to one
shared cluster. It is the obvious fast path, and it was measured against the
alternative on the live dev database rather than argued from intuition:

| step                           | time       | size           |
| ------------------------------ | ---------- | -------------- |
| dev database `unicoach`        | —          | 99 MB          |
| `pg_dump -Fc`                  | **0.83 s** | 10 MB artifact |
| `createdb` + `pg_restore -j 4` | **0.93 s** | 87 MB restored |

**Decision: the template path is not implemented. Two commands, not three, and
the name `clone` stays unspent.**

The reasoning, in the order that decided it:

1. **The win being competed for is already gone.** The expensive thing is the
   ingest, at roughly a minute plus JVM startup. `db-restore` removes it and
   lands at 0.93 s. A template copy would compete against 0.93 s, not against
   the minute. There is no user-visible difference between the two.

2. **Its precondition is hostile in exactly this workflow.** `TEMPLATE` requires
   **zero connections** to the source database. The operating mode this RFC is
   written for is 7+ worktrees with long-lived dev daemons, each holding a
   connection to its own database. The moment the source worktree has a server
   up — the normal state — the fast path is unavailable, and the command's job
   degrades into telling the operator to go stop a daemon in another checkout. A
   fast path that is usually blocked, and whose remedy is the same class of
   cross-worktree interference this RFC set out to eliminate, is not worth its
   own verb.

3. **A fallback would make the command dishonest.** Had `db-restore` preferred
   the template when it could and fallen back to the file, its two branches
   would mean different things: one reproduces a recorded artifact, the other
   copies a neighbouring worktree's _live, possibly half-migrated_ state. The
   operator could not tell from the invocation which happened. That ambiguity is
   the same category of surprise as the shared `db-reset` this RFC removes, and
   it would be reintroduced in the very command meant to make recovery cheap.

The file-based path also carries capabilities the template path structurally
cannot: it is portable across machines and Postgres versions, it survives a
cluster wipe, and the artifact outlives the source database. Those are the
properties that make it the right single mechanism rather than merely the
acceptable one.

If the template path is ever wanted, it is a separate command with its own name
and its own preconditions stated up front. This RFC records that it was measured
and declined, so the question does not need re-litigating from scratch.

#### 4b. Format and shape

**`pg_dump -Fc`** (custom format), not plain SQL: it compressed 99 MB to 10 MB,
it is what `pg_restore -j` needs for parallel restore, and it allows selective
restore later. The cost is that the artifact is not human-readable and needs
`pg_restore` rather than `psql`, which is an acceptable trade for a regenerable
cache.

**`db-restore` drops and recreates the target first.** Restoring over a
populated database yields a merge, not a restore. It reuses `bin/db-drop -y` and
`bin/db-create`, following `bin/db-reset:35-37`'s precedent, and omits
`db-migrate` because the dump already carries the schema and the
`schema_migrations` rows.

One wrinkle that must be handled rather than discovered: `bin/db-create`
pre-creates an empty `schema_migrations` (`bin/db-create:66-72`), which collides
with the same table in the dump. `db-restore` therefore runs
`pg_restore --single-transaction --clean --if-exists --no-owner
--no-privileges`.
`db-create` is still required in the sequence — it is what issues the
`GRANT CONNECT` to `DATABASE_USER`, a database-level grant that a plain
`pg_dump` does not carry.

**`--single-transaction` is the load-bearing flag, and it is not a tidiness
measure.** `pg_restore` is **best-effort by default**: it continues past errors
and reports a count at the end. That was measured, not assumed — a scratch pair
produced three ignored errors, one table restored, one not, and exit `1`, over a
database that had already been dropped. The dangerous half is what the dump
carries: `schema_migrations` can restore while a table does not, and
`bin/db-status` would then report a **fully migrated database over a broken
schema**. That is precisely the "looks right, is wrong" failure this RFC exists
to eliminate, sitting inside the recovery tool meant to repair it. Every other
step in the pair is atomic — the archive gate below, `db-dump`'s temp-then-`mv`
publish — and this was the one that was neither. `--single-transaction` (which
implies `--exit-on-error`) makes a failed restore **roll back**, so the outcome
is the whole archive or an **empty** database and never a plausible half. The
failure message says so, in those words: exit `5` means the target is empty and
must be restored again.

**Nothing is dropped until the input is known restorable.** `db-restore`
verifies the input with `pg_restore --list` **before** it calls `bin/db-drop`,
and refuses with exit `7` if that fails, carrying `pg_restore`'s own stderr —
the single most important refusal in the pair is not one to print without its
reason. Without that gate, a truncated file or a plain-SQL file passed by
mistake (the natural mistake, since `-Fc` is not human-readable) ended at exit
`5` **with the database already gone**. Ordering the gate before the drop is
what makes the destructive step safe to reach.

**`db-restore` prompts before destroying data.** It calls `bin/db-drop -y`
internally, and that `-y` disarms the only confirmation in the chain, so
`db-restore` takes its own `-y` and calls `require_dangerous_confirmation`
itself. A destructive command must not become silent by delegating. On a stdin
restore the prompt cannot share stdin with the dump, so it is read from the
controlling terminal; with no terminal the command **refuses**, because the
alternative is a silent destructive run at exactly the moment nobody is
watching.

**Exit codes.** `bin/INVARIANTS.md` requires a distinct documented code per
failure reason, so both scripts enumerate theirs in `help()`:

`bin/db-dump`:

| code | meaning                                                    |
| ---- | ---------------------------------------------------------- |
| 2    | postgres unreachable, or the existence probe itself failed |
| 3    | the `-o` target exists and `-f` was not given              |
| 4    | the database to dump does not exist                        |
| 5    | `pg_dump` failed, or the publish move failed               |
| 10   | unknown option                                             |
| 11   | option without its value                                   |
| 20   | unexpected positional                                      |
| 22   | `-d` or `-o` given an EMPTY value                          |
| 23   | `-f` without `-o`                                          |

`bin/db-restore`:

| code | meaning                                                                               |
| ---- | ------------------------------------------------------------------------------------- |
| 3    | the named input file does not exist or is not readable (an EMPTY argument lands here) |
| 4    | no input: stdin is a terminal, or the stream was empty                                |
| 5    | `pg_restore` failed; THE RESTORE WAS ROLLED BACK AND THE TARGET IS EMPTY              |
| 6    | no terminal available to confirm a stdin restore (pass `-y`)                          |
| 7    | the input is not a readable `pg_dump` archive; NOTHING was dropped                    |
| 10   | unknown option                                                                        |
| 11   | option without its value                                                              |
| 20   | unexpected positional                                                                 |

Code `2` is the repo-wide "postgres unreachable" meaning (`bin/db-run`), reused
rather than reinvented; `db-restore` never needs it because the `db-drop` it
delegates to reports it already. `22` is the other lesson the codes carry: an
empty option value is a **malformed invocation**, not an absent option. Read as
absent, `-d ""` silently became `POSTGRES_DB` — dumping the wrong database with
the flag that exists to name a different one — and an empty positional turned a
file restore into a **stdin** restore, bypassing the exit-`3` refusal that
protects exactly that case. Presence is therefore held separately from value in
both scripts.

These two ship as a designed **pair**, and the pair is read together far more
often than either script is read alone. An earlier draft of the implementation
had `5` mean "`pg_dump` failed" in one script and "no terminal to confirm" in
the other, so the same number meant "the tool broke" on one side of a pipe and
"I could not ask you" on the other. That is resolved in favour of the pair:
**`5` is "the underlying `pg_*` tool failed" in both**, and `db-restore`'s
no-terminal refusal takes the next free operational code, `6`. The alternative —
same number, two meanings, an explanation of why that is fine — buys nothing
except not editing two `help()` blocks.

#### 4c. Where the artifact lives

The artifact is a **cache, not an asset**: regenerable from a fresh ingest,
periodically refreshed, and never authoritative. It is stored **outside the
repo** at `$HOME/opt/unicoach/var/dumps/`, mirroring `.env.dev:11-13`'s
reasoning for `POSTGRES_DATA_DIR` — the whole point is that one worktree dumps
and another restores, so a `$PROJECT_ROOT/var/` path would defeat the purpose.

No new dotenv key is introduced. The default is stdout/stdin and the path is an
explicit argument, so the directory is a documented convention rather than
configuration. That keeps the commands composable:

    nix develop -c bin/db-dump -o ~/opt/unicoach/var/dumps/dev.dump
    nix develop -c bin/db-restore ~/opt/unicoach/var/dumps/dev.dump
    nix develop -c bin/db-dump | ssh other-mac 'nix develop -c bin/db-restore -y'

The `-y` in the third form is not optional. A non-interactive `ssh` has no
controlling terminal, so the confirmation described in 4b cannot be asked and
`db-restore` refuses rather than destroying data unwatched. The pipe form is
written with `-y` in both scripts' own help for the same reason.

The stream form is for a **different machine**, and the documented example says
so. `bin/db-dump | bin/db-restore` **within one checkout** is not a workflow and
is not advertised as one: both ends resolve the same `POSTGRES_DB`, so it drops
a live dev database in order to restore it from itself. It survives only as a
mechanism test on a private cluster, commented as such.

#### 4d. Local-only

Both are local tooling, like `bin/state-apply`. `bin/deploy` is an
**allow-list** (`bin/deploy:124-138` `REPO_PATHS`), so a new `bin/` script is
excluded by construction and **`bin/deploy` needs no edit**. `bin/state-apply`
is excluded the same way, by omission.

Because omission is invisible, two guards are added against a future "helpful"
addition: a `Local-only tooling; never deployed.` line in each `help()`
(mirroring `bin/state-apply:12`), and a negative assertion in
`bin/scripts-tests` — the mirror image of the existing positive `unit_bundled`
check at `bin/scripts-tests:1047-1059`.

### 5. Migration of existing checkouts

Every worktree's dev data currently lives in the database literally named
`unicoach`. After this change, dev points at `unicoach-dev-<worktree>`, which
does not exist, and the first DB-backed command in each worktree fails until the
database is created.

**The old `unicoach` database is deliberately left in place.** No script drops
it (`bin/db-drop` only ever drops `$POSTGRES_DB`), so it remains as the source
for the first dump:

    nix develop -c bin/db-dump -d unicoach -o ~/opt/unicoach/var/dumps/dev.dump   # once, from any checkout
    nix develop -c bin/db-restore ~/opt/unicoach/var/dumps/dev.dump               # in each worktree

The `-d unicoach` is what makes the first line runnable at all, and 4's
asymmetry is why it is on the dump and not on the restore. After this change no
checkout's `.env.dev` resolves `unicoach`, and the environment cannot be used to
say so — `bin/common` layers the dotenvs under `set -a` after the caller's
environment, so `POSTGRES_DB=unicoach bin/db-dump` loses to `.env.dev`. An
earlier draft of this section said "or by hand"; that phrase was the
incompleteness, and `-d` replaces it.

This is documented in the RFC and in `README.md`; it is a one-time operator
step, not something a script performs automatically.

## Files Modified

**Dotenv layer**

- `.env` — remove `POSTGRES_DB=unicoach`.
- `.env.dev` — add `POSTGRES_DB=unicoach-dev-$(basename "$PROJECT_ROOT")`; add
  `PORT`/`ADMIN_WEB_PORT`/`PUBLIC_WEB_PORT` derived from `checkout_block_base`
  at `+10`/`+11`/`+12`; re-derive `SERVER_PORT=$PORT` after them; update the
  `APP_DOMAIN` comment that cites `${SERVER_PORT:-8080}`.
- `.env.prod` — add explicit `POSTGRES_DB=unicoach` (must match
  `infra/rds.tf:28`).
- `.env.template` — move `POSTGRES_DB` out of the base block into the `.env.dev`
  block (derived form) and add it commented to the cloud-env block; update the
  port lines to note the dev derivation.
- `.env.deploy.prod` — **no change** (deploy-control only; verified against the
  final tree).

**Ports**

- `bin/functions` — new `checkout_name()` and `checkout_block_base()` holding
  the two pure derivations, the `CHECKOUT_BLOCK_FLOOR` / `_SIZE` / `_COUNT`
  geometry and the `CHECKOUT_DEV_OFFSET_REST_SERVER` / `_ADMIN_WEB` /
  `_PUBLIC_WEB` offsets they are made of, the `CHECKOUT_NAME_MAX_BYTES`
  truncation guard (1a), the unexported `CHECKOUT_BLOCK_BASE` value under its
  `PROJECT_ROOT` guard, and the shared `require_postgres_reachable()` /
  `database_exists()` helpers the new `bin/db-dump` uses.
- `bin/checkout-port` — call `checkout_block_base "$PROJECT_ROOT"`; narrow
  `block_is_free` to `+0..+9` and rename it `ephemeral_half_is_free`, branching
  on `bin/check-port`'s three exits; derive `EPHEMERAL_SPAN` from
  `CHECKOUT_DEV_OFFSET_REST_SERVER`; update the offset registry in `help()` with
  `+10/+11/+12` dev and the no-advance rule for the dev half.
- `bin/daemon-up` — **no change** (its existing port-held fatal is the collision
  behaviour; see 2d). Verified against the final tree.

**Dump / restore**

- `bin/db-dump` — new.
- `bin/db-restore` — new.
- `bin/deploy` — **no change** (allow-list; verified against the final tree).

**iOS**

- `bin/ios-url-functions` — **new**: the shared dotenv load, the URL derivation,
  the separate `ios_require_valid_app_domain` build-time policy, the
  three-outcome bundle reader, and `ios_assert_bundle_is_this_checkouts` with
  `IOS_EXIT_STALE_BUNDLE`.
- `bin/build-ios` — use the helper; call the domain policy explicitly; drop the
  `${SERVER_PORT:-8080}` fallback from both the code and the help text.
- `bin/release-ios` — use the helper (removes the duplicate derivation); the
  local target now layers `.env` then `.env.dev`, matching `bin/build-ios`.
- `bin/ios-functions` — staleness check in `ios_boot_install_launch`.
- `bin/install-ios` — staleness check on the device path;
  `IOS_EXIT_STALE_BUNDLE` in `help()`.
- `bin/ios-simulator`, `bin/screenshot-ios` — document the exit-`3` refusal they
  can now emit.

**Tests**

- `bin/db-scripts-tests` — new "Dump & Restore" section.
- `bin/scripts-tests` — dev-half no-advance assertions; move the existing
  `base+12` foreign-listener fixture; the two-worktree `db-reset` isolation
  test; the `gen-deployed-env prod` pairing assertion; the `.env.dev` /
  `.env.template` key-set comparison; `not_bundled` deploy assertions.
- `bin/ios-scripts-tests` — staleness-check assertions, the domain allowlist
  asserted through the shipped function, and the no-leak assertion on
  `xcodebuild`'s environment.

**Docs**

- `CONFIGURATION.md` — the `PORT=8080 ... unconditional` paragraph; the
  `POSTGRES_DB` role split. It now **points at** `bin/checkout-port -h` instead
  of restating the offset registry: the RFC says that help text is the
  authority, and a prose copy is a second authority.
- `README.md` — config table `POSTGRES_DB` row; the per-worktree derivation
  paragraph; dev port references; the migration note from section 5; the same
  de-duplication of the registry.
- `ios-app/DEPLOY.md` — the derive-path and troubleshooting sections.
- `ios-app/env/simulator.env`, `ios-app/env/local.env.example` — port comments.

## Implementation Plan

1. **`checkout_block_base` in `bin/functions`**, `bin/checkout-port` calls it,
   `block_is_free` narrowed to `+0..+9` and renamed `ephemeral_half_is_free`,
   help-text registry updated. Land the `bin/scripts-tests` changes with it: the
   existing `base+12` fixture moves into the ephemeral half, plus a new
   assertion that a listener on the dev half does _not_ advance.
2. **`.env.dev` ports** (`+10/+11/+12` and the `SERVER_PORT` re-derive). Verify
   two worktrees run dev servers concurrently.
3. **`POSTGRES_DB` move** — `.env` removal, `.env.dev` derivation, `.env.prod`
   addition, `.env.template`, all in one commit. Verify
   `bin/gen-deployed-env prod` still resolves.
4. **`bin/db-dump` / `bin/db-restore`** plus the `bin/db-scripts-tests` section
   and the `not_bundled` assertions.
5. **iOS**: extract the URL helper, wire `build-ios`/`release-ios` to it, add
   the staleness check at both choke points, assert in `bin/ios-scripts-tests`.
6. **Docs**, then the full `nix develop -c bin/test -f` and `bin/shell-tests`.
   `-f` because Gradle does not model a dotenv change and an unforced run would
   execute nothing (see Tests).

No schema migration: this RFC adds no table and no column.

## Tests

**The isolation assertion — the point of the change.** A `bin/db-reset` in one
worktree must leave another worktree's dev data untouched. Asserted directly,
not assumed: create two checkout roots, populate a marker row in each one's dev
database, run `bin/db-reset` in the first, and assert the marker still exists in
the second and is gone from the first. This is the regression that motivated the
RFC and it gets an explicit test.

**Ports** (`bin/scripts-tests`)

- `checkout_block_base` is deterministic and equal to `bin/checkout-port`'s base
  when nothing is held (the registry and the derivation cannot drift apart).
- A listener on the **dev** half (`+10`) does **not** cause an advance — the new
  rule, and the inverse of the current `base+12` test.
- A listener on the **ephemeral** half still advances by a whole block — the
  existing RFC 132 guarantee, with its fixture moved off `+12`.
- Two distinct checkout names derive disjoint dev port triples.
- `.env.dev` yields `SERVER_PORT == PORT == base+10` (guards the `.env:30`
  trap).
- `bin/deploy` bundles neither `db-dump` nor `db-restore`.

**Database** (`bin/db-scripts-tests`, private throwaway cluster)

- `db-dump` → `db-restore` round-trips row data.
- `db-restore` over a populated database replaces rather than merges.
- `db-restore` restores `schema_migrations` such that `bin/db-status` reports no
  pending migrations.
- stdout/stdin piping works (`db-dump | db-restore`) — a **mechanism** test on
  the private cluster, commented as such, not a recommended workflow (4c).
- Usage errors map to the documented codes: unknown option `10`, option without
  value `11`, stray positional `20`, an empty `-d`/`-o` value `22`; operational
  failures (missing input file, refusing to overwrite without `-f`) use
  documented `1`–`9` codes, never the `10`–`29` band.
- A non-archive fed to `db-restore` exits `7` and the database **survives** —
  the pre-drop gate, triggered rather than assumed.
- `db-dump -d` dumps the named database rather than `POSTGRES_DB`.
- `db-restore` without `-y` prompts.
- `db-dump | db-restore` without `-y` and with **no terminal** exits `6` and
  leaves the database intact — the destructive path's refusal, triggered rather
  than assumed. The terminal `db-restore` reads the confirmation from is named
  by `UNICOACH_TTY_DEVICE` (default `/dev/tty`) purely so a harness, which
  always has a real `/dev/tty`, can reach this branch; the same test-hook
  convention as `UNICOACH_DOTENV` and `UNICOACH_ENV_DIR`.

**iOS** (`bin/ios-scripts-tests`, shimmed `xcodebuild`/`xcrun`)

- A bundle whose baked `UnicoachBackendURL` differs from the checkout's derived
  URL is refused at install, on both the simulator and device paths.
- A matching bundle installs without complaint.
- `bin/build-ios` and `bin/release-ios` derive identical URLs from one fixture
  dotenv (guards the de-duplication, and is why `release-ios`'s local layering
  had to change).
- The domain allowlist is asserted **through the shipped
  `ios_require_valid_app_domain`**, not against a copied regex — a copied
  assertion would keep passing after the library was fixed.
- `xcodebuild` receives `UNICOACH_BACKEND_URL` and `UNICOACH_CLIENT_KEY` as
  build **settings** while its inherited environment carries none of the dotenv
  values (the `set -a` leak, asserted rather than reasoned about).

**Config**

- `bin/gen-deployed-env prod` resolves `POSTGRES_DB` **equal to the `db_name`
  read out of `infra/rds.tf`**. It asserts the **pairing**, not the literal
  `unicoach`, and that distinction is the point: `unicoach` now lives in
  `infra/rds.tf` (the owner), `.env.prod`, and the test. A test that spelled the
  literal would stay green through a rename in `rds.tf` while the instance JVM
  connected to a database RDS never created — the test would have become a third
  copy of the coupled value instead of a check on it. The read is `sed`, because
  a `tofu output` needs an initialised directory, and the reason is stated at
  the read.
- It is asserted against the **real** env files, not the hermetic `gentest`
  fixture: the fixture writes its own base `.env`, so it cannot observe a
  removal from the real one at all, and a fixture that still set `POSTGRES_DB`
  would keep the whole suite green through a `.env.prod` regression.
  `gen-deployed-env` only prints, so running it for real is read-only. The
  `gentest` fixture is moved to the same role split for consistency —
  `POSTGRES_DB` out of its fake base, into its cloud file.
- Existing `ServiceConfTest` / `SessionConfigTest` / `AdminConfigTest` /
  `PublicWebConfigTest` continue to pass: they inject values explicitly and
  never read the dotenv layer.

**What was executed on the final tree.** `bin/scripts-tests` **307**,
`bin/db-scripts-tests` **57**, `bin/shell-tests` **396** in total,
`bin/ios-scripts-tests` **214**, and `nix develop -c bin/test -f` **2259**
Kotlin tests with **0** failures.

`-f` is not optional here, and this change is the exception `CLAUDE.md` names.
Gradle does not model a dotenv file, so a bare `bin/test` executes **zero**
tests for a change whose entire subject is `.env` / `.env.dev` / `.env.prod` /
`.env.template` — an empty run that reports green. A forced run is the only one
that carries evidence.

**Live verification**, because a fixture is not a listener. A real `rest-server`
in this checkout bound `127.0.0.1:41770` (the derived `base+10`), `/healthz`
answered `200`, and `8080` was **free** — dev no longer takes the shared port.
With that daemon live, `bin/checkout-port` still printed **41760**, not 41776:
the no-advance property of 2b, confirmed against a real bind rather than a
fixture. The first start also failed correctly, with
`database "unicoach-dev-unicoach-rfc-156" does not exist` — section 5 happening
for real.

The gate is `nix develop -c bin/test` plus `nix develop -c bin/shell-tests`, and
the landing commit runs the full `bin/pre-commit` hook.

## Open items

Review deliberately stopped short in a few places, and they are recorded here so
the next reader knows they were decided rather than missed.

- **`bin/db-create`, `bin/db-run`, `bin/db-repl` and `bin/admin-grant` keep
  their own inline copies** of the postgres-reachable preamble and the
  database-existence probe. The new `require_postgres_reachable()` and
  `database_exists()` in `bin/functions` are used by `bin/db-dump` only. All
  four are in `bin/deploy`'s `REPO_PATHS` allow-list and run **on the
  instance**; rewriting a deployed script to satisfy a style rule is how a
  config change becomes an outage. For the same reason there is no
  `bin/db-functions`: a new library sourced by a deployed script would have to
  be added to `REPO_PATHS`, contradicting this RFC's verified "`bin/deploy` — no
  change".
- **`.env.test` and `.env.fuzz` still spell `$(basename "$PROJECT_ROOT")`** in
  their own database derivations rather than calling `checkout_name`. They work,
  and routing them through the function would spread its "`bin/functions` must
  be sourced first" precondition to two more files for no functional gain.
- **`bin/ios-url-functions` holds both the URL derivation and the install-time
  refusal policy.** The seam is recorded, not split: splitting a one-day-old
  library, on a change already carrying three parts, is the speculative
  restructuring this review otherwise spent its time removing.
- **`IOS_BUNDLE_ID` is still spelled in three places** (`bin/install-ios`,
  `bin/release-ios`, `bin/ios-functions`). Pre-existing, and outside this
  change's files.
- **The "no `plutil` on `PATH`" branch of the bundle reader is covered only via
  the corrupt-plist path.** The status is exercised; that particular cause is
  not.
- **Only one worktree was exercised with a live daemon.** The sibling checkouts
  were live ship runs owned by other sessions, and `bin/scripts-tests` stops a
  checkout's daemons on exit, so starting servers there would have interfered
  with another agent's work. Cross-worktree port disjointness therefore rests on
  the derivation test plus the single real bind above.
