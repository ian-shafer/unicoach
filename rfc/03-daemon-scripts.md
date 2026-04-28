## Executive Summary

This specification dictates the implementation of generic daemon lifecycle
scripts (`docker-daemon-start`, `docker-daemon-stop`, `docker-daemon-restart`,
`docker-daemon-check`) utilizing the strict `${domain}-${action}` naming
convention. It mandates the creation of thin wrapper scripts for the REST
server. By extracting shell logic, Docker Compose orchestration commands, native
healthcheck evaluations, and concurrency locks into unified generic engine
scripts, code duplication is eliminated. This spec also covers replacing static
execution flows in `bin/test-fuzz` with the new wrapper abstractions, and the
creation of an automated bash validation harness.

## Detailed Design

### Generic Daemon Engines

The core engine scripts contain the shell logic and Docker Compose orchestration
commands. All shell scripts MUST source `common` via the exact invocation
`source "$(dirname "$0")/common"` at the very top of the file to establish the
`$PROJECT_ROOT` environment variable and ensure path resolution works securely
from any working directory.

All engine scripts implicitly rely on a Docker Compose convention, invoking
`"$PROJECT_ROOT/bin/docker-compose" -f "$PROJECT_ROOT/docker/${service-name}-compose.yaml" <command>`.

- `bin/docker-daemon-start <service-name> [options]`:
  - Assumes a `"$PROJECT_ROOT/docker/${service-name}-compose.yaml"` exists.
  - Implements concurrency control utilizing a custom locking mechanism via
    `"$PROJECT_ROOT/bin/file-lock"`. It calls
    `file-lock -o start -t 16 "$PROJECT_ROOT/var/run/${service-name}.daemon.lock" 60`.
  - If `file-lock` natively evaluates an interception matching the established
    `start` state (exiting `10`), it must assume another start is already
    running and orchestrate cleanly exiting `0` natively without attempting
    redundant Docker Compose evaluations. If `file-lock` exits with any other
    non-zero status (indicating the lock is actively held by a conflicting
    operation), it must print an error and `exit 1`.
  - To prevent deleting locks owned by other processes upon failure, the script
    MUST implement stateful lock teardown by deferring the declaration of
    `trap 'rm -rf "$LOCK_DIR"' EXIT INT TERM` until strictly _after_ successful
    lock acquisition. It MUST NOT execute the trap and recklessly delete the
    lock directory if `file-lock` fails and forces an exit.
  - Boots the service detached and strictly awaits native healthchecks utilizing
    Docker Compose's explicit wait mechanism:
    `"$PROJECT_ROOT/bin/docker-compose" -f "$PROJECT_ROOT/docker/${service-name}-compose.yaml" up -d --wait`.
  - If the container fails to become healthy or times out, the script MUST dump
    `docker-compose logs` to the terminal, execute a synchronous teardown
    (`docker-compose down --remove-orphans`), and then exit `1`.
  - Exits `0` immediately if the service is already actively running and
    healthy.

- `bin/docker-daemon-stop <service-name> [options]`:
  - Implements concurrency control to prevent collision with concurrent boots.
    It must invoke
    `bin/file-lock -o stop -t 16 "$PROJECT_ROOT/var/run/${service-name}.daemon.lock" 60`
    to wait up to 16 seconds by default. This wait limit can be overridden by
    passing a `--lock-timeout <seconds>` flag (no short option). If it fails to
    acquire the lock within the timeout, it must explicitly print an error and
    `exit 1`.
  - To prevent tearing down locks it never acquired, it MUST defer the
    declaration of its teardown `trap 'rm -rf "$LOCK_DIR"' EXIT INT TERM` until
    strictly _after_ successful lock acquisition.
  - Safely terminates a running daemon.
  - Accepts an optional `-d | --docker-down` flag. If provided, it executes
    `docker-compose down --remove-orphans` (tearing down network/containers but
    leaving volumes intact).
  - Accepts an explicit `--wipe-data` flag. If provided, it executes
    `docker-compose down -v --remove-orphans` (destructively removing all
    containers, networks, AND volumes).
  - Otherwise, it defaults to `docker-compose stop` (leaving containers and
    artifacts intact).
  - Must be idempotent (exiting reliably with `0` if the daemon is already
    stopped or does not exist).

- `bin/docker-daemon-restart <service-name> [options]`:
  - Restarts an existing daemon.
  - Accepts `-d | --docker-down` and `--wipe-data` (passed through to
    `docker-daemon-stop`).
  - Sequentially invokes `docker-daemon-stop` then `docker-daemon-start`.

- `bin/docker-daemon-check <service-name> [options]`:
  - Verifies daemon health utilizing strict, machine-readable Docker label
    filtering rather than parsing generic text output.
  - Implements liveness evaluations explicitly by executing
    `docker ps -q -f "label=com.docker.compose.service=${service-name}" -f "status=running"`.
  - If the underlying container configuration specifies a healthcheck, the
    script must augment the evaluation to verify readiness (e.g., appending
    `-f "health=healthy"`).
  - Determines liveness programmatically: returns `0` if the `docker ps` output
    contains container IDs (string length `> 0`), or returns `1` if the output
    is empty (indicating stopped/unhealthy).

### REST Server Thin Wrappers

Service-specific scripts (`bin/rest-server-*`) act as configuration
pass-throughs invoking the underlying `docker-daemon-*` engine scripts. They
must explicitly execute the engines utilizing the pattern:
`"$PROJECT_ROOT/bin/docker-daemon-[action]" rest-server "$@"`.

- `bin/rest-server-start`
- `bin/rest-server-stop`
- `bin/rest-server-restart`
- `bin/rest-server-check`

### Execution Constraints and Error Handling

- **Help Documentation**: Every script intercepts `-h` and `--help` flags,
  rendering multiline usage blocks via a `help()` function utilizing a heredoc
  block. Executing these explicitly MUST cleanly exit with `0`.
- **Argument Parsing**: Configurable options should usually support both short
  and long flag definitions (e.g., `-d` and `--docker-down`), especially for
  common options. For less common options, a single, long flag will suffice.
- **Error Codes**: All scripts must enforce deliberate failing constraints using
  specific non-zero exit codes (e.g., `exit 1`).

## Tests

Automated regression coverage will be enforced via a custom bash test harness
(`bin/scripts-tests`). Crucially, this harness will test the generic
`docker-daemon-*` engine directly against a lightweight dummy compose stack,
rather than waiting for the REST server JVM to boot. The harness will
programmatically execute state validations, concurrency checks, and assert
strict script idempotency without human intervention.

A `trap 'bin/docker-daemon-stop -d dummy-service' EXIT INT TERM` hook must be
configured at the top of the test harness to guarantee automatic teardown of the
test containers, regardless of test pass/fail state.

A localized integration test will also be performed by running `bin/test-fuzz`
and verifying `bin/rest-server-restart` and `bin/rest-server-stop --docker-down`
gracefully handle the orchestration operations.

## Implementation Plan

_(Note: Remember to execute `chmod +x` against all bash files as they are
created)._

1. **Setup Test Harness and Environments:**
   - Create `bin/scripts-tests` with a `trap '...' EXIT` hook for automatic
     teardown.
   - Create `docker/dummy-service-compose.yaml`: Microscopic container with
     native healthcheck (`test: ["CMD-SHELL", "true"]`, `interval: 1s`,
     `timeout: 1s`, `retries: 3`).
   - Create `docker/dummy-service-failing-compose.yaml`: Configured to rapidly
     fail (`test: ["CMD-SHELL", "false"]`, `interval: 1s`, `retries: 1`).
2. **Implement and Test File Lock:**
   - Create `bin/file-lock` exclusively utilizing atomic `mkdir` operations. It
     must strictly enforce the following interface:
     - `Usage: file-lock [options] <lock-dir> <max-lock-duration>`
     - `-h | --help`: help message
     - `-o | --operation`: text that will be written verbatim to `<lock-dir>/op`
     - `-t | --timeout`: utilizes `bin/wait-for` internally to wait to acquire
       the lock. If this option is not given, it wil immediately fail if the
       lock is already held.
   - It will calculate an expiration stamp (`date +%s` + `<max-lock-duration>`)
     into `$LOCK_DIR/expires-at`.
   - To resolve stale lock deadlocks, it must evaluate `expires-at`. If expired,
     it attempts a safe break via atomic rename
     (`mv "$LOCK_DIR" "${LOCK_DIR}.stale.$$"`) before creating a fresh lock and
     logging a warning via a new `log-warning` utility in `bin/functions`.
   - Ensure it leverages `bin/wait-for` internally when waiting on a `-t`
     timeout.
   - **Test**: Add assertions to `scripts-tests` verifying: standard
     allocation/release, timeout waiting/failure when locked by conflicting
     `op`, immediate exit 10 fast-fail when locked by matching `op`, and
     successful verification/recovery when a lock is stale.
3. **Implement and Test Daemon Check:**
   - Create `bin/docker-daemon-check` utilizing strict Docker label filtering.
   - **Test**: Add `Check Accuracy` (asserting `1` when stopped) and
     `Help Flags` assertions (asserting passing `-h` correctly prints usage and
     exits cleanly with `0`). Run harness.
4. **Implement and Test Daemon Start:**
   - Create `bin/docker-daemon-start` coordinating orchestration and directory
     lockdown.
   - **Test**: Add `Start Idempotency`, `Start Failure Path` (asserting failure
     teardown and lock release), `Invalid Service`, and `Concurrency` (using
     background `&` tasks) assertions to the harness. Run harness.
5. **Implement and Test Daemon Stop & Restart:**
   - Create `bin/docker-daemon-stop` (implementing its upfront concurrency lock)
     and `bin/docker-daemon-restart`.
   - **Test**: Add `Stop Idempotency`, `Restart Behavior`, and
     `Wipe Data Integrity` assertions to the harness. Run harness.
6. **Implement and Test Service Wrappers:**
   - Create `bin/rest-server-*` wrappers forwarding all options `"$@"` to the
     engine scripts.
   - **Test**: Add `Wrapper Forwarding` test verifying `bin/rest-server-check`
     functions identically. Run harness fully one last time.
7. **Integrate Application Systems:**
   - Modify `docker/rest-server-compose.yaml` to include a native Docker
     `healthcheck` block (`curl -f http://localhost:8080/hello`). You MUST
     dictate explicit polling parameters to prevent utilizing Docker's default
     30s delays (e.g., `interval: 2s`, `timeout: 2s`, `retries: 15`).
   - Modify `bin/test-fuzz` to orchestrate utilizing `bin/rest-server-restart`
     and adapt its `trap` teardown to `bin/rest-server-stop --docker-down`.

## Files Modified

#### [NEW]

- `docker/dummy-service-compose.yaml`: Minimal container for engine tests.
- `docker/dummy-service-failing-compose.yaml`: Failing container for native
  failure validation.
- `bin/docker-daemon-start`: Generic engine coordinating docker-compose
  up/locking.
- `bin/docker-daemon-stop`: Generic engine resolving teardowns/data wipes.
- `bin/docker-daemon-restart`: Generic engine sequencing stop then start.
- `bin/docker-daemon-check`: Generic engine evaluating strict Docker labels.
- `bin/rest-server-start`: Thin configuration wrapper for start.
- `bin/rest-server-stop`: Thin configuration wrapper for stop.
- `bin/rest-server-restart`: Thin configuration wrapper for restart.
- `bin/rest-server-check`: Thin configuration wrapper for check.
- `bin/scripts-tests`: Automated harness executing native engine regression.
- `bin/file-lock`: Concurrency mechanism resolving directory deadlocks.

#### [MODIFY]

- `docker/rest-server-compose.yaml`: Injects explicit fast-polling native health
  checks.
- `bin/test-fuzz`: Replaces static orchestration flows with wrappers.
- `bin/functions`: Adds log-warning utility for reporting broken locks.
