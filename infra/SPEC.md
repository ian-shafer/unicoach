# `infra/` — AWS Deployment Topology

## I. Overview

**Domain:** the multi-environment, code-defined AWS deployment of the
`rest-server` and `queue-worker` JVM processes against managed PostgreSQL,
reachable by the iOS client over TLS. Each environment (e.g. `prod`, `staging`)
is a fully isolated stack — separate resource names, SSM prefix, state key, and
hosted zone — instantiated by authoring a single `.env.<env>` file and running
`bin/infra-apply`.

This directory is the **sole source of resource definitions**. It holds three
distinct concerns, all covered by this one spec:

- **Root configuration** (`*.tf`) — the live topology (VPC, ALB, EC2, RDS, ACM,
  Route53, S3, SSM, IAM), authored in HCL and applied with **OpenTofu** against
  a remote S3 backend.
- **`bootstrap/`** — a separate, minimal OpenTofu configuration with **local
  state** whose only job is to create the remote-state bucket the root
  configuration then uses.
- **`files/`** — host-side runtime assets (cloud-init, `systemd` units, the
  render-env and deploy-on-instance scripts) injected verbatim into the instance
  by the root configuration's `ec2.tf`.

`files/` and `bootstrap/` do **not** carry their own `SPEC.md`: `files/` is
templated into the host by the root config and shares the SSM-prefix and
`/opt/unicoach`-layout contracts with it, and `bootstrap/`'s entire durable
content is the single separation invariant below.

Operator entry points (`bin/deploy`, `bin/infra-*`) live under `bin/`, not here,
and carry **no** resource logic; they are out of scope for this spec except
where `files/deploy-on-instance.sh` defines the on-host half of a deploy.

## II. Invariants (the guardrails)

### Network & exposure

- Traffic MUST follow a single linear path **internet → `alb` → `ec2` → `rds`**.
  Each security group admits ingress **only** from the prior tier:
  - `ec2` ingress is **only** 8080 from the `alb` group. No rule MUST ever open
    SSH (22) or any other port.
  - `rds` ingress is **only** 5432 from the `ec2` group, and `rds` MUST have
    **no egress** rules.
- There MUST be **no NAT gateway**. The instance reaches the internet directly
  through the internet gateway via its own public IP; the private route table
  carries only the implicit local route.
- The instance's public IP MUST NOT widen its exposure: it exists solely for
  outbound egress (Anthropic, SES, package installs), gated by the `ec2` ingress
  rule above.

### Administration

- All administration and deployment MUST flow through **AWS Systems Manager**
  (Session Manager + Run Command). The host MUST NOT be reachable by SSH.
- The instance IAM role MUST be least-privilege: SSM managed core, parameter
  read scoped to the env's SSM prefix (`arn:…:parameter/unicoach/<env>` and
  `…/unicoach/<env>/*`), `kms:Decrypt` on the SSM key, `ses:SendEmail` /
  `ses:SendRawEmail` for transactional email, and read-only access to the
  artifacts bucket. It MUST NOT grant write to SSM parameters or broad S3
  access.

### DNS & TLS

- The parent hosted zone (`hosted_zone_name`) MUST be referenced by **data
  source**, never created or managed — a managed zone resource would conflict
  with the one Route53 Domains auto-creates. The ACM certificate, its DNS
  validation records, and the API ALIAS record ARE OpenTofu-managed.
- TLS MUST terminate at the ALB. The HTTP:80 listener MUST redirect to HTTPS:443
  (no plaintext app traffic reaches the instance).

### Email sending (SES)

- The app sends transactional email (account verification) from
  `noreply@<app_domain>` with `EMAIL_PROVIDER=ses`; the `ses:SendEmail` grant is
  in `iam.tf`. SES refuses mail until the **sending domain is a verified
  identity**, so `ses.tf` codifies the domain identity (`var.app_domain`) plus
  its verification DNS: Easy DKIM (three CNAME tokens) and a custom MAIL FROM
  subdomain `mail.<app_domain>` (MX + SPF TXT), all published into
  `data.aws_route53_zone.main`. Every environment provisions its own identity in
  the same apply — SES verification is **not** a manual out-of-band step.
- A live apex may already run inbound mail (prod's `uni.coach` apex is Google
  Workspace); SES only adds the DKIM CNAMEs and the isolated `mail.` MAIL FROM
  subdomain, so it MUST NOT touch the apex `MX`/`TXT`.
- **Two account-level steps are not expressible in HCL and are manual runbook
  actions when standing up an environment:**
  1. **Exit the SES sandbox.** A fresh account is sandboxed (send only to
     verified recipients, 200/day). Request production access via
     `aws sesv2 put-account-details --production-access-enabled --mail-type
     TRANSACTIONAL --website-url https://<app_domain> …`.
     AWS may auto-deny the first request; if so, reply to the opened Support
     case (the `CaseId` under `get-account`'s `Details.ReviewDetails`) with
     fuller justification. Until production access is granted, real users cannot
     receive mail.
  2. **Verify test recipients while sandboxed.** To send a test before sandbox
     exit, verify the recipient with
     `aws sesv2 create-email-identity --email-identity <you@example.com>` and
     click the AWS confirmation link. This is a testing aid, not infrastructure
     — do not codify recipient identities.
- Verification and delivery are confirmed with
  `aws sesv2 get-email-identity --email-identity <app_domain>` (expect
  `VerifiedForSendingStatus: true`, `DkimAttributes.Status: SUCCESS`,
  `MailFromAttributes.MailFromDomainStatus: SUCCESS`) then an end-to-end
  `aws sesv2 send-email` from `noreply@<app_domain>` to a verified recipient.

### Secrets & database identity

- The RDS master password MUST be generated by OpenTofu and written straight to
  an SSM SecureString; no human handles it. It MUST NOT be exposed as an output.
- Operator-seeded SecureStrings (`DATABASE_PASSWORD`, `CHAT_ANTHROPIC_API_KEY`)
  MUST be created with a placeholder value **and** `ignore_changes` on `value`,
  so subsequent applies never revert the real secret seeded out-of-band.
- The application MUST connect as the less-privileged `app_db_user` login role
  (created on the instance by `bin/db-create-role`), never as the RDS master
  role. The master role is used only for migration `psql` steps.

### State

- Remote state MUST live in an encrypted, versioned, private S3 bucket using S3
  **native lockfile** locking (`use_lockfile`); there MUST be no DynamoDB lock
  table.
- `bootstrap/` MUST keep **local state** and create that bucket. It MUST NOT
  adopt the S3 backend — doing so reintroduces the chicken-and-egg the bootstrap
  exists to resolve. It is applied once, before the root config's `init`.

### Host layout & release lifecycle

- `files/cloud-init.yaml` MUST be the **only** place that encodes the host
  layout. The `systemd` units and on-host scripts MUST be injected verbatim from
  the committed `files/*` sources via `filebase64`, never duplicated inline.
- A deploy MUST repoint `/opt/unicoach/current` and restart the units **only
  after** migrations (`db-create-role` → `db-create` → `db-migrate`) succeed, so
  a failed migration leaves the previous release serving.
- `render-env` MUST write `/etc/unicoach/env` as `root`-owned, mode `0600`, and
  MUST fail (non-zero) if the SSM prefix yields no parameters.

### Engine & runtime

- The RDS engine version MUST track the dev/test PostgreSQL **major** (`18`),
  declared as a variable carrying the major only (RDS selects the minor).
- The deployment MUST run the unmodified Gradle `installDist` distributions
  under `systemd`. There MUST be **no Docker** and **no CI**; deployment is
  operator-invoked.

## III. Behavioral Contracts

### The per-env SSM prefix — the OpenTofu ↔ host seam

`locals.tf` owns the SSM prefix (`/unicoach/<env>`, derived from
`var.environment`); `ssm.tf` writes every runtime key under it.
`files/render-env.sh` fetches the **whole** prefix (with decryption, recursive)
and flattens it to `/etc/unicoach/env` as `KEY=VALUE` (leaf name after the
prefix). That single file is consumed two ways: as the `systemd`
`EnvironmentFile` for both units, and as `ENV_FILE` sourced by `bin/common`
during migrations. The contract is the **ownership split**, not the literal key
list:

| Class                                         | Owner                              | Reverted by apply?        |
| --------------------------------------------- | ---------------------------------- | ------------------------- |
| Non-secret `String` params                    | OpenTofu (`ssm.tf`)                | Yes (authoritative)       |
| `PGPASSWORD` SecureString                     | OpenTofu (generated RDS master pw) | Yes                       |
| `DATABASE_PASSWORD`, `CHAT_ANTHROPIC_API_KEY` | Operator (out-of-band)             | **No** (`ignore_changes`) |

`SSM_PREFIX` is written to the host by cloud-init (from the OpenTofu-templated
per-env value) and has **no default**: `render-env` exits fatally if it is unset
or if the fetched prefix is empty. It additionally refuses to render if any
out-of-band secret still holds the `PLACEHOLDER_SEED_OUT_OF_BAND` value,
preventing a host from booting with an unseeded credential.

- **Side effects:** `render-env` and `deploy-on-instance` call the AWS CLI
  (`ssm get-parameters-by-path`, `s3 cp`) on the host; they read SSM and S3,
  write only local files under `/opt/unicoach` and `/etc/unicoach`.
- **Idempotency:** `render-env` is fully idempotent (rewrites the env file).
  `deploy-on-instance` creates a fresh timestamped release dir per run; the
  symlink swap is atomic (`ln -sfn`).

### Renaming a stack (`name_prefix` migration)

Every resource name and tag derives from `local.name_prefix` (`unicoach-<env>`),
so changing `var.environment` — or otherwise moving `name_prefix` — re-plans the
whole stack. A `tofu plan` for such a rename (the shape of the one-time prod
`unicoach` → `unicoach-prod` recreate) splits the resources into two classes,
not a uniform recreate:

- **Rename in place** (attribute/tag update, resource retained): the VPC, the
  four subnets, the internet gateway, both route tables, the RDS instance, the
  EC2 instance, and the Route53 API alias record. These carry the identity only
  as a mutable handle — the `Name` **tag** for the VPC, subnets, internet
  gateway, and route tables; an attribute the AWS API mutates in place for the
  rest. RDS renames via `ModifyDBInstance` (new `identifier`, and it
  re-associates the freshly-named DB subnet group). The EC2 instance updates its
  `Name` tag and its `iam_instance_profile` / `vpc_security_group_ids`
  references. The API record retargets its ALIAS to the replacement ALB's new
  DNS name.
- **Replace** (destroy + create): the ALB and its two listeners, the target
  group and its attachment, the three security groups and their six rules, the
  IAM role / inline policy / managed-policy attachment / instance profile, the
  S3 artifacts bucket and its four sub-configurations (versioning, encryption,
  public-access block, lifecycle), and the DB subnet group. Each of these keys
  `name_prefix` into an argument the AWS API treats as immutable (`aws_lb.name`,
  `aws_lb_target_group.name`, `aws_security_group.name`, `aws_iam_role.name` /
  `aws_iam_role_policy.name` / `aws_iam_instance_profile.name`,
  `aws_s3_bucket.bucket`, `aws_db_subnet_group.name`), so a changed name forces
  replacement; the dependent listeners, security-group rules, target-group
  attachment, managed-policy attachment, and bucket sub-configs replace as a
  knock-on. For the concrete prod rename that split is 24 resources replaced and
  11 renamed in place.

Because the SSM prefix and state key key off `var.environment` — not
`name_prefix` — an env keeping the same `environment` id across a rename keeps
its state and its operator-seeded SecureStrings; only the AWS resources above
move. `bin/deploy` then re-creates the app DB role and migrates the (empty)
database against the renamed RDS instance.

### Health & ingress

- The ALB target group health check is `GET /healthz` expecting `200` — the same
  path `bin/rest-server-check` validates.
- The ALB idle timeout is raised to **300s** so proxied SSE chat streams are not
  severed by the default 60s cutoff. Generations exceeding 300s still fail.

### Error handling / edge cases

- **Hosted zone unresolved** (registration incomplete) → the zone data source
  resolves nothing and `dns.tf` apply **fails closed**; no partial TLS state.
- **Unseeded secret still at placeholder** → `render-env` exits fatally before
  writing `/etc/unicoach/env`, so the JVM never starts with a bogus credential;
  the failure surfaces in the SSM Run Command output.
- **Absent `PGPASSWORD`** (RDS rejects libpq trust auth) → migrations fail
  before the symlink swap, leaving the prior release serving.

## IV. Infrastructure & Environment

- **Engine:** OpenTofu (not HashiCorp Terraform — its BSL license would force
  `allowUnfree` into the Nix flake). `required_version >= 1.6`; providers
  `hashicorp/aws ~> 5.0` and `hashicorp/random ~> 3.6`. All `tofu` invocations
  run inside the Nix dev shell (`opentofu`, `awscli2` provided there).
- **Environment parameterization:** `var.environment` (required, no default) is
  the single identity token. `locals.tf` owns `name_prefix` (`unicoach-<env>`),
  `ssm_prefix` (`/unicoach/<env>`), `api_domain` (`api.<app_domain>`), and
  `hosted_zone_name` (coalesced from `var.hosted_zone_name` or
  `var.app_domain`). All resource names, tags, IAM scope, and SSM paths derive
  from these locals. The RDS `db_name` and master `username` remain literal
  (`unicoach` / `unicoach_admin`) — Postgres identifiers disallow hyphens, and
  each env has its own isolated RDS instance.
- **Backend:** S3 bucket `unicoach-tofu-state`, `us-east-1`, encrypted,
  `use_lockfile`. The state key (`unicoach/<env>/terraform.tfstate`) is a
  partial backend — supplied at `tofu init` time by `bin/infra-*` via
  `-backend-config="key=…"` so each environment's state is disjoint and no env
  is a silent default. Created by `bootstrap/` (local state).
- **Region:** `us-east-1` default (variable).
- **Account:** each cloud env targets its own AWS account, named by
  `AWS_ACCOUNT_ID` in that env's `.env.<env>`. The operator entry points
  `bin/infra-plan`, `bin/infra-apply`, and `bin/deploy` assert the active
  credentials resolve to the selected env's account
  (`aws sts
  get-caller-identity`) before any `tofu`/`aws` action, so a stray
  ambient AWS profile fails fast rather than targeting the wrong account. (These
  scripts pin no profile; the guard turns a wrong ambient profile into a loud
  failure instead of a silent misfire.)
- **Compute:** Amazon Linux 2023 ARM (Graviton) AMI via `most_recent` data
  source; SSM agent preinstalled. cloud-init installs Amazon Corretto 21 and the
  PostgreSQL client, creates the `unicoach` system user (no login shell) and the
  `/opt/unicoach` layout, and enables both units (inactive until first deploy).
- **Database:** RDS PostgreSQL, single-AZ, gp3, storage-encrypted, not publicly
  accessible, in the private subnet group.
- **Gitignored, never committed:** `.terraform/`, `*.tfstate*`, `*.tfplan`,
  `terraform.tfvars`. `terraform.tfvars.example` is the committed template of
  every `variables.tf` knob.

## V. History

- [x] [RFC-050: Deploy the Backend REST API to AWS](../rfc/50-deploy-rest-api-aws.md)
- [x] [RFC-059: Named iOS build targets](../rfc/59-ios-build-targets.md)
- [x] [RFC-087: Multi-environment config and deploy](../rfc/87-multi-environment-config-and-deploy.md)
