# Native Daemon Scripts

## Executive Summary
This spec outlines the migration from a Docker-backed process orchestration model to a native host process model managed via `nix`. We will replace `docker-daemon-*` lifecycle scripts with generic native bash equivalents (`daemon-up`, `daemon-down`, `daemon-check`, `daemon-bounce`, `daemon-status`). This removes all Docker dependencies, including the `docker/` configuration folder, while preserving the existing developer interfaces (e.g., `bin/postgres-up`). Daemons will execute natively in the background, with standard output routed to `var/log/<service>.log` and PIDs tracked in `var/run/<service>.pid`.

Two additional design decisions are incorporated: (1) **Build/run separation** — JVM daemon wrappers launch pre-built Gradle `installDist` binaries rather than invoking `./gradlew run`; a new `bin/build` top-level script owns full compilation by delegating to per-module build scripts; (2) **Health nonce removal** — the `HealthMarker` nonce-file readiness protocol is deleted from the JVM codebase; native readiness checks use port probing (`rest-server`) and PID liveness (`queue-worker`) instead.

## Detailed Design

**Execution Context (`daemon-up`):**
Instead of inferring commands from `docker-compose.yaml`, `daemon-up` will accept the explicit command to run as trailing arguments: `bin/daemon-up <service-name> -- <command...>`. The engine will natively execute the command in the background (e.g., using `nohup <command> > var/log/<service>.log 2>&1 &` or similar).

**Process Tracking & Lifecycle:**
Upon execution, `daemon-up` will capture the child's bash process ID (`$!`) and persist it to `var/run/<service>.pid`.

**Concurrency & Idempotency (`daemon-up`):**
`daemon-up` must use `bin/file-lock -o up -t 16 var/run/<service>.daemon.lock 60` to serialize concurrent invocations. If the lock is already held by an `up` operation (exit code 10), the second caller exits `0` immediately without spawning a duplicate process. If the lock is held by a conflicting operation, `daemon-up` fatals. After acquiring the lock, `daemon-up` checks whether the PID file exists and the recorded process is still alive (`bin/check-pid <pid>`) before launching; if the process is already live, it exits `0` without re-spawning. The lock directory is `var/run/<service>.daemon.lock` and is released via a trap on `EXIT INT TERM`.

**Process Termination (`daemon-down`):**
The `stop` semantics are being replaced globally with `down`. `daemon-down` will read the associated PID file, send `SIGTERM`, and use `bin/wait-for` with `bin/check-pid` to gracefully ensure the process terminates (e.g., `bin/wait-for 30s bin/check-pid <pid>`). If it refuses, it will escalate to `SIGKILL`. All service wrappers (e.g., `bin/postgres-stop`) will be renamed to `bin/postgres-down`.

**Postgres Lifecycle Exception (`pg_ctl` / `postmaster.pid`):**
PostgreSQL is not managed via `daemon-up`/`daemon-down`. It uses native `pg_ctl` because postgres maintains its own authoritative PID file (`postmaster.pid`) and process group semantics.

- **`bin/postgres-up`**: calls `pg_ctl start -D "$DATA_DIR" -l "$LOG_FILE" -o "..." --silent < /dev/null > /dev/null 2>&1 || true`. The explicit fd redirection prevents the postmaster from inheriting the calling shell's pipes; if it did, the parent shell would receive a signal or die when postgres subsequently shuts down and closes inherited fds. The `|| true` handles concurrent-start races gracefully — the downstream health check (`postgres-wait-for-health`) catches any genuine failure. Idempotency is checked against `postmaster.pid` before calling `pg_ctl`.
- **`bin/postgres-down`**: reads the PID from `head -1 "$DATA_DIR/postmaster.pid"`, sends `SIGTERM` (smart shutdown — no process-group propagation), then polls on `postmaster.pid` deletion with a 4-second grace period. `kill -0` is intentionally avoided because PID reuse makes it unreliable: a different process may acquire the same PID before the poll loop checks. Escalates to `SIGKILL` if the grace period expires.
- **`bin/db-init`**: owns one-time cluster initialisation via `initdb`. `postgres-up` does NOT call `initdb`; it requires the cluster to already exist. `db-init` is the entry point for bootstrapping a fresh environment.
- **`bin/postgres-bounce`**: delegates to `postgres-down` then `exec postgres-up`.

**PID Liveness (`bin/check-pid`):**
A new utility script `bin/check-pid <pid>` will exit `0` if the process is alive (`kill -0 <pid>`) and `1` if it is not. Used by `daemon-down` for graceful termination polling.

**Build/Run Separation (`bin/build` and per-module scripts):**
JVM daemon wrappers (`bin/rest-server-up`, `bin/queue-worker-up`) must not invoke `./gradlew run`. Compilation is a separate, explicit step owned by a two-layer build script hierarchy:

- **Per-module scripts** each invoke a single Gradle task for their module:
  - Library modules (`bin/build-common`, `bin/build-db`, `bin/build-service`, `bin/build-queue`, `bin/build-net`) → `./gradlew :<module>:assemble`
  - Executable modules (`bin/build-rest-server`, `bin/build-queue-worker`) → `./gradlew :<module>:installDist`, producing self-contained launch scripts at `<module>/build/install/<module>/bin/<module>`
- **`bin/build`** calls all per-module scripts in dependency order: `common` → `db` → `service` → `queue` → `net` → `rest-server` → `queue-worker`. If any per-module script fails, `bin/build` exits immediately with its exit code.

The `*-up` wrappers pass the `installDist` binary path directly to `daemon-up` as the command. If the binary does not exist at startup, the wrapper must fail fast with an explicit error rather than silently invoking Gradle.

**Health Nonce Removal:**
The `HealthMarker` nonce-file protocol (`var/run/<service>.check`, JVM system properties `run.dir`, `service.name`, `health.nonce`) is removed entirely. All call sites (`rest-server/Application.kt`, `queue-worker/Application.kt`) and the class itself (`common/HealthMarker.kt`, `common/HealthMarkerTest.kt`) are deleted.

**Health Check Abstraction (`*-check` and `*-wait-for-health`):**
Readiness checks are factored into a two-layer abstraction rather than inlined into `*-up` scripts:

- **`<service>-check`** — a single-attempt health probe. Exits `0` if the service is healthy, non-zero otherwise. All `*-check` scripts print a human-readable status line to **stderr** (`<service> is running` / `<service> is NOT running`) so operators get feedback during interactive use without interfering with exit-code-based automation. Service-specific probe implementations:
  - `postgres-check`: `pg_isready -q -h localhost -p 5432 -U $POSTGRES_USER` (connectivity proof)
  - `rest-server-check`: `curl -sf http://localhost:${PORT}/hello` (HTTP endpoint proof)
  - `queue-worker-check`: `daemon-check queue-worker` (PID liveness — no port to probe)

- **`<service>-wait-for-health`** — wraps `bin/wait-for <timeout> <service>-check`, prints a human-readable waiting message before polling and a confirmation on success.

`*-up` scripts call `daemon-up` to background the process, then delegate immediately to `*-wait-for-health`. `daemon-up` has no knowledge of health checks.

**Native Health Checks (readiness timeouts):**
- `postgres-wait-for-health`: `wait-for 4s postgres-check`
- `rest-server-wait-for-health`: `wait-for 4s rest-server-check`
- `queue-worker-wait-for-health`: `wait-for 4s queue-worker-check`

**Service Dependency Ordering:**
`*-up` scripts do not start their own dependencies. Dependency ordering (e.g. postgres before queue-worker) is the caller's responsibility. This keeps each script focused on a single service and allows isolated testing.

**Environment (`PGHOST`, `POSTGRES_DATA_DIR`, `SERVER_PORT`):**
Postgres is started with `-c "unix_socket_directories=$DATA_DIR"`, placing the Unix socket under `var/postgres/` rather than `/tmp`. To avoid requiring `-h localhost` on every `psql` invocation, `PGHOST=localhost` is set in `.env` and `.env.test`. All libpq clients (`psql`, `pg_isready`, `pg_dump`, etc.) honour this variable automatically.

`POSTGRES_DATA_DIR` is declared as an absolute path using the `$PROJECT_ROOT` variable (e.g. `POSTGRES_DATA_DIR=$PROJECT_ROOT/var/postgres`). Scripts read the value as-is; there is no per-script relative-path expansion. This ensures the data directory resolves correctly regardless of the working directory from which a script is invoked.

`SERVER_PORT` is derived from `PORT` (`SERVER_PORT=$PORT`) in all env files. This makes `PORT` the single source of truth for the bind address so that `rest-server.conf` (`port = ${?SERVER_PORT}`) and health-check scripts (`curl http://localhost:${PORT}/hello`) remain consistent.

**Test/Dev Cluster Sharing:**
Both the dev (`.env`) and test (`.env.test`) environments share the same PostgreSQL cluster (`$PROJECT_ROOT/var/postgres/`). Isolation is at the database level: `POSTGRES_DB=unicoach` for dev and `POSTGRES_DB=unicoach-test` for test. This avoids the overhead of maintaining two separate clusters during local development. `db-init` provisions both databases during initial bootstrap.

**Gradle Test Runner (`bin/test`):**
`bin/test` currently shells out to a Docker Compose Gradle runner container. Post-migration it will invoke `./gradlew` directly on the host, using the `java` binary provided by `nix`. The Docker network teardown trap and the Gradle runner compose file reference are removed.

**Environment & Dependencies (`flake.nix`):**
All toolchain dependencies are declared in `flake.nix` and provided as pre-built nix packages. This replaces the Docker images and eliminates any separate version manager. Developers activate the environment once with `nix develop`; all tools are then available on `PATH` for the duration of that shell session.

| Tool | nix package | Replaces |
|---|---|---|
| Java 21 (Temurin) | `pkgs.temurin-bin-21` | `eclipse-temurin:21-jdk` |
| PostgreSQL 18 | `pkgs.postgresql_18` | `postgres:18` |
| Python 3 | `pkgs.python3` | `schemathesis:stable` |
| Deno 2 | `pkgs.deno` | `node:20-alpine` + `npx prettier` |
| ktlint | `pkgs.ktlint` | `eclipse-temurin:21-jdk` + `gradlew ktlintFormat` |
| git | `pkgs.git` | system `/usr/bin/git` (macOS stub) |

`git` is explicitly declared because nix sets `GIT_EXEC_PATH` to its own nix store path during shell setup, which makes the macOS `/usr/bin/git` stub unable to find its subcommands. Including `pkgs.git` puts a fully self-consistent git binary first on `PATH`.

`bin/dev-bootstrap` verifies that `nix` is installed and prints the activation instructions. It no longer registers plugins or runs install commands.


**Formatter Migration (`bin/format`, `bin/pre-commit`):**
Both scripts currently shell out to Docker to run formatters. Post-migration:
- Kotlin formatting: `ktlint --format "**/*.kt" "**/*.kts"` (invokes the `ktlint` binary from nix). Existing `.editorconfig` rules are preserved and read natively by ktlint.
- Markdown formatting: `deno fmt "**/*.md"` (Deno's built-in formatter). A new `deno.json` config file replaces `.prettierrc` and `.prettierignore`. Note: Deno uses `"lineWidth"` (not Prettier's `"printWidth"`) and `"proseWrap"` (same key name). Exclusions go under `"fmt": { "exclude": [...] }` (not `.prettierignore`). The exact `deno.json` structure is:

  ```json
  {
    "fmt": {
      "lineWidth": 80,
      "proseWrap": "always",
      "exclude": ["build/", ".gradle/", "var/", ".idea/", ".gemini/"]
    }
  }
  ```

- `.prettierrc` and `.prettierignore` are deleted.

## Tests

**Daemon Engine (`bin/scripts-tests`):**
`test_daemon_wrapper` covers `rest-server` and `queue-worker` only. Postgres is excluded because it is managed via `pg_ctl` (not `daemon-*` scripts) and its lifecycle is not a core application concern.

- Assert that `var/run/<service>.pid` is created by `daemon-up` and deleted by `daemon-down`.
- Assert that `var/log/<service>.log` is created and receives stdout/stderr output from the backgrounded process.
- Assert that a stale PID file (process exited externally) is detected and handled gracefully by `daemon-down` without error.
- Assert that `daemon-up` is idempotent: a second invocation while the service is running exits `0` without spawning a duplicate process.
- Assert concurrent `daemon-up` invocations for the same service do not spawn duplicate processes (lock coordination via `bin/file-lock`).
- Assert `daemon-down` escalates to `SIGKILL` when the process ignores `SIGTERM` (tested via a dummy `sigkill-test` service — a bash process that traps and ignores SIGTERM, with its PID written to `var/run/sigkill-test.pid`).
- Remove the "Stale Marker Resilience" test block entirely — the `var/run/<service>.check` nonce file no longer exists.

**Daemon Utilities (`bin/scripts-tests`):**
- Assert `daemon-status` prints each known service name alongside its running/stopped state, one per line.
- Assert `daemon-status -h` exits `0`.

**PID Liveness (`bin/check-pid`):**
- Assert `check-pid <pid>` exits `0` when the PID is alive.
- Assert `check-pid <pid>` exits `1` when the PID does not exist.

**Build Scripts (`bin/build`, `bin/build-*`):**
- Assert that each per-module script (`bin/build-common`, `bin/build-db`, `bin/build-service`, `bin/build-queue`, `bin/build-net`, `bin/build-rest-server`, `bin/build-queue-worker`) exits `0` when Gradle succeeds and propagates the non-zero exit code on Gradle failure.
- Assert that after `bin/build-rest-server`, `rest-server/build/install/rest-server/bin/rest-server` exists and is executable.
- Assert that after `bin/build-queue-worker`, `queue-worker/build/install/queue-worker/bin/queue-worker` exists and is executable.
- Assert that `bin/build` exits non-zero (and immediately) if any per-module script fails.

**Wrapper Guard (`bin/rest-server-up`, `bin/queue-worker-up`):**
- Assert that invoking `*-up` when the `installDist` binary is absent exits non-zero with an explicit error message (not a Gradle invocation).

**Formatter Scripts (`bin/format`, `bin/pre-commit`):**
- Assert `bin/format` exits `0` on a correctly formatted `.kt` file and exits `0` on a correctly formatted `.md` file.
- Assert `bin/format` exits non-zero if either formatter fails (ktlint rule violation or Deno parse error).
- Assert `bin/pre-commit` exits non-zero when a `.kt` file has a lint violation.
- Assert `bin/pre-commit` exits non-zero when a `.md` file violates `deno fmt --check`.

**JVM Unit Tests (Kotlin):**
- Delete `common/HealthMarkerTest.kt` — the class is removed; no replacement test is required.
- Verify `rest-server` and `queue-worker` compile and their existing unit tests pass after removing the `HealthMarker` import and call site.

## Implementation Plan
1. Create `flake.nix` declaring all project tools (`temurin-bin-21`, `postgresql_18`, `python3`, `deno`, `ktlint`) as nix packages. Update `bin/dev-bootstrap` to verify nix is installed and print the `nix develop` activation instructions. Create `deno.json` translating `.prettierrc`/`.prettierignore` rules. Delete `.prettierrc` and `.prettierignore`. Verify: `nix develop` activates without error and puts all tools on PATH.
2. Delete `common/HealthMarker.kt` and `common/HealthMarkerTest.kt`. Remove the `HealthMarker.markHealthy()` call and its import from `rest-server/Application.kt` and `queue-worker/Application.kt`. Verify: `./gradlew :rest-server:compileKotlin :queue-worker:compileKotlin :common:test` passes. Additionally verify: `grep -r "HealthMarker" . --include="*.kt"` returns zero matches.
3. Create `bin/check-pid`. Verify: `bin/check-pid $$` exits `0`; `bin/check-pid 999999` exits `1`.
4. Create the seven per-module build scripts: `bin/build-common`, `bin/build-db`, `bin/build-service`, `bin/build-queue`, `bin/build-net`, `bin/build-rest-server`, `bin/build-queue-worker`. Create `bin/build` as the top-level orchestrator. Verify: `bin/build-rest-server` produces `rest-server/build/install/rest-server/bin/rest-server`; `bin/build-queue-worker` produces `queue-worker/build/install/queue-worker/bin/queue-worker`; `bin/build` succeeds end-to-end.
5. Create the new engine scripts: `bin/daemon-up`, `bin/daemon-down`, `bin/daemon-check`, `bin/daemon-bounce`, `bin/daemon-status`.
6. Delete the legacy Docker engine scripts (`bin/docker-daemon-*`, `bin/docker-compose`, `bin/docker-run`, `bin/docker-status`) and the entire `docker/` directory.
7. Create wrapper `*-down` scripts (`bin/postgres-down`, `bin/rest-server-down`, `bin/queue-worker-down`) and delete the corresponding `*-stop` scripts.
8. Create wrapper `*-up` scripts (`bin/postgres-up`, `bin/rest-server-up`, `bin/queue-worker-up`) and delete the corresponding `*-start` scripts. Each JVM `*-up` script guards that the pre-built `installDist` binary exists before calling `daemon-up` and runs its service-specific readiness probe via `bin/wait-for` as specified in §Detailed Design. Delete the corresponding `*-start` scripts.
9. Update `bin/postgres-check` (use `pg_isready`), `bin/rest-server-check` (use `curl /hello`), and `bin/queue-worker-check` (delegate to `daemon-check`). Create `bin/postgres-wait-for-health`, `bin/rest-server-wait-for-health`, and `bin/queue-worker-wait-for-health`. Update `*-up` scripts to call `*-wait-for-health` instead of inlining `bin/wait-for` invocations.
10. Update `bin/test` to invoke `./gradlew` natively, removing the Gradle runner Docker Compose reference and the `docker network rm` trap.
11. Update `bin/test-fuzz` to use `rest-server-up`/`rest-server-down` natively and invoke `schemathesis` via the `python` binary provided by `nix`, removing all Docker references.
12. Update `bin/db-run` and `bin/db-repl` to invoke `psql` natively (via the `postgres` binary from `nix`) instead of `docker-compose exec`. Add `PGHOST=localhost` to `.env` and `.env.template` so all psql clients use TCP by default.
13. Update `bin/format` to invoke `ktlint --format` (Kotlin) and `deno fmt` (Markdown) natively. Update `bin/pre-commit` to invoke `ktlint` (check) and `deno fmt --check` (Markdown) natively. Verify: `bin/format` exits `0` on clean project; `bin/pre-commit` exits `0` on clean project.
14. Refactor `bin/scripts-tests`, `bin/db-scripts-tests`, `bin/db-users-tests`, and `bin/q-scripts-tests` to remove all Docker references (network teardown, `docker exec`, `bin/docker-compose`). Update `scripts-tests` to remove the stale nonce marker test block. Verify: `bin/scripts-tests` passes end-to-end.

## Files Modified

**New engine scripts:**
- `flake.nix` [NEW]
- `bin/build` [NEW]
- `bin/build-common` [NEW]
- `bin/build-db` [NEW]
- `bin/build-service` [NEW]
- `bin/build-queue` [NEW]
- `bin/build-net` [NEW]
- `bin/build-rest-server` [NEW]
- `bin/build-queue-worker` [NEW]
- `bin/check-pid` [NEW]
- `bin/daemon-up` [NEW]
- `bin/daemon-down` [NEW]
- `bin/daemon-check` [NEW]
- `bin/daemon-bounce` [NEW]
- `bin/daemon-status` [NEW]
- `bin/postgres-wait-for-health` [NEW]
- `bin/rest-server-wait-for-health` [NEW]
- `bin/queue-worker-wait-for-health` [NEW]

**Deleted Docker engine scripts:**
- `bin/docker-daemon-start` [DELETE]
- `bin/docker-daemon-stop` [DELETE]
- `bin/docker-daemon-check` [DELETE]
- `bin/docker-daemon-restart` [DELETE]
- `bin/docker-status` [DELETE]
- `bin/docker-compose` [DELETE]
- `bin/docker-run` [DELETE]
- `docker/` [DELETE]

**Health nonce removal (JVM):**
- `common/src/main/kotlin/ed/unicoach/common/HealthMarker.kt` [DELETE]
- `common/src/test/kotlin/ed/unicoach/common/HealthMarkerTest.kt` [DELETE]
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` [MODIFY]
- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` [MODIFY]

**Service wrapper renames:**
- `bin/postgres-start` [DELETE]
- `bin/postgres-up` [NEW]
- `bin/postgres-stop` [DELETE]
- `bin/postgres-down` [NEW]
- `bin/postgres-check` [MODIFY]
- `bin/postgres-bounce` [MODIFY]
- `bin/rest-server-start` [DELETE]
- `bin/rest-server-up` [NEW]
- `bin/rest-server-stop` [DELETE]
- `bin/rest-server-down` [NEW]
- `bin/rest-server-check` [MODIFY]
- `bin/rest-server-bounce` [MODIFY]
- `bin/queue-worker-start` [DELETE]
- `bin/queue-worker-up` [NEW]
- `bin/queue-worker-stop` [DELETE]
- `bin/queue-worker-down` [NEW]
- `bin/queue-worker-check` [MODIFY]
- `bin/queue-worker-bounce` [MODIFY]

**Formatter config:**
- `deno.json` [NEW]
- `.prettierrc` [DELETE]
- `.prettierignore` [DELETE]

**Utility and tool scripts:**
- `bin/dev-bootstrap` [MODIFY]
- `bin/db-run` [MODIFY]
- `bin/db-repl` [MODIFY]
- `bin/db-init` [MODIFY]
- `bin/db-destroy` [MODIFY]
- `bin/db-status` [MODIFY]
- `bin/format` [MODIFY]
- `bin/pre-commit` [MODIFY]
- `.env` [MODIFY]
- `.env.template` [MODIFY]

**Test suites:**
- `bin/scripts-tests` [MODIFY]
- `bin/db-scripts-tests` [MODIFY]
- `bin/db-users-tests` [MODIFY]
- `bin/q-scripts-tests` [MODIFY]
- `bin/test` [MODIFY]
- `bin/test-fuzz` [MODIFY]
