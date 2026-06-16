# SPEC: db/src/main/resources

## I. Overview

This directory owns the HOCON configuration file for the `db` Gradle module. It
contains exactly one file — `db.conf` — which declares the `database` stanza
consumed by `DatabaseConfig` at application startup to construct a HikariCP
connection pool.

---

## II. Invariants

- The directory MUST contain exactly one `.conf` file named `db.conf`. Any
  additional `.conf` file violates the HOCON Naming Mandate and risks classpath
  collision with sibling modules (e.g., `service.conf`, `common.conf`).
- `db.conf` MUST be named after its parent module directory (`db`). Generic
  names such as `database.conf` or `application.conf` are FORBIDDEN.
- `db.conf` MUST declare the `database { ... }` stanza as a complete,
  self-contained block. The `database` stanza MUST NOT be split across multiple
  `.conf` files on the `db` module's classpath.
- `database.jdbcUrl` MUST be constructed as the HOCON expression
  `"jdbc:postgresql://"${database.host}":"${POSTGRES_PORT}"/"${POSTGRES_DB}`.
  Only the scheme and separators (`jdbc:postgresql://`, `:`, `/`) are hardcoded
  literals; the host is interpolated from `database.host`, the port is injected
  via the REQUIRED substitution `${POSTGRES_PORT}`, and the database name via
  the REQUIRED substitution `${POSTGRES_DB}`. The `${POSTGRES_PORT}` and
  `${POSTGRES_DB}` substitutions MUST resolve at config load: if either is
  unset, HOCON MUST throw `Could not resolve substitution to a value` at load
  time (`AppConfig.load()`), before `DatabaseConfig.from()` runs. This hard
  load-time failure is intentional — there is NO silent fallback (the port is
  not defaulted to `5432`). `database.host`, by contrast, ALWAYS resolves
  because it carries a default (see the `database.host` invariant below).
- `database.host` MUST default to the literal `"localhost"` and MUST be
  overridable via the OPTIONAL substitution `${?DATABASE_HOST}`. Because the
  default is unconditional, `database.host` MUST ALWAYS resolve — it has NO
  load-time failure mode (unlike `POSTGRES_PORT`/`POSTGRES_DB`). When
  `DATABASE_HOST` is unset, the `${?DATABASE_HOST}` override is a no-op and the
  `"localhost"` default stands. `database.jdbcUrl` interpolates this value.
- `database.user` MUST be present and non-blank. It MUST resolve via
  `${?DATABASE_USER}` (optional substitution). Absent at runtime →
  `DatabaseConfig.from()` returns a `Failure` via `getNonBlankString`.
- `database.password` MAY be absent or blank (legitimate in local dev). The
  HOCON key MUST use `${?DATABASE_PASSWORD}` (optional substitution) so that
  `DatabaseConfig` can detect its presence via
  `config.hasPath("database.password")`.
- `database.maximumPoolSize` MUST have a hardcoded default of `10`. It MUST be
  overridable at runtime via `${?DATABASE_MAXIMUM_POOL_SIZE}`.
- `database.connectionTimeout` MUST have a hardcoded default of `30000`
  (milliseconds). No environment variable override is defined; this value is
  static.
- `db.conf` MUST NOT ship environment-specific content (no `dev.conf` blocks, no
  conditional includes). All environment-specific values are injected
  exclusively via environment variables at runtime (12-Factor compliance).

---

## III. Behavioral Contracts

### `db.conf` — HOCON Configuration File

**Consumer**:
[`DatabaseConfig.from(config: Config)`](../kotlin/ed/unicoach/db/DatabaseConfig.kt)

| Key                          | HOCON Form                                                                 | Required                                                  | Default     | Override Env Var                                |
| ---------------------------- | -------------------------------------------------------------------------- | --------------------------------------------------------- | ----------- | ----------------------------------------------- |
| `database.host`              | `"localhost"` + `${?DATABASE_HOST}`                                        | Always resolves (defaulted)                               | `localhost` | `DATABASE_HOST`                                 |
| `database.jdbcUrl`           | `"jdbc:postgresql://"${database.host}":"${POSTGRES_PORT}"/"${POSTGRES_DB}` | MUST be non-blank; `POSTGRES_PORT`/`POSTGRES_DB` REQUIRED | None        | `POSTGRES_PORT` (port), `POSTGRES_DB` (db name) |
| `database.user`              | `${?DATABASE_USER}`                                                        | MUST be non-blank                                         | None        | `DATABASE_USER`                                 |
| `database.password`          | `${?DATABASE_PASSWORD}`                                                    | Optional                                                  | Absent      | `DATABASE_PASSWORD`                             |
| `database.maximumPoolSize`   | `10` + `${?DATABASE_MAXIMUM_POOL_SIZE}`                                    | Yes                                                       | `10`        | `DATABASE_MAXIMUM_POOL_SIZE`                    |
| `database.connectionTimeout` | `30000`                                                                    | Yes                                                       | `30000` ms  | _(none)_                                        |

**Side Effects**: None. This file is static configuration; it does not perform
I/O or cause side effects directly.

**Error Handling**:

- If `POSTGRES_PORT` or `POSTGRES_DB` is unset at config load → HOCON throws
  `Could not resolve substitution to a value` from `AppConfig.load()`, before
  `DatabaseConfig.from()` is reached. There is no fallback port.
- If `database.jdbcUrl` otherwise resolves to blank or is absent →
  `DatabaseConfig.from()` returns `Result.Failure` wrapping the exception thrown
  by `getNonBlankString`.
- If `database.user` resolves to blank or is absent → same failure path.
- If `database.password` key is absent, `DatabaseConfig` sets `password = null`
  (permitted; HikariCP accepts null password).
- No explicit HOCON parse errors are handled here; classpath loading failures
  propagate from `AppConfig.load()` to the caller.

**Idempotency**: N/A — configuration files are read-only resources.

---

## IV. Infrastructure & Environment

**Module**: `db` Gradle module (`db/build.gradle.kts`).

**Classpath Loading**: `db.conf` MUST be included as an argument to
`AppConfig.load()` at every call site that requires database connectivity.
Current call sites:

- `db` module tests: `AppConfig.load("common.conf", "db.conf")`
- `service`/`rest-server`:
  `AppConfig.load("common.conf", "db.conf", "service.conf", ...)`

**Classpath**: `db/src/main/resources/` is on the `db` module's compile and
runtime classpath. Files in this directory are merged onto the unified
application classpath when `rest-server` assembles the fat JAR.

**Required Environment Variables at Runtime**:

| Variable                     | Role                                                                     | Fail Behavior                                                                                                             |
| ---------------------------- | ------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------- |
| `DATABASE_HOST`              | Supplies the host in the JDBC URL (e.g. RDS endpoint); optional override | Absent → `database.host` defaults to `localhost`; pool connects to local Postgres                                         |
| `POSTGRES_PORT`              | Supplies the port in the JDBC URL (required substitution)                | Unset → HOCON throws `Could not resolve substitution to a value: ${POSTGRES_PORT}` at config load (no fallback to `5432`) |
| `POSTGRES_DB`                | Supplies the database name in the JDBC URL (required substitution)       | Unset → HOCON throws `Could not resolve substitution to a value: ${POSTGRES_DB}` at config load                           |
| `DATABASE_USER`              | PostgreSQL username                                                      | `DatabaseConfig.from()` returns `Failure`                                                                                 |
| `DATABASE_PASSWORD`          | PostgreSQL password                                                      | Absent → `password = null`; pool connects without password                                                                |
| `DATABASE_MAXIMUM_POOL_SIZE` | HikariCP pool size override                                              | Absent → defaults to `10`                                                                                                 |

**Collision Risk**: The HOCON classpath merge in `rest-server` covers all module
`src/main/resources` directories. A second module introducing a
`database { ... }` block into any `.conf` file on the same classpath would
silently shadow or conflict with `db.conf` at runtime.

---

## V. History

- [x] [RFC-14: Extract Database Module](../../../../rfc/14-db-module.md)
- [x] [RFC-50: Deploy the Backend REST API to AWS](../../../../rfc/50-deploy-rest-api-aws.md)
