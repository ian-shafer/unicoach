# 20: Test Environment Isolation

## Executive Summary

`bin/scripts-tests`, `bin/db-scripts-tests`, `bin/q-scripts-tests`,
`bin/db-users-tests`, and `bin/test-fuzz` share Docker infrastructure with the
dev environment. All compose files hardcode `name: unicoach-network` in their
`networks.default` block. All test harnesses tear down containers via `stop -d`
(which calls `docker compose down`), destroying the shared network and killing
any dev-side containers attached to it. A developer running `rest-server`
locally will find their environment dead after a test run.

This spec isolates the test environment by parameterizing the Docker network
name through a `UNICOACH_NETWORK` environment variable. `.env` defaults to
`unicoach-network`. `.env.test` overrides it to `unicoach-test-network`. All
compose files replace the hardcoded network name with the variable. All test
harness EXIT traps replace the hardcoded `docker network rm unicoach-network`
with `docker network rm "$UNICOACH_NETWORK"`. `bin/docker-status` replaces
hardcoded network name references with the variable.

`POSTGRES_DATA_DIR` is separated: dev keeps `./var/postgres`, test uses
`./var/postgres-test`. The previous comment in `.env.test` claiming the data dir
"should be identical across dev and test to avoid docker-compose container
restarts/contention" is no longer valid — network isolation already forces
different containers. Without this separation, test harnesses that call
`rm -rf "$POSTGRES_DATA_DIR"` or `bin/db-destroy` destroy all dev database
contents. Port contention on 5432 is avoided because only one postgres instance
exists at a time — test harnesses always stop postgres before starting their
own.

## Detailed Design

### Environment Variable: `UNICOACH_NETWORK`

A new environment variable controls the Docker network name used by all compose
files and shell scripts.

#### `.env`

Add:

```
UNICOACH_NETWORK=unicoach-network
```

Remove the stale comment
`# POSTGRES_DATA_DIR should be identical across dev and test to avoid docker-compose container restarts/contention`.

#### `.env.test`

Add:

```
UNICOACH_NETWORK=unicoach-test-network
```

Update:

```
POSTGRES_DATA_DIR=./var/postgres-test
```

Remove the stale comment
`# POSTGRES_DATA_DIR should be identical across dev and test to avoid docker-compose container restarts/contention`.

### Docker Compose Changes

Every compose file that currently hardcodes `name: unicoach-network` in the
`networks.default` block must be updated to reference the variable.

Before:

```yaml
networks:
  default:
    name: unicoach-network
```

After:

```yaml
networks:
  default:
    name: ${UNICOACH_NETWORK:-unicoach-network}
```

The `:-` default ensures compose files remain functional if invoked outside the
wrapper (e.g., raw `docker compose` commands during debugging).

Affected compose files:

- `docker/postgres-compose.yaml`
- `docker/rest-server-compose.yaml`
- `docker/queue-worker-compose.yaml`
- `docker/test-compose.yaml`
- `docker/test-fuzz-compose.yaml`

### Shell Layer: `bin/docker-compose`

Add `UNICOACH_NETWORK` to the inline environment variable block:

```bash
HOST_UID="$(id -u)" \
HOST_GID="$(id -g)" \
PORT="${PORT:-8080}" \
HEALTH_NONCE="${HEALTH_NONCE:-}" \
COMPOSE_IGNORE_ORPHANS=true \
POSTGRES_DATA_DIR="${POSTGRES_DATA_DIR:-./var/postgres}" \
POSTGRES_USER="${POSTGRES_USER:-postgres}" \
POSTGRES_DB="${POSTGRES_DB:-unicoach}" \
POSTGRES_HOST_AUTH_METHOD="${POSTGRES_HOST_AUTH_METHOD:-trust}" \
UNICOACH_NETWORK="${UNICOACH_NETWORK:-unicoach-network}" \
docker compose --env-file /dev/null --project-directory "$PROJECT_ROOT" "$@"
```

### Shell Layer: `bin/docker-status`

Replace all three hardcoded `unicoach-network` references with
`$UNICOACH_NETWORK`:

```bash
echo "Network:"
if docker network inspect "$UNICOACH_NETWORK" >/dev/null 2>&1; then
  echo "  $UNICOACH_NETWORK    active"
else
  echo "  $UNICOACH_NETWORK    missing"
fi
```

### Shell Layer: Test Harness EXIT Traps

Every test harness EXIT trap that calls `docker network rm unicoach-network`
must be updated to use the variable. `bin/test` currently has no EXIT trap and
must gain one to clean up the test network. Affected files and their updated
traps:

#### `bin/scripts-tests`

```bash
trap 'bin/rest-server-stop -d >/dev/null 2>&1 || true; bin/queue-worker-stop -d >/dev/null 2>&1 || true; bin/postgres-stop -d >/dev/null 2>&1 || true; docker network rm "$UNICOACH_NETWORK" >/dev/null 2>&1 || true' EXIT INT TERM
```

#### `bin/db-scripts-tests`

```bash
trap 'bin/rest-server-stop -d >/dev/null 2>&1 || true; bin/postgres-stop -d >/dev/null 2>&1 || true; docker network rm "$UNICOACH_NETWORK" >/dev/null 2>&1 || true' EXIT INT TERM
```

#### `bin/q-scripts-tests`

```bash
trap 'bin/postgres-stop -d >/dev/null 2>&1 || true; docker network rm "$UNICOACH_NETWORK" >/dev/null 2>&1 || true' EXIT INT TERM
```

#### `bin/db-users-tests`

```bash
trap 'bin/postgres-stop -d >/dev/null 2>&1 || true; docker network rm "$UNICOACH_NETWORK" >/dev/null 2>&1 || true' EXIT INT TERM
```

#### `bin/test-fuzz`

```bash
trap '"$PROJECT_ROOT/bin/rest-server-stop" --docker-down >/dev/null 2>&1 || true; "$PROJECT_ROOT/bin/postgres-stop" --docker-down >/dev/null 2>&1 || true; docker network rm "$UNICOACH_NETWORK" >/dev/null 2>&1 || true' EXIT INT TERM
```

#### `bin/test` (new)

```bash
trap 'docker network rm "$UNICOACH_NETWORK" >/dev/null 2>&1 || true' EXIT INT TERM
```

`bin/test` does not stop postgres because it is shared with the dev environment.
The trap only removes the test network left behind by the compose `run` command.

### Shell Layer: Stale Comment Updates

Replace hardcoded `unicoach-network` references in comments with
environment-neutral wording.

#### `bin/scripts-tests`

Line 64: Replace `unicoach-network` with `the Docker network` in the comment
about JVM daemon stop behavior.

Line 149: Replace `unicoach-network` with `the Docker network` in the comment
about database connectivity.

#### `bin/test`

Line 43: Replace `unicoach-network` with `the Docker network` in the comment
about gradle-runner connectivity.

Line 47: Rewrite comment to:
`# Network cleanup is handled by the EXIT trap above.`

### Data Models

No new data models. No database changes.

### API Contracts

No API changes.

### Error Handling / Edge Cases

- **Variable unset**: All compose files use
  `${UNICOACH_NETWORK:-unicoach-network}` as a fallback. `bin/docker-compose`
  defaults to `unicoach-network` via `:-`. Scripts sourcing `bin/common` (which
  sources the env file) will always have the variable set. The system degrades
  to the current behavior if the variable is missing.
- **Concurrent dev and test**: A test run while dev is active will still stop
  the dev postgres (because test harnesses call `bin/postgres-stop -d`). This is
  unchanged behavior and acceptable — the test harnesses have always done this.
  The improvement is that teardown no longer destroys the dev _network_ or the
  dev _data directory_. After tests complete, `bin/postgres-start` in the dev
  context recreates only the dev postgres on `unicoach-network` with dev data
  intact.
- **Separate `POSTGRES_DATA_DIR`**: Dev uses `./var/postgres`, test uses
  `./var/postgres-test`. Both are covered by the existing `var/` entry in
  `.gitignore`. Test harnesses that call `rm -rf "$POSTGRES_DATA_DIR"` or
  `bin/db-destroy` only affect test data.
- **Port contention on 5432**: Only one postgres instance can bind to host
  port 5432. Test harnesses enforce this by stopping postgres before starting
  their own. This is unchanged.

### Dependencies

No new dependencies.

## Tests

### Manual Verification: Dev Environment Survival

1. Start the dev environment:
   `bin/postgres-start && bin/db-init && bin/db-migrate && bin/rest-server-start`.
2. Insert dev test data:
   `echo "CREATE TABLE IF NOT EXISTS canary (id INT);" | bin/db-update`.
3. Verify dev is healthy: `bin/docker-status` shows `unicoach-network active`,
   `postgres running`, `rest-server running`.
4. Run `bin/scripts-tests` in a separate terminal.
5. After tests complete, verify: `docker network inspect unicoach-network`
   succeeds (the dev network was not destroyed).
6. Verify: `bin/docker-daemon-check rest-server` still reports healthy (dev
   rest-server was not killed by test teardown).
7. Verify dev data survived: `echo "SELECT * FROM canary;" | bin/db-query`
   succeeds (dev postgres data was not nuked by test harness).

### Manual Verification: Test Network Isolation

1. Run `bin/scripts-tests`.
2. During test execution, verify:
   `docker network ls | grep unicoach-test-network` shows the test network
   exists.
3. After tests complete, verify: `docker network inspect unicoach-test-network`
   fails (test network was cleaned up by EXIT trap).

### Existing Test Suites Pass

All existing test harnesses must continue to pass without modification to test
logic:

- `bin/scripts-tests`
- `bin/db-scripts-tests`
- `bin/q-scripts-tests`
- `bin/db-users-tests`
- `bin/test` (Gradle JVM tests)

### `bin/docker-status` Reflects Active Environment

1. Source `.env` (dev). Run `bin/docker-status`. Output shows `unicoach-network`
   in the Network section.
2. Source `.env.test` (test). Run `bin/docker-status`. Output shows
   `unicoach-test-network` in the Network section.

## Implementation Plan

### Step 1: Update environment files

In `.env`:

- Add `UNICOACH_NETWORK=unicoach-network`.
- Remove the stale `# POSTGRES_DATA_DIR should be identical...` comment.

In `.env.test`:

- Add `UNICOACH_NETWORK=unicoach-test-network`.
- Change `POSTGRES_DATA_DIR=./var/postgres` to
  `POSTGRES_DATA_DIR=./var/postgres-test`.
- Remove the stale `# POSTGRES_DATA_DIR should be identical...` comment.

Verify: `grep -E 'UNICOACH_NETWORK|POSTGRES_DATA_DIR' .env .env.test` shows
correct values for both environments.
`grep -c 'should be identical' .env .env.test` returns 0 matches.

### Step 2: Update `bin/docker-compose`

Add `UNICOACH_NETWORK="${UNICOACH_NETWORK:-unicoach-network}"` to the inline
environment variable block, before the `docker compose` invocation.

Verify: `bin/docker-compose --help` exits 0.

### Step 3: Update all Docker Compose files

Replace `name: unicoach-network` with
`name: ${UNICOACH_NETWORK:-unicoach-network}` in:

- `docker/postgres-compose.yaml`
- `docker/rest-server-compose.yaml`
- `docker/queue-worker-compose.yaml`
- `docker/test-compose.yaml`
- `docker/test-fuzz-compose.yaml`

Verify: `grep UNICOACH_NETWORK docker/*-compose.yaml` returns 5 matches.

### Step 4: Update `bin/docker-status`

Replace the three hardcoded `unicoach-network` string references with
`$UNICOACH_NETWORK`.

Verify: `bin/docker-status -h` exits 0.

### Step 5: Update test harness EXIT traps and stale comments

Replace `docker network rm unicoach-network` with
`docker network rm "$UNICOACH_NETWORK"` in:

- `bin/scripts-tests`
- `bin/db-scripts-tests`
- `bin/q-scripts-tests`
- `bin/db-users-tests`
- `bin/test-fuzz`

Add a new EXIT trap to `bin/test` (which currently has none):

```bash
trap 'docker network rm "$UNICOACH_NETWORK" >/dev/null 2>&1 || true' EXIT INT TERM
```

Update stale `unicoach-network` references in comments in `bin/scripts-tests`
(lines 64, 149) and `bin/test` (lines 43, 47) per the Detailed Design.

Verify:
`grep 'unicoach-network' bin/scripts-tests bin/db-scripts-tests bin/q-scripts-tests bin/db-users-tests bin/test-fuzz bin/test`
returns 0 matches.

### Step 6: Full verification

Run `bin/scripts-tests` to verify all daemon lifecycle tests pass with the
isolated test network.

## Files Modified

- `.env` [MODIFY]
- `.env.test` [MODIFY]
- `bin/docker-compose` [MODIFY]
- `bin/docker-status` [MODIFY]
- `bin/scripts-tests` [MODIFY]
- `bin/db-scripts-tests` [MODIFY]
- `bin/q-scripts-tests` [MODIFY]
- `bin/db-users-tests` [MODIFY]
- `bin/test` [MODIFY]
- `bin/test-fuzz` [MODIFY]
- `docker/postgres-compose.yaml` [MODIFY]
- `docker/rest-server-compose.yaml` [MODIFY]
- `docker/queue-worker-compose.yaml` [MODIFY]
- `docker/test-compose.yaml` [MODIFY]
- `docker/test-fuzz-compose.yaml` [MODIFY]
