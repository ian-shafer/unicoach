# 18: Docker Infrastructure Hardening

## Executive Summary

The Docker network is managed as an `external: true` resource, requiring 4
dedicated `docker-network-daemon-*` scripts and explicit pre-flight calls in
every service wrapper. This creates the single most common class of startup
failures — the network must exist before any compose file can be evaluated.
After `docker system prune`, Docker Desktop restarts, or manual cleanup, all
services fail until the network is manually recreated.

This spec eliminates the external network pattern by switching all compose files
to `name: unicoach-network` without `external: true`, which tells Docker Compose
to auto-create the network on `up` and leave it for other stacks. The 4 network
daemon scripts are deleted, and all wrapper `start`/`restart` scripts are
simplified to remove the network pre-flight call. Additionally, `bin/test-fuzz`
is updated to use daemon wrappers (clearing spec 03 debt — spec 03 requires all
Docker orchestration to flow through daemon wrappers), `docker-compose.test.yml`
is normalized into `docker/test-compose.yaml`, and a new `bin/docker-status`
diagnostic tool is introduced.

## Detailed Design

### Network Topology Change

All compose files currently declare:

```yaml
networks:
  default:
    external: true
    name: unicoach-network
```

This changes to:

```yaml
networks:
  default:
    name: unicoach-network
```

Removing `external: true` changes Docker Compose behavior:

- **On `up`**: Creates `unicoach-network` if it does not exist. If it already
  exists (created by another compose stack), connects to the existing network.
- **On `down`**: Removes `unicoach-network` only if no other containers are
  connected to it. If postgres is running, stopping rest-server will not remove
  the network.
- **On `stop`**: Does not touch the network (same as current behavior).

This makes the system self-healing: after `docker system prune`, the next
`bin/postgres-start` auto-creates the network without any pre-flight logic.

Docker Compose service names (e.g., `postgres`, `rest-server`) are registered as
DNS aliases on the named network. Cross-compose-project DNS resolution works
because Docker network-level DNS resolves aliases for any container connected to
the same network, regardless of which compose project created it.

### Deleted Scripts

The following 4 scripts are deleted entirely:

- `bin/docker-network-daemon-start`
- `bin/docker-network-daemon-stop`
- `bin/docker-network-daemon-restart`
- `bin/docker-network-daemon-check`

### Simplified Wrapper Scripts

The `docker-network-daemon-start` pre-flight call is removed from:

- `bin/postgres-start`: Becomes a direct delegation to `docker-daemon-start`.
- `bin/postgres-restart`: Becomes a direct delegation to
  `docker-daemon-restart`.
- `bin/rest-server-start`: Becomes a direct delegation to `docker-daemon-start`.
- `bin/rest-server-restart`: Becomes a direct delegation to
  `docker-daemon-restart`.

After simplification, these wrappers follow the exact same pattern as the `stop`
and `check` wrappers — a single `exec` delegation.

Example `bin/postgres-start` after change:

```bash
#!/usr/bin/env bash
source "$(dirname "$0")/common"

exec "$PROJECT_ROOT/bin/docker-daemon-start" "$@" postgres
```

`bin/queue-worker-start` is unchanged — it already delegates through
`bin/postgres-start` (which will be simplified) and then to
`docker-daemon-start`.

### `test-fuzz` Update

`bin/test-fuzz` currently bypasses the daemon engine entirely, calling
`docker-compose up -d` and `docker-compose down` directly. Per spec 03, it must
use daemon wrappers.

The updated script:

1. **Trap**: `bin/rest-server-stop --docker-down` and
   `bin/postgres-stop --docker-down` (replaces raw `docker-compose down`).
2. **Postgres**: `bin/postgres-start` (ensures the database is online before the
   rest-server boots — the rest-server compose file has no postgres service and
   will crash on JDBC connect if postgres is not already running on
   `unicoach-network`).
3. **Boot**: `bin/rest-server-restart --docker-down` (replaces raw
   `docker-compose up -d`).
4. **Readiness**: Retain `bin/wait-for 90s curl ...` since JVM services
   currently have no Docker-level healthcheck. This becomes unnecessary after
   spec 19 (daemon health marker) is implemented.
5. **Fuzzer**:
   `bin/docker-compose -f "$PROJECT_ROOT/docker/test-fuzz-compose.yaml" run --rm fuzzer`
   (runs the fuzzer as a standalone compose invocation on `unicoach-network`).

`docker/test-fuzz-compose.yaml` must declare the shared network so the fuzzer
container can resolve the `rest-server` hostname:

```yaml
services:
  fuzzer:
    image: schemathesis/schemathesis:stable
    volumes:
      - ./api-specs:/api-specs
    command:
      - run
      - /api-specs/openapi.yaml
      - --url=http://rest-server:${PORT:-8080}
      - --checks=all

networks:
  default:
    name: unicoach-network
```

### `docker-compose.test.yml` Normalization

The test compose file is the only compose file located at the project root
instead of `docker/`. It also uses the deprecated `version: '3.8'` key and
`env_file:` (all other compose files route environment variables through
`bin/docker-compose`'s inline block).

Changes:

1. Delete `docker-compose.test.yml` from the project root.
2. Create `docker/test-compose.yaml` with:
   - `version:` key removed.
   - `env_file: .env.test` retained (it loads environment variables into the
     container for the Gradle process).
   - `user: "${HOST_UID}:${HOST_GID}"` added to match other compose files.
   - `networks.default.name: unicoach-network` (no `external: true`).
3. Update `bin/test` to reference the new path and use `$PROJECT_ROOT`-qualified
   paths consistently.

Resulting `docker/test-compose.yaml`:

```yaml
services:
  gradle-runner:
    image: eclipse-temurin:21-jdk
    user: "${HOST_UID}:${HOST_GID}"
    volumes:
      - .:/workspace
    working_dir: /workspace
    env_file:
      - .env.test
    environment:
      GRADLE_USER_HOME: /workspace/var/gradle
      HOME: /workspace/var/gradle
      DATABASE_JDBCURL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-unicoach-test}
    command: ./gradlew test

networks:
  default:
    name: unicoach-network
```

### `bin/docker-status` Diagnostic Tool

A new script that provides a single-command view of all Docker infrastructure.

Output format:

```
Network:
  unicoach-network    active

Services:
  postgres            running
  rest-server         stopped
  queue-worker        stopped

Locks:
  (none)
```

Service status is derived from `bin/docker-daemon-check` exit codes: exit `0` →
`running`, non-zero → `stopped`. Docker-level health detail (e.g., `healthy`) is
not exposed by the check scripts and is out of scope.

Implementation:

- Check network existence via `docker network inspect unicoach-network`.
- Check each service via `bin/docker-daemon-check`. Suppress stderr, evaluate
  exit code.
- Scan `$PROJECT_ROOT/var/run/*.docker-daemon.lock` for active locks. For each
  lock found, read `op` and `expires-at` to display the operation and whether
  the lock is stale.
- All services to check: `postgres`, `rest-server`, `queue-worker`.
- Help flag (`-h`, `--help`) must exit `0`.

### Trap Teardown Simplification

Every test harness (`bin/scripts-tests`, `bin/db-scripts-tests`,
`bin/q-scripts-tests`, `bin/db-users-tests`) contains a trap that explicitly
calls `docker-network-daemon-stop` and `docker network rm`. These must be
simplified to remove all network daemon references.

The new trap pattern uses `*-stop -d` (which runs `docker compose down`),
letting Docker Compose handle network cleanup:

```bash
trap 'bin/rest-server-stop -d >/dev/null 2>&1 || true; bin/queue-worker-stop -d >/dev/null 2>&1 || true; bin/postgres-stop -d >/dev/null 2>&1 || true; docker network rm unicoach-network >/dev/null 2>&1 || true' EXIT INT TERM
```

The trailing `docker network rm` is a defensive safety net. After all compose
stacks are torn down, the network should already be gone, but the `rm` handles
edge cases idempotently.

For harnesses that do not use all services (e.g., `bin/db-users-tests` only uses
postgres), the trap should only stop the services it starts.

Note: the addition of `bin/queue-worker-stop -d` to the `bin/scripts-tests` trap
is a **latent bug fix** — the current trap does not stop the queue-worker even
though `test_daemon_wrapper "queue-worker"` starts it.

### Dependencies

No new dependencies. No Kotlin or Gradle changes.

### Error Handling

- If `docker network inspect` fails in `docker-status`, the script reports the
  network as missing and continues (does not exit).
- If `docker-daemon-check` fails for a service in `docker-status`, the script
  reports it as stopped and continues.
- All existing error handling in `docker-daemon-*` engine scripts is unchanged.

## Tests

### Existing Test Modifications

The `test_daemon_wrapper` function in `bin/scripts-tests` already tests the full
lifecycle matrix (start, stop, restart, check, idempotency, concurrency) for
each wrapper. After this spec:

- Remove the `test_daemon_wrapper "docker-network-daemon" "unicoach-network"`
  invocation (the scripts no longer exist).
- Remove the manual `docker network create unicoach-network` setup line (Docker
  Compose auto-creates it).
- The remaining `test_daemon_wrapper` calls for `rest-server`, `queue-worker`,
  and `postgres` implicitly validate that the auto-create network pattern works
  — `docker compose up` will fail if Docker Compose cannot create or connect to
  `unicoach-network`.

### New Tests

#### `docker-status` Help Flag

Added to `bin/scripts-tests`:

- Execute `bin/docker-status -h`. Assert exit code `0`.

#### `docker-status` Output Accuracy

Added to `bin/scripts-tests`:

- After the `postgres` wrapper test completes (postgres is running), execute
  `bin/docker-status`. Assert the output contains `postgres` and `running`.
  Assert it contains `rest-server` and `stopped`. This validates the diagnostic
  tool reports accurate state.

### Verification Plan

- `bin/scripts-tests`: Full pass. Validates daemon lifecycle for all wrappers
  without any network pre-flight.
- `bin/db-scripts-tests`: Full pass. Validates database operations still work
  with auto-created network.
- `bin/q-scripts-tests`: Full pass. Validates queue CLI tools still work.
- `bin/db-users-tests`: Full pass.
- Manual: Execute `docker system prune --all`, then `bin/postgres-start`.
  Confirm the network is auto-created and postgres boots without error.
- Manual: Execute `bin/test-fuzz` to confirm the fuzzer can resolve the
  `rest-server` hostname on the shared network.

## Implementation Plan

1. **Update all compose files to remove `external: true`.**
   - Modify `docker/postgres-compose.yaml`: Remove `external: true` from the
     networks block.
   - Modify `docker/rest-server-compose.yaml`: Remove `external: true`.
   - Modify `docker/queue-worker-compose.yaml`: Remove `external: true`.
   - Restructure `docker/test-fuzz-compose.yaml`: Add
     `networks: default: name: unicoach-network` block. Remove the stale weaving
     comment (the fuzzer now runs as a standalone compose invocation, not weaved
     with `rest-server-compose.yaml`).
   - Verify: `bin/docker-compose -f docker/postgres-compose.yaml config` parses
     without error for each file.

2. **Delete network daemon scripts.**
   - Delete `bin/docker-network-daemon-start`.
   - Delete `bin/docker-network-daemon-stop`.
   - Delete `bin/docker-network-daemon-restart`.
   - Delete `bin/docker-network-daemon-check`.

3. **Simplify wrapper scripts.**
   - Modify `bin/postgres-start`: Remove the `docker-network-daemon-start` line.
     Reduce to `exec "$PROJECT_ROOT/bin/docker-daemon-start" "$@" postgres`.
   - Modify `bin/postgres-restart`: Remove the `docker-network-daemon-start`
     line. Reduce to
     `exec "$PROJECT_ROOT/bin/docker-daemon-restart" "$@" postgres`.
   - Modify `bin/rest-server-start`: Remove the `docker-network-daemon-start`
     line. Reduce to
     `exec "$PROJECT_ROOT/bin/docker-daemon-start" "$@" rest-server`.
   - Modify `bin/rest-server-restart`: Remove the `docker-network-daemon-start`
     line. Reduce to
     `exec "$PROJECT_ROOT/bin/docker-daemon-restart" "$@" rest-server`.
   - Verify: `bin/postgres-start -h` and `bin/rest-server-start -h` exit `0`.

4. **Normalize test compose file.**
   - Delete `docker-compose.test.yml` from the project root.
   - Create `docker/test-compose.yaml` with normalized content (no `version`,
     `user` added, `external: true` removed).
   - Modify `bin/test`: Update the compose invocation to:
     `"$PROJECT_ROOT/bin/docker-compose" -f "$PROJECT_ROOT/docker/test-compose.yaml" run --rm gradle-runner ./gradlew "${GRADLE_ARGS[@]}"`.
     All bare `bin/` references must use `"$PROJECT_ROOT/bin/..."` consistently.
   - Verify: `bin/docker-compose -f docker/test-compose.yaml config` parses
     without error.

5. **Update `bin/test-fuzz` to use daemon wrappers.**
   - Replace the trap with `"$PROJECT_ROOT/bin/rest-server-stop" --docker-down`
     and `"$PROJECT_ROOT/bin/postgres-stop" --docker-down`.
   - Add `"$PROJECT_ROOT/bin/postgres-start"` before the rest-server boot.
   - Replace `docker-compose up -d` with
     `"$PROJECT_ROOT/bin/rest-server-restart" --docker-down`.
   - Retain the `wait-for curl` readiness poll.
   - Replace the weaved fuzzer invocation with a standalone
     `bin/docker-compose -f docker/test-fuzz-compose.yaml run --rm fuzzer`.
   - Verify: `bin/test-fuzz -h` exits `0`.

6. **Simplify test harness traps.**
   - Modify `bin/scripts-tests`: Remove `docker-network-daemon-stop` and
     `docker network rm` from the trap. Remove the
     `docker network create unicoach-network` setup line. Remove the
     `test_daemon_wrapper "docker-network-daemon" "unicoach-network"` call. Add
     `bin/queue-worker-stop -d` to the trap. Add `docker network rm` as a
     trailing safety net.
   - Modify `bin/db-scripts-tests`: Simplify trap to `bin/postgres-stop -d` and
     trailing `docker network rm`.
   - Modify `bin/q-scripts-tests`: Simplify trap to `bin/postgres-stop -d` and
     trailing `docker network rm`.
   - Relocate `tests/db/test_users_table.sh` to `bin/db-users-tests` (normalizes
     test harness location convention). Update source paths from `../../` to
     standard `$(dirname "$0")/` pattern. Simplify trap to
     `bin/postgres-stop -d` and trailing `docker network rm`. Delete the empty
     `tests/` directory tree.

7. **Create `bin/docker-status`.**
   - Create `bin/docker-status` with help flag support, network check, service
     status for `postgres`/`rest-server`/`queue-worker`, and lock detection.
   - `chmod +x bin/docker-status`.
   - Add help flag test and output accuracy test to `bin/scripts-tests`.
   - Verify: `bin/docker-status -h` exits `0`.

8. **Full verification.**
   - Run `bin/scripts-tests`. All tests must pass.
   - Run `bin/db-scripts-tests`. All tests must pass.
   - Run `bin/q-scripts-tests`. All tests must pass.
   - Run `bin/db-users-tests`. All tests must pass.

## Files Modified

#### [DELETE]

- `bin/docker-network-daemon-start`
- `bin/docker-network-daemon-stop`
- `bin/docker-network-daemon-restart`
- `bin/docker-network-daemon-check`
- `docker-compose.test.yml`
- `tests/db/test_users_table.sh`
- `tests/db/` (empty directory)
- `tests/` (empty directory)

#### [NEW]

- `docker/test-compose.yaml`
- `bin/docker-status`
- `bin/db-users-tests`

#### [MODIFY]

- `docker/postgres-compose.yaml`
- `docker/rest-server-compose.yaml`
- `docker/queue-worker-compose.yaml`
- `docker/test-fuzz-compose.yaml`
- `bin/postgres-start`
- `bin/postgres-restart`
- `bin/rest-server-start`
- `bin/rest-server-restart`
- `bin/test-fuzz`
- `bin/test`
- `bin/scripts-tests`
- `bin/db-scripts-tests`
- `bin/q-scripts-tests`
