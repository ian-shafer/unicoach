# SPEC: Gradle

## I. Overview

Gradle (Kotlin DSL) is the build system for the Unicoach JVM backend. Humans
never invoke Gradle directly — shell scripts in `bin/` are the sole interface.

- **`bin/build`** compiles all modules in dependency order. Each
  `bin/build-<module>` script runs exactly one `./gradlew` task:
  `:module:assemble` for libraries, `:module:installDist` for applications.
- **`bin/test`** bootstraps a fresh test database, then runs explicit per-module
  `:module:test` tasks.
- **`bin/format`** and **`bin/pre-commit`** run ktlint via Gradle.

Build and run are strictly separated. Gradle produces artifacts (JARs or
`installDist` launch scripts); daemon wrappers in `bin/` consume those artifacts
to start services. `./gradlew run` is never used.

The JDK (Temurin 21) is provided by `flake.nix`. All dependency and plugin
versions are centralized in a single version catalog
([`gradle/libs.versions.toml`](gradle/libs.versions.toml)).

---

## II. Invariants

### DSL

- All build files MUST use Kotlin DSL (`.gradle.kts`).

### Version Catalog

- [`gradle/libs.versions.toml`](gradle/libs.versions.toml) is the single source
  of truth for every dependency and plugin version.
- Module build files MUST reference dependencies via `libs.*` accessors (e.g.,
  `libs.ktor.server.core`) and plugins via `libs.plugins.*` accessors (e.g.,
  `libs.plugins.kotlin.jvm`). Hard-coded version strings in `build.gradle.kts`
  files are banned.

### Multi-Module Architecture

- The project is a multi-module Gradle build. Every module MUST be registered in
  [`settings.gradle.kts`](settings.gradle.kts).
- Modules are either **libraries** or **daemons**:
  - **Library**: produces a JAR built via `:module:assemble`.
  - **Daemon**: produces an executable distribution via `:module:installDist`.
    MUST apply the `application` plugin and declare a `mainClass`.

#### Current Modules

| Module         | Type    | Plugins (beyond kotlin-jvm)                |
| -------------- | ------- | ------------------------------------------ |
| `common`       | Library | ktlint, kotlin-serialization, java-library |
| `db`           | Library | ktlint                                     |
| `service`      | Library | ktlint                                     |
| `queue`        | Library | ktlint, kotlin-serialization               |
| `net`          | Library | ktlint, kotlin-serialization               |
| `rest-server`  | Daemon  | ktor, kotlin-serialization                 |
| `queue-worker` | Daemon  | —                                          |

### Dependency Graph

Inter-module dependencies MUST follow this DAG. Cycles are fatal.

```
common ← db ← service
               queue ← net
rest-server ← common, db, service, queue
queue-worker ← common, db, service, queue, net
```

- `common` has zero project dependencies. It is the dependency root.
- Dependencies flow strictly downward: `common` → `db` → domain/infra →
  application. An application module MUST NOT be depended on by another module.

### Dependency Scoping

- `api`: exposes the dependency to consumers' compile classpath. Reserved for
  transitive contracts. Currently: `typesafe-config` and
  `kotlinx-serialization-json` in `common`.
- `implementation`: default scope. Does not leak to consumers.
- `compileOnly`: compile-time-only. Used when the dependency is provided at
  runtime by a downstream module (e.g., `postgresql` in `queue`).
- `testImplementation`: test-only.

### Plugin Conventions

- Every module MUST apply `libs.plugins.kotlin.jvm`.
- `ktlint` is applied globally via the root `allprojects` block. Individual
  modules MAY redundantly declare it, but it is not required.
- `kotlin-serialization` MUST be applied in any module that uses `@Serializable`
  annotations.
- The `ktor` plugin MUST only be applied to modules that embed a Ktor server.
  Currently: `rest-server`.

### Root Build Configuration

[`build.gradle.kts`](build.gradle.kts) (root) handles four concerns:

1. **ktlint enforcement**: applied to `allprojects`.
2. **Repository declaration**: `mavenCentral()` for `allprojects`.
3. **System property forwarding**: for all `subprojects` with the `application`
   plugin, the `run` task forwards `RUN_DIR`, `SERVICE_NAME`, and `HEALTH_NONCE`
   from environment variables to JVM system properties.
4. **Test config-overlay isolation**: every `subprojects` `Test` task pins the
   `unicoach.config.dir` system property to an in-build directory, so the
   `AppConfig` local overlay (`<base>/unicoach/local.conf`) resolves to an
   absent file and a developer's real host overlay can never alter test config.

### Test Configuration

- Testing framework: JUnit 5 via `useJUnitPlatform()`.
- Modules with tests MUST configure `tasks.withType<Test>` with:
  - `exceptionFormat = TestExceptionFormat.FULL`
  - `events("failed")`
- `bin/test` is the canonical entry point for running Gradle tests. It
  bootstraps a fresh test database before running explicit per-module
  `:module:test` tasks.
- Tests are hermetic against the `AppConfig` local overlay: the root build pins
  `unicoach.config.dir` to an overlay-free in-build directory for every `Test`
  task, so a developer's real `~/.config/unicoach/local.conf` cannot leak into
  the test JVM and override packaged config (e.g. swapping the `log` chat
  provider for a live one).

---

## III. Behavioral Contracts

### Build Pipeline

`bin/build` invokes per-module build scripts in dependency order:

```
common → db → service → queue → net → rest-server → queue-worker
```

Each `bin/build-<module>` script runs exactly one Gradle task:

| Module Type | Gradle Task           | Output                                               |
| ----------- | --------------------- | ---------------------------------------------------- |
| Library     | `:module:assemble`    | JAR in `module/build/libs/`                          |
| Application | `:module:installDist` | Launch scripts in `module/build/install/module/bin/` |

Application modules (`rest-server`, `queue-worker`) are started by their
`bin/*-up` wrappers, which expect the `installDist` binary to already exist. The
wrappers MUST NOT invoke `./gradlew run` — build and run are strictly separated.

### Adding a New Module

1. Create `<module>/build.gradle.kts` with `libs.plugins.kotlin.jvm` at minimum.
2. Add `include("<module>")` to `settings.gradle.kts`.
3. Add any new dependencies to `gradle/libs.versions.toml`.
4. Create `bin/build-<module>` following the existing template.
5. Insert the new module at the correct position in `bin/build`'s ordered
   sequence.

### Adding a New Dependency

1. Add the version to `[versions]` in `gradle/libs.versions.toml` (if not
   already present).
2. Add the library coordinate to `[libraries]` using `version.ref`.
3. Reference via `libs.<alias>` in the consuming module's `build.gradle.kts`.

---

## IV. Infrastructure & Environment

- **Gradle version**: 8.11.1, distributed via the committed wrapper (`gradlew`,
  `gradle/wrapper/`).
- **JDK**: Temurin 21, provided by `flake.nix`. Host-machine JDK resolution
  outside of Nix is not supported.
- **Daemon**: Disabled (`org.gradle.daemon=false` in `gradle.properties`).
  Originally set during the Docker era, where each build ran in an ephemeral
  container and a resident daemon could never be reused. Now that builds run
  natively via Nix, re-enabling the daemon would allow JVM reuse across the 7
  sequential `./gradlew` invocations in `bin/build`. Worth reconsidering.
- **Heap**: 2048 MB (`org.gradle.jvmargs=-Xmx2048m`).
- **Build cache**: Enabled (`org.gradle.caching=true`).
- **Kotlin version**: 2.3.20.

---

## V. History

- [x] [RFC-01: REST API Stack](rfc/01-rest-api-stack.md)
- [x] [RFC-02: Hello World OpenAPI Spec](rfc/02-hello-world-open-api-spec.md)
- [x] [RFC-08: Auth Registration](rfc/08-auth-registration.md)
- [x] [RFC-09: Global Config](rfc/09-global-config.md)
- [x] [RFC-14: DB Module](rfc/14-db-module.md)
- [x] [RFC-15: Queue Data Layer](rfc/15-queue-data-layer.md)
- [x] [RFC-16: Queue Worker Framework](rfc/16-queue-worker-framework.md)
- [x] [RFC-17: Queue Worker Daemon](rfc/17-queue-worker-daemon.md)
- [x] [RFC-19: Daemon Health Marker](rfc/19-daemon-health-marker.md)
- [x] [RFC-21: Session Expiry Queue](rfc/21-session-expiry-queue.md)
- [x] [RFC-23: Native Daemon Scripts](rfc/23-native-daemon-scripts.md)
