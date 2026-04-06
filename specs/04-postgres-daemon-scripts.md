## Executive Summary

This specification defines the implementation of daemon lifecycle wrappers and a
containerized PostgreSQL environment. The setup will allow developers to
flexibly start, stop, restart, and check a local database instance via standard
engine scripts. Crucially, the orchestrations will be designed so that the rest
server and other future services can reliably resolve and connect (within a
standard 15-second initialization threshold) to the database securely via an
internal DNS system, while still allowing developers to connect via local
desktop GUI tools.

## Detailed Design

### Docker Compose Topology

PostgreSQL will be configured in `docker/postgres-compose.yaml` utilizing
localized default overrides:

- **Image**: `postgres:18` (or similar stable version).
- **Port Mapping**: `5432:5432` to allow local host GUI tools to connect.
- **Persistence**: A host-bound directory driven by an environment variable
  `POSTGRES_DATA_DIR` (defaulting to `./var/postgres` in `.env`) to persist
  state across daemon restarts while preventing test pollution.
- **Networking**: `postgres-compose.yaml` and `rest-server-compose.yaml` will
  both attach to an external network via
  `networks: default: external: true, name: unicoach-network` to share internal
  DNS resolution and prevent teardown collision.

### Environment & Authentication

The database configuration will be synchronized securely via local environment
files. Both `.env` and `.env.template` will be modified to include:

- `POSTGRES_USER=postgres`
- `POSTGRES_DB=unicoach`
- `POSTGRES_HOST_AUTH_METHOD=trust` (Disabling passwords for local developer
  ease and container bridging).

### Daemon Wrappers & Healthchecks

Four thin wrapper scripts (`bin/postgres-start`, `bin/postgres-stop`,
`bin/postgres-restart`, and `bin/postgres-check`) will be introduced. They will
strictly pass through execution to their respective `bin/docker-daemon-[action]`
engines.

To handle the shared network natively while strictly decoupling orchestrations,
four new utilities will also be introduced:
`bin/docker-network-daemon-{start,stop,restart,check}`. These native scripts
will deliberately mirror the file-locking and concurrency logic currently baked
into `bin/docker-daemon-[action]` to ensure absolute safety from race
conditions without sacrificing simple transparency through excessive abstractions.
Their orchestrations will rely purely on native `docker network` executions
rather than hitting Docker Compose.

For `docker-network-daemon-check`, it will independently execute explicit native
validation (e.g., `docker network inspect unicoach-network >/dev/null 2>&1`).

The `postgres-start`, `rest-server-start`, `postgres-restart`, and
`rest-server-restart` scripts will now invoke `docker-network-daemon-start` before
their delegated `exec` hand-off, ensuring that even after out-of-bounds network
pruning, a restart cycle can safely initialize the DNS bridge before spinning up
containers. Crucially, daemon stop scripts will **not** invoke
`docker-network-daemon-stop`, ensuring safe teardown without "active endpoint"
collisions from other running daemons.

In addition, explicit validation of core environment variables (e.g.,
`POSTGRES_USER`, `POSTGRES_DB`) and overlapping host ports will be enforced
natively by Docker Compose during boot. Relying on compose ensures clean
failures for overlaps or missing schema properties directly without requiring
brittle bash pre-flight implementations.

To satisfy the generic engine's synchronization requirements,
`postgres-compose.yaml` will explicitly map the `.env` variables down into the
Postgres service's `environment:` block. This ensures they are accessible inside
the container shell for the native orchestrator healthcheck utilizing
`pg_isready -U ${POSTGRES_USER}`. This will be tuned with the following
parameters: `interval: 2s`, `timeout: 2s`, `retries: 7`.

## Tests

The test harness will be expanded by updating `bin/scripts-tests` (or
introducing a dedicated `bin/daemon-wrapper-tests`). This harness will
comprehensively test the wrappers (`rest-server`, `postgres`, and
`docker-network`). A global `trap` teardown will ensure all daemons are safely
killed regardless of test failures.

Every daemon (including the network utilities) will be verified against the
following matrix:

1. **Simple Start & Stop**: Start completes successfully, `check` returns 0,
   stop completes successfully, `check` returns 1.
2. **Idempotent Start**: Starting an already running daemon resolves cleanly
   without booting duplicate containers.
3. **Concurrent Start**: Running two start commands simultaneously via shell
   backgrounds (`&`) resolves cleanly into a single running daemon.
4. **Idempotent Stop**: Issuing stop against an already stopped daemon exits
   cleanly with 0.
5. **Concurrent Stop**: Running two stop commands simultaneously safely resolves
   to full teardown without lock corruption.
6. **Restart Lifecycle**: Restarting correctly stops then immediately starts the
   service.
7. **Healthcheck Failure**: Intentionally supply an invalid configuration to
   guarantee healthcheck failure. Verify that start exits with 1, the wrapper
   proxies the orchestration error, and subsequent checks return 1.
8. **Port Overlap Prevention**: Start a rogue dummy service binding port `5432`
   on the host, then verify `postgres-start` fails definitively with a `1` exit
   code and a clear error directly from Docker Compose natively without
   corrupting or restarting the rogue process.
9. **Missing Environment Validation**: Explicitly unset `POSTGRES_USER` in the
   current shell environment and execute the wrapper. Verify the start
   delegation fails with a `1` exit code directly from Docker Compose enforcing
   its schema.
10. **Data Persistence**: Override `POSTGRES_DATA_DIR` to an ephemeral test path
    (e.g., `./var/postgres-test`). Start the Postgres daemon, execute a query to
    create a dummy table and insert a record. Stop the daemon, start it again,
    and query to verify the record still exists. Finally, the test harness must
    manually wipe this ephemeral directory to prevent test pollution.
11. **Network Bridge Integration**: Start both Postgres and the REST server
    daemons successfully. From inside the REST server dummy container, execute a
    command (e.g., `ping -c 1 postgres`) to verify the custom network correctly
    resolves the internal DNS bridge.

## Implementation Plan

1. **Refactor Test Harness**: Update `bin/scripts-tests` to parameterize the
   lifecycle test matrix. Begin by successfully testing the existing
   `rest-server` wrapper to establish a baseline.
2. **Environment & Core Compose Configuration**: Update `.env`/`.env.template`
   with core database variables. Update `docker/rest-server-compose.yaml` to
   enforce the custom network layout.
3. **PostgreSQL Compose Instantiation**: Create `docker/postgres-compose.yaml`
   enforcing port mappings, the parameterized `POSTGRES_DATA_DIR` persistent
   volume, network bridging, and the `pg_isready` healthcheck. _Verification_:
   Execute `docker compose -f docker/postgres-compose.yaml config` immediately
   to ensure schema interpolation and YAML correctness locally before
   proceeding.
4. **Network Daemon Creation**: Create the `bin/docker-network-daemon-*` utilities.
   These should be handwritten and inherently mirror the `file-lock` idempotency
   patterns utilized by the `docker-daemon-*` family explicitly, but relying on
   `docker network` execution payloads natively.
5. **Thin Wrapper Generation**: Create `bin/postgres-*` wrappers and explicitly
   `chmod +x` them. Their internal bash content must strictly mirror the
   `bin/rest-server-*` wrappers (e.g.,
   `exec "$PROJECT_ROOT/bin/docker-daemon-[action]" postgres "$@"`).
   Finally, attach `docker-network-daemon-start` execution blocks to the tops
   of the `rest-server` and `postgres` start/restart wrappers.
6. **Test Validation & Completion**: Pass the newly created `postgres` wrapper
   through the parameterized test matrix to verify structural start, stop,
   check, and restart idempotency.

## Files Modified

#### [NEW]

- `docker/postgres-compose.yaml`
- `bin/postgres-start`
- `bin/postgres-stop`
- `bin/postgres-restart`
- `bin/postgres-check`
- `bin/docker-network-daemon-start`
- `bin/docker-network-daemon-stop`
- `bin/docker-network-daemon-restart`
- `bin/docker-network-daemon-check`

#### [MODIFY]

- `bin/rest-server-start` (Invoke `docker-network-daemon-start` before execution)
- `bin/rest-server-restart` (Invoke `docker-network-daemon-start` before execution)
- `bin/scripts-tests` (Refactor to utilize parametrized suite tests against
  actual wrappers, and add `docker-network-daemon-stop` manually to trap teardown)
- `docker/rest-server-compose.yaml` (Inject explicit external network routing)
- `.env`
- `.env.template`
