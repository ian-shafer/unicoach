# RFC 92: Ops Tool Runner

## Executive Summary

`bin/ingest-colleges` loads the College Scorecard CSV pair into `colleges`/
`college_programs` (RFC 67, RFC 78), but only runs locally: RDS accepts
connections only from the EC2 instance's security group, and the two CSVs are
multi-MB, uncommitted files an operator holds locally. There is no way to run it
— or any future admin/ops `bin/` tool — against prod.

This RFC generalizes `bin/deploy` into the sole artifact-delivery path for ops
tools, not just the two long-running services: it now also builds
`:college:installDist` and bundles it, plus the tool's own `bin/` script, into
the same release tarball already shipped to `/opt/unicoach/current`. A new
`bin/remote <env> -f name:path... -- <script> [args]` triggers an
already-deployed script via `aws ssm send-command` against the AWS-owned
`AWS-RunShellScript` document — the same primitive `bin/deploy` already uses —
so there is no new SSM document and no per-run code bundle. `bin/remote` uploads
each declared input file to the existing artifacts bucket, substitutes its S3
URI wherever the trailing args reference it by name (`@name`), and deletes every
object it uploaded once the run completes.

The tool itself, `bin/ingest-colleges`, becomes the same script whether a
developer runs it locally or `bin/remote` triggers it remotely. Because
`bin/deploy` strips the `build/install/` prefix when it tars a dist, the
launcher lives at `college/build/install/college/bin/college` in the local dev
tree but at `college/bin/college` under `/opt/unicoach/current` on the instance;
the single script resolves that split through a new shared `resolve_launcher`
(mirroring how the two service `-up` scripts hardcode the local path while
systemd runs the deployed path). It runs the resulting prebuilt launcher (never
Gradle) and resolves each file argument through a new shared `resolve_file_arg`,
accepting either a local path or an `s3://` URI. Credentials need no new
plumbing — the existing `ENV_FILE` convention (`bin/common`) already points a
script at `/etc/unicoach/env` when it runs on the instance, exactly as
`deploy-on-instance.sh` already relies on for the migration scripts.

Four `bin/INVARIANTS.md` rules codify the conventions this design depends on;
one of them (a pre-built-launcher rule) generalizes and replaces the existing
daemon-`-up` "boot a pre-built binary or fatal" rule rather than sitting
alongside it.

## Detailed Design

### Data Models

No database schema changes. One new Terraform resource, in a new `infra/ops.tf`:

- **`aws_cloudwatch_log_group.ops_run`** —
  `/unicoach/${var.environment}/ops-run`, `retention_in_days = 30` (matches the
  artifacts bucket's existing 30-day expiration). Target of `bin/remote`'s
  `--cloud-watch-output-config`.

`infra/iam.tf`'s existing `data.aws_iam_policy_document.instance` gains one
statement — `logs:CreateLogGroup`, `logs:CreateLogStream`, `logs:PutLogEvents`,
`logs:DescribeLogGroups`, scoped to that log group's ARN.
`AmazonSSMManagedInstanceCore` (already attached) covers command execution but
not CloudWatch output delivery.

`infra/outputs.tf` gains `ops_log_group_name`, read the same way
`artifacts_bucket`/`instance_id` already are — `bin/remote` reads it rather than
reconstructing the naming convention in bash.

No new S3 bucket: input files land under a new `ops-inputs/` prefix in the
existing artifacts bucket, already covered by its bucket-wide 30-day lifecycle
rule and by the instance role's existing `ReadArtifacts` statement (no IAM
change needed for reads). No new SSM document: `AWS-RunShellScript` is AWS-owned
and already in use by `bin/deploy`.

### API Contracts

**`bin/build-college`** (new) — mirrors `bin/build-admin-web`:
`gradlew :college:installDist`. `college` joins `bin/build`'s `MODULES` array.

**`bin/deploy`** (extended) — gains a third dist alongside `REST_DIST`/
`WORKER_DIST`: `COLLEGE_DIST="college/build/install/college"`, tarred into the
same bundle the same way. `REPO_PATHS` gains `bin/ingest-colleges` (and any
future ops tool's own script) — `bin/common`/`bin/functions` are already there.
`deploy-on-instance.sh` is unchanged: it already unpacks the whole tar
generically into the release directory `current` is symlinked to.

**`resolve_file_arg`** (new, `bin/functions`) — used by any ops-tool script that
takes file arguments:

```
resolve_file_arg <path-or-s3-uri>
```

Resolves a file argument to an absolute local path, accepting either a local
path or an `s3://` URI. An `s3://` argument is downloaded into a lazily-created
scratch directory (one per script invocation, `mktemp -d`), preserving the
object's basename; an `EXIT` trap (registered once) removes that directory on
exit. Anything else is resolved via `realpath`, fataling if it doesn't exist.
Both branches return an absolute path.

**`resolve_launcher`** (new, `bin/functions`) — used by any ops-tool script that
runs an `installDist` launcher in both the local dev tree and the deployed tree:

```
resolve_launcher <module>
```

Because `bin/deploy` tars each dist from its `build/install` parent (stripping
that prefix), the launcher for `<module>` lives at
`<module>/build/install/<module>/bin/<module>` locally but at
`<module>/bin/<module>` under `/opt/unicoach/current` on the instance.
`resolve_launcher` returns whichever of those two candidate paths (resolved
under `$PROJECT_ROOT`) exists, fataling if neither does — the two candidate
paths are codified in this one place rather than hardcoded per ops tool. (It
does not supersede the daemon `-up` scripts, which run only in the local tree
and hardcode the local path directly; see the pre-built-artifact invariant.)

**`bin/ingest-colleges`** (modified) — same two-positional-argument contract as
today. Two changes: each argument is resolved via `resolve_file_arg` (was:
`realpath` only), and the final invocation runs the launcher returned by
`resolve_launcher college` directly (was: `gradlew :college:run --args=...`).
`IngestApplication`'s own logic is untouched.

**`bin/remote`** (new):

```
bin/remote <env> -f <name>:<local-path> [-f <name>:<local-path>]... -- <script> [args...]
```

1. Guards, run in this order: `unicoach_env_file`,
   `require_env_vars ENVIRONMENT`, `require_cloud_domain "$env"` (rejects
   `env=local` before any build or AWS call — the mechanism has no meaning
   there; a tool's own local invocation is the correct path),
   `assert_aws_account`. (`bin/remote` needs only `ENVIRONMENT`, not the app
   domain or Google client IDs some infra scripts also require.)
2. Parses `-f NAME:PATH` (repeatable, via `getopts`) into a name→path map:
   splits on the first `:`; fatals if no `:` is present, if `NAME` is empty, if
   `NAME` doesn't match `^[a-zA-Z0-9_-]+$` or starts with `-`, or if `PATH`
   doesn't exist.
3. Uploads each declared file to
   `s3://<artifacts_bucket>/ops-inputs/<run-id>/<basename>`; registers an `EXIT`
   trap that `aws s3 rm`s every uploaded object, firing regardless of outcome.
4. Scans the trailing `[args...]`: an argument matching `@<name>` is substituted
   with that name's `s3://` URI — fatal if `<name>` was never declared via `-f`.
   Anything else is forwarded untouched.
5. Runs `tofu -chdir=infra init -backend-config=key=…` for the env (same
   `TF_DATA_DIR`/backend-key selection `bin/deploy` performs before any output
   read), then reads `instance_id` and `ops_log_group_name` from that env's
   OpenTofu outputs. The init is not optional — an output read against an
   uninitialized or foreign-keyed backend fails or returns the wrong env's
   state.
6. `aws ssm send-command --document-name AWS-RunShellScript
   --instance-ids <id> --parameters
   commands=["cd /opt/unicoach/current && ENV_FILE=/etc/unicoach/env
   bin/<script> <rewritten args>"] --cloud-watch-output-config
   CloudWatchLogGroupName=<name>,CloudWatchOutputEnabled=true
   --timeout-seconds <n, default 1800>`.
7. Polls `aws ssm get-command-invocation` to a terminal status, prints
   `StandardOutputContent`/`StandardErrorContent` (noting the ~24KB truncation
   cap and pointing at the CloudWatch stream for the full record), and exits
   with the invocation's `ResponseCode`. A failure before the tool ever runs
   exits with `bin/remote`'s own code, never the tool's, split along the band
   `bin/INVARIANTS.md` mandates: a usage error (malformed `-f`, `@name`
   undeclared, `env=local`) fatals in the reserved 10–29 band via the existing
   `EXIT_*` constants (e.g. `EXIT_INVALID_ARG_VALUE`), while an operational
   trigger failure (S3 upload or `send-command` itself) fatals in the 1–9 band.
   `help()` documents both. There is no build step to fail here, since the code
   is already deployed.

`bin/remote` does not run the caller's `<script> [args...]` as a local `"$@"`
child: it rewrites the args (S3 substitution) and interpolates the result into a
single `aws ssm send-command --parameters commands=[...]` string, one argument
`send-command` receives. Its relationship to the child-forwarding invariant is
spelled out in the Invariants section.

### Error Handling / Edge Cases

- `env=local` → `require_cloud_domain` fatals before any AWS call.
- Malformed `-f` (no `:`, empty name, invalid name characters, nonexistent path)
  → fatal before any AWS call.
- `@name` with no matching `-f` declaration → fatal before `send-command`.
- Instance unregistered/stopped → `send-command` fails; the upload cleanup trap
  still fires.
- An ops tool that was never `bin/deploy`ed (missing build artifact on the
  instance) → `resolve_launcher` finds neither candidate path and fatals; the
  script does not build on demand (see the pre-built-artifact invariant).
- Tool's own non-zero exit → propagated as `ResponseCode`; `bin/remote` exits
  non-zero with the captured output.
- Output over the ~24KB `StandardOutputContent` cap → noted explicitly; the
  CloudWatch Logs stream holds the untruncated record.
- Concurrent runs → no locking; each gets uniquely named S3 keys.
  `ingest-colleges` is already upsert-based (RFC 67), so overlapping runs of it
  are self-correcting.
- Concurrent `bin/deploy` → cannot race: no ops-tool script may touch `current`
  or restart a service (invariant below).

### Dependencies

No new libraries; `:college`'s dependencies (`commons-csv`, etc.) are unchanged.
`aws_cloudwatch_log_group` is supported by the already-pinned
`hashicorp/aws ~> 5.0` provider. `AWS-RunShellScript` is AWS-owned and already
in use by `bin/deploy` — no new Terraform resource for it.

## Tests

Live AWS execution cannot run in the automated suite (no AWS in `bin/test`),
mirroring RFC 50's precedent for the original deploy path.

### Automated

All shell tests below land in `bin/scripts-tests` (the existing `bin/` test host
— it already holds the `assert_aws_account` guard, `resolve`, and
duration-function tests, and the deploy-bundle assertion).

- **OpenTofu static validation.** `tofu -chdir=infra validate` and
  `tofu -chdir=infra fmt -check` pass with `infra/ops.tf` and the
  `iam.tf`/`outputs.tf` edits.
- **Script lint.** `bash -n` (and `shellcheck` where available) over
  `bin/deploy`, `bin/build`, `bin/build-college`, `bin/ingest-colleges`,
  `bin/remote`, and `bin/functions`.
- **`bin/remote` argument validation (no AWS reached).** Missing `<env>`;
  missing `--`; `-f` with no `:`; `-f` with an empty name; `-f` with an invalid
  name (containing `/`, or starting with `-`); `-f` with a nonexistent path;
  `@name` referencing an undeclared name; `env=local`; `-h`.
- **`bin/ingest-colleges` argument validation (no AWS reached).** No arguments;
  one argument only; a third positional; a nonexistent local path; `-h`.
- **`resolve_file_arg` (local-path branch only; the `s3://` branch requires AWS
  and is covered by deployment verification).** An existing local path resolves
  via `realpath`; a nonexistent path fatals.
- **`resolve_launcher`.** With a fixture `$PROJECT_ROOT` laid out as the local
  tree (`<module>/build/install/<module>/bin/<module>`), it returns that path;
  laid out as the deployed tree (`<module>/bin/<module>`), it returns that one;
  with neither present, it fatals.
- **Deploy-bundle test removed.** `test_deploy_bundle_includes_migration_path`
  is deleted, not extended: it parses `bin/deploy`'s source (`awk` over the
  `REPO_PATHS=( … )` array literal, then `grep`s for quoted paths) rather than
  asserting a real bundle, and step 7 would only bolt on a second, structurally
  different check for `college/` (a `-C … installDist` tar member the array
  parse cannot see). The guarantee it approximated — the bundle carries every
  script the on-instance step needs — is enforced for real by
  `deploy-on-instance`, which fails loudly on a missing script before the
  symlink swap and restart.
- **`:college` unchanged.** The existing `CollegeScorecardLoaderTest` suite (RFC
  67, RFC 78) continues to pass unmodified.

### Deployment verification (manual, post-apply)

- `tofu -chdir=infra apply` creates the log group and IAM statement with no
  unrelated diff.
- `bin/deploy prod` ships `college/` and `bin/ingest-colleges` into
  `/opt/unicoach/current` alongside the two services.
- `bin/remote prod -f 1:<institution.csv> -f 2:<fields.csv> --
  ingest-colleges @1 @2`
  completes, exits 0, and prints ingested counts; `bin/db-query` (env pointed at
  RDS) shows the expected row counts in `colleges`/`college_programs`.
- Re-running the same command is a no-op on row counts (upsert idempotence, RFC
  67).
- The CloudWatch Logs stream for the run holds the tool's full output.
- `aws s3 ls s3://<artifacts_bucket>/ops-inputs/` shows nothing after the run
  completes (cleanup trap fired).

## Invariants

**Rule:** A `bin/` script that runs a JVM program MUST invoke a pre-built
`installDist` launcher — never build and run in the same script (no
`gradlew … run`). It MUST fatal if the launcher is absent (directly, or via a
resolver such as `resolve_launcher` that fatals when no candidate path exists) —
it MUST NOT invoke Gradle to build one on demand. **Why:** Building on demand
couples execution to the build step and silently forks behavior between a dev
machine (which can build) and the production instance (which cannot); the build
step (`bin/build-<module>`) is deliberately a separate, explicit phase, and a
fatal on the absent launcher surfaces a skipped build loudly instead of hiding
it inside a "run" operation. **Target:** `bin/INVARIANTS.md`. This rule
**generalizes and replaces** the existing "Daemon `-up` boots a pre-built binary
or fatals" rule (the daemon `-up` scripts are the local-tree case: they run only
where the local dist exists, so they hardcode `…/build/install/…` and fatal on
its absence directly, no `resolve_launcher` needed). The Implementation Plan
replaces the old rule rather than appending a second, so the two never coexist.

**Rule:** A `bin/` script MUST parse its own options via `getopts`, using
single-letter options only — never a long-form (`--foo`) option of its own. The
exception is a thin front over a third-party CLI (`infra-apply`, `infra-plan`,
`infra-init`, `infra-output`, `infra-bootstrap`, each forwarding to `tofu`)
whose own argument grammar isn't ours to constrain. **Why:** `getopts` is the
only option parser already in use across `bin/`; a second, long-form syntax
would fork parsing style per script and complicate a script like `bin/remote`,
which must cleanly separate its own options from an opaque forwarded command.
**Target:** `bin/INVARIANTS.md`.

**Rule:** An ops-tool script invoked via `bin/remote` MUST read runtime
credentials only from `/etc/unicoach/env` (via `ENV_FILE`); it MUST NOT receive
a secret through `bin/remote`'s own arguments or the SSM command it sends.
**Why:** Both are visible in plaintext to anyone with CloudTrail/console access;
`/etc/unicoach/env` is already the one trusted credentials path every deployed
script uses. **Target:** `bin/INVARIANTS.md`.

**Rule:** An ops-tool script invoked via `bin/remote` MUST NOT modify
`/opt/unicoach/current` or restart a systemd unit. **Why:**
`deploy-on-instance.sh` treats the symlink swap and restart as atomic and gated
on a successful migration; a second, unsynchronized path touching either could
serve a half-deployed release or restart a service out from under an in-flight
request. **Target:** `bin/INVARIANTS.md`.

**Also:** `bin/remote` is added to two existing invariants' enumerated exception
lists (not new rules):

- "`exec <command>` and `"$@"`/`"$*"` child-forwarding are forbidden."
  `bin/remote` does **not** run the caller's `<script> [args...]` as a local
  `"$@"` child — it word-splits its own options off, rewrites the trailing args
  (S3 substitution), and interpolates the result into a single
  `aws ssm send-command --parameters commands=[…]` **string** that
  `send-command` receives as one argument. That is the same "one diagnostic
  string" shape already carved out (the `fatal … "$*"` and `log-*` primitives),
  not an argv forward. A distinct sub-bullet records it under that exception,
  worded as SSM command-string interpolation rather than local child-forwarding,
  so the boundary the invariant prizes stays exact.
- "Operational scripts reject unexpected arguments." The trailing
  `<script>
  [args...]` is an open-ended caller command with no fixed count to
  reject — the same open-ended-grammar exemption already granted to `daemon-up`,
  `wait-for`, `db-run`'s trailing SQL, etc.

## Implementation Plan

1. **`infra/ops.tf`.** Add `aws_cloudwatch_log_group.ops_run` (30-day
   retention). _Verify:_ `tofu -chdir=infra validate`,
   `tofu -chdir=infra fmt -check`.
2. **`infra/iam.tf`.** Add the CloudWatch Logs write statement to
   `data.aws_iam_policy_document.instance`, scoped to the new log group ARN.
   _Verify:_ `tofu -chdir=infra validate`.
3. **`infra/outputs.tf`.** Add `ops_log_group_name`. _Verify:_
   `tofu -chdir=infra validate`.
4. **`bin/functions`.** Add `resolve_file_arg` and `resolve_launcher`. _Verify:_
   `bash -n bin/functions`; the `resolve_file_arg` local-path and
   nonexistent-path cases and the `resolve_launcher` local-tree/deployed-tree/
   neither cases from Tests.
5. **`bin/build-college` (new) + `bin/build`.** Add the wrapper; add `college`
   to `bin/build`'s `MODULES` array **and** to the module chain printed by
   `bin/build`'s `help()` (the two must stay in sync, or the help under-reports
   what is built). _Verify:_ `nix develop -c bin/build-college` produces
   `college/build/install/college/bin/college`.
6. **`bin/ingest-colleges`.** Swap the `gradlew :college:run` invocation for the
   launcher returned by `resolve_launcher college`; resolve both arguments via
   `resolve_file_arg`. _Verify:_ the argument-validation cases from Tests; a
   manual local run against the existing fixture CSVs still ingests correctly.
7. **`bin/deploy`.** Add `COLLEGE_DIST="college/build/install/college"` to the
   existence check and as a third `-C … installDist` tar member; add
   `bin/ingest-colleges` to `REPO_PATHS`. _Verify:_ `bash -n`/`shellcheck`; a
   local dry run's assembled bundle contains `college/` and
   `bin/ingest-colleges` (`tar -tzf`).
8. **`bin/scripts-tests`.** Add the `resolve_file_arg`, `resolve_launcher`,
   `bin/remote` and `bin/ingest-colleges` argument-validation cases; and delete
   `test_deploy_bundle_includes_migration_path` — a source-parse of
   `bin/deploy`'s `REPO_PATHS` array that step 7's bundle change would break,
   superseded by `deploy-on-instance`'s own fail-loud-on-missing-script guard.
   _Verify:_ `nix develop -c bin/test scripts` (or the harness that runs
   `bin/scripts-tests`) is green with the new cases executed.
9. **`bin/remote` (new).** Includes the per-env `tofu init` before the output
   reads (step 5 of the API contract). _Verify:_ the argument-validation cases
   from Tests (no AWS reached).
10. **`bin/INVARIANTS.md`.** Add the three new rules (the `getopts`-only option
    rule, `/etc/unicoach/env`-only credentials, and no `current`/service
    mutation); **replace** the existing "Daemon `-up` boots a pre-built binary
    or fatals" rule with the generalized pre-built-launcher rule that supersedes
    it (do not append a second); and make the two exception-list edits
    (child-forwarding SSM-string sub-bullet; unexpected-argument exemption).
    _Verify:_ file contains the generalized launcher rule (old daemon-`-up` rule
    gone), the three ops-tool Rule/Why entries, and both list edits.
11. **Operator: `tofu -chdir=infra apply` and `bin/deploy prod` against prod.**
    Not part of the agent's verifiable steps (cost/credentials/live AWS), per
    RFC 50's precedent. _Verify:_ Deployment verification section, run manually.

## Files Modified

- `infra/ops.tf` (new)
- `infra/iam.tf`
- `infra/outputs.tf`
- `bin/functions`
- `bin/build-college` (new)
- `bin/build`
- `bin/ingest-colleges`
- `bin/deploy`
- `bin/remote` (new)
- `bin/scripts-tests`
- `bin/INVARIANTS.md`
