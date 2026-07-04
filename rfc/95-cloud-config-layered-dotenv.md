# RFC 95: Cloud Configuration as a Uniformly-Layered Dotenv

## Executive Summary

Cloud config today is a special case. Locally, `bin/common` layers a dotenv:
base `.env` then one delta (`.env.test`/`.env.<env>`). A deploy host does the
opposite — skips the base ("a deploy host has no `.env`") and sources only
`/etc/unicoach/env`, which `render-env` materializes from the `/unicoach/<env>`
SSM prefix. So prod's non-secret config has no single authored source: it splits
between an HCL `ssm_string_params` map in `infra/ssm.tf` and a partial
`.env.prod`.

This RFC makes every cloud host's config the same layered dotenv as local, with
one authored source. Non-secret prod config is authored as `base → .env.<env>`
(mirroring local), flattened once on the laptop into a `deploy-env` artifact,
and merged on the instance with an SSM fetch now holding **only** secrets and
Terraform-owned RDS identity. The result is one complete flat
`/etc/unicoach/env` both consumers — systemd `EnvironmentFile` and `bin/common`
— read identically. `ssm_string_params` shrinks from ~20 to four.

To keep a dev-base default from reaching prod, dev-dangerous values move out of
the base into a new `.env.dev`, which the deploy path never layers; a key
omitted from `.env.<env>` is then unset, not silently inherited, so the presence
check fatals on the laptop. Security/mode toggles whose committed default is
permissive (`GOOGLE_AUTH_PROVIDER=stub`, etc.) become **required** HOCON
substitutions, so a forgotten prod override fails the JVM at boot. The flatten
and its check live in a new `bin/gen-deployed-env <env>`. Deploy-control values
(`AWS_ACCOUNT_ID`, `REGION`, and the `AWS_REGION` derivation) move to
`.env.deploy.<env>`, decoupling `bin/deploy` from app config. `ENVIRONMENT` is
removed; the `<env>` argument is the sole identity.

## Detailed Design

### Config-file layering

Five roles, each a committed dotenv (or SSM), replacing the current
base/`.env.prod`/`ssm_string_params` split:

- **`.env`** — the base. Env-**neutral** non-secret app config only: values
  identical in dev and every cloud env (e.g. `POSTGRES_DB`, `POSTGRES_PORT`,
  `PUBLIC_WEB_PORT`, `ADMIN_WEB_PORT`, `DATABASE_MAXIMUM_POOL_SIZE`,
  `PORT`/`SERVER_PORT`). Holds **no** dev-dangerous value and **no**
  deploy-control value.
- **`.env.dev`** — local dev's env-specific and dev-dangerous values, split out
  of the base: `DATABASE_PASSWORD=password`, `POSTGRES_HOST_AUTH_METHOD=trust`,
  `POSTGRES_DATA_DIR`, `POSTGRES_ADMIN_DB`, `POSTGRES_USER`, `PGHOST=localhost`,
  `DATABASE_USER`, `APP_DOMAIN=localhost`, plus **new** dotenv keys for the
  three bind hosts (`SERVER_HOST`/`PUBLIC_WEB_HOST`/`ADMIN_WEB_HOST`) and the
  dev values of the required toggles (below). Loaded by default for ambient
  local scripts (see `bin/common`). The bind hosts are **new keys**, not
  relocations — they are absent from `.env` today; their local defaults live in
  the `.conf` files (`SERVER_HOST` defaults to `0.0.0.0` in `rest-server.conf`,
  while `PUBLIC_WEB_HOST`/`ADMIN_WEB_HOST` default to `127.0.0.1` in
  `public-web.conf`/`admin-web.conf`). Adding them as dotenv keys makes the bind
  host an explicit, per-env dotenv value (dev overrides `SERVER_HOST` to
  `127.0.0.1` for a loopback-only local server; all three become `0.0.0.0` in
  `.env.<env>`, so a cloud box binds all interfaces). The `.conf` defaults stay
  as the last-resort fallback when the key is unset.
- **`.env.<env>`** (e.g. `.env.prod`) — the target cloud env's non-secret app
  config: `APP_DOMAIN`, `GOOGLE_CLIENT_IDS`, the `0.0.0.0` bind hosts, the
  cookie and provider toggles, and the email values derived from `APP_DOMAIN`.
  Read only by `bin/gen-deployed-env` (to flatten) and `bin/infra-*` (to export
  `TF_VAR_*`), always layered as `base → .env.<env>` with **`.env.dev`
  excluded** (see the deploy/infra chain below). Contains no secret and no
  deploy-control value.
- **`.env.deploy.<env>`** (e.g. `.env.deploy.prod`) — deploy-control only:
  `AWS_ACCOUNT_ID`, `REGION`, and the `AWS_REGION` derivation that today lives
  in the base. Both `REGION` and the `AWS_REGION="${AWS_REGION:-$REGION}"` line
  move here together (out of the base), so the `--region` callers keep resolving
  it: `bin/deploy`/`bin/remote`/`bin/infra-*` source this file, which sets
  `REGION` (→ `TF_VAR_region`) and derives `AWS_REGION` from it in the same
  file, feeding every `aws … --region "$AWS_REGION"` call. Keeping the
  derivation adjacent to its `REGION` input in the same deploy-control file
  (rather than orphaning it in the base after `REGION` leaves) preserves the
  source-once relationship and the externally-exported-`AWS_REGION`-still-wins
  behavior. Sourced by `bin/deploy`/`bin/remote`/`bin/infra-*` to reach the
  right AWS account/region. Never shipped to the instance.
- **SSM `/unicoach/<env>`** — the two Terraform-owned classes only (secrets and
  RDS identity); see the Key ownership rule.

### Key ownership rule

SSM owns exactly two classes; every other key is committed to the dotenv roles
above.

1. **Secrets** — `DATABASE_PASSWORD`, `CHAT_ANTHROPIC_API_KEY`, `PGPASSWORD`
   (SecureStrings, unchanged).
2. **Terraform-owned RDS identity** — values whose canonical source is
   Terraform, not an author: `PGHOST`/`DATABASE_HOST`
   (`aws_db_instance.main.address`) and `POSTGRES_USER`
   (`aws_db_instance.main.username`) are RDS **resource attributes**;
   `DATABASE_USER` (`var.app_db_user`) is a Terraform **variable** that also
   drives `bin/db-create-role`, so its one home is that variable, not a second
   committed copy. Committing any of the four would duplicate a Terraform-owned
   fact and risk drift.

Everything currently in `ssm_string_params` other than these four is a static
non-secret constant; each moves to the committed dotenv role above, so
`ssm_string_params` shrinks from ~20 entries to the four RDS-identity keys and
the secrets list is unchanged. The two email values become dotenv interpolations
(`noreply@$APP_DOMAIN` / `https://app.$APP_DOMAIN/verify-email`) rather than
HCL-computed strings.

### `bin/gen-deployed-env <env>`

New script: a pure transformation `env → flat KEY=VALUE`, producing the
non-secret box config. It resolves the `<env>` positional to `.env.<env>` in a
pre-`common` `export ENV_FILE=` prelude (so `bin/common` loads exactly
`base → .env.<env>` in one pass, **not** the `base → .env.dev` default — see the
deploy/infra chain rule below), asserts every `DEPLOY_VAR_NAMES` entry is set
(`require_env_vars`), and prints each `DEPLOY_VAR_NAMES` key with its resolved
value to stdout. A missing key fatals here — on the laptop, at deploy time.

**Flatten-to-literal is load-bearing, and relies on source-time expansion.** The
`deploy-env` this script emits (and the `/etc/unicoach/env` it feeds) is a
**flat literal** file: each line is `KEY=<already-resolved-value>` with no
`$VAR` or `$(…)` surviving. This is required because one of its two on-host
consumers, systemd's `EnvironmentFile`, does **no** shell expansion — it reads
each line verbatim, so an unresolved `EMAIL_DEFAULT_FROM=noreply@$APP_DOMAIN`
would reach the JVM with a literal `$APP_DOMAIN` in it. The resolution therefore
happens at **source time** on the laptop: `bin/common` sources the dotenv layers
under `set -a`, so the shell expands every `$VAR`/`$(…)` (e.g. the email
interpolations `noreply@$APP_DOMAIN` / `https://app.$APP_DOMAIN/verify-email`)
before `gen-deployed-env` reads each `DEPLOY_VAR_NAMES` value from the
environment and prints its literal. What lands in `deploy-env` is thus the fully
expanded string, safe for systemd's expansion-free `EnvironmentFile`. (The other
consumer, `bin/common` via `ENV_FILE=/etc/unicoach/env`, would re-expand `$` —
but there is nothing left to expand once the values are literal, so both
consumers see identical values.)

**Deploy/infra chain — `.env.dev` is never layered.** Every script on the
build-and-ship path (`bin/gen-deployed-env`, `bin/deploy`, `bin/remote`,
`bin/infra-plan`, `bin/infra-apply`) sets an explicit single
`ENV_FILE=.env.<env>` so `bin/common` layers exactly `base → .env.<env>`. It
MUST NOT fall through to `bin/common`'s ambient `base → .env.dev` default and
MUST NOT layer `.env.dev` under `.env.<env>`. Rationale: `.env.dev` holds
local-dev values (dev password, `trust`, `localhost`, the dev toggle values); if
it layered in, a key **omitted** from `.env.<env>` would inherit its dev value,
`require_env_vars` would see it set, and the dev value would ship to prod — the
exact leak this RFC exists to prevent. With `.env.dev` excluded, an omitted
`.env.<env>` key is simply unset, so `require_env_vars` fatals on the laptop
before build (and `require_cloud_domain`, now repurposed, additionally rejects a
`localhost`/`env=local` target — see the `bin/remote` section). The empty base
(below) carries nothing, so `base → .env.<env>` and a hypothetical no-base
`.env.<env>` are identical; the base's only job on this path is to keep the
layering shape uniform.

`DEPLOY_VAR_NAMES` is the enumerated set of non-secret box keys, defined in this
script: `POSTGRES_PORT`, `POSTGRES_DB`, `DATABASE_MAXIMUM_POOL_SIZE`,
`SERVER_HOST`, `SERVER_PORT`, `PUBLIC_WEB_HOST`, `ADMIN_WEB_HOST`,
`PUBLIC_WEB_PORT`, `ADMIN_WEB_PORT`, `SESSION_COOKIE_SECURE`,
`ADMIN_COOKIE_SECURE`, `APP_DOMAIN`, `EMAIL_DEFAULT_FROM`,
`EMAIL_VERIFICATION_VERIFY_URL_BASE`, `EMAIL_PROVIDER`, `CHAT_PROVIDER`,
`GOOGLE_AUTH_PROVIDER`, `GOOGLE_CLIENT_IDS`. The RDS-identity and secret keys
are **not** here — they are supplied by SSM on the instance.

### `bin/common`: ordered-list layering, `.env.dev` default, empty base on host

Three changes:

- **`ENV_FILES` — a `PATH`-like `:`-separated string.** In addition to
  `ENV_FILE` (a single delta), accept `ENV_FILES`: a `PATH`-like `:`-separated
  string of delta files, layered after the base in order (each overriding the
  earlier). `bin/common` splits it on `:`, drops empty segments, resolves each
  relative entry against `PROJECT_ROOT` (exactly as it resolves `ENV_FILE`), and
  leaves `ENV_FILES` itself **untouched and exported**, so a parent's stack
  survives to its child — and grandchild — processes. `ENV_FILE` and `ENV_FILES`
  are mutually exclusive (both set → fatal). A plain **exported scalar** (not a
  bash array, which cannot cross a process boundary) is the load-bearing choice:
  `bin/test` runs `postgres-up`, `db-reset`, and `db-tests` as **separate child
  processes**, and each must see the same `.env.dev → .env.test` stack to target
  the per-worktree test DB rather than the shared dev DB; because `ENV_FILES` is
  an exported string, those children inherit the full stack across the process
  boundary with no re-declaration. Required by every test harness that runs
  against the local dev cluster: the DB-connection values it needs
  (`DATABASE_PASSWORD`, the `trust` auth method, `PGHOST`, `POSTGRES_DATA_DIR`,
  `POSTGRES_ADMIN_DB`, `POSTGRES_USER`, `DATABASE_USER`) and the
  now-**required** toggles' dev values all move from the base into `.env.dev`,
  so a harness that only layered `.env.test` would lose them. Each such harness
  — `bin/test`, `bin/test-fuzz`, `bin/scripts-tests`, `bin/db-tests`,
  `bin/db-convos-tests`, `bin/db-system-prompts-tests`, `bin/db-users-tests`,
  `bin/q-scripts-tests` — switches its `export ENV_FILE=.env.test|.env.fuzz` to
  `export ENV_FILES=".env.dev:.env.test|.env.fuzz"`, layering
  `base → .env.dev → .env.test|.env.fuzz`. `bin/db-scripts-tests` is exempt: it
  writes a self-contained private env file and pins `ENV_FILE` to it, so it
  never depended on the base's dev block and must keep `ENV_FILE` (its private
  env is complete). Standalone-vs-inherited: a harness that can also run on its
  own (e.g. `bin/db-tests`, invoked both under `bin/test` and directly) writes
  its export as `export ENV_FILES="${ENV_FILES:-.env.dev:.env.test}"` — it
  **inherits** the parent's stack when run under `bin/test` and falls back to
  the same default only when run standalone, so inheritance and default are one
  mechanism, not two. **`bin/db-users-tests` inner temp env file** — beyond its
  top-level `ENV_FILE`→`ENV_FILES` switch, `bin/db-users-tests` builds an inner
  temp env file (`ROLE_TEST_ENV_FILE`) that `source`s `.env.test` and overrides
  only `DATABASE_USER`, then runs `db-create-role` with
  `ENV_FILE=$ROLE_TEST_ENV_FILE`. Because that inner file is pinned as a single
  `ENV_FILE` (not `ENV_FILES`), `bin/common` layers `base → $ROLE_TEST_ENV_FILE`
  only — it does **not** pull in `.env.dev`. After the split the DB-connection
  block (`DATABASE_PASSWORD`, `PGHOST`, `POSTGRES_USER`, `POSTGRES_ADMIN_DB`,
  the `trust` auth method) that `db-create-role` needs no longer lives in the
  base or in `.env.test` — it lives in `.env.dev`, which this chain excludes —
  so the temp file must restore it explicitly. Fix: the temp file's source chain
  gains `.env.dev` **before** `.env.test` (it becomes
  `source "$PROJECT_ROOT/.env.dev"` then `source "$PROJECT_ROOT/.env.test"` then
  the `DATABASE_USER` override), so
  `base → .env.dev → .env.test →
  DATABASE_USER-override` resolves the full DB
  block and `db-create-role` connects. (Keeping the inner file on a single
  `ENV_FILE` — rather than switching it to `ENV_FILES` — preserves the existing
  `DATABASE_USER`-clobber workaround that the whole temp-file dance exists for:
  `bin/common` re-sources `ENV_FILE` in the child, so the override must sit
  inside that one sourced file.)
- **Neither set → `base → .env.dev`.** With dev-dangerous values now in
  `.env.dev`, the ambient local default layers it automatically, so bare local
  scripts (`bin/db-status`, the `-up` daemons) keep working with no per-script
  change.
- **Empty base on the host, no special case.** The "a deploy host has no `.env`"
  branch is removed. On the instance the release bundle carries an intentionally
  **empty** `.env` (an explanatory comment only), so `bin/common` runs the same
  `base → delta` shape everywhere; with `ENV_FILE=/etc/unicoach/env` the empty
  base layers under the complete materialized env, contributing nothing.

The script's top-level body is restructured into named steps (per the SLA review
lens now covering script bodies).

### Producer flow (`bin/deploy`)

`bin/deploy <env>` no longer sources the app config. It:

1. sources `bin/common` with an explicit `ENV_FILE=.env.<env>` prelude (so the
   base + that env layer, **not** `.env.dev`, per the chain rule) and
   `$PROJECT_ROOT/.env.deploy.$ENV` (`AWS_ACCOUNT_ID`, `REGION`); `bin/deploy`
   itself reads only account/region and `<env>` — the app config it forwards to
   `gen-deployed-env`, which resolves its own `ENV_FILE=.env.<env>`;
2. `assert_aws_account` against the active credentials (unchanged guard);
3. runs `bin/gen-deployed-env "$ENV" > deploy-env` (fatal aborts the deploy);
4. adds `deploy-env` and an empty `.env` to the tar bundle (new `REPO_PATHS`
   members / generated files);
5. builds, uploads, and issues the SSM Run Command (unchanged).

`ENVIRONMENT` and `require_env_vars ENVIRONMENT` are removed; the state key and
`TF_DATA_DIR` already use the `<env>` argument.

### On-instance flow (`infra/files/*`)

`render-env` takes the release directory, and instead of writing SSM directly to
`/etc/unicoach/env`, it **merges**: fetch the (now secrets-plus-RDS-identity)
SSM prefix to a temp file, and layer it over the bundle's `deploy-env` (SSM
last-wins), writing the complete flat `/etc/unicoach/env` (mode 0600). The
`PLACEHOLDER_SEED_OUT_OF_BAND` fail-closed check is retained.
`deploy-on-instance.sh` passes the fresh `$RELEASE_DIR` to `render-env`; the
migration steps and their `ENV_FILE=/etc/unicoach/env` are otherwise unchanged
(now layering the empty base under the complete env). systemd units are
unchanged — still one `EnvironmentFile=/etc/unicoach/env`.

`render-env` reaches a **running** prod instance only via a live SSM write of
`/opt/unicoach/bin/render-env` (cloud-init does not re-run on a live host); this
is a rollout step, performed once before the first deploy under the new model.

### Required HOCON toggles

Five substitutions whose committed dev default is the permissive/silent one are
made **required** (`${VAR}`, no default line), so an unset value fails the JVM
at boot rather than silently running the dev mode:

| key                     | file                             | dev value (`.env.dev`) | prod value (`.env.prod`) |
| ----------------------- | -------------------------------- | ---------------------- | ------------------------ |
| `GOOGLE_AUTH_PROVIDER`  | `service/…/service.conf`         | `stub`                 | `google`                 |
| `SESSION_COOKIE_SECURE` | `rest-server/…/rest-server.conf` | `false`                | `true`                   |
| `ADMIN_COOKIE_SECURE`   | `admin-web/…/admin-web.conf`     | `false`                | `true`                   |
| `EMAIL_PROVIDER`        | `email/…/email.conf`             | `log`                  | `ses`                    |
| `CHAT_PROVIDER`         | `chat/…/chat.conf`               | `log`                  | `anthropic`              |

### `bin/remote` and `bin/infra-*`

`bin/remote`'s `load_cloud_env` today does three removed-key-relevant things:
sources the bare `.env.<env>` delta over `bin/common`'s base, then calls
`require_env_vars ENVIRONMENT` and `require_cloud_domain "$env"`. All three
change:

- **Pin `ENV_FILE=.env.<env>`** (base + env, no `.env.dev`, per the chain rule)
  in place of today's mid-file bare `.env.<env>` source. Because `bin/common`
  now performs the layering, `load_cloud_env`'s manual
  `set -a; source
  "$ENV_FILE"; set +a` block is dropped — the delta is already
  loaded.
- **Drop `require_env_vars ENVIRONMENT`.** `ENVIRONMENT` is removed from every
  dotenv by this RFC; the guard would fatal on every `bin/remote` run. The env
  identity `bin/remote` needs is already the `<env>` positional (`UNICOACH_ENV`,
  passed to `load_cloud_env`), never a sourced `ENVIRONMENT`. `bin/remote` reads
  no other removed key: its remaining `require_env_vars`-eligible input,
  `AWS_ACCOUNT_ID`, now comes from `.env.deploy.<env>` (below) and is checked by
  the existing `assert_aws_account`.
- **Keep `require_cloud_domain "$env"`, repurposed** (see the
  `require_cloud_domain` note below): it is the guard that rejects an
  `env=local`/dev target, which `bin/remote` still wants — the SSM trigger has
  no meaning locally.

`bin/remote` also sources `.env.deploy.<env>` for `AWS_ACCOUNT_ID`/`REGION`. Its
on-instance command string (`ENV_FILE=/etc/unicoach/env bin/<tool>`) is already
correct and unchanged.

`bin/infra-plan`/`bin/infra-apply` likewise pin `ENV_FILE=.env.<env>` so
`bin/common` layers `base → .env.<env>` (never `.env.dev`, which would let a dev
value mask a missing `TF_VAR_*` source); they derive `TF_VAR_environment` from
the `<env>` argument (not `ENVIRONMENT`), source `.env.deploy.<env>` for
`AWS_ACCOUNT_ID` and `TF_VAR_region`, and read `.env.<env>` for the other
`TF_VAR_*` exports (`app_domain`, `google_client_ids`, ports,
`hosted_zone_name`). They drop `require_env_vars ENVIRONMENT` (the `ENVIRONMENT`
token in `require_env_vars ENVIRONMENT APP_DOMAIN GOOGLE_CLIENT_IDS`) but keep
`require_env_vars APP_DOMAIN GOOGLE_CLIENT_IDS` and `require_cloud_domain`.
Today these scripts source `bin/common` (base only, since `ENV_FILE` is unset)
and then source `.env.<env>` on top; under the new default that unset-`ENV_FILE`
path would layer `.env.dev`, so pinning `ENV_FILE` is required, not cosmetic.

**`require_cloud_domain`'s fate — repurposed, not removed.** Today
`require_cloud_domain` exists to catch a cloud `.env.<env>` that omits
`APP_DOMAIN`: the base `.env` layers `APP_DOMAIN=localhost` underneath, so a
plain `require_env_vars APP_DOMAIN` never sees it empty. This RFC destroys that
premise on the deploy/infra path — `APP_DOMAIN` moves to `.env.dev`, which that
path excludes — so an omitted `APP_DOMAIN` is now simply **unset** and the
omitted-key fatal is `require_env_vars APP_DOMAIN`'s job, not
`require_cloud_domain`'s. `require_cloud_domain` is **repurposed** to its
still-needed second job: rejecting a target whose resolved `APP_DOMAIN` is
`localhost` — i.e. an `env=local`/dev target reached on the deploy/infra path,
where the SSM/OpenTofu action has no meaning. It keeps its exact current body
(fatal unless `APP_DOMAIN` is set and non-`localhost`); only its comment is
rewritten to state the new premise (the `localhost` value now arrives only from
an operator mistargeting `local`/dev, not from a base default). It therefore
stays in `bin/functions` and stays called by `bin/remote`/`bin/infra-*`.

### Error handling / edge cases

- Missing `DEPLOY_VAR_NAMES` key in `.env.<env>` → `gen-deployed-env` fatals on
  the laptop, before build/upload.
- Missing required HOCON toggle in the materialized env → JVM boot fails
  (`ConfigException.UnresolvedSubstitution`); the service restart-loops and its
  ALB target never turns healthy — loud, not silent.
- Unseeded secret (`PLACEHOLDER_SEED_OUT_OF_BAND`) → `render-env` fatals before
  writing `/etc/unicoach/env`; the previous release keeps serving.
- **Dev value omitted from `.env.<env>` → fatal, not leaked.** Because the
  deploy/infra path pins `ENV_FILE=.env.<env>` and never layers `.env.dev` (the
  chain rule above), a key present in `.env.dev` but omitted from `.env.<env>`
  is simply **unset** on that path, so `require_env_vars` (in `gen-deployed-env`
  and `bin/infra-*`) fatals on the laptop naming the key, before build/upload.
  The dev value cannot be inherited and cannot ship. This is the RFC's core
  guarantee — that the dev base cannot leak to prod — enforced by construction,
  not by a deferred mitigation.

### Dependencies

No new runtime dependencies. `render-env` continues to use
`aws ssm
get-parameters-by-path`; the merge is flat-file only.
`deno`/`ktlint`/Postgres toolchain unchanged.

## Tests

- **`bin/gen-deployed-env` — happy path.**
  `nix develop -c bin/gen-deployed-env
  test` emits exactly the
  `DEPLOY_VAR_NAMES` keys, each resolved (no `$` left), and nothing else (no
  `PATH`/`HOME`). Assert `APP_DOMAIN` reflects the delta and
  `EMAIL_DEFAULT_FROM` is `noreply@<domain>` (interpolation resolved).
- **`bin/gen-deployed-env` — missing key fatals.** A fixture `.env.<env>`
  omitting a `DEPLOY_VAR_NAMES` key (e.g. `GOOGLE_AUTH_PROVIDER`) makes
  `gen-deployed-env` exit non-zero naming that key. Drives the real script, not
  a parse of it.
- **`bin/gen-deployed-env` — no secret/identity leakage.** Output contains none
  of the secrets (`DATABASE_PASSWORD`, `CHAT_ANTHROPIC_API_KEY`, `PGPASSWORD`)
  nor any of the four Terraform-owned RDS-identity keys (`PGHOST`,
  `DATABASE_HOST`, `POSTGRES_USER`, `DATABASE_USER`) — asserted symmetrically
  with the four-key ownership rule, so no SSM-owned key is emitted.
- **`bin/gen-deployed-env` — `.env.dev` is not layered (no dev leak).** With a
  `DEPLOY_VAR_NAMES` key present in `.env.dev` but omitted from the fixture
  `.env.<env>`, the script fatals naming the key (it does **not** inherit the
  `.env.dev` value and emit it). The complementary assertion: when the key is
  present in both with different values, the output carries the `.env.<env>`
  value, never the `.env.dev` one. This is the direct test of the leak-close.
- **`bin/common` — `ENV_FILES` layering.** With
  `ENV_FILES=".env.dev:.env.test"`, a key set only in `.env.test` wins over
  `.env.dev` wins over base; both-set (`ENV_FILE` and `ENV_FILES`) fatals; the
  split resolves each relative entry against `PROJECT_ROOT` and drops empty
  segments. A child process inherits the exported `ENV_FILES` and layers the
  same stack (the cross-process case the string form exists for).
- **`bin/common` — empty base on host shape.** With an empty base and
  `ENV_FILE=<complete file>`, sourcing yields exactly the complete file's values
  and does not fatal (no "deploy host has no `.env`" path).
- **`render-env` merge.** Given a `deploy-env` fixture and a stubbed SSM fetch,
  the written file is their union with SSM winning on overlap; an unseeded
  `PLACEHOLDER` still fatals; mode is `0600`.
- **HOCON required toggles.** A JVM/config load with `GOOGLE_AUTH_PROVIDER`
  unset fails to resolve (assert the boot-time failure), and resolves to the set
  value when present — one test per toggle, block-body (not expression-body).
- **`assert_aws_account` from `.env.deploy.<env>`.** `bin/deploy`/`bin/remote`
  read `AWS_ACCOUNT_ID` from `.env.deploy.<env>`; a mismatch fatals before any
  AWS action (existing behavior, new source).
- **Test harnesses layer `.env.dev`.** `nix develop -c bin/db-tests` (a
  local-cluster harness now on `ENV_FILES=".env.dev:.env.test"`) connects and
  passes, proving the DB block (`DATABASE_PASSWORD`, `trust`, `PGHOST`) reaches
  the JVM from `.env.dev` after it left the base. A module owning a now-required
  toggle (`nix develop -c bin/test chat`) boots, proving the toggle's dev value
  reaches it via `.env.dev`.
- **`bin/db-users-tests` inner temp env resolves the DB block.**
  `nix develop -c bin/db-users-tests` green — specifically the
  `db-create-role creates a LOGIN role` / `is idempotent` assertions, which
  drive `db-create-role` through the inner `ROLE_TEST_ENV_FILE` temp file. This
  proves the temp file's new `.env.dev` source line supplies the DB-connection
  block (`DATABASE_PASSWORD`, `PGHOST`, `POSTGRES_USER`, `trust`) after it left
  the base, so `db-create-role` still connects with `DATABASE_USER` overridden.
  Drives the real harness end-to-end, not a parse of the temp-file construction.
- **Full suite.** `nix develop -c bin/test -f` green (forced re-run; confirm "N
  executed"), covering the migration scripts under the empty-base/host path.
- **iOS build unaffected.** `bin/build-ios` still reads `.env.prod` (uses
  `APP_DOMAIN` only); verify `prod` target build resolves the backend URL with
  `ENVIRONMENT`/`AWS_ACCOUNT_ID` removed from that file.

## Invariants

- **Amends `CONFIGURATION.md` invariant #1.** The committed #1 ("Committed
  config is development-only… non-dev environments override solely through the
  process environment materialized from SSM") no longer holds: this RFC commits
  each cloud env's full non-secret config to `.env.<env>` and ships it via the
  `deploy-env` artifact, so SSM is no longer the sole non-dev override channel.
  Its replacement wording, which implementation writes in place of the current
  #1: **"Committed config spans every environment, split by role. `.env` holds
  env-neutral non-secret values; `.env.dev` holds local-dev values; each
  `.env.<env>` holds that cloud env's non-secret values. No secret and no
  Terraform-owned identity is committed — those are the only two classes SSM
  owns (`/unicoach/<env>`). A fresh checkout still runs with zero setup and zero
  AWS, because `bin/common` layers `.env → .env.dev` by default."** **Why:** the
  old #1's guarantee (a checkout needs no AWS; secrets/identity are never
  committed) survives verbatim; only its mechanism (SSM as the _sole_ non-dev
  channel) changes, so the record must state the new guarantee rather than leave
  #1 contradicting the code. **Target:** `CONFIGURATION.md` (replaces #1).

- **Rule:** A config value whose committed dev default would run silently-wrong
  in a deployed environment — weakening security (an auth bypass, an unsecured
  cookie) or running a wrong-but-non-erroring mode (email/chat routed to `log`)
  — MUST be a **required** HOCON substitution (`${VAR}`, no default line) and be
  set explicitly in every environment's dotenv (`.env.dev`, `.env.<env>`).
  **Why:** silence is the hazard — a required substitution turns a forgotten
  override from a silent wrong-mode into a boot failure. **Target:**
  `CONFIGURATION.md` (the home of config invariants; recorded there as #7).

- **Rule:** On a cloud host, `/etc/unicoach/env` is the **complete**
  materialized environment; both systemd (`EnvironmentFile`) and `bin/common`
  MUST read exactly that one file, and no other on-host source may contribute
  app config. **Why:** a partial materialization or a second on-host config
  source lets the two consumers diverge, so a service and an ops tool would run
  different config. **Target:** `CONFIGURATION.md` (recorded there as #8).

## Implementation Plan

1. **Split the base into `.env`, `.env.dev`, `.env.deploy.prod`; rebuild
   `.env.prod`.** Move dev-dangerous/toggle-dev values that today live in `.env`
   to a new `.env.dev`, and **add** the three bind-host keys there as new dotenv
   entries (`SERVER_HOST=127.0.0.1`, `PUBLIC_WEB_HOST=127.0.0.1`,
   `ADMIN_WEB_HOST=127.0.0.1` — the dev overrides of the `.conf` defaults, one
   of which, `SERVER_HOST`, currently defaults to `0.0.0.0`); leave only
   env-neutral values in `.env`; create `.env.deploy.prod` (`AWS_ACCOUNT_ID`,
   `REGION`, and the `AWS_REGION="${AWS_REGION:-$REGION}"` derivation moved out
   of the base alongside `REGION`); rewrite `.env.prod` as full non-secret app
   config (add the 0.0.0.0 hosts, cookie/provider toggles,
   `GOOGLE_AUTH_PROVIDER=google`, derived email values; remove `ENVIRONMENT`,
   `AWS_ACCOUNT_ID`); rewrite `.env.template` to document the five roles.
   _Verify:_ `nix develop -c deno fmt --check`; grep confirms no
   `DATABASE_PASSWORD`/`trust`/`ENVIRONMENT` in `.env`, none in `.env.prod`.
2. **`bin/common`: `ENV_FILES` `PATH`-like string, `.env.dev` default,
   empty-base handling, named steps.** Then switch the local-cluster test
   harnesses (`bin/test`, `bin/test-fuzz`, `bin/scripts-tests`, `bin/db-tests`,
   `bin/db-convos-tests`, `bin/db-system-prompts-tests`, `bin/db-users-tests`,
   `bin/q-scripts-tests`) from `export ENV_FILE=.env.test|.env.fuzz` to
   `export ENV_FILES=".env.dev:.env.test|.env.fuzz"` — an exported `:`-separated
   string, so `bin/test`'s `postgres-up`/`db-reset`/`db-tests` child processes
   inherit the full stack across the process boundary; leave
   `bin/db-scripts-tests`'s pinned private-env `ENV_FILE` untouched. _Verify:_
   `nix develop -c bin/format -c`; a scratch `ENV_FILES`/both-set/empty-base
   check exits as specified; `nix develop -c bin/test db` green (the DB block
   now reaches the JVM via `.env.dev`); `nix develop -c bin/db-scripts-tests`
   still green (unaffected).
3. **Add `bin/gen-deployed-env` with `DEPLOY_VAR_NAMES`.** _Verify:_
   `nix develop -c bin/gen-deployed-env test` emits the expected keys; an
   omitted-key fixture fatals.
4. **Make the five HOCON toggles required; add their dev values to `.env.dev`,
   prod values to `.env.prod`.** _Verify:_ `nix develop -c bin/test rest-server`
   (and the modules owning each `.conf`) green; unset-toggle boot test fails.
5. **`bin/deploy`: pin `ENV_FILE=.env.<env>` (no `.env.dev`), source
   `.env.deploy.<env>`, call `gen-deployed-env`, bundle `deploy-env` + empty
   `.env`, drop `ENVIRONMENT`.** _Verify:_ `nix develop -c bin/format -c`; a
   dry-run/`-h` and the bundle-assembly path list the new files.
6. **`render-env.sh` + `deploy-on-instance.sh`: merge `deploy-env` under SSM;
   pass `$RELEASE_DIR`.** _Verify:_ `render-env` merge unit test (fixture +
   stub) green; `0600`; `PLACEHOLDER` still fatals.
7. **`infra/ssm.tf`: shrink `ssm_string_params` to the four RDS-identity keys.**
   _Verify:_ `nix develop -c tofu -chdir=infra validate` (or `fmt`); grep
   confirms the static keys are gone.
8. **`bin/remote` + `bin/infra-plan`/`infra-apply`: pin `ENV_FILE=.env.<env>`
   (never `.env.dev`), source `.env.deploy.<env>`, `TF_VAR_environment` from
   arg, drop the `ENVIRONMENT` token from every `require_env_vars` call (and, in
   `bin/remote`, its now-redundant manual `.env.<env>` source), keep
   `require_cloud_domain`; rewrite `require_cloud_domain`'s comment in
   `bin/functions` to its new premise (body unchanged); rewrite
   `infra/variables.tf`'s `var.environment` description.** _Verify:_
   `nix develop -c bin/format -c`; `nix develop -c bin/db-scripts-tests` (and
   remote/infra script tests) green; grep confirms no
   `require_env_vars
   ENVIRONMENT` remains in `bin/`.
9. **In `CONFIGURATION.md`: replace invariant #1 with the amended wording (per
   the Invariants section), add #7 and #8, and update the config-model prose
   (the five-role dotenv, the deploy/infra chain excluding `.env.dev`, the
   merged on-host env).** _Verify:_ `nix develop -c deno fmt --check`; #1 no
   longer says "development-only / solely through SSM"; #7 and #8 present with
   Rule/Why.
10. **Full gate.** _Verify:_ `nix develop -c bin/test -f` green ("N executed");
    `nix develop -c bin/format -c` clean.

## Files Modified

- `.env` — sanitize to env-neutral non-secret values only; in particular
  `REGION` and the `AWS_REGION="${AWS_REGION:-$REGION}"` derivation both leave
  the base (they are deploy-control, moving together to `.env.deploy.<env>`).
- `.env.dev` — **new**: dev-dangerous/toggle-dev values moved out of `.env`,
  plus the three **new** bind-host keys (`SERVER_HOST`/`PUBLIC_WEB_HOST`/
  `ADMIN_WEB_HOST`, dev-overriding the `.conf` defaults to `127.0.0.1`).
- `.env.prod` — rewrite as full non-secret prod app config; remove `ENVIRONMENT`
  and `AWS_ACCOUNT_ID`.
- `.env.deploy.prod` — **new**: `AWS_ACCOUNT_ID`, `REGION`, and the
  `AWS_REGION="${AWS_REGION:-$REGION}"` derivation (moved from the base with
  `REGION` so `bin/deploy`/`bin/remote`/`bin/infra-*`'s `--region "$AWS_REGION"`
  calls still resolve).
- `.env.template` — rewrite schema doc for the five roles.
- `bin/common` — `ENV_FILES`, `.env.dev` default, empty-base/host handling,
  named-step body.
- `bin/gen-deployed-env` — **new**: flatten + `DEPLOY_VAR_NAMES` + presence
  check.
- `bin/deploy` — pin `ENV_FILE=.env.<env>` (not the `.env.dev` default); load
  `.env.deploy.<env>` via the shared `load_deploy_control_env` helper; call
  `gen-deployed-env`; bundle `deploy-env` + empty `.env`; drop
  `ENVIRONMENT`/`require_env_vars ENVIRONMENT`.
- `bin/remote` — in `load_cloud_env`: pin `ENV_FILE=.env.<env>` and drop the
  manual `set -a; source "$ENV_FILE"; set +a` block (`bin/common` now layers
  it); drop `require_env_vars ENVIRONMENT` (`ENVIRONMENT` is removed — the guard
  would fatal on every run; env identity is the `<env>` positional); keep
  `require_cloud_domain "$env"` (repurposed local/dev-target reject); load
  `.env.deploy.<env>` (`AWS_ACCOUNT_ID`/`REGION`) via `load_deploy_control_env`.
- `bin/infra-plan` — pin `ENV_FILE=.env.<env>` (never `.env.dev`);
  `TF_VAR_environment` from arg (not `ENVIRONMENT`); load `.env.deploy.<env>`
  (account/`TF_VAR_region`) via `load_deploy_control_env`; drop the
  `ENVIRONMENT` token from
  `require_env_vars ENVIRONMENT APP_DOMAIN GOOGLE_CLIENT_IDS` (keep
  `APP_DOMAIN GOOGLE_CLIENT_IDS`); keep `require_cloud_domain`.
- `bin/infra-apply` — same as `bin/infra-plan` (the two are byte-identical but
  for the `apply`/`plan` token).
- `bin/test` — `export ENV_FILES=".env.dev:.env.test"` in place of
  `export ENV_FILE=.env.test` (exported `:`-separated string, so the
  `postgres-up`/`db-reset`/`db-tests` child processes inherit the full stack).
- `bin/test-fuzz` — `export ENV_FILES=".env.dev:.env.fuzz"` in place of
  `export ENV_FILE=.env.fuzz`.
- `bin/scripts-tests` — `export ENV_FILES=".env.dev:.env.test"`.
- `bin/db-tests` — `export ENV_FILES="${ENV_FILES:-.env.dev:.env.test}"` in
  place of the `ENV_FILE` default. Run as a child of `bin/test`, it **inherits**
  the parent's exported `ENV_FILES` (an exported string crosses the process
  boundary); the `${ENV_FILES:-…}` default applies only when it is run
  standalone.
- `bin/db-convos-tests` — `export ENV_FILES=".env.dev:.env.test"`.
- `bin/db-system-prompts-tests` — `export ENV_FILES=".env.dev:.env.test"`.
- `bin/db-users-tests` — two changes: (1) top-level
  `export ENV_FILES="${ENV_FILES:-.env.dev:.env.test}"` in place of
  `export ENV_FILE=.env.test`; (2) its inner `ROLE_TEST_ENV_FILE` temp file —
  sourced as a single pinned `ENV_FILE` and so **not** covered by (1) — gains a
  `source "$PROJECT_ROOT/.env.dev"` line **before** its existing
  `source "$PROJECT_ROOT/.env.test"`, so the DB block (`DATABASE_PASSWORD`,
  `PGHOST`, `POSTGRES_USER`, `POSTGRES_ADMIN_DB`, `trust`) — which left the base
  for `.env.dev` — reaches `db-create-role` through that temp file; the
  `DATABASE_USER` override stays last.
- `bin/q-scripts-tests` — `export ENV_FILES=".env.dev:.env.test"`.
- `.env.test` — no key changes required (the DB block and toggle dev values now
  come from `.env.dev`); drop the now-redundant `GOOGLE_AUTH_PROVIDER=stub` line
  (`.env.dev` supplies it), keeping each value one home.
- `.env.fuzz` — same as `.env.test`: drop the redundant
  `GOOGLE_AUTH_PROVIDER=stub` line.
- `infra/files/render-env.sh` — take `$RELEASE_DIR`; merge `deploy-env` under
  the SSM fetch; retain `PLACEHOLDER` check.
- `infra/files/deploy-on-instance.sh` — pass `$RELEASE_DIR` to `render-env`;
  correct the migration-step comment that reads "the host has no base .env, so
  ENV_FILE … is sourced as the delta" (the host now carries an empty base under
  the complete `/etc/unicoach/env` delta).
- `infra/ssm.tf` — shrink `ssm_string_params` to the four RDS-identity keys.
- `infra/variables.tf` — rewrite `var.environment`'s description (it now derives
  from the `<env>` arg, not from `.env.<env>`'s `ENVIRONMENT`, which is
  removed); it is the only description that names `ENVIRONMENT`. Other
  descriptions that cite `.env`/`.env.<env>` stay valid.
- `service/src/main/resources/service.conf` —
  `provider = ${GOOGLE_AUTH_PROVIDER}`.
- `rest-server/src/main/resources/rest-server.conf` —
  `cookieSecure =
  ${SESSION_COOKIE_SECURE}`.
- `admin-web/src/main/resources/admin-web.conf` —
  `cookieSecure =
  ${ADMIN_COOKIE_SECURE}`.
- `email/src/main/resources/email.conf` — `provider = ${EMAIL_PROVIDER}`.
- `chat/src/main/resources/chat.conf` — `provider = ${CHAT_PROVIDER}`.
- `CONFIGURATION.md` — update the config model (the five-role dotenv, the
  deploy/infra chain that excludes `.env.dev`, the merged on-host
  `/etc/unicoach/env`); **replace** invariant #1 with the amended wording
  (Invariants section) and add #7 and #8.
- `bin/tests-common` / a `bin/*-scripts-tests` file — tests for
  `gen-deployed-env` and the `bin/common` layering/empty-base cases.
- `bin/functions` — rewrite `require_cloud_domain`'s comment to state its new
  premise (an `APP_DOMAIN=localhost` now signals an operator mistargeting
  `local`/dev, not a base default; the omitted-`APP_DOMAIN` fatal is now
  `require_env_vars`'s); its body is unchanged. `unicoach_env_file` stays (still
  used by `bin/remote`/`bin/infra-*` to resolve `.env.<env>`, and unaffected).
  Add `load_deploy_control_env <env>` — the single owner of the deploy-control
  bootstrap (resolve `.env.deploy.<env>`, fatal-if-absent, source under
  `set -a`, `assert_aws_account`) shared by `bin/deploy`, `bin/remote`,
  `bin/infra-plan`, and `bin/infra-apply`, so the sequence lives in one place
  rather than being copy-pasted across the four callers.
