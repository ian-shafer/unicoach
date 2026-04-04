# 02 Hello World OpenAPI Spec

## Executive Summary

This specification defines the foundational OpenAPI endpoint for the REST API.
It implements a "Hello, World!" endpoint returning a static string. Its primary
architectural purpose is to act as a "tracer bullet" validating the complete
project scaffolding, including the multi-module Gradle configuration, Ktor
routing, Dockerized environment constraints, and OpenAPI Schemathesis contract
fuzzing.

## Detailed Design

### Endpoint Definition

- **Method**: `GET`
- **Path**: `/hello`
- **Response Format**: `text/plain; charset=utf-8`
- **Output string**: `"Hello, Ian. I love you 😘"`

### OpenAPI Contract

- **Location**: `api-specs/openapi.yaml`
- The schema will define a single `200 OK` response returning the exact static
  unicode string under the `text/plain; charset=utf-8` media type.

### Foundation Scaffolding

- **Multi-Module Gradle Strategy**: Bootstrap a multi-module Ktor architecture
  using a root `settings.gradle.kts` and `build.gradle.kts`. The application
  logic will be physically isolated within the `rest-server` submodule to
  guarantee architectural boundaries.
- **Dependency Management**: Dependencies will be managed centrally via a
  `gradle/libs.versions.toml` Version Catalog to prevent version drift across
  current and future submodules.
- **Linting & Formatting**: Enforce Kotlin style guidelines using the
  `org.jlleitschuh.gradle.ktlint` plugin applied to all projects at the root
  level, supported by a baseline `.editorconfig` file.
- **Ktor Configuration (`rest-server.conf`)**: Establish the application entry
  point and configure the server port via HOCON. The `rest-server` MUST utilize
  the **Netty** engine.
- **Environment Context Rules**:
  - **Docker Rule Enforcement**: The host `PORT` must be injected directly into
    the container by `docker/rest-server-compose.yaml`.
  - **No `.env` Parsers**: The design strictly forbids the installation of
    third-party Kotlin `.env` parsing libraries (e.g., `kotlin-dotenv`).
    `rest-server.conf` must natively resolve the injected port using HOCON
    syntax (e.g., `ktor.deployment.port = ${?PORT}`). **CRITICAL BINDING**: It
    MUST also explicitly declare `ktor.deployment.host = 0.0.0.0` to bind to all
    network interfaces, abandoning Ktor's `localhost` default so Docker can
    bridge its traffic.

## Tests

### Ktor `testApplication` Engine E2E

- _TestCase 1_: Assert that executing a `GET /hello` restricts the HTTP response
  entirely to a `200 OK` status.
- _TestCase 2_: Assert that the HTTP Response `Content-Type` header precisely
  mandates `text/plain; charset=utf-8`.
- _TestCase 3_: Assert that the raw response body text identically matches the
  unicode string `"Hello, Ian. I love you 😘"`.

### API Fuzzing (`Schemathesis`)

- _TestCase 4_: A bash script (`bin/test-fuzz`) will spin up an ephemeral
  container running the `schemathesis/schemathesis` Docker image. This fuzzer
  container will attach natively to the Docker compose network to reach the
  running `rest-server`. Because the server orchestration scripts are not yet
  robust, this test must implicitly "fail fast"—if the developer has not
  manually booted the `rest-server` beforehand, the fuzzer will immediately
  abort.

## Implementation Plan

1. **Bootstrap Gradle (Docker Context)**: Execute a Dockerized command to
   generate the Gradle wrapper directly, satisfying the host JVM ban. It MUST
   map the host user ID to prevent root-owned files from permanently locking the
   developer out of their local project directory:
   ```bash
   docker run --rm -u $(id -u):$(id -g) -v $PWD:/workspace -w /workspace -e GRADLE_USER_HOME=/workspace/var/gradle -e HOME=/workspace/var/gradle gradle:8.6-jdk21 gradle wrapper
   ```
2. **Initialize Build System Strategy**: Create `.gitignore` (which MUST
   explicitly ignore the `var/` directory). Create `gradle/libs.versions.toml`
   to centrally declare dependencies. The implementor MUST explicitly include
   the following Kotlin (2.x+) and Ktor (3.x+) artifacts to prevent version
   fragmentation and missing classpath tooling:
   - `io.ktor:ktor-server-core`
   - `io.ktor:ktor-server-netty`
   - `io.ktor:ktor-server-test-host`
   - `io.ktor.plugin` (Gradle Plugin)
   - `org.jetbrains.kotlin.jvm` (Gradle Plugin)
   - `org.jetbrains.kotlin:kotlin-test-junit5`
   - `ch.qos.logback:logback-classic`
   - `net.logstash.logback:logstash-logback-encoder` (Required for JSON SLF4J
     logging)

   Define the root `build.gradle.kts` with the `ktlint` community plugin
   injected globally. Initialize the root `.editorconfig` with a minimalistic
   baseline setting `[*.{kt,kts}]` and `insert_final_newline=true`. Create
   `gradle.properties` to govern Gradle cache and memory configurations.
   **CRITICAL CACHE RULE**: The implementor MUST explicitly configure
   `org.gradle.daemon=false` within this file. Because `bin/docker-run` spins up
   ephemeral containers, any spawned daemons will be violently killed by Docker
   upon exit, leaving orphaned lockfiles that will corrupt future executions.
   - _Note on Architecture_: While Spec 01 demands `common`, `service`, etc.,
     for this tracer bullet MVP, the implementor must INTENTIONALLY postpone
     creating those modules and ONLY scaffold `rest-server` for now.

3. **Manual Validation Scripting**:
   - Construct a `bin/functions` script to house standard utilities (e.g.,
     `log-info`, `fatal`). Let `bin/common` script (that is NOT randomly
     executable but strictly sourced) automatically source `bin/functions`.
     `bin/common` dynamically computes `PROJECT_ROOT` as the parent directory of
     `bin/` so that execution path context is always absolute. All other bash
     scripts MUST source `bin/common`.
   - Construct a `bin/docker-run` bash script. This script acts as a universal
     abstraction. It MUST source `bin/common` and automatically inject
     `--user "$(id -u):$(id -g)"`, volume mount `"$PROJECT_ROOT":/workspace`,
     set the working directory `-w /workspace`, and enforce
     `-e GRADLE_USER_HOME=/workspace/var/gradle` and
     `-e HOME=/workspace/var/gradle`. **CRITICAL CONTEXT**: It MUST verify if
     `"$PROJECT_ROOT/.env"` exists; if so, it MUST dynamically inject
     `--env-file "$PROJECT_ROOT/.env"` into the docker run invocation to
     guarantee environment parity with `docker-compose`. **CRITICAL SIGNATURE**:
     To remain "universal," the script MUST accept the target Docker image as
     its first CLI argument (parsing `"$1"` as the image and `"${@:2}"` as the
     command payload). All future single-command container tasks MUST use this
     script to prevent UID 501 permission bugs and root lockouts.
   - Construct a `bin/dev-bootstrap` bash script. It MUST source `bin/common`.
     This script will remain as an empty placeholder for now.
   - Construct a `bin/pre-commit` shell script. It MUST source `bin/common`.
     This script will be run _manually_ by the developer to validate the
     codebase before committing. Because the host JVM is explicitly banned, this
     script MUST execute gradle unit tests and `ktlintCheck` by utilizing the
     `bin/docker-run` abstraction (e.g.,
     `bin/docker-run eclipse-temurin:21-jdk ./gradlew check`) rather than raw
     Docker commands, as the `check` task natively invokes both tests and
     linting.
4. **Define OpenAPI Contract**: Construct `api-specs/openapi.yaml` detailing the
   `GET /hello` string schema exactly mapping to the unicode emoji.
5. **Scaffold `rest-server` Submodule**: Formulate `settings.gradle.kts` mapping
   to `rest-server`. Construct `rest-server/build.gradle.kts` leveraging the
   `.toml` catalog for dependencies. **CRITICAL WIRING**: Because we are
   bypassing the default `application.conf`, the implementor MUST explicitly
   instruct Ktor to load `rest-server.conf` in this build file (e.g., by
   ensuring `applicationArgs` or `-Dconfig.resource=` points to it), otherwise
   the server will boot empty and silently ignore the injected port. To
   correctly trigger Ktor's configuration parsing, the implementor MUST
   explicitly configure
   `application { mainClass.set("io.ktor.server.netty.EngineMain") }`. Do NOT
   artificially enforce `-Dfile.encoding=UTF-8` on the JVM args; the execution
   bounds guarantee JDK 21, establishing UTF-8 as the native default across the
   architecture (JEP 400).
6. **Implement Ktor Server & Tests**: Implement `Application.kt` and
   `Routing.kt`. Map `rest-server.conf` logic to utilize HOCON exclusively for
   environment inputs. Create `logback.xml` for structured JVM logging.
   **Implement `RoutingTest.kt` to execute the three `testApplication` E2E cases
   defined in the Tests section.** The implementor MUST configure
   `testApplication { environment { config = ApplicationConfig("rest-server.conf") } }`
   to ensure unit tests execute against the exact configuration parameters as
   the production runtime.
7. **Docker Compose Orchestration**: Create `.env.template` defining baseline
   variables and copy it to `.env` representing the local developer context
   (e.g., `PORT=8080`). Then create `docker/rest-server-compose.yaml` to
   orchestrate the Ktor development environment. The implementor MUST explicitly
   configure the compose service to:
   - Export the port back to the host via a `ports: - "${PORT}:${PORT}"` stanza.
     Without this mapping, the container will act as a black box and reject all
     local HTTP and Schemathesis traffic.
   - Use a pure JDK base image (e.g., `eclipse-temurin:21-jdk`) rather than a
     heavy Gradle image. This enforces the industry standard of relying entirely
     on the committed Gradle Wrapper (`./gradlew`) to pull and cache the exact
     required native build tools without redundant layers.
   - Use the `user: "${HOST_UID}:${HOST_GID}"` attribute to prevent root-owned
     file spillage.
   - Volume-mount the host's raw source code into the container (e.g.,
     `- ./:/workspace`). Because `docker-compose` is orchestrated with
     `--project-directory`, `./` resolves natively to the repository root.
   - Set the working directory to `/workspace` (e.g., `working_dir: /workspace`)
     so the `./gradlew` execution originates within the mounted project root
     rather than the container's `/` directory.
   - Start the server using the standard command: `./gradlew :rest-server:run`.
   - **NO HOT RELOADING**: We explicitly disable JVM hot reloading / swapping.
     The architect will not waste time getting this fragile system to work. Do
     NOT use the `-t` continuous build flag, and do NOT configure
     `watch = [ classes ]` in `rest-server.conf`.
   - DO NOT use a named Docker volume for the cache. Instead, explicitly inject
     both the host's port and the project-local cache path into the container
     (`environment: - PORT=${PORT}`, `- GRADLE_USER_HOME=/workspace/var/gradle`,
     and `- HOME=/workspace/var/gradle`). **CRITICAL BINDING**: Without
     injecting `PORT=${PORT}` into the interior environment, Ktor's
     `rest-server.conf` (`${?PORT}`) will silently resolve to null or crash,
     completely ignoring the exterior `.env` port mapping.
   - **Compose Wrapper**: Construct a `bin/docker-compose` bash script. It MUST
     source `bin/common`. To satisfy the `user:` attribute requirement above
     securely, this wrapper MUST dynamically `export HOST_UID="$(id -u)"` and
     `export HOST_GID="$(id -g)"` before passing all arguments to the underlying
     `docker compose --project-directory "$PROJECT_ROOT" "$@"` command. This
     absolutely guarantees the `.env` file at the project root is sourced during
     boot. Ensure all scripts are set `chmod +x`.
8. **Finalize Fuzz Orchestration**: Imprint `bin/test-fuzz` bash script. It MUST
   source `bin/common`. To maintain strict separation of concerns, the fuzzer
   MUST be housed in its own dedicated compose file:
   `docker/test-fuzz-compose.yaml`. The orchestration MUST deploy the scalable
   abstraction script `bin/wait-for` to verify the port connection organically
   before unleashing the fuzzer payload.
   - **Volume Contract**: The fuzzer compose service MUST volume-mount the
     host's `api-specs` directory into the ephemeral container so Schemathesis
     can legally parse the local `openapi.yaml` contract. **CRITICAL PATHING**:
     Because `bin/docker-compose` passes `--project-directory "$PROJECT_ROOT"`,
     all relative paths in the YAML are evaluated from the repository root. The
     implementor MUST set the volume map to `- ./api-specs:/api-specs` (NOT
     `../`) to correctly mount the directory without escaping the project
     boundary.
   - **Execution & Networking**: The `bin/test-fuzz` script will orchestrate
     exactly one command. **CRITICAL PATHING RULE**: To avoid breaking when
     executed from outside the project root without modifying shell directory
     state, the script MUST explicitly prefix all compose file paths with the
     `$PROJECT_ROOT` variable:
     `bin/docker-compose -f "$PROJECT_ROOT/docker/rest-server-compose.yaml" -f "$PROJECT_ROOT/docker/test-fuzz-compose.yaml" run --rm fuzzer`.
     By passing both files via `-f`, Compose dynamically merges them into the
     same virtual context. This guarantees the fuzzer runs on the exact same
     network as the server, natively resolving internal DNS (e.g.
     `http://rest-server:${PORT}`).
   - **Fuzzer Command Payload**: The `schemathesis/schemathesis` image lacks an
     inherent understanding of the target API URL or contract location. The
     implementor MUST configure the fuzzer compose service to execute a strict
     command payload:
     `command: ["run", "/api-specs/openapi.yaml", "--base-url", "http://rest-server:${PORT}"]`.
     Without this explicit instruction, the container will instantly exit
     without performing any tests.
   - **Fail Fast Policy**: To honor the strict requirement that the fuzzer
     instantly aborts if the developer hasn't manually spun up the environment
     beforehand, the fuzzer compose service MUST NOT declare
     `depends_on: ["rest-server"]`.

## Files Modified

> [!IMPORTANT] **Executable Permissions**: The implementor MUST execute
> `chmod +x` on the `gradlew` wrapper and EVERY script located in the `bin/`
> directory immediately after generating them. Automated text generation
> defaults to `644` permissions, which will trigger immediate and fatal
> `EACCES: Permission denied` crashes universally across the local host and CI
> pipelines.
>
> **Sourcing Mandate**: EVERY executable script in the `bin/` directory MUST
> strictly source `bin/common` before continuing execution to uniformly
> establish `$PROJECT_ROOT`.

- `gradle/wrapper/gradle-wrapper.properties` [NEW]: Bootstrapper definitions.
- `gradle/wrapper/gradle-wrapper.jar` [NEW]: Bootstrapper binary executable.
- `gradlew` [NEW]: Unix bootstrapper shell entrypoint.
- `gradlew.bat` [NEW]: Windows bootstrapper shell entrypoint.
- `gradle.properties` [NEW]: Cache and memory definitions.
- `gradle/libs.versions.toml` [NEW]: Singular dependency version catalog matrix.
- `build.gradle.kts` [NEW]: Root gradle definitions and `ktlint` enforcement.
- `settings.gradle.kts` [NEW]: Multi-module bindings initialization targeting
  `rest-server`.
- `.editorconfig` [NEW]: Formatting baseline rules underpinning ktlint
  validation.
- `.gitignore` [NEW]: Cache and build directory exclusions.
- `bin/functions` [NEW]: Standard diagnostic and utility functions (e.g.,
  `log-info`, `fatal`).
- `bin/common` [NEW]: Non-executable configuration script resolving
  `$PROJECT_ROOT` and sourcing `bin/functions`.
- `bin/docker-run` [NEW]: Universal container execution wrapper preventing root
  file lockouts.
- `bin/docker-compose` [NEW]: Universal compose wrapper dynamically exporting
  user identity.
- `bin/dev-bootstrap` [NEW]: Placeholder for future host environment setup
  scripts.
- `bin/pre-commit` [NEW]: Manual Dockerized validation script (tests & linting)
  to run before commits.
- `.env.template` [NEW]: Baseline structural template for dev configuration.
- `.env` [NEW]: Local context variables utilized during local booting.
- `api-specs/openapi.yaml` [NEW]: True strict OpenAPI standard source of truth.
- `rest-server/build.gradle.kts` [NEW]: Local Ktor compilation logic.
- `rest-server/src/main/resources/rest-server.conf` [NEW]: HOCON variables and
  module loader. **CRITICAL WIRING**: The implementor MUST include a
  `ktor.application.modules = [ ed.unicoach.rest.ApplicationKt.module ]` stanza
  so Ktor natively mounts the routing; without this, the server will boot but
  return 404s for all requests.
- `rest-server/src/main/resources/logback.xml` [NEW]: Diagnostic SLF4J JSON
  logging structure. The implementor MUST utilize the
  `net.logstash.logback.encoder.LogstashEncoder` within its appenders and MUST
  NOT attempt to fake JSON syntax using native string patterns.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` [NEW]: Kotlin
  standard web server entry point wrapper. **CRITICAL IMPLEMENTATION**: The
  implementor MUST utilize
  `fun main(args: Array<String>) { io.ktor.server.netty.EngineMain.main(args) }`
  to preserve the automatic HOCON configuration loading, rather than falling
  into the `embeddedServer` trap.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt` [NEW]: End-point
  mapping code routing to the spec string.
- `rest-server/src/test/kotlin/ed/unicoach/rest/RoutingTest.kt` [NEW]:
  `testApplication` logic driving unit level validations.
- `docker/rest-server-compose.yaml` [NEW]: Base orchestration template
  controlling dev environments.
- `docker/test-fuzz-compose.yaml` [NEW]: Dedicated standalone orchestration
  defining the fuzzer.
- `bin/wait-for` [NEW]: Universal command wrapper to poll commands securely
  until successful.
- `bin/test-fuzz` [NEW]: Bounded automated validation executor for Schemathesis.
