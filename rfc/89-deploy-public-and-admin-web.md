# RFC 89: Deploy public-web and admin-web

## Executive Summary

`public-web` and `admin-web` build and run locally but are absent from the AWS
deployment: the instance runs only `rest-server` and `queue-worker`, and the ALB
forwards all `:443` traffic to `rest-server` (`api.<domain>`). This RFC
co-locates the two web services on the existing instance and routes them by
host: `app.<app_domain>` → public-web, `admin.<app_domain>` → admin-web, with
`api.<app_domain>` → rest-server unchanged.

The mechanism is the multi-service extension of the RFC 87 topology, not a new
one. Two systemd units join the two existing ones on the same instance; the ALB
gains two target groups (`:8082`, `:8081`), two host-header listener rules on
the existing `:443` listener (the default action stays rest-server, so `api` is
untouched), a wildcard `*.<app_domain>` ACM certificate replacing the api-only
cert, and two Route53 ALIAS records. A single `local.web_services` map threads
each service's port into its target group, instance attachment, security-group
rules, and listener rule; the port value itself is sourced once in `.env`
(`PUBLIC_WEB_PORT`/`ADMIN_WEB_PORT`) and reaches both Terraform (via
`TF_VAR_public_web_port`/`TF_VAR_admin_web_port`, mirroring the existing
`app_domain` pattern) and the JVM (via SSM materialization), so the load
balancer's target port and the service's configured bind port cannot drift.

Public-web is served only at `app.<app_domain>` (not the apex), so the prod
email-verification link — today `https://<app_domain>/verify-email` — moves to
`https://app.<app_domain>/verify-email`. The web services bind `0.0.0.0` in
cloud (both default to loopback), and admin's session cookie is marked `Secure`.

No application code changes: the modules already serve `/healthz` and their
pages. The change is Terraform, host files (systemd units, cloud-init,
deploy-on-instance), `bin/deploy`, and prod config.

## Detailed Design

### 1. Current state

One EC2 instance runs `unicoach-rest-server` (`:8080`) and
`unicoach-queue-worker` (no port) as systemd units. `infra/alb.tf` defines one
target group (`aws_lb_target_group.app`, `:8080`) and one `:443` listener whose
`default_action` forwards to it; `infra/dns.tf` issues an ACM cert for
`local.api_domain` (`api.<app_domain>`) and an ALIAS `api.<app_domain>` → ALB.
`infra/security_groups.tf` opens exactly `:8080` between the ALB and the
instance. `bin/deploy` builds and bundles the `rest-server` and `queue-worker`
installDist trees; `deploy-on-instance.sh` restarts those two units.

`public-web` (`publicWeb.server`, default `127.0.0.1:8082`, `/healthz`, no auth
gate) and `admin-web` (`admin.web`, default `127.0.0.1:8081`, `/healthz`, behind
an email/password login + session-cookie gate) are Gradle `application` modules
built by `bin/build` but shipped nowhere.

`infra/ssm.tf` already carries `PUBLIC_WEB_PORT = "8082"` (solely to satisfy a
HOCON `${?VAR}` resolution) and `SERVER_HOST = "0.0.0.0"`, but no
`PUBLIC_WEB_HOST`, `ADMIN_WEB_HOST`, `ADMIN_WEB_PORT`, or `ADMIN_COOKIE_SECURE`.
`EMAIL_VERIFICATION_VERIFY_URL_BASE = "https://${var.app_domain}/verify-email"`
points at the apex.

### 2. Service map

One map declares the two host-routed web services co-located beyond the default
rest-server. It is the single source every derived resource reads.

```hcl
# infra/locals.tf (addition)
locals {
  # Host-routed web services on the app instance, beyond the default rest-server
  # (api, served by the listener's default_action). Each drives a target group,
  # instance attachment, security-group ingress/egress, ALB listener rule, and
  # Route53 ALIAS. port is var.public_web_port/var.admin_web_port (below), not a
  # Terraform-owned literal, so it cannot drift from the JVM's bind port.
  web_services = {
    public-web = { port = var.public_web_port, subdomain = "app", priority = 10 }
    admin-web  = { port = var.admin_web_port, subdomain = "admin", priority = 20 }
  }
}
```

`each.key` is the service (and systemd/dist) name; `port` is the instance bind
port and target-group port; `subdomain` yields the host
`"${subdomain}.${var.app_domain}"`; `priority` is the listener rule's unique
priority. The default rest-server (`api`) is not in the map — it remains the
listener's default action and keeps its existing standalone DNS record, so
adding these services touches nothing api serves.

`public_web_port`/`admin_web_port` are needed by both Terraform (the
target-group/security-group port above) and the JVM (`public-web.conf`'s /
`admin-web.conf`'s `${?PUBLIC_WEB_PORT}`/`${?ADMIN_WEB_PORT}` bind config) — the
`app_domain` case exactly, so per `CONFIGURATION.md`'s prime directive the value
lives in the shell environment, not as a Terraform-owned default. `.env` already
carries `PUBLIC_WEB_PORT=8082` (the local dev bind port); this RFC adds
`ADMIN_WEB_PORT=8081` beside it — the same value in every environment, so the
base file is the only home (no `.env.prod` override). `infra/variables.tf` gains
`variable "public_web_port"`/ `variable "admin_web_port"` (no default, mirroring
`app_domain`); `bin/infra-apply`/`bin/infra-plan` export
`TF_VAR_public_web_port`/ `TF_VAR_admin_web_port` from the sourced dotenv,
alongside the existing `TF_VAR_app_domain` line.

### 3. ALB routing

`infra/alb.tf` gains three `for_each = local.web_services` resources and leaves
the `:443` listener's `default_action` (→ `aws_lb_target_group.app`, api)
intact.

- `aws_lb_target_group.web[*]` — name `"${local.name_prefix}-${each.key}"`, port
  `each.value.port`, protocol HTTP, health check `GET /healthz` matcher `200`
  (identical to the api target group; both web modules already serve it).
- `aws_lb_target_group_attachment.web[*]` — attaches `aws_instance.app.id` on
  `each.value.port`.
- `aws_lb_listener_rule.web[*]` — on `aws_lb_listener.https`, priority
  `each.value.priority`, condition
  `host_header = ["${each.value.subdomain}.${var.app_domain}"]`, action forward
  to `aws_lb_target_group.web[each.key]`.

The listener's single wildcard certificate (§4) covers every SNI host, so no
`aws_lb_listener_certificate` is needed. The `:80`→`:443` redirect listener is
unchanged. An unmatched host (including a direct ALB-DNS hit) falls to the
default action and reaches rest-server, preserving today's behavior.

### 4. Certificate and DNS

`infra/dns.tf` replaces the api-only certificate with a wildcard covering every
one-level host under the app domain (`api`, `app`, `admin`, and any future
sibling), and adds the two web ALIAS records.

- `aws_acm_certificate.wildcard` — `domain_name = "*.${var.app_domain}"`,
  `validation_method = "DNS"`, `create_before_destroy = true` (renamed from
  `.api`; changing `domain_name` forces replacement, which the lifecycle
  handles). The existing `for_each` over `domain_validation_options` and
  `aws_acm_certificate_validation` follow the rename unchanged — a wildcard
  emits a single validation record, which the loop already handles generically.
- `aws_route53_record.api` — unchanged (ALIAS `api.<app_domain>` → ALB).
- `aws_route53_record.web[*]` — `for_each = local.web_services`, ALIAS
  `"${each.value.subdomain}.${var.app_domain}"` → ALB.

For a subdomain-per-env (RFC 87), `app_domain = staging.uni.coach` yields
`*.staging.uni.coach`, which covers `api/app/admin.staging.uni.coach` — one
level each — so the wildcard generalizes with no per-env special case.

`alb.tf`'s listener `certificate_arn` reference updates from
`aws_acm_certificate_validation.api` to `.wildcard`.

### 5. Security groups

`infra/security_groups.tf` opens the two new ports between the ALB and the
instance, leaving the `:8080` rules untouched (no api-path disruption on apply).

- `aws_vpc_security_group_ingress_rule.ec2_from_alb_web[*]` —
  `for_each = local.web_services`, on the ec2 SG,
  `from/to_port = each.value.port`, `referenced_security_group_id = alb`.
- `aws_vpc_security_group_egress_rule.alb_to_ec2_web[*]` — the mirror on the ALB
  SG toward the ec2 SG on `each.value.port`.

The instance's all-egress and RDS's `:5432`-from-ec2 rules are unchanged; both
web services reach RDS over the existing path.

### 6. Host runtime: systemd, cloud-init, deploy

Two systemd units mirror `unicoach-rest-server.service`, differing only in
description and `ExecStart`:

- `infra/files/unicoach-public-web.service` —
  `ExecStart=/opt/unicoach/current/public-web/bin/public-web`.
- `infra/files/unicoach-admin-web.service` —
  `ExecStart=/opt/unicoach/current/admin-web/bin/admin-web`.

Both use `Type=simple`, `User=unicoach`, `EnvironmentFile=/etc/unicoach/env`,
`Restart=on-failure`, `WantedBy=multi-user.target` — the established pattern.

`infra/files/cloud-init.yaml` gains a `write_files` entry for each unit (base64
via `filebase64`, mirroring the existing two) and a `systemctl enable` line for
each in `runcmd`. Like the existing units they stay inactive until the first
deploy creates the `current` symlink.

`infra/files/deploy-on-instance.sh` adds
`systemctl restart unicoach-public-web.service` and `unicoach-admin-web.service`
after the atomic symlink swap, alongside the two existing restarts.

`bin/deploy` adds `public-web` and `admin-web` to the built distributions it
bundles: their installDist trees (`<m>/build/install/<m>`, already produced by
the `bin/build` it invokes) join the `REST_DIST`/`WORKER_DIST` existence guard
and the `tar -C` set, entering the bundle as bare `public-web/` and `admin-web/`
— the paths the new units expect at `current/<svc>/bin/<svc>`.

### 7. Configuration

Prod config additions and one change, all in `infra/ssm.tf`'s
`ssm_string_params` (the placement-rule home for JVM values overridden per-env,
per RFC 87). Each web service reads `/etc/unicoach/env` like the existing
services; every `${VAR}` its packaged `.conf` files require already resolves
from the shared prefix.

| SSM key                              | Value                                        | Reason                                                                                                        |
| ------------------------------------ | -------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `PUBLIC_WEB_HOST`                    | `0.0.0.0`                                    | public-web defaults to loopback; the ALB reaches the private IP                                               |
| `ADMIN_WEB_HOST`                     | `0.0.0.0`                                    | admin-web defaults to loopback                                                                                |
| `ADMIN_WEB_PORT`                     | `tostring(var.admin_web_port)`               | sourced from `.env` (§2), mirroring `APP_DOMAIN`; also drives the admin target group via `local.web_services` |
| `ADMIN_COOKIE_SECURE`                | `true`                                       | admin session cookie over the public HTTPS ALB                                                                |
| `PUBLIC_WEB_PORT`                    | `tostring(var.public_web_port)`              | was literal `"8082"`; now sourced from `.env` (§2) — no Terraform-owned default, no drift                     |
| `EMAIL_VERIFICATION_VERIFY_URL_BASE` | `https://app.${var.app_domain}/verify-email` | public-web is served only at `app.<domain>`; the apex is not routed                                           |

The `PUBLIC_WEB_PORT` comment (which today explains it is resolution-only) is
rewritten to state it is the deployed service's bind port, sourced from `.env`
like `APP_DOMAIN` (§2) rather than a Terraform-owned default. Admin's cookie
domain stays host-only (empty), scoping the admin session to `admin.<domain>`;
it is not widened. `PUBLIC_WEB_OPEN_IN_APP_URL` (the optional iPhone deep link
on the verify-success page) is left unset — out of scope; unset yields no link.

`infra/outputs.tf` adds `public_web_url` (`https://app.${var.app_domain}`) and
`admin_web_url` (`https://admin.${var.app_domain}`) for the operator deploy
gate, mirroring `app_url`.

`.env.prod`'s derivation comment (which lists
`verify base = https://$APP_DOMAIN/verify-email` and calls `APP_DOMAIN` the web
host) is corrected: the verify base and public web host are now
`app.$APP_DOMAIN`. `APP_DOMAIN` itself is unchanged (`uni.coach`); it remains
the zone/apex and the api/session-cookie root.

### 8. Error handling, edge cases, dependencies

- **Loopback bind:** without `*_WEB_HOST=0.0.0.0` the service binds `127.0.0.1`
  and the ALB health check fails the target — closed by §7.
- **Port drift:** target-group port (`local.web_services`) and configured bind
  port (`infra/ssm.tf`) both trace to the same `var.public_web_port`/
  `var.admin_web_port`, sourced once in `.env`, so they cannot disagree.
- **api regression:** the listener default action and the `:8080` SG rules are
  untouched; `app`/`admin` are additive.
- **Stale verify link:** moving the base to `app.<domain>` keeps `/verify-email`
  reachable now that the apex is not routed.
- **Admin exposure:** admin-web is reachable from the internet, gated by its
  existing login/session check with `Secure` cookies (decided: login gate only,
  no IP allowlist).
- **Cert replacement:** the wildcard rename forces one cert re-issue;
  `create_before_destroy` avoids a gap.
- **Dependencies:** an existing Route53 zone and (for cert issuance) DNS
  validation — both already required by RFC 87. No new libraries, no iOS
  changes, no application code changes.

## Tests

No automated substitute exists for a real deploy: whether the ALB routes each
host to the right service, the units start, and the rendered env binds `0.0.0.0`
can only be confirmed by deploying. End-to-end correctness is the **operator
deploy gate**. Automated coverage is the cheap checks below.

**Automated (mandatory):**

- `tofu -chdir=infra validate` with `environment`/`app_domain`/
  `google_client_ids`/`public_web_port`/`admin_web_port` supplied — catches
  HCL/type errors across the new target groups, listener rules, wildcard cert,
  SG rules, DNS records, `local.web_services`, and the `tostring(...)` SSM
  references.
- `nix develop -c bin/test -f` green (confirm "N executed"). This RFC changes no
  committed `.conf` dev default and no application code, so the suite is a
  regression guard, not a target; it must stay green.

**Manual fast-fail smoke (no AWS) — run once:**

- `nix develop -c bin/build` then assert
  `public-web/build/install/public-web/bin/public-web` and
  `admin-web/build/install/admin-web/bin/admin-web` exist and are executable —
  proves `bin/deploy`'s new bundle inputs are produced.

**Operator deploy gate (requires AWS — the real test):**

1. `bin/infra-apply prod` — creates the two target groups, listener rules,
   wildcard cert, SG rules, and `app`/`admin` ALIAS records;
   `bin/infra-plan
   prod` then reports _No changes_.
2. `bin/deploy prod` — bundles and ships all four distributions; the two new
   units start.
3. `https://app.uni.coach/healthz` → `200` and `https://app.uni.coach/` → the
   brand page.
4. `https://admin.uni.coach/healthz` → `200` and `https://admin.uni.coach/` →
   the login page (gate intact); a valid admin login succeeds over HTTPS
   (`Secure` cookie set).
5. `https://api.uni.coach/healthz` → `200` (regression: the default action still
   serves rest-server).
6. A real verification email's `https://app.uni.coach/verify-email?token=…` link
   renders the confirm page and verifies end-to-end.

## Invariants

None.

## Implementation Plan

Terraform and host changes only; the suite stays green throughout because no
committed dev default or application code changes. Run dev-shell commands via
`nix develop -c`.

1. **Port variables + service map.** Add `ADMIN_WEB_PORT=8081` next to the
   existing `PUBLIC_WEB_PORT=8082` in `.env`; add
   `variable "public_web_port"`/`variable "admin_web_port"` (no default) to
   `infra/variables.tf`; export `TF_VAR_public_web_port`/
   `TF_VAR_admin_web_port` in `bin/infra-apply` and `bin/infra-plan` alongside
   the existing `TF_VAR_app_domain` line; add `local.web_services` to
   `infra/locals.tf`, with `port` as `var.public_web_port`/`var.admin_web_port`.
   _Verify:_ `tofu -chdir=infra validate` (with the five required vars);
   `nix develop -c bash -c 'set -a; source .env; set +a; echo "$PUBLIC_WEB_PORT
   $ADMIN_WEB_PORT"'`
   prints both values (`.env` sources cleanly with the new var present —
   `bin/format -c` does not cover `.env`/shell scripts, only Kotlin and
   Markdown).

2. **Certificate + DNS.** In `infra/dns.tf`, rename
   `aws_acm_certificate.api`/`aws_acm_certificate_validation.api` → `.wildcard`
   with `domain_name = "*.${var.app_domain}"`; add `aws_route53_record.web`
   (`for_each = local.web_services`). _Verify:_ `tofu
   validate`.

3. **ALB target groups + rules.** In `infra/alb.tf`, add
   `aws_lb_target_group.web`, `aws_lb_target_group_attachment.web`,
   `aws_lb_listener_rule.web` (all `for_each = local.web_services`); repoint the
   `:443` listener `certificate_arn` to `.wildcard`. _Verify:_ `tofu validate`.

4. **Security groups.** In `infra/security_groups.tf`, add `ec2_from_alb_web`
   and `alb_to_ec2_web` (`for_each = local.web_services`). _Verify:_
   `tofu validate`.

5. **SSM + outputs.** In `infra/ssm.tf`, add `PUBLIC_WEB_HOST`,
   `ADMIN_WEB_HOST`, `ADMIN_COOKIE_SECURE`; set `PUBLIC_WEB_PORT`/
   `ADMIN_WEB_PORT` to `tostring(var.public_web_port)`/
   `tostring(var.admin_web_port)` (rewrite the `PUBLIC_WEB_PORT` comment — no
   longer literal, no longer derived from `local.web_services`); change
   `EMAIL_VERIFICATION_VERIFY_URL_BASE` to `app.${var.app_domain}`. In
   `infra/outputs.tf`, add `public_web_url`, `admin_web_url`. _Verify:_
   `tofu validate`.

6. **Systemd units.** Add `infra/files/unicoach-public-web.service` and
   `infra/files/unicoach-admin-web.service`. _Verify:_ file presence; validated
   on the host at the deploy gate.

7. **Cloud-init + on-instance restart.** In `infra/files/cloud-init.yaml`, write
   and `enable` both units; in `infra/files/deploy-on-instance.sh`, restart both
   after the symlink swap. _Verify:_ `tofu validate` (templatefile inputs
   unchanged); exercised at the deploy gate.

8. **Deploy bundle.** In `bin/deploy`, add the `public-web`/`admin-web`
   installDist trees to the existence guard and the `tar -C` set. _Verify:_
   `nix develop -c bin/build`, then confirm both `.../bin/<svc>` launch scripts
   exist.

9. **Docs/config comment.** Correct `.env.prod`'s derivation comment (verify
   base / web host → `app.$APP_DOMAIN`); add a public/admin subdomain subsection
   to `README.md`'s deployment section (three hosts, co-located services,
   host-header routing). _Verify:_
   `nix develop -c deno fmt --check
   .env.prod README.md` (and
   `bin/format -c`).

10. **Full gate.** `nix develop -c bin/test check` green.

11. **Operator deploy (after merge, requires AWS).** `bin/infra-apply prod`,
    `bin/deploy prod`; run the deploy-gate checks (§Tests); confirm
    `bin/infra-plan prod` → _No changes_.

## Files Modified

- `.env` — add `ADMIN_WEB_PORT=8081` next to the existing
  `PUBLIC_WEB_PORT=8082`.
- `infra/variables.tf` — add `variable "public_web_port"`/
  `variable "admin_web_port"` (no default, mirroring `app_domain`).
- `bin/infra-apply` — export `TF_VAR_public_web_port`/`TF_VAR_admin_web_port`
  alongside the existing `TF_VAR_app_domain` line.
- `bin/infra-plan` — same export addition as `bin/infra-apply`.
- `infra/locals.tf` — add `local.web_services` (ports
  `var.public_web_port`/`var.admin_web_port`).
- `infra/dns.tf` — api-only cert → wildcard `*.${var.app_domain}` (rename `.api`
  → `.wildcard`); add `aws_route53_record.web` (`for_each`).
- `infra/alb.tf` — add `aws_lb_target_group.web`,
  `aws_lb_target_group_attachment.web`, `aws_lb_listener_rule.web` (`for_each`);
  repoint the `:443` listener `certificate_arn` to the wildcard cert. Default
  action and the api target group unchanged.
- `infra/security_groups.tf` — add ec2-ingress and alb-egress rules for the web
  ports (`for_each`); `:8080` rules unchanged.
- `infra/ssm.tf` — add `PUBLIC_WEB_HOST`, `ADMIN_WEB_HOST`,
  `ADMIN_COOKIE_SECURE`; `PUBLIC_WEB_PORT`/`ADMIN_WEB_PORT` set to
  `tostring(var.public_web_port)`/`tostring(var.admin_web_port)` (rewrite the
  `PUBLIC_WEB_PORT` comment); `EMAIL_VERIFICATION_VERIFY_URL_BASE` →
  `app.${var.app_domain}`.
- `infra/outputs.tf` — add `public_web_url`, `admin_web_url`.
- `infra/files/unicoach-public-web.service` — NEW systemd unit.
- `infra/files/unicoach-admin-web.service` — NEW systemd unit.
- `infra/files/cloud-init.yaml` — write and enable both new units.
- `infra/files/deploy-on-instance.sh` — restart both new units after the symlink
  swap.
- `bin/deploy` — bundle the `public-web`/`admin-web` installDist trees (guard +
  `tar -C`).
- `.env.prod` — correct the derivation comment (verify base / web host →
  `app.$APP_DOMAIN`); no value change.
- `README.md` — document the `app`/`admin` subdomains, co-located services, and
  host-header routing.
