# Configuration

How configuration reaches each consumer, and the durable guarantees the model
rests on. Full design:
[`rfc/87-multi-environment-config-and-deploy.md`](rfc/87-multi-environment-config-and-deploy.md).

## Config sources

- **Shell environment** — a layered dotenv `bin/common` sources, and the same
  layered shape reaches the iOS build, Terraform, and the cloud hosts. Five
  roles (RFC 95), split by scope:
  - **`.env`** — the base: env-**neutral** non-secret app config only (values
    identical in dev and every cloud env). No dev-dangerous value, no
    deploy-control value.
  - **`.env.dev`** — local-dev's env-specific and dev-dangerous values (dev DB
    password, `trust`, `localhost`, the bind-host loopback overrides, the
    required toggles' dev values). Layered by `bin/common`'s ambient default
    (`.env → .env.dev`) and by the test harnesses
    (`ENV_FILES=".env.dev:.env.test|.env.fuzz"`). The deploy/infra path
    **never** layers it.
  - **`.env.<env>`** (e.g. `.env.prod`) — a cloud env's non-secret app config.
    Read only by `bin/gen-deployed-env` (to flatten into the `deploy-env`
    artifact) and `bin/infra-*` (to export `TF_VAR_*`), always as
    `.env → .env.<env>` with **`.env.dev` excluded** (the deploy/infra chain
    rule). A key omitted here is **unset**, not inherited from `.env.dev`, so
    `require_env_vars` fatals on the laptop before build.
  - **`.env.deploy.<env>`** — deploy-control only (`AWS_ACCOUNT_ID`, `REGION`,
    and the `AWS_REGION` derivation). Sourced by
    `bin/deploy`/`bin/remote`/`bin/infra-*` to reach the right AWS
    account/region; never shipped to the instance.
  - **SSM `/unicoach/<env>`** — the two Terraform-owned classes only: secrets
    (SecureStrings) and RDS identity (`PGHOST`/`DATABASE_HOST`/`POSTGRES_USER`/
    `DATABASE_USER`).

  A **harness-only override** is the one thing outside these five roles.
  `.env`'s `PORT=8080`, `ADMIN_WEB_PORT=8081` and `PUBLIC_WEB_PORT=8082` are
  unconditional — as the base must be, so a cloud `.env.<env>` that omits a key
  fatals rather than inheriting ambient environment — which means a test harness
  cannot hand its children a `PORT`: the base layer destroys it in every child
  before `.env.test` is reached. The harness therefore exports one key the base
  deliberately does **not** own, and the last layer re-derives every service
  port from it: `bin/scripts-tests` and `bin/test-fuzz` both export
  `BASE_TEST_PORT`, the base of this checkout's 16-port block from
  `bin/checkout-port`, and `.env.test` / `.env.fuzz` assign each service an
  offset inside it (`PORT`/`ADMIN_WEB_PORT`/`PUBLIC_WEB_PORT` at `+0/+1/+2` for
  test, `+7/+8/+9` for fuzz — RFC 131, RFC 132). The offset registry in
  `bin/checkout-port`'s help text is the authority for who owns which offset;
  ports are assigned there, never scanned for. One key, not a per-service one:
  the block is the unit a checkout owns, so a new consumer takes the next
  reserved offset rather than a new harness variable. `BASE_TEST_PORT` is set
  only by a harness at run time, never committed to any `.env*`, and never read
  by the JVM — the JVM sees only the `PORT`/`SERVER_PORT` it resolves to.

  `ENV_FILE` selects a single delta; `ENV_FILES` is a PATH-like `:`-separated,
  exported string of deltas layered left-to-right (later wins) — the two are
  mutually exclusive. On a **cloud host** the release bundle carries an
  intentionally **empty** `.env` and `render-env` writes the **complete**
  `/etc/unicoach/env` by merging the bundle's flat `deploy-env` **under** the
  SSM fetch (SSM last-wins); both systemd (`EnvironmentFile`) and `bin/common`
  (`ENV_FILE=/etc/unicoach/env`, layered over the empty base) read that one file
  identically. The `<env>` argument to `bin/infra-*`/`bin/deploy` is the sole
  env identity (there is no `ENVIRONMENT` dotenv key).
- **HOCON (`*.conf`)** — read only by the JVM; pulls shell environment values in
  via `${?VAR}` (or a required `${VAR}` for a security/mode toggle — see
  invariant 7).
- **Local overrides and secrets** — `~/.config/unicoach/local.conf`, on a
  developer's machine only.

## Prime directive

A config value is set once and only once — in the shell environment files or in
HOCON, never both.

- Needed by the JVM **and** any other consumer → set it in the shell environment
  files.
- Needed **only** by the JVM → set it in a HOCON file.

**Terraform is a consumer too.** A plain Terraform `variable` with its own
default (`region`, `instance_type`, `db_instance_class`) is correct only when no
other consumer needs the value. A value the JVM also needs — e.g. a port that is
both an ALB target/security-group port and the service's HOCON bind port — falls
under "needed by the JVM and any other consumer": it belongs in the shell
environment, with Terraform reading it via `TF_VAR_*` (mirror `app_domain`),
never owning it as a `local`/`variable` default and pushing it out to SSM.

## Precedence

Highest wins: JVM system properties (`-D`) > `~/.config/unicoach/local.conf`
overlay > process environment (`${?VAR}`, SSM-rendered on cloud) > committed
`.conf` defaults.

## Invariants

1. **Committed config spans every environment, split by role.** `.env` holds
   env-neutral non-secret values; `.env.dev` holds local-dev values; each
   `.env.<env>` holds that cloud env's non-secret values. No secret and no
   Terraform-owned identity is committed — those are the only two classes SSM
   owns (`/unicoach/<env>`). A fresh checkout still runs with zero setup and
   zero AWS, because `bin/common` layers `.env → .env.dev` by default.
   _Secrets/identity are never committed; a checkout needs no AWS._
2. **Secrets are never committed.** A secret lives only in `/unicoach/<env>` SSM
   SecureStrings (cloud) or `~/.config/unicoach/local.conf` (local) — never in a
   committed file. _A pushed secret is effectively unrevocable._
3. **The JVM reads environment variables only through HOCON.** Application code
   never calls `System.getenv`; config enters via HOCON `${?VAR}`, JVM system
   properties (`-D`), or `main()` arguments. _One read-boundary keeps every
   value's source uniform._
4. **Each value has exactly one home.** Defined once, in the shell environment
   or HOCON per the prime directive — never both a HOCON default and a dotenv
   definition. _Two homes drift._
5. **The local overlay never reaches the test JVM.** Every test JVM pins
   `unicoach.config.dir` to an overlay-free directory, so `local.conf` resolves
   to nothing. _A developer's personal overlay must not change test outcomes._
6. **Environments are isolated by disjoint identity.** Each environment's SSM
   prefix (`/unicoach/<env>`), Terraform state key, on-disk state dir, IAM
   scope, and AWS resource names are disjoint. _Shared identity lets one
   environment read or clobber another's config, state, or resources._
7. **A silently-wrong-in-prod toggle is a required HOCON substitution.** A
   config value whose committed dev default would run silently-wrong in a
   deployed environment — weakening security (an auth bypass, an unsecured
   cookie) or running a wrong-but-non-erroring mode (email/chat routed to `log`)
   — MUST be a **required** HOCON substitution (`${VAR}`, no default line) and
   be set explicitly in every environment's dotenv (`.env.dev`, `.env.<env>`).
   _Silence is the hazard — a required substitution turns a forgotten override
   from a silent wrong-mode into a boot failure._
8. **`/etc/unicoach/env` is the complete on-host environment.** On a cloud host,
   `/etc/unicoach/env` is the **complete** materialized environment; both
   systemd (`EnvironmentFile`) and `bin/common` MUST read exactly that one file,
   and no other on-host source may contribute app config. _A partial
   materialization or a second on-host config source lets the two consumers
   diverge, so a service and an ops tool would run different config._
9. **Config is validated at startup, but only without IO.** Every config value
   whose validity is checkable without IO — parsing, range/enum checks, a regex,
   `ZoneId.of` and the like — MUST be validated as config is parsed at startup,
   so a malformed value crashes the process before it binds. Validation that
   would require IO (a DB query, an RPC, a reachability probe) MUST NOT run at
   startup; such a value is trusted at parse time and surfaces at first use if
   wrong. _Fail fast on what's cheap to check; never turn startup into a
   dependency probe._
