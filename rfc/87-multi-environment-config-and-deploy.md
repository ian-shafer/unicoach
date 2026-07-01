# RFC 87: Multi-Environment Configuration and Deployment

## Executive Summary

This RFC has two aims. First, get the configuration model right end to end —
across the bash dotenv layer, HOCON, iOS, Terraform, and the JVM — so there is
one documented answer to "where does config X come from in environment Y," every
value is declared once, and secrets are unambiguous. Second, generalize the
prod-only deploy so additional cloud environments can be stood up from one
`.env.<env>` file, without disturbing live prod.

The config model rests on a placement rule keyed on each value's consumers (JVM
reads HOCON; Shell, iOS, and Terraform read the dotenv layer) and on a set of
invariants: committed, auto-read config holds development values only; secrets
are never committed; the JVM reads env vars only through HOCON; each value has
exactly one home; and the local overlay never reaches the test JVM. Concretely
it makes the committed `.conf` defaults development values (flipping the three
that are accidentally prod-flavored), layers the dotenv files (`.env` is the
base; `.env.test`/`.env.fuzz`/`.env.<env>` are deltas), removes the lone direct
env read (`System.getenv("XDG_CONFIG_HOME")`), and pushes per-env overrides
through SSM (adding the missing `GOOGLE_AUTH_PROVIDER` and `EMAIL_PROVIDER`).

For multi-env, it parameterizes Terraform on `environment` and a decoupled
`hosted_zone_name`, deriving a `name_prefix`, SSM prefix, IAM scope, state key,
and on-disk state dir per environment; `bin/infra-*`/`bin/deploy` select and
validate the environment. prod holds no data, so it is recreated once under the
uniform `unicoach-<env>` naming. A second cloud env is enabled and documented
but not stood up; the iOS app stays local + prod only.

## Detailed Design

### 1. Audited current state

The configuration surface spans three planes that meet at the process
environment.

**Plane A — packaged HOCON defaults (`*/src/main/resources/*.conf`).** One
`.conf` per module (the
`common/src/main/kotlin/ed/unicoach/common/config/SPEC.md` rule: exactly one
in-tree `.conf` per module; no environment-specific `.conf` files; variation
only through `${?VAR}` substitution and the out-of-tree overlay). Each
host-varying key exposes an `${?VAR}` hook. The complete env-var → key map:

| `.conf` file                         | env var → key (default)                                                                                                                                                                                                                                                                                                                                                                                  |
| ------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `rest-server/.../rest-server.conf`   | `SERVER_PORT`→`server.port` (8080); `SERVER_HOST`→`server.host` ("0.0.0.0"); `SERVER_MAX_REQUEST_SIZE`→`server.requestSize.maxSize` ("8 KiB"); `SESSION_COOKIE_NAME`→`session.cookieName`; `APP_DOMAIN`→`session.cookieDomain` ("localhost"); `SESSION_COOKIE_SECURE`→`session.cookieSecure` (false); `SESSION_EXPIRATION`→`session.expiration` ("7d"); `UNICOACH_CLIENT_KEYS`→`clientKeyGate.keys` ("") |
| `admin-server/.../admin-server.conf` | `ADMIN_SERVER_HOST`, `ADMIN_SERVER_PORT` (8081), `ADMIN_COOKIE_DOMAIN`, `ADMIN_COOKIE_SECURE`, `ADMIN_DISPLAY_*` glyphs/timezone                                                                                                                                                                                                                                                                         |
| `db/.../db.conf`                     | `DATABASE_HOST`→`database.host` ("localhost"); `DATABASE_USER`; `DATABASE_PASSWORD`; `DATABASE_MAXIMUM_POOL_SIZE` (10); required (non-`?`) `${POSTGRES_PORT}`, `${POSTGRES_DB}` injected into `database.jdbcUrl`                                                                                                                                                                                         |
| `email/.../email.conf`               | `EMAIL_DEFAULT_FROM` ("noreply@uni.coach"); `EMAIL_PROVIDER` ("log"); `EMAIL_SES_REGION`; `EMAIL_SES_ACCESS_KEY_ID`; `EMAIL_SES_SECRET_ACCESS_KEY`                                                                                                                                                                                                                                                       |
| `chat/.../chat.conf`                 | `CHAT_PROVIDER` ("log"); `CHAT_ANTHROPIC_API_KEY`; `CHAT_ANTHROPIC_BASE_URL`; timeouts                                                                                                                                                                                                                                                                                                                   |
| `service/.../service.conf`           | `COACHING_*`; `EMAIL_VERIFICATION_TOKEN_TTL`, `EMAIL_VERIFICATION_VERIFY_URL_BASE` ("https://uni.coach/verify-email"); `GOOGLE_AUTH_PROVIDER` ("google"); `GOOGLE_CLIENT_IDS` ([]); `GOOGLE_JWKS_URI`; `EXTRACTION_*`                                                                                                                                                                                    |
| `public-web/.../public-web.conf`     | `PUBLIC_WEB_HOST`, `PUBLIC_WEB_PORT` (8082), `PUBLIC_WEB_OPEN_IN_APP_URL`                                                                                                                                                                                                                                                                                                                                |
| `net/.../net.conf`                   | none                                                                                                                                                                                                                                                                                                                                                                                                     |

Three committed defaults are **prod-flavored, not dev**:
`email.defaultFrom =
"noreply@uni.coach"`,
`emailVerification.verifyUrlBase =
"https://uni.coach/verify-email"`, and
`auth.google.provider = "google"` (with empty `clientIds`, which cannot work on
a fresh checkout). Two keys carry a HOCON default that **duplicates** a value
already declared in `.env`: `session.cookieDomain = "localhost"` (also `.env`
`APP_DOMAIN=localhost`) and `server.port = 8080` (also `.env` `SERVER_PORT`).

**Plane B — the AppConfig engine (`common/.../config/AppConfig.kt`).**
Precedence, highest first: JVM system properties → `<base>/unicoach/local.conf`
overlay → rightmost classpath `.conf` → leftmost `.conf`. `<base>` =
`unicoach.config.dir` system property → `XDG_CONFIG_HOME` env var → `~/.config`.
Config _values_ enter only through Typesafe's `${?VAR}` resolution inside
`ConfigFactory.load`; the only direct reads in the codebase are three in
`AppConfig` locating the overlay file:
`System.getProperty("unicoach.config.dir")`, `System.getProperty("user.home")`,
and `System.getenv("XDG_CONFIG_HOME")` — the last being the sole direct env
read. The root `build.gradle.kts` pins `unicoach.config.dir` for every `Test`
task to an overlay-free build directory, keeping the dev overlay out of the test
JVM.

**Plane C — shell dotenv files.** `bin/common` sources `${ENV_FILE:-.env}` with
`set -a`. Files: `.env` (local dev, committed, `APP_DOMAIN=localhost`),
`.env.template` (schema reference, not sourced, missing `SERVER_PORT`),
`.env.test`/`.env.fuzz` (test-DB harnesses; each _re-states_ the full Postgres
block **and** the app keys `DATABASE_USER`/`DATABASE_PASSWORD`/
`DATABASE_MAXIMUM_POOL_SIZE`/`SERVER_PORT`, plus `GOOGLE_AUTH_PROVIDER=stub`,
which `.env` omits), `.env.prod` (committed single source of prod non-secrets:
`APP_DOMAIN=uni.coach`, `GOOGLE_CLIENT_IDS`), `ios-app/env/*.env` (iOS build
targets). There is no `.env.local`.

**Deploy chain.** `.env.prod` → `bin/infra-plan`/`apply` export
`TF_VAR_app_domain`, `TF_VAR_google_client_ids` → `infra/variables.tf` +
`locals.tf` (`hosted_zone_name = var.app_domain`,
`api_domain =
"api.${var.app_domain}"`) → `infra/ssm.tf` writes
`/unicoach/prod/*` → `render-env.sh` flattens the prefix to `/etc/unicoach/env`
→ systemd `EnvironmentFile` → JVM `${?VAR}`. The host learns its prefix/region
from `/etc/unicoach/deploy.env`, written by `cloud-init.yaml` from `ec2.tf`'s
`templatefile` inputs `ssm_prefix = local.ssm_prefix`, `region = var.region`.
`bin/deploy` reads `artifacts_bucket`/`instance_id` from `tofu output`.

**iOS chain (unchanged here).** `bin/build-ios`/`release-ios` source a target
`.env` + (`.env`|`.env.prod`), derive `UNICOACH_BACKEND_URL`
(`https://api.$APP_DOMAIN` for deploy, `http://$APP_DOMAIN:$SERVER_PORT` for
local), pass it as an xcodebuild setting baked into `Info.plist`, read at
runtime by `BackendURL.swift`.

**Prod gaps the model exposes.** `ssm.tf` overrides `EMAIL_DEFAULT_FROM`,
`EMAIL_VERIFICATION_VERIFY_URL_BASE`, `CHAT_PROVIDER`, `GOOGLE_CLIENT_IDS`, but
**not** `GOOGLE_AUTH_PROVIDER` or `EMAIL_PROVIDER` — so prod relies on the
(currently prod-flavored) `.conf` defaults for those, and `email.provider`
defaults to the `log` stub, meaning **prod sends no real email**.

**Prod-only hardcodes blocking a second env.** `backend.tf`
`key =
"unicoach/prod/terraform.tfstate"`; `ssm.tf`
`ssm_prefix = "/unicoach/prod"`; `iam.tf` ARNs `…/unicoach/prod[/*]`; every AWS
resource named the literal `unicoach` (ALB, RDS, IAM, VPC/subnets/SGs, S3, EC2
tag); `locals.tf` `hosted_zone_name = var.app_domain` (assumes the base domain
is its own zone); and the single `infra/.terraform` working dir.

### 2. The config model

**Planes and the hand-off.** The **process environment is the universal hand-off
point.** Everything upstream exists to populate it (or, for iOS, to bake into
`Info.plist`):

- **Local:** `bin/*` source `.env` → exported env → JVM `${?VAR}`; the
  `local.conf` overlay sits highest.
- **Cloud:** `.env.<env>` → `TF_VAR_*` → `ssm.tf` writes `/unicoach/<env>/*` →
  `render-env.sh` → `/etc/unicoach/env` → systemd → JVM `${?VAR}`. No `.env`, no
  overlay on the host.

The roles disambiguate the layers: `.conf` is the _schema and dev defaults_;
`.env*` is the _per-env values_ for dotenv consumers; `/unicoach/<env>` SSM is
the _cloud host's runtime env_; `local.conf` is the _dev machine's
secrets/overrides_.

**Read-channels** (which consumer reads which layer):

| Consumer  | Reads config from                                                      |
| --------- | ---------------------------------------------------------------------- |
| JVM       | HOCON (`.conf` default → process-env `${?VAR}` → `local.conf` overlay) |
| Shell     | `.env*` (sourced)                                                      |
| iOS       | `.env*` (sourced at build → baked into `Info.plist`)                   |
| Terraform | `TF_VAR_*`, exported by `bin/infra-*` from `.env.<env>`                |

Only the JVM reads HOCON; Shell, iOS, and Terraform all read the dotenv layer.

**Placement rule** (a value is defined once, in the lowest layer all its
consumers read):

| Consumers                    | Home                                  | HOCON treatment                               |
| ---------------------------- | ------------------------------------- | --------------------------------------------- |
| JVM only                     | HOCON `.conf`                         | `key = devDefault` then `key = ${?VAR}`       |
| Shell / iOS / Terraform only | `.env` (dev), `.env.<env>` (cloud)    | absent                                        |
| JVM + any dotenv consumer    | `.env` / `.env.<env>` (single source) | `key = ${VAR}` — **required, no dev default** |

The third row is the `POSTGRES_PORT` case generalized: the value cannot be
duplicated into HOCON, so HOCON pulls it from the env with a _required_ `${VAR}`
and the one dev value lives in `.env`. Applying the rule: `APP_DOMAIN` (JVM +
Shell + iOS + Terraform) and `SERVER_PORT` (JVM + iOS) move fully to the dotenv
layer — `session.cookieDomain = ${APP_DOMAIN}` and
`server.port = ${SERVER_PORT}` lose their HOCON dev defaults.
(`admin.server.port`/`publicWeb.server.port` are JVM-only and keep theirs.) Some
JVM-only values are overridden in prod by a _Terraform-computed_ value rather
than an authored `.env.<env>` one (`database.host`/`PGHOST` = RDS address,
`PGPASSWORD`); these still follow row 1 — only the prod-side authorship differs,
covered by the secret inventory (§6).

**Committed config is development.** Following the rule and the dev-default
invariant, the three prod-flavored defaults flip to dev values, and prod
overrides them through SSM. The template is the already-correct
`chat.provider = "log"` (dev) → `CHAT_PROVIDER = "anthropic"` (SSM, prod):

| `.conf` key                       | new dev default                      | prod override (SSM)                             |
| --------------------------------- | ------------------------------------ | ----------------------------------------------- |
| `email.defaultFrom`               | `noreply@localhost`                  | `EMAIL_DEFAULT_FROM` (existing)                 |
| `emailVerification.verifyUrlBase` | `http://localhost:8080/verify-email` | `EMAIL_VERIFICATION_VERIFY_URL_BASE` (existing) |
| `auth.google.provider`            | `stub`                               | **`GOOGLE_AUTH_PROVIDER = "google"` (new)**     |
| `email.provider`                  | `log` (already dev)                  | **`EMAIL_PROVIDER = "ses"` (new)**              |

`GOOGLE_AUTH_PROVIDER` is the sharp one: flipping the default to `stub` without
also setting it in `ssm.tf` would silently drop prod to stub auth (an auth
bypass), so the two changes are paired. `EMAIL_PROVIDER = "ses"` additionally
requires `ses:SendEmail` on the instance IAM role (it has only SSM/KMS/S3 today)
and a **verified SES sender identity** — the latter an out-of-band prerequisite,
like the Route53 zone.

**The env → JVM boundary.** The lone direct env read,
`System.getenv("XDG_CONFIG_HOME")`, is removed; the overlay base resolves from
`unicoach.config.dir` (system property) → `user.home`/.config. A developer who
relocates their config dir passes `-Dunicoach.config.dir="$XDG_CONFIG_HOME"`
(bash reads the env var; the JVM only sees the system property). This makes
`unicoach.config.dir` the **single lever** for the overlay base, which the test
pin already sets to an overlay-free dir — so test hermeticity is unchanged and,
with XDG gone, no env var can influence it.

### 3. Invariants

These are the durable guarantees the model rests on. They are human-gated; the
architect owns their final wording and placement in `INVARIANTS.md` (config
invariants under `common/.../config`, the isolation invariant under `infra/`).

1. **Committed runtime config is development-only.** Committed, automatically
   read runtime config — the `.conf` defaults and the `.env` sourced at runtime
   — MUST hold working development values. No non-dev value may be a committed
   default; non-dev environments override solely through the process environment
   (`${?VAR}`), materialized from SSM on cloud hosts. _Why:_ a fresh checkout
   must run with zero setup and zero AWS, and prod-flavored defaults silently
   leak into dev.
2. **Secrets are never committed.** A secret MUST NEVER appear in a committed
   file; secrets live only in `/unicoach/<env>` SSM SecureStrings (cloud) or
   `~/.config/unicoach/local.conf` (local). _Why:_ a pushed secret is
   effectively unrevocable.
3. **The JVM reads environment variables only through HOCON.** Application code
   MUST NEVER call `System.getenv`. Config enters the JVM exclusively via HOCON
   `${?VAR}` substitution, JVM system properties (`-D`), or `main()` arguments.
   _Why:_ one read-boundary makes every value's source uniform and the bash↔SSM
   hand-off invisible to the app.
4. **Each config value has exactly one home, chosen by its consumers.** A value
   MUST be defined once, in the lowest layer all its consumers read (per the
   placement rule). A value MUST NOT carry both a HOCON default and a dotenv
   definition. _Why:_ two homes drift; one home answers "where does config X
   come from."
5. **The local overlay never reaches the test JVM.** Every test JVM MUST pin
   `unicoach.config.dir` to an overlay-free directory, so
   `~/.config/unicoach/local.conf` resolves to nothing. _Why:_ a developer's
   personal overlay must not change test outcomes.
6. **Environments are isolated by disjoint identity** (infra). Each
   environment's SSM prefix (`/unicoach/<env>`), Terraform state key, on-disk
   state dir, IAM scope, and AWS resource names MUST be disjoint across
   environments. _Why:_ shared identity lets one environment read or clobber
   another's config, state, or resources.

Supporting precedence (assumed by 1–5, not itself an invariant): system
properties > `local.conf` overlay > process env (`${?VAR}`) > `.conf` defaults.

### 4. Layered dotenv files and environment selection

`.env` is the **base** local config; `.env.test`, `.env.fuzz`, and the cloud
`.env.<env>` files are **deltas** sourced after it. This is already how the
deploy scripts work (`bin/infra-plan` sources `.env` via `bin/common`, then
layers `.env.prod`); the change makes it uniform. `bin/common` sources `.env`
unconditionally, then sources `$ENV_FILE` if set and not `.env`. Existing
callers that set `ENV_FILE=.env.test` keep working and gain the layering; the
delta files slim to only the keys whose values differ from `.env`. The exact
retained delta per file (every other key inherits from `.env`):

- `.env.test` retains only the keys whose values differ from `.env`: `PORT`
  (`${PORT:-8081}`, harness-overridden), `POSTGRES_DB`
  (`unicoach-test-$(basename "$PROJECT_ROOT")`), `DATABASE_USER=postgres` (vs
  `.env`'s `unicoach`), `DATABASE_MAXIMUM_POOL_SIZE=2`, `SERVER_PORT=$PORT`
  (re-derived after `PORT`), and `GOOGLE_AUTH_PROVIDER=stub` (absent from `.env`
  — load-bearing: it selects the offline Google token verifier so `startServer`
  boots without JWKS/clientIds).
- `.env.fuzz` retains the same set with `PORT=8082` and
  `POSTGRES_DB=unicoach-fuzz-…`.

The shared values live once in `.env`, so the now-required
`${APP_DOMAIN}`/`${SERVER_PORT}` resolve in the test JVM by inheritance. The
keys dropped from both `.env.test` and `.env.fuzz` (their values are identical
to `.env`, so a line-by-line implementor must delete rather than keep them) are
the Postgres-cluster keys `POSTGRES_HOST_AUTH_METHOD`, `PGHOST`,
`POSTGRES_DATA_DIR`, `POSTGRES_ADMIN_DB`, `POSTGRES_PORT`, and `POSTGRES_USER`
(both `postgres`), plus `DATABASE_PASSWORD` (both `password`) — and
`APP_DOMAIN=localhost`, which neither delta restated but which the now-required
`${APP_DOMAIN}` needs and `.env` supplies. No `.env.local-base`, no
`.env.local`.

A dependency-free helper in `bin/functions` (the lower layer all scripts share)
centralizes cloud-env selection and validation:

- `unicoach_env_file <env>` — echoes the env-file path (`local`→`.env`, else
  `.env.<env>`); `fatal -s "$EXIT_MISSING_REQUIRED_ARG"` on empty; `fatal` if
  the file is absent.
- `require_env_vars VAR…` — `fatal -s "$EXIT_INVALID_ARG_VALUE"` naming the
  first unset/empty variable.

`bin/infra-plan <env>` / `bin/infra-apply <env>` / `bin/deploy <env>` require an
explicit env (no prod default), source/validate via the helper, export the
`TF_VAR_*` set, set `TF_DATA_DIR="infra/.terraform-<env>"`, and
`tofu init
-backend-config="key=unicoach/<env>/terraform.tfstate"`. `.env.prod`
gains `ENVIRONMENT=prod`; a future cloud env is added by authoring `.env.<env>`
only. `.env.template` documents the full schema (`SERVER_PORT`, `UNICOACH_ENV`,
`ENVIRONMENT`, `HOSTED_ZONE_NAME`, `REGION`). `ENVIRONMENT` is inert for the iOS
build path: `bin/build-ios`/`release-ios` source `.env.prod` only to derive
`UNICOACH_BACKEND_URL` from `APP_DOMAIN`/`SERVER_PORT` (and read
`GOOGLE_CLIENT_IDS`), so the added `ENVIRONMENT=prod` key is sourced-but-unread
and changes no iOS build.

`bin/infra-init` and `bin/infra-output` are committed bare wrappers over
`tofu -chdir=infra init`/`output` with no `TF_DATA_DIR` and no backend-config.
Under the per-env model they break: `infra-init` cannot supply the partial
backend's now-required `-backend-config="key=…"`, and `infra-output` reads the
default `.terraform` dir instead of the per-env `.terraform-<env>` state. Both
take an explicit `<env>` arg, source/validate via the helper, and set
`TF_DATA_DIR="infra/.terraform-<env>"` (with `infra-init` adding the
backend-config) so they target the selected env's state — identically to the
plan/apply/deploy scripts. `infra-output` is fixed for direct operator use:
`bin/deploy` does **not** call it — it reads `artifacts_bucket`/`instance_id`
via its own `tofu -chdir=infra output -raw` (`bin/deploy:89,99`), already
covered by `bin/deploy`'s own per-env `TF_DATA_DIR`/backend handling above.

### 5. Environment identity and isolation

The selector is `UNICOACH_ENV`. Canonical name and derivation: `UNICOACH_ENV` is
the single canonical environment selector, passed as the explicit invocation
argument to `bin/infra-*`/`bin/deploy`; the `.env.<env>` file's `ENVIRONMENT`
key (exported as `TF_VAR_environment`, consumed by Terraform's
`var.environment`) is derived from it and MUST equal it, and the TF
`environment` is `var.environment` — one selector, two downstream restatements
that mirror it. This `UNICOACH_ENV` selector is distinct from the pre-existing
`UNICOACH_ENV_DIR` (the iOS env-file directory override in
`bin/build-ios`/`install-ios`/`release-ios`/ `ios-scripts-tests`); the name
prefix collides but the two never interact — `UNICOACH_ENV_DIR` is an iOS build
path, `UNICOACH_ENV` a cloud-deploy selector. Every per-env value derives from
two tokens (`environment`, plus the base domain); there is no special case for
prod.

| `UNICOACH_ENV`                               | env file                | cloud? | `environment` | `name_prefix`    | SSM prefix        | state key                          | `TF_DATA_DIR`            | `app_domain`      | `hosted_zone_name` |
| -------------------------------------------- | ----------------------- | ------ | ------------- | ---------------- | ----------------- | ---------------------------------- | ------------------------ | ----------------- | ------------------ |
| `local`                                      | `.env`                  | no     | —             | —                | —                 | —                                  | —                        | localhost         | —                  |
| `test`/`fuzz`                                | `.env.test`/`.env.fuzz` | no     | —             | —                | —                 | —                                  | —                        | localhost         | —                  |
| `prod`                                       | `.env.prod`             | yes    | prod          | unicoach-prod    | /unicoach/prod    | unicoach/prod/terraform.tfstate    | infra/.terraform-prod    | uni.coach         | uni.coach          |
| `staging` _(illustrative; not created here)_ | `.env.staging`          | yes    | staging       | unicoach-staging | /unicoach/staging | unicoach/staging/terraform.tfstate | infra/.terraform-staging | staging.uni.coach | uni.coach          |

**Isolation model (decided): one AWS account.** All environments share the
single AWS account (confirmed: not a member of an AWS Organization), isolated by
per-env `name_prefix`, SSM prefix, IAM scope, state key, and on-disk state dir
(`TF_DATA_DIR`); no Terraform workspaces. A per-env state key + `TF_DATA_DIR`
selected by `UNICOACH_ENV` is more explicit than a workspace (implicit process
state, easy to mis-target) and makes two envs unable to share on-disk state.
Account-per-env would need AWS Organizations, cross-account state/artifact
access, and per-account billing/SSO — out of scope, and the per-env-key model
migrates to it later by pointing each env's backend/provider at its own account.

**Domain model (decided): prod is the apex, every other env is a subdomain of
it.** prod serves `uni.coach` (`app_domain = hosted_zone_name = uni.coach`, so
it omits `HOSTED_ZONE_NAME`); every non-prod env serves `<env>.uni.coach`
(`app_domain = staging.uni.coach`, `HOSTED_ZONE_NAME = uni.coach`). All envs
share the one `uni.coach` Route53 zone, which `infra/dns.tf` references
read-only via `data.aws_route53_zone` (no Terraform resource owns it — it was
created by out-of-band registration). A non-prod env looks up that zone and
writes its own `api.<env>.uni.coach` records into it; record names differ per
env, so there is no collision and no new registration. This is exactly the
`hosted_zone_name =
coalesce(var.hosted_zone_name, var.app_domain)` mechanism in
§6 — no code change, just the convention that non-prod sets `HOSTED_ZONE_NAME`
to the parent zone.

### 6. Terraform generalization

Two new inputs and one derived local generalize every prod-only hardcode.

```hcl
# infra/variables.tf (additions)
variable "environment" {            # no default — fail fast if unset
  type        = string
  description = "Deployment environment id; drives SSM prefix, state key, IAM scope, name_prefix."
}
variable "hosted_zone_name" {       # default null — apex envs omit it
  type        = string
  default     = null
  description = "Route53 zone containing app_domain. Defaults to app_domain (apex); set to the parent zone for subdomain-per-env."
}

# infra/locals.tf (replaces the current block)
locals {
  name_prefix      = "unicoach-${var.environment}"
  ssm_prefix       = "/unicoach/${var.environment}"
  hosted_zone_name = coalesce(var.hosted_zone_name, var.app_domain)
  api_domain       = "api.${var.app_domain}"
}
```

`locals.tf` is the sole owner of `ssm_prefix`: `ssm.tf` currently defines
`locals { ssm_prefix = "/unicoach/prod" }` (its own `locals` block) and already
references `local.ssm_prefix`. That definition MOVES to `locals.tf`, and
`ssm.tf`'s `locals` block — the `ssm_prefix` line plus the surrounding
`locals { … }` wrapper — is REMOVED; leaving it would declare `ssm_prefix` twice
and fail `tofu validate`. `ssm.tf`'s other `locals` members
(`ssm_string_params`, `ssm_out_of_band_secrets`) stay, so the move drops only
the `ssm_prefix` assignment from that block. `iam.tf` is a different change: its
two resource ARNs are the **literal** strings `…:parameter/unicoach/prod` and
`…/unicoach/prod/*`, which become `…:parameter${local.ssm_prefix}` and
`${local.ssm_prefix}/*` (a real substitution, not a no-op).

**Resource renames.** Every literal `unicoach` resource name/tag in `alb.tf`,
`rds.tf`, `network.tf`, `security_groups.tf`, `ec2.tf`, `s3.tf`, `iam.tf`
(including the IAM role/policy/profile name `unicoach-instance`) becomes
`local.name_prefix`. **Exempt from the rename** are PostgreSQL identifiers,
which disallow hyphens: `rds.tf`'s `db_name = "unicoach"` and master
`username = "unicoach_admin"` stay literal — renaming `db_name` to
`unicoach-prod` would be an invalid Postgres identifier and would desync from
the deploy-invariant `POSTGRES_DB = "unicoach"` literal `ssm.tf` keeps. `dns.tf`
already reads `local.hosted_zone_name`/`local.api_domain`; `ec2.tf` already
passes `local.ssm_prefix`/`var.region` into cloud-init. `backend.tf` drops its
`key` line (partial backend), so `tofu init` requires `-backend-config="key=…"`
(no silent prod default).

`ssm.tf` also gains the two missing prod overrides —
`GOOGLE_AUTH_PROVIDER =
"google"` and `EMAIL_PROVIDER = "ses"` — and `iam.tf`
gains a `ses:SendEmail` statement on the instance role; SES sender-identity
verification is an out-of-band prerequisite. `ssm.tf` keeps
`POSTGRES_DB =
"unicoach"`, the RDS master `POSTGRES_USER` (from
`aws_db_instance.main.username`), and
`SERVER_HOST`/`SERVER_PORT`/`SESSION_COOKIE_SECURE`/`CHAT_PROVIDER` as
deploy-invariant literals — the kept-literal `POSTGRES_DB`/username match the
exempt `rds.tf` identifiers above. **Assumption:** each environment has its own
isolated RDS, so a shared `POSTGRES_DB`/master username never collide.

**prod migration (one-time recreate).** prod's resources are named `unicoach`
today; the uniform model names them `unicoach-prod`. Because prod holds no data
and downtime is acceptable, prod is recreated rather than carrying a name shim.
The SSM prefix and state key are unchanged by `${var.environment}` = `prod`, so
operator-seeded SecureStrings survive — only AWS resources are rebuilt. The
operator runs `bin/infra-apply prod` (recreates ALB/RDS/EC2/SGs/IAM/VPC), then
`bin/deploy prod` (re-creates the app DB role, migrates the empty DB); afterward
`bin/infra-plan prod` reports no changes.

### 7. Secrets inventory and seeding

| Secret                         | Cloud home                                            | Set by                                           | Local home                           |
| ------------------------------ | ----------------------------------------------------- | ------------------------------------------------ | ------------------------------------ |
| `PGPASSWORD` (RDS master)      | `/unicoach/<env>/PGPASSWORD` SecureString             | Terraform `random_password`                      | n/a (local `trust`)                  |
| `DATABASE_PASSWORD` (app role) | `/unicoach/<env>/DATABASE_PASSWORD` SecureString      | operator-seeded (placeholder + `ignore_changes`) | `.env` dev value / `local.conf`      |
| `CHAT_ANTHROPIC_API_KEY`       | `/unicoach/<env>/CHAT_ANTHROPIC_API_KEY` SecureString | operator-seeded                                  | `local.conf` (if provider=anthropic) |
| `UNICOACH_CLIENT_KEYS`         | `/unicoach/<env>/UNICOACH_CLIENT_KEYS` SecureString   | operator-seeded (gate off by default)            | `local.conf`                         |
| `EMAIL_SES_*`                  | `/unicoach/<env>/EMAIL_SES_*` (only if static creds)  | operator-seeded                                  | `local.conf`                         |

Non-secrets (`APP_DOMAIN`, `HOSTED_ZONE_NAME`, `GOOGLE_CLIENT_IDS`,
`ENVIRONMENT`, `REGION`) live committed in `.env.<env>`. `render-env.sh` gains a
guard that fails the host render if any fetched value still equals
`PLACEHOLDER_SEED_OUT_OF_BAND`.

`render-env.sh:18` currently falls back to
`SSM_PREFIX="${SSM_PREFIX:-/unicoach/prod}"` when `/etc/unicoach/deploy.env`
omits `SSM_PREFIX`. That fallback is removed: under per-env isolation a non-prod
host that fails to source its `SSM_PREFIX` would fetch the **prod** prefix and
render prod secrets onto a non-prod box — a cross-env leak that violates the
env-isolation invariant (§3, invariant 6). `render-env.sh` instead requires
`SSM_PREFIX` to be set (sourced from `deploy.env`) and is fatal if absent
(`require_env_vars`-equivalent check), with no environment baked into the
default. The matching `AWS_REGION` fallback stays (region is not env-identity
and a wrong region fails closed — the SSM fetch errors rather than leaking).

### 8. Per-environment manifest, validation, add-an-environment

| Variable                                      | Where                                                    | Validated by                            | Failure               |
| --------------------------------------------- | -------------------------------------------------------- | --------------------------------------- | --------------------- |
| `UNICOACH_ENV`                                | invocation                                               | `unicoach_env_file`                     | `fatal` missing-arg   |
| `ENVIRONMENT`                                 | `.env.<env>` → `TF_VAR_environment`                      | `require_env_vars`; TF var (no default) | `fatal`; `tofu` error |
| `APP_DOMAIN`                                  | `.env.<env>` → `TF_VAR_app_domain`                       | `require_env_vars`; TF var (no default) | `fatal`; `tofu` error |
| `HOSTED_ZONE_NAME`                            | `.env.<env>` (omit for apex) → `TF_VAR_hosted_zone_name` | `coalesce` default = `app_domain`       | none                  |
| `GOOGLE_CLIENT_IDS`                           | `.env.<env>` → `TF_VAR_google_client_ids`                | `require_env_vars`; TF var (no default) | `fatal`; `tofu` error |
| `REGION`                                      | `.env.<env>` → `TF_VAR_region`                           | TF var default `us-east-1`              | none                  |
| AWS account / session                         | ambient                                                  | `tofu`/`aws`                            | provider auth error   |
| Zone `HOSTED_ZONE_NAME` exists                | AWS (out-of-band)                                        | `data.aws_route53_zone`                 | `tofu` plan error     |
| SES sender identity verified                  | AWS (out-of-band)                                        | runtime SES send                        | email send error      |
| `DATABASE_PASSWORD`, `CHAT_ANTHROPIC_API_KEY` | seeded into `/unicoach/<env>/*`                          | `render-env.sh` guard                   | host render `fatal`   |

**Add-an-environment** (documented in `README.md`): (1) author `.env.<env>`; (2)
`bin/infra-apply <env>`; (3) seed operator secrets
(`aws ssm put-parameter …
--type SecureString`); (4) `bin/db-create-role`; (5)
`bin/deploy <env>`.

### 9. Local out-of-the-box

A fresh checkout runs locally with zero AWS via the standard dev workflow
(`bin/build-* && bin/*-up`, which source `.env`): localhost Postgres (`trust`,
dev password), `.conf` dev defaults (`chat`/`email` = `log`, `auth.google` =
`stub`, dev URLs). Live providers/secrets are opted in via
`~/.config/unicoach/local.conf` only.

### 10. Error handling, edge cases, dependencies

- **Wrong/missing env:** `unicoach_env_file` fails fast.
- **Accidental prod:** explicit env arg + partial backend; no implicit default.
- **State isolation:** disjoint state keys _and_ `TF_DATA_DIR` per env.
- **Required `${VAR}`:** a missing cross-cutting key fails the JVM at startup;
  tests inherit them from `.env` via the layered sourcing.
- **Unseeded secret:** `render-env.sh` placeholder guard fails the host render.
- **prod recreate:** one-time; secrets survive (SSM prefix unchanged).
- **Dependencies:** OpenTofu, AWS CLI, an authenticated session, an existing
  Route53 zone, and (for email) a verified SES identity — all out-of-band. No
  new libraries; no iOS changes.

## Tests

There is no automated substitute for a real deploy: whether prod recreates
cleanly, the app serves traffic, the host renders a working `/etc/unicoach/env`,
and seeded secrets survive can only be confirmed by deploying. End-to-end
correctness is verified by the **operator deploy gate** (a human step the
implementation cannot self-certify). Automated coverage is two cheap, mandatory
checks; script wiring is smoke-checked via fast-fail paths. No `tofu`/`aws` stub
harness is built.

**Automated (mandatory):**

- `tofu -chdir=infra validate` with `environment`/`app_domain`/
  `google_client_ids` supplied — catches HCL/type errors.
- `nix develop -c bin/test -f` and `nix develop -c bin/test-fuzz` green after
  the config changes (confirm "N executed"; the per-worktree test DB resolves;
  no overlay/env bleed). Mandatory: the layered `.env`, the required `${VAR}`
  flips, and the dev-default flips all change config loading. Tests asserting
  the old prod-flavored defaults (`noreply@uni.coach`, the `https://uni.coach`
  verify URL) must be updated to the dev values. The `auth.google.provider` flip
  likewise breaks an existing test: `GoogleAuthConfigTest`'s
  `applies documented defaults from the packaged service conf`
  (`service/src/test/kotlin/ed/unicoach/auth/GoogleAuthConfigTest.kt`) parses
  the packaged `service.conf` with `setUseSystemEnvironment(false)` (so the
  `${?GOOGLE_AUTH_PROVIDER}` override resolves to nothing and the file's own
  default is observed) and currently asserts `provider == "google"`. As part of
  the default flip its expectation changes to `provider == "stub"`. With that
  updated expectation, this test is the regression guard ensuring the packaged
  default resolves to `stub`: a regressed `google` default fails the suite
  instead of passing silently.

**Manual fast-fail smoke (no AWS) — run each once:**

- `bin/infra-apply` (no arg) → non-zero "environment required", before any
  `tofu`.
- `bin/infra-plan bogus` (no `.env.bogus`) → non-zero naming the missing file.
- a `.env.<env>` missing `APP_DOMAIN` → non-zero from `require_env_vars`.
- a non-default `unicoach.config.dir` (`-Dunicoach.config.dir=/tmp/x`) with no
  `unicoach/local.conf` there → overlay is a no-op (proves XDG removal didn't
  regress overlay location).

**Operator deploy gate (requires AWS — the real test):**

1. `bin/infra-apply prod` — recreates prod under `unicoach-prod`.
2. `bin/deploy prod` — re-creates the role, migrates, ships the bundle.
3. A real request through `https://api.uni.coach` succeeds (health + one
   authenticated call); a verification email actually sends (proves
   `EMAIL_PROVIDER`/IAM/SES).
4. `bin/infra-plan prod` reports _No changes_.
5. _(Generalization proof, on demand)_ author a throwaway `.env.staging`
   (`staging.uni.coach`, `HOSTED_ZONE_NAME=uni.coach`);
   `bin/infra-apply staging` stands up a full env (`0 to destroy`); seed
   secrets; `bin/deploy staging`; reach `https://api.staging.uni.coach`; then
   `tofu`-destroy it.

## Implementation Plan

Config-layer steps land first so the suite stays green throughout; infra next;
docs; then the operator migration. Run dev-shell commands via `nix develop -c`.

1. **Layered dotenv.** Update `bin/common` to source `.env` then `$ENV_FILE` (if
   set and not `.env`); slim `.env.test`/`.env.fuzz` to deltas (re-derive
   `SERVER_PORT=$PORT`). _Verify:_ `bin/test -f` and `bin/test-fuzz` green
   (unchanged behavior, deduplicated).

2. **Env→JVM boundary.** Remove `System.getenv("XDG_CONFIG_HOME")` from
   `AppConfig.kt`; base = `unicoach.config.dir` → `user.home`/.config; update
   its KDoc. _Verify:_ `bin/test -f` green (AppConfigTest covers overlay
   location).

3. **Committed `.conf` defaults → dev.** `email.conf` `defaultFrom` →
   `noreply@localhost`; `service.conf` `verifyUrlBase` →
   `http://localhost:8080/verify-email`, `auth.google.provider` → `stub`;
   `rest-server.conf` drop the `cookieDomain` and `server.port` literal defaults
   → `${APP_DOMAIN}` / `${SERVER_PORT}` (required). _Verify:_ `bin/test -f`
   green (update tests asserting old defaults — including
   `GoogleAuthConfigTest`'s
   `applies documented defaults from the packaged service conf` in
   `service/src/test/kotlin/ed/unicoach/auth/GoogleAuthConfigTest.kt`, whose
   `provider == "google"` assertion flips to `provider == "stub"`).

4. **Env-selection helper.** Add `unicoach_env_file`, `require_env_vars` to
   `bin/functions`. _Verify (smoke):_ `unicoach_env_file local`→`.env`,
   `unicoach_env_file ''` non-zero, `require_env_vars` flags an empty var.

5. **Terraform generalize + prod overrides.** Add `environment`,
   `hosted_zone_name` to `variables.tf`; rewrite `locals.tf` to own
   `name_prefix` and `ssm_prefix` (the latter moved in from `ssm.tf`) plus the
   `hosted_zone_name` coalesce; DELETE the `locals { ssm_prefix }` assignment
   from `ssm.tf`; replace literal `unicoach` names with `local.name_prefix`
   across `alb/rds/network/security_groups/ec2/s3/iam` (incl. the
   `unicoach-instance` IAM role/policy/profile names), keeping `rds.tf`'s
   `db_name`/`username` literal; point `iam.tf`'s two literal
   `/unicoach/prod[/*]` ARNs at `local.ssm_prefix`; add
   `GOOGLE_AUTH_PROVIDER`/`EMAIL_PROVIDER` to `ssm.tf` and `ses:SendEmail` to
   `iam.tf`; add an `environment` output to `outputs.tf` (`app_url` already
   derives per-env, no change); drop the `key` from `backend.tf`. _Verify:_
   `tofu validate` (the moved `ssm_prefix` must not be declared twice).

6. **Deploy scripts select the env.** Update `bin/infra-plan`/`infra-apply`/
   `deploy`/`infra-init`/`infra-output` (env arg, helper, `TF_VAR_*` where
   exported, `TF_DATA_DIR`, backend-config on init); add `.terraform-*/` to
   `.gitignore`. _Verify (smoke):_ `bin/infra-apply` (no arg) and
   `bin/infra-plan bogus` exit non-zero before any `tofu`.

7. **Host render guard.** Add the `PLACEHOLDER_SEED_OUT_OF_BAND` guard to
   `render-env.sh`; remove its `SSM_PREFIX:-/unicoach/prod` fallback (require
   `SSM_PREFIX`, fatal if absent); fix its stale `prod` comment. _Verify:_ via
   the deploy gate.

8. **Env files.** Add `ENVIRONMENT=prod` to `.env.prod`; complete the schema in
   `.env.template` (`SERVER_PORT` + cloud-env keys). _Verify:_ `bin/test -f`.

9. **Documentation.** Add a "Configuration model & environments" section to
   `README.md` (mental model, placement rule, invariants pointer, per-env
   manifest, secret inventory, add-an-environment), **and revise the now-stale
   existing prose** the multi-env shift invalidates: the `## Deployment`
   section's "single-environment AWS topology" framing (the topology is now
   per-env, `unicoach-<env>`, selected by `UNICOACH_ENV`); the prod-specific
   `### 0. Domain gate` subsection (the apex-vs-subdomain `HOSTED_ZONE_NAME`
   convention generalizes it beyond `api.uni.coach`/`uni.coach`); and the
   `## Environment Variables` section's "`.env.test` mirrors `.env` with
   test-specific overrides" claim (`.env.test`/`.env.fuzz` are now slimmed
   deltas layered _after_ `.env`, not mirrors). _Verify:_
   `deno fmt --check README.md`.

10. **Full gate.** `nix develop -c bin/test check` green.

11. **prod migration (operator, requires AWS — after merge).**
    `bin/infra-apply
    prod`, `bin/deploy prod`, confirm `bin/infra-plan prod`
    → _No changes_ and a real request + email succeed.

## Files Modified

- `common/src/main/kotlin/ed/unicoach/common/config/AppConfig.kt` — drop the
  `XDG_CONFIG_HOME` read; overlay base = system property → `user.home`.
- `rest-server/src/main/resources/rest-server.conf` — `cookieDomain` →
  `${APP_DOMAIN}`, `server.port` → `${SERVER_PORT}` (drop literal defaults).
- `email/src/main/resources/email.conf` — dev `defaultFrom`.
- `service/src/main/resources/service.conf` — dev `verifyUrlBase`;
  `auth.google.provider = "stub"`.
- `service/src/test/kotlin/ed/unicoach/auth/GoogleAuthConfigTest.kt` — in
  `applies documented defaults from the packaged service conf`, the packaged-
  default expectation flips from `provider == "google"` to `provider == "stub"`
  (the regression guard for the dev default).
- `bin/common` — source `.env` base, then `$ENV_FILE` override.
- `bin/functions` — add `unicoach_env_file`, `require_env_vars`.
- `bin/infra-plan` — env arg, validate, `TF_VAR_*`, `TF_DATA_DIR`, backend init.
- `bin/infra-apply` — same as `infra-plan`.
- `bin/deploy` — env arg, `TF_DATA_DIR` + backend-config before reading outputs.
- `bin/infra-init` — env arg, helper validate, `TF_DATA_DIR`, pass the partial
  backend's `-backend-config="key=…"`.
- `bin/infra-output` — env arg, helper validate, `TF_DATA_DIR` so direct
  operator use reads the per-env state (`bin/deploy` does not use it; it calls
  `tofu output` directly under its own per-env handling).
- `.env.test` — slim to the §4 delta (keep `GOOGLE_AUTH_PROVIDER=stub`; sourced
  after `.env`).
- `.env.fuzz` — slim to the §4 delta (keep `GOOGLE_AUTH_PROVIDER=stub`).
- `.env.prod` — add `ENVIRONMENT=prod`.
- `.env.template` — full schema incl. `SERVER_PORT` and cloud-env keys.
- `infra/variables.tf` — add `environment`, `hosted_zone_name`.
- `infra/locals.tf` — add `name_prefix`, `ssm_prefix` (moved in from `ssm.tf`),
  and the `hosted_zone_name` coalesce.
- `infra/backend.tf` — remove `key` (partial backend).
- `infra/ssm.tf` — REMOVE the `locals { ssm_prefix = "/unicoach/prod" }`
  assignment (the def moves to `locals.tf`; `ssm.tf` keeps referencing
  `local.ssm_prefix` and keeps its other `locals` members); add
  `GOOGLE_AUTH_PROVIDER`, `EMAIL_PROVIDER`.
- `infra/iam.tf` — role/policy/profile name `unicoach-instance` → `name_prefix`;
  the two **literal** `/unicoach/prod[/*]` ARNs → `local.ssm_prefix`; add
  `ses:SendEmail`.
- `infra/rds.tf` — subnet-group/instance names + tags → `name_prefix`;
  `db_name = "unicoach"` and `username = "unicoach_admin"` stay **literal**
  (Postgres identifiers disallow hyphens; match the kept `POSTGRES_DB` SSM
  literal).
- `infra/alb.tf`, `infra/network.tf`, `infra/security_groups.tf`, `infra/ec2.tf`
  — names/tags → `name_prefix`.
- `infra/s3.tf` — artifacts bucket → `${name_prefix}-artifacts-<account_id>`.
- `infra/outputs.tf` — add an `environment` output; `app_url` already derives
  per-env via `local.api_domain` (no change).
- `infra/files/render-env.sh` — placeholder-secret guard; remove the
  `SSM_PREFIX:-/unicoach/prod` fallback (require `SSM_PREFIX`, fatal if absent,
  to prevent cross-env prod-secret leak); stale-`prod` comment.
- `.gitignore` — add `.terraform-*/`.
- `README.md` — add the configuration model & environments section, and revise
  the stale existing prose: the `## Deployment` "single-environment AWS
  topology" framing → per-env `unicoach-<env>` selected by `UNICOACH_ENV`; the
  prod-only `### 0. Domain gate` subsection → the generalized apex-vs-subdomain
  `HOSTED_ZONE_NAME` convention; and the `## Environment Variables` claim that
  "`.env.test` mirrors `.env`" → `.env.test`/`.env.fuzz` are slimmed deltas
  layered after `.env`.
