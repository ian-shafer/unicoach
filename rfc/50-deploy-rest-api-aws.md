# RFC 50: Deploy the Backend REST API to AWS

## Executive Summary

This RFC provisions a single-environment AWS deployment of the `rest-server` and
`queue-worker` JVM processes against a managed PostgreSQL database, defined
entirely as code. The motivation is to make the backend reachable by the iOS
client over TLS; today the services run only as local native daemons
(`bin/*-up`) against a local Postgres cluster.

The topology is one VPC across two availability zones: an Application Load
Balancer terminates TLS in public subnets and forwards to a single EC2 instance
in a public subnet running both services under `systemd`; an RDS Postgres
instance sits in two private subnets, reachable only from the instance. The
instance has a public IP so its outbound egress (Anthropic API, SES, package
installs) routes directly through the internet gateway, but its security group
admits inbound only from the ALB on port 8080 and never opens SSH; all
administration and deployment flow through AWS Systems Manager (SSM) Session
Manager and Run Command, scoped by the instance IAM role. Placing the instance
in a public subnet avoids a NAT gateway's recurring hourly and per-GB
data-processing charges, in exchange for a public IP fronted by a restrictive
security group.

The application ships as the existing Gradle `installDist` distribution (no
Docker), migrations run through the existing `bin/db-*` scripts pointed at RDS,
and the only code change is parameterizing the hard-coded `localhost` JDBC host
in `db.conf`. Infrastructure is authored in HCL and applied with OpenTofu;
secrets live in SSM Parameter Store and are injected as environment variables
the JVM already reads.

Explicitly out of scope, to keep this bounded: CI/CD automation,
autoscaling/multi-instance/blue-green rollout, CloudWatch log shipping (logs
stay in the on-host journal, read via SSM), wiring the email provider into
production, and a WAF.

## Detailed Design

### Build artifacts and the one required code change

The deployment runs the unmodified Gradle `installDist` outputs:
`rest-server/build/install/rest-server/bin/rest-server` and
`queue-worker/build/install/queue-worker/bin/queue-worker`, each a launcher
script plus a `lib/` directory of JARs, produced by `bin/build`.

One code change is required. `db/src/main/resources/db.conf` hard-codes the JDBC
host as `localhost`, so the JVM cannot be pointed at an RDS endpoint by
environment override alone. The fix introduces a `database.host` key, defaulted
to `localhost` and overridable by a new `DATABASE_HOST` environment variable,
and recomposes the JDBC URL from it. The default preserves current local and
test behavior exactly (backward-compatible); production sets `DATABASE_HOST` to
the RDS endpoint.

This overrides the current `db/src/main/resources/SPEC.md` invariant that "only
the scheme and host (`jdbc:postgresql://localhost:`) are hardcoded literals" —
after this change the host is a substitution (`${?DATABASE_HOST}`, default
`localhost`) on equal footing with the existing `${POSTGRES_PORT}` and
`${POSTGRES_DB}` substitutions. The spec-sync pass reconciles that document; the
implementer does not edit it.

```hocon
database {
    host = "localhost"
    host = ${?DATABASE_HOST}
    jdbcUrl = "jdbc:postgresql://"${database.host}":"${POSTGRES_PORT}"/"${POSTGRES_DB}
    user = ${?DATABASE_USER}
    password = ${?DATABASE_PASSWORD}
    maximumPoolSize = 10
    maximumPoolSize = ${?DATABASE_MAXIMUM_POOL_SIZE}
    connectionTimeout = 30000
}
```

The `psql`-based scripts (the `bin/db-run` family — `bin/db-run` and its
front-ends `bin/db-write`, `bin/db-query`) already select their host from the
libpq `PGHOST` environment variable, so every `bin/db-*` script targets RDS with
no change.

### Application-role creation on a managed cluster

A new idempotent script `bin/db-create-role` creates the application login role
(`DATABASE_USER` with `DATABASE_PASSWORD`) against whatever cluster `PGHOST`
points at. This fills a gap: role creation currently lives inside
`bin/db-bootstrap`, which also runs `initdb` — illegal against RDS, which is the
managed cluster. `bin/db-create` creates the database and grants but assumes the
role already exists.

`bin/db-create-role` runs the role-creation `DO` block (create-if-absent,
swallow duplicate) via `bin/db-write -d "${POSTGRES_ADMIN_DB:-postgres}"`, which
connects as the master role (`-U "$POSTGRES_USER"`) — the same block currently
embedded in `bin/db-bootstrap`. `bin/db-bootstrap` is refactored to delegate to
it after `initdb`, keeping a single source of truth for role creation and
leaving local behavior unchanged. The RDS database initialization sequence is
therefore `db-create-role` → `db-create` → `db-migrate`, all run on the instance
with the environment pointed at RDS.

### Network topology

One VPC spanning two availability zones, sized for the AZ-count minimums that
the ALB (≥2 subnets in 2 AZs) and the RDS subnet group (≥2 AZs) impose:

| Resource          | Placement                          | Purpose                                              |
| ----------------- | ---------------------------------- | ---------------------------------------------------- |
| Internet gateway  | VPC                                | Inbound to ALB; outbound egress for the EC2 instance |
| Public subnet ×2  | one per AZ                         | ALB nodes (both); EC2 instance (one)                 |
| Private subnet ×2 | one per AZ                         | RDS subnet group (both)                              |
| Route tables      | public → IGW; private → local only | —                                                    |

No NAT gateway: the EC2 instance reaches the internet directly through the
internet gateway via its own public IP, and the RDS instance in the private
subnets needs no egress.

Security groups enforce a linear path:

| SG    | Ingress                  | Egress                    |
| ----- | ------------------------ | ------------------------- |
| `alb` | 443, 80 from `0.0.0.0/0` | to `ec2` on 8080          |
| `ec2` | 8080 from `alb`          | all (Anthropic, SES, SSM) |
| `rds` | 5432 from `ec2`          | none                      |

The instance's public IP does not widen its exposure: the `ec2` group's only
ingress rule is 8080 from the `alb` group, and no rule opens SSH.

### Compute and service lifecycle

A single EC2 instance (Amazon Linux 2023, Graviton `t4g.small`; the JVM
distribution is architecture-neutral) in a public subnet, assigned a public IP
but reached for application traffic only through the ALB and for administration
only through SSM. The Amazon Linux 2023 SSM agent is preinstalled. Instance
bootstrap (cloud-init `user_data`) installs Amazon Corretto 21 and the
PostgreSQL client, creates the `unicoach` system user and the `/opt/unicoach`
layout, and installs the two `systemd` unit files.

Releases live under `/opt/unicoach/releases/<id>/` with `/opt/unicoach/current`
a symlink to the active release; deployment swaps the symlink and restarts the
units. Both units are `EnvironmentFile=/etc/unicoach/env`, `User=unicoach`,
`Restart=on-failure`:

| Unit                            | ExecStart                                             |
| ------------------------------- | ----------------------------------------------------- |
| `unicoach-rest-server.service`  | `/opt/unicoach/current/rest-server/bin/rest-server`   |
| `unicoach-queue-worker.service` | `/opt/unicoach/current/queue-worker/bin/queue-worker` |

The JVM logs (Logback, JSON to stdout) are captured by the `systemd` journal and
read on demand through an SSM session; no log-shipping agent is deployed.

### Ingress and TLS

An internet-facing ALB across the two public subnets terminates TLS:

- HTTPS:443 listener using an ACM certificate, forwarding to a target group of
  protocol HTTP on port 8080 with the registered instance.
- HTTP:80 listener redirecting to 443.
- Target-group health check: `GET /hello`, expecting 200 (the path the existing
  `bin/rest-server-check` already validates).
- Idle timeout raised to 300s so streamed chat responses (Server-Sent Events
  proxied from the Anthropic provider) are not severed by the ALB's default
  60-second idle cutoff.

The API is served at `api.unicoachapp.com`. The parent domain `unicoachapp.com`
is registered through Route53 Domains, which auto-creates its hosted zone, so
OpenTofu references that zone by **data source** rather than creating it (a
managed zone resource would conflict with the auto-created one). The ACM
certificate (subject `api.unicoachapp.com`) is DNS-validated by records written
into that zone, and an ALIAS record `api.unicoachapp.com` → the ALB completes
ingress. The certificate, validation records, and alias are OpenTofu-managed;
the zone is not.

The API host and parent zone are OpenTofu variables (`api_domain` =
`api.unicoachapp.com`, `hosted_zone_name` = `unicoachapp.com`), defaulted to
these values. `SESSION_COOKIE_DOMAIN` is `api.unicoachapp.com` and `JWT_ISSUER`
is `https://api.unicoachapp.com/`.

### Database

An RDS PostgreSQL instance (`db.t4g.micro`, gp3 storage, single-AZ) in the
private subnet group, reachable only from the `ec2` security group. The engine
version is the OpenTofu variable `db_engine_version`, defaulted to `"18"` (major
only; RDS selects the latest minor, currently 18.4). This matches the dev/test
major (`flake.nix` pins `postgresql_18`), giving full parity. RDS has offered
major 18 since November 2025, so no apply-time fallback is required; the
variable remains the single knob if a different major is ever wanted.

The RDS master password is generated by OpenTofu (`random_password`) and written
directly to an SSM SecureString parameter, so no human handles it. The
application connects as the separate, less-privileged `DATABASE_USER` role
created by `bin/db-create-role`.

### Secrets and configuration

All runtime configuration lives in SSM Parameter Store under the
`/unicoach/prod/` prefix — SecureString for secrets, String for non-secret
values. The instance IAM role grants `ssm:GetParametersByPath` on that prefix
and `kms:Decrypt` on the SSM key. A render step — `infra/files/render-env.sh`,
installed by cloud-init to `/opt/unicoach/bin/render-env` — fetches the full
prefix with decryption and writes `/etc/unicoach/env` (owner `root`, mode
`0600`) as `KEY=VALUE` lines. That single file is consumed two ways: as the
`systemd` `EnvironmentFile` for both services, and as `ENV_FILE` sourced by
`bin/common` during the on-instance migration step (`bin/common` does
`set -a; source`, so the plain `KEY=VALUE` format satisfies both).

Parameter inventory:

| Parameter (`/unicoach/prod/…`) | Type         | Source                         | Consumed by        |
| ------------------------------ | ------------ | ------------------------------ | ------------------ |
| `PGPASSWORD`                   | SecureString | OpenTofu (RDS master pw)       | migration `psql`   |
| `DATABASE_PASSWORD`            | SecureString | set out-of-band                | JVM app role       |
| `JWT_SECRET`                   | SecureString | set out-of-band                | JVM                |
| `CHAT_ANTHROPIC_API_KEY`       | SecureString | set out-of-band                | JVM                |
| `PGHOST` / `DATABASE_HOST`     | String       | OpenTofu (RDS endpoint)        | `psql` / JVM       |
| `POSTGRES_PORT`                | String       | `5432`                         | both               |
| `POSTGRES_DB`                  | String       | `unicoach`                     | both               |
| `POSTGRES_USER`                | String       | RDS master user                | migration `psql`   |
| `DATABASE_USER`                | String       | `unicoach`                     | JVM, role creation |
| `SERVER_HOST` / `SERVER_PORT`  | String       | `0.0.0.0` / `8080`             | JVM                |
| `SESSION_COOKIE_SECURE`        | String       | `true`                         | JVM                |
| `SESSION_COOKIE_DOMAIN`        | String       | `api.unicoachapp.com`          | JVM                |
| `JWT_ISSUER`                   | String       | `https://api.unicoachapp.com/` | JVM                |
| `CHAT_PROVIDER`                | String       | `anthropic`                    | JVM                |

The `DATABASE_USER` parameter is fed by the OpenTofu `app_db_user` variable
(default `unicoach`), the single knob for the application role name; the same
value drives `bin/db-create-role` via this parameter. `PGHOST` and
`DATABASE_HOST` carry the same RDS endpoint because the `psql` scripts and the
JDBC URL read different variable names. The SecureString parameters set
out-of-band are created by OpenTofu with a placeholder value and
`ignore_changes` on the value, so the operator seeds the real secret with the
AWS CLI without OpenTofu reverting it. The email provider is left at its `log`
default — it is not yet wired into `rest-server` and is out of scope.

### Deployment mechanism

`bin/deploy` is the single operator entry point and contains no AWS resource
definitions (those are OpenTofu's). Its sequence:

1. `bin/build` — produce both `installDist` distributions locally via Nix.
2. Assemble a deploy bundle tarball preserving repository-relative paths: the
   two `build/install/*` distributions, `db/schema/`, and the migration scripts
   they transitively need (`bin/common`, `bin/functions`, `bin/db-create-role`,
   `bin/db-create`, `bin/db-migrate`, `bin/db-status`, `bin/db-write`,
   `bin/db-query`, `bin/db-run`). `bin/db-query` is required because
   `bin/db-status` and `bin/db-create` both invoke it for their existence
   checks.
3. Upload the bundle to the S3 artifacts bucket (OpenTofu output).
4. Issue an SSM Run Command to the instance (OpenTofu output instance id) that
   executes the on-instance deploy step.

The on-instance deploy step (`deploy-on-instance.sh`, installed by cloud-init):
unpack the bundle into a new `/opt/unicoach/releases/<id>/`; run
`/opt/unicoach/bin/render-env` to refresh `/etc/unicoach/env`; with
`ENV_FILE=/etc/unicoach/env`, run `bin/db-create-role` → `bin/db-create` →
`bin/db-migrate` against RDS; repoint `/opt/unicoach/current`;
`systemctl restart` both units. A single-instance restart implies brief downtime
per deploy, which is accepted at this scale.

Five thin wrappers front OpenTofu so operators never type raw `tofu`. Four
target the main config — `bin/infra-init`, `bin/infra-plan`, `bin/infra-apply`,
and `bin/infra-output` shell out to `tofu -chdir=infra init|plan|apply|output`,
each baking in its verb. The fifth, `bin/infra-bootstrap`, fronts the one-time
state-backend bootstrap and forwards its subcommand to
`tofu -chdir=infra/bootstrap init|apply` — run once as `init` then `apply`, so it
does not bake in a single verb. All run inside the Nix dev shell and carry no
resource logic; the HCL under `infra/` and `infra/bootstrap/` is the sole source
of resource definitions.

There is no CI; deployment is operator-invoked, matching the project's existing
decision to forgo GitHub Actions in favor of local scripts.

### Infrastructure-as-code engine and state

Infrastructure is authored in HCL under `infra/` and applied with **OpenTofu**,
the open-source, drop-in Terraform engine — chosen over HashiCorp Terraform
because the latter's BSL license would force an `allowUnfree` flag into the Nix
flake; the HCL and `plan`/`apply` workflow are identical. `opentofu` and
`awscli2` are added to the dev shell.

State is stored remotely in an encrypted, versioned, private S3 bucket using S3
native lockfile locking (no DynamoDB table). A separate minimal
`infra/bootstrap/` configuration with local state creates that bucket, resolving
the chicken-and-egg of the backend bucket's own existence.

OpenTofu resource inventory under `infra/`:

| File                                                                        | Resources                                                                                                            |
| --------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| `versions.tf`                                                               | required OpenTofu + provider versions (`aws`, `random`)                                                              |
| `backend.tf`                                                                | S3 backend (bucket, key, region, encrypt, `use_lockfile`)                                                            |
| `variables.tf`                                                              | `region`, `api_domain`, `hosted_zone_name`, `instance_type`, `db_instance_class`, `db_engine_version`, `app_db_user` |
| `network.tf`                                                                | VPC, 2 public + 2 private subnets, IGW, route tables + associations                                                  |
| `security_groups.tf`                                                        | `alb`, `ec2`, `rds` security groups                                                                                  |
| `iam.tf`                                                                    | instance role + profile; SSM-managed-core, scoped SSM/KMS read, S3 artifact read                                     |
| `s3.tf`                                                                     | artifacts bucket (versioned, SSE, lifecycle expiry)                                                                  |
| `ssm.tf`                                                                    | one `aws_ssm_parameter` per row of the inventory above                                                               |
| `rds.tf`                                                                    | DB subnet group, `random_password`, RDS instance                                                                     |
| `ec2.tf`                                                                    | AL2023 ARM AMI data source, instance (public subnet, public IP) with profile + `user_data`                           |
| `alb.tf`                                                                    | ALB, target group (`/hello`, 8080, idle 300s), instance attachment, 443 + 80 listeners                               |
| `dns.tf`                                                                    | Route53 zone (data source), ACM cert, validation records, cert validation, ALIAS                                     |
| `outputs.tf`                                                                | `alb_dns_name`, `app_url`, `rds_endpoint`, `artifacts_bucket`, `instance_id`                                         |
| `terraform.tfvars.example`                                                  | committed template enumerating every `variables.tf` knob; operators copy it to the gitignored `terraform.tfvars`     |
| `files/cloud-init.yaml`                                                     | Corretto 21 + psql install, user/dirs, unit + script install                                                         |
| `files/unicoach-rest-server.service`, `files/unicoach-queue-worker.service` | systemd units                                                                                                        |
| `files/render-env.sh`, `files/deploy-on-instance.sh`                        | on-instance render + deploy steps                                                                                    |

### Error handling and edge cases

- **Hosted zone not yet propagated** — if the `unicoachapp.com` zone data source
  resolves nothing (registration incomplete), `apply` of `dns.tf` fails closed;
  no partial TLS state.
- **Unseeded secrets** — if a SecureString still holds its placeholder, the JVM
  fails fast on startup (existing config validation) or the DB connection is
  rejected; surfaced in the journal via SSM, not silently degraded. The
  master-role `psql` steps additionally require `PGPASSWORD` in
  `/etc/unicoach/env` (RDS rejects libpq trust auth, unlike the local cluster);
  an absent `PGPASSWORD` fails at the migration step, before the symlink swap.
- **SSE idle severing** — mitigated by the 300s ALB idle timeout; long
  generations that exceed it still fail, but the common case is covered.
- **Migration failure on deploy** — `bin/db-migrate` halts on first error
  (existing behavior); the symlink swap and restart occur only after migrations
  succeed, so a failed migration leaves the previous release serving.

### Dependencies

- New dev-shell tools: `opentofu`, `awscli2` (Nix flake).
- Runtime AWS services: VPC, EC2, RDS, ALB, ACM, Route53, S3, SSM Parameter
  Store, IAM, KMS (SSM key).
- The registered domain `unicoachapp.com` and its Route53 hosted zone (in
  place).
- Existing project scripts (`bin/db-*`, `bin/build`) and the `installDist`
  outputs, reused unchanged except for `bin/db-bootstrap`.

## Tests

Infrastructure provisioning itself cannot be exercised in the automated suite
(it requires live AWS, a domain, and spend); those paths are verified by
operator smoke tests post-`apply`. The automated tests cover the code and script
changes this RFC introduces.

### Automated

- **`db.conf` host substitution (db module).** This exercises HOCON resolution,
  not `DatabaseConfig.from` — the host is composed at config-load time (the
  `jdbcUrl` reaches `DatabaseConfig` already substituted), so the test resolves
  the real `db.conf` resource rather than the literal-`jdbcUrl` strings the
  existing `DatabaseConfigTest` cases use. Drive resolution with
  `ConfigFactory.parseMap(mapOf("POSTGRES_PORT" to "5432", "POSTGRES_DB" to
  "unicoach", …)).withFallback(ConfigFactory.parseResources("db.conf")).resolve()`,
  controlling `DATABASE_HOST` through that same map rather than the process
  environment (`${?DATABASE_HOST}` resolves from the map under `resolve()`): (a)
  with `DATABASE_HOST` absent from the map, assert the resolved
  `database.jdbcUrl` host is `localhost` (backward compatibility); (b) with
  `DATABASE_HOST` set to a sentinel (e.g. `rds.example`), assert the resolved
  `jdbcUrl` host is that sentinel while the port and database segments are
  unchanged.
- **`bin/db-create-role` idempotence (db scripts test).** Against the local test
  cluster: drop the app role if present; run `bin/db-create-role` and assert the
  role now exists with `LOGIN`; run it a second time and assert exit 0 with no
  error (duplicate swallowed).
- **`bin/db-bootstrap` delegation.** Assert `bin/db-bootstrap` still produces
  the app role on a fresh local cluster after the refactor (the existing
  bootstrap/role assertion in `bin/db-users-tests` continues to pass), proving
  delegation preserved behavior.
- **OpenTofu static validation.** `tofu -chdir=infra validate` and
  `tofu -chdir=infra fmt -check` pass; same for `infra/bootstrap`. These verify
  HCL well-formedness without contacting AWS.
- **Script lint.** `bash -n` (and `shellcheck` where available) over
  `bin/deploy`, `bin/db-create-role`, and the `infra/files/*.sh` scripts.

### Deployment verification (manual, post-apply)

- `curl -fsS https://<domain>/hello` returns 200 over a publicly trusted
  certificate.
- An SSM session to the instance shows both `systemd` units `active (running)`.
- `bin/db-status` on the instance (env pointed at RDS) reports all migrations
  applied.
- A registration + login round-trip against `https://<domain>/api/v1/auth/*`
  succeeds, confirming RDS connectivity through the app role.
- A coaching conversation streams tokens, confirming Anthropic egress through
  the internet gateway and that the ALB idle timeout does not sever the stream.

## Implementation Plan

Each step is atomic and locally verifiable. AWS `apply` and the live smoke tests
are operator actions (cost, domain, credentials) and are not part of the agent's
verifiable steps; the agent's verification stops at static validation and the
local test suite. All commands run inside the Nix dev shell.

0. **(Prerequisite, operator — complete) Domain registered.** `unicoachapp.com`
   is registered through Route53 Domains; its hosted zone is auto-created. The
   API is served at `api.unicoachapp.com`. Not a code change; recorded because
   every TLS/DNS resource references this zone.

1. **Parameterize the JDBC host.** Edit `db/src/main/resources/db.conf` to add
   `database.host` (default `localhost`, `${?DATABASE_HOST}` override) and
   recompose `jdbcUrl` from it.
   - Verify: `nix develop -c bin/test db --force` (DatabaseConfig host tests
     pass); `nix develop -c bin/build-rest-server` succeeds.

2. **Add `bin/db-create-role` and refactor `bin/db-bootstrap`.** Create the
   idempotent role script; change `bin/db-bootstrap` to delegate role creation
   to it after `initdb`.
   - Verify: `nix develop -c bin/db-users-tests` passes; running
     `nix develop -c bin/db-create-role` twice against the local cluster exits 0
     both times.

3. **Extend the dev shell.** Add `opentofu` and `awscli2` to `flake.nix`
   `packages`.
   - Verify: `nix develop -c tofu version`; `nix develop -c aws --version`.

4. **Ignore local state.** Add `.terraform/`, `*.tfstate`, `*.tfstate.*`,
   `*.tfplan`, and `terraform.tfvars` to `.gitignore`.
   - Verify: `git status --porcelain` shows no state artifacts after a later
     `tofu init`.

5. **State-backend bootstrap.** Author `infra/bootstrap/` (local state) creating
   the versioned, encrypted, access-blocked state bucket.
   - Verify: `tofu -chdir=infra/bootstrap fmt -check`;
     `tofu -chdir=infra/bootstrap validate`.

6. **Core infrastructure.** Author the `infra/` files in the resource-inventory
   table (`versions`, `backend`, `variables`, `network`, `security_groups`,
   `iam`, `s3`, `ssm`, `rds`, `ec2`, `alb`, `dns`, `outputs`).
   - Verify: `tofu -chdir=infra fmt -check`; `tofu -chdir=infra validate` (after
     `tofu -chdir=infra init -backend=false`).

7. **On-instance assets.** Author `infra/files/cloud-init.yaml`, the two
   `systemd` unit files, `infra/files/render-env.sh`, and
   `infra/files/deploy-on-instance.sh`.
   - Verify: `bash -n` over the two shell scripts; YAML well-formedness of
     `cloud-init.yaml` (`nix develop -c deno eval` with `jsr:@std/yaml`, per the
     project's YAML-validation convention).

8. **Deploy and infra wrapper scripts.** Author `bin/deploy` and the thin
   wrappers `bin/infra-init`, `bin/infra-plan`, `bin/infra-apply`,
   `bin/infra-output`, and `bin/infra-bootstrap`.
   - Verify: `bash -n` and `shellcheck` over each.

9. **Operator documentation.** Add a Deployment section to `README.md` covering
   the step-0 domain gate, the state-backend bootstrap via `bin/infra-bootstrap`
   then `bin/infra-init`/`bin/infra-apply`, secret seeding, and `bin/deploy`.
   Step 1 invokes the wrappers exclusively — no raw `tofu`.
   - Verify: `nix develop -c deno fmt --check README.md`.

## Files Modified

Created:

- `bin/db-create-role`
- `bin/deploy`
- `bin/infra-init`
- `bin/infra-plan`
- `bin/infra-apply`
- `bin/infra-output`
- `bin/infra-bootstrap`
- `infra/bootstrap/versions.tf`
- `infra/bootstrap/main.tf`
- `infra/versions.tf`
- `infra/backend.tf`
- `infra/variables.tf`
- `infra/network.tf`
- `infra/security_groups.tf`
- `infra/iam.tf`
- `infra/s3.tf`
- `infra/ssm.tf`
- `infra/rds.tf`
- `infra/ec2.tf`
- `infra/alb.tf`
- `infra/dns.tf`
- `infra/outputs.tf`
- `infra/terraform.tfvars.example`
- `infra/files/cloud-init.yaml`
- `infra/files/unicoach-rest-server.service`
- `infra/files/unicoach-queue-worker.service`
- `infra/files/render-env.sh`
- `infra/files/deploy-on-instance.sh`

Modified:

- `db/src/main/resources/db.conf` — parameterize the JDBC host.
- `bin/db-bootstrap` — delegate role creation to `bin/db-create-role`.
- `flake.nix` — add `opentofu` and `awscli2` to the dev shell.
- `.gitignore` — ignore OpenTofu state and plan artifacts.
- `README.md` — add the Deployment section.
- `db/src/test/kotlin/ed/unicoach/db/DatabaseConfigTest.kt` — add
  `DATABASE_HOST` override / default-host tests to the existing fixture.
- `bin/db-users-tests` — add the `bin/db-create-role` idempotence case.
