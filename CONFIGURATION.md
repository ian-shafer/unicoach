# Configuration

How configuration reaches each consumer, and the durable guarantees the model
rests on. Full design:
[`rfc/87-multi-environment-config-and-deploy.md`](rfc/87-multi-environment-config-and-deploy.md).

## Config sources

- **Shell environment** — `.env` (base) plus a `.env.<env>` delta (e.g.
  `.env.prod`), sourced by nearly every shell script and also read by the iOS
  build and Terraform. On cloud hosts the environment is materialized from SSM
  (`/unicoach/<env>`) into `/etc/unicoach/env` and handed to the JVM by systemd.
- **HOCON (`*.conf`)** — read only by the JVM; pulls shell environment values in
  via `${?VAR}`.
- **Local overrides and secrets** — `~/.config/unicoach/local.conf`, on a
  developer's machine only.

## Prime directive

A config value is set once and only once — in the shell environment files or in
HOCON, never both.

- Needed by the JVM **and** any other consumer → set it in the shell environment
  files.
- Needed **only** by the JVM → set it in a HOCON file.

## Precedence

Highest wins: JVM system properties (`-D`) > `~/.config/unicoach/local.conf`
overlay > process environment (`${?VAR}`, SSM-rendered on cloud) > committed
`.conf` defaults.

## Invariants

1. **Committed config is development-only.** Committed `.conf` defaults and the
   `.env` sourced at runtime hold working dev values; no non-dev value is a
   committed default. Non-dev environments override solely through the process
   environment (`${?VAR}`), materialized from SSM on cloud hosts. _A fresh
   checkout runs with zero setup and zero AWS._
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
