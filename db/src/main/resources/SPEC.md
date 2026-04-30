# SPEC: db/src/main/resources

## I. Overview

This directory owns the HOCON configuration file for the `db` Gradle module.
It contains exactly one file — `db.conf` — which declares the `database` stanza
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
  `"jdbc:postgresql://localhost:5432/"${POSTGRES_DB}`. The base URL prefix
  (`jdbc:postgresql://localhost:5432/`) is a hardcoded literal; only the
  database name suffix is injected via `${POSTGRES_DB}`. If `POSTGRES_DB`
  is unset at runtime, the URL resolves to `"jdbc:postgresql://localhost:5432/"`
  (non-blank, so `getNonBlankString` passes), but HikariCP MUST fail at pool
  initialization when it cannot connect to an unspecified database.
- `database.user` MUST be present and non-blank. It MUST resolve via
  `${?DATABASE_USER}` (optional substitution). Absent at runtime →
  `DatabaseConfig.from()` returns a `Failure` via `getNonBlankString`.
- `database.password` MAY be absent or blank (legitimate in local dev). The
  HOCON key MUST use `${?DATABASE_PASSWORD}` (optional substitution) so that
  `DatabaseConfig` can detect its presence via `config.hasPath("database.password")`.
- `database.maximumPoolSize` MUST have a hardcoded default of `10`. It MUST be
  overridable at runtime via `${?DATABASE_MAXIMUM_POOL_SIZE}`.
- `database.connectionTimeout` MUST have a hardcoded default of `30000`
  (milliseconds). No environment variable override is defined; this value is
  static.
- `db.conf` MUST NOT ship environment-specific content (no `dev.conf` blocks,
  no conditional includes). All environment-specific values are injected
  exclusively via environment variables at runtime (12-Factor compliance).

---

## III. Behavioral Contracts

### `db.conf` — HOCON Configuration File

**Consumer**: [`DatabaseConfig.from(config: Config)`](../kotlin/ed/unicoach/db/DatabaseConfig.kt)

| Key | HOCON Form | Required | Default | Override Env Var |
|-----|-----------|----------|---------|-----------------|
| `database.jdbcUrl` | `"jdbc:postgresql://localhost:5432/"${POSTGRES_DB}` | MUST be non-blank | None | `POSTGRES_DB` (suffix) |
| `database.user` | `${?DATABASE_USER}` | MUST be non-blank | None | `DATABASE_USER` |
| `database.password` | `${?DATABASE_PASSWORD}` | Optional | Absent | `DATABASE_PASSWORD` |
| `database.maximumPoolSize` | `10` + `${?DATABASE_MAXIMUM_POOL_SIZE}` | Yes | `10` | `DATABASE_MAXIMUM_POOL_SIZE` |
| `database.connectionTimeout` | `30000` | Yes | `30000` ms | _(none)_ |

**Side Effects**: None. This file is static configuration; it does not perform
I/O or cause side effects directly.

**Error Handling**:
- If `database.jdbcUrl` resolves to blank or is absent → `DatabaseConfig.from()`
  returns `Result.Failure` wrapping the exception thrown by `getNonBlankString`.
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
- `service`/`rest-server`: `AppConfig.load("common.conf", "db.conf", "service.conf", ...)`

**Classpath**: `db/src/main/resources/` is on the `db` module's compile and
runtime classpath. Files in this directory are merged onto the unified
application classpath when `rest-server` assembles the fat JAR.

**Required Environment Variables at Runtime**:

| Variable | Role | Fail Behavior |
|----------|------|---------------|
| `POSTGRES_DB` | Appended to the JDBC URL base string | `jdbcUrl` becomes `"jdbc:postgresql://localhost:5432/"` (connects to no DB) → connection error at pool init |
| `DATABASE_USER` | PostgreSQL username | `DatabaseConfig.from()` returns `Failure` |
| `DATABASE_PASSWORD` | PostgreSQL password | Absent → `password = null`; pool connects without password |
| `DATABASE_MAXIMUM_POOL_SIZE` | HikariCP pool size override | Absent → defaults to `10` |

**Collision Risk**: The HOCON classpath merge in `rest-server` covers all
module `src/main/resources` directories. A second module introducing a
`database { ... }` block into any `.conf` file on the same classpath would
silently shadow or conflict with `db.conf` at runtime.

---

## V. History

- [x] [RFC-14: Extract Database Module](../../../../rfc/14-db-module.md)
