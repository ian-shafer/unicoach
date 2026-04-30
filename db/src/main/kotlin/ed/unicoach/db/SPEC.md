# SPEC: `db/src/main/kotlin/ed/unicoach/db`

## I. Overview

This package is the **connection-pooling and configuration root** of the `db`
Gradle module. It owns two responsibilities: (1) parsing database connection
parameters from HOCON into a typed `DatabaseConfig`, and (2) wrapping a HikariCP
connection pool behind a `Database` facade that enforces transactional semantics
and exposes a `SqlSession` abstraction to all DAOs. No domain logic resides here;
this package is pure infrastructure consumed by `dao/` and `models/`.

---

## II. Invariants

- **`Database` MUST wrap every DAO-facing connection inside an explicit
  transaction.** `withConnection` disables `autoCommit`, commits on normal
  return, and rolls back on any exception.
- **`Database.withConnection` MUST restore the connection's original
  `autoCommit` state in the `finally` block** before returning the connection
  to the Hikari pool, regardless of success or failure.
- **`Database.withConnection` MUST return the connection to the Hikari pool
  (via `conn.close()`) in the `finally` block** even when the block or commit
  raises an exception.
- **`Database.withConnection` MUST commit BEFORE any handler code executes
  outside the block.** The `withConnection` block MUST return before further
  handler execution proceeds (i.e., no long-running handler work may run inside
  a `withConnection` block).
- **`Database.createRawConnection` MUST bypass the Hikari pool.** It connects
  directly via `DriverManager.getConnection` using the pool's URL, username, and
  password. Callers (e.g., `QueueWorker`'s `LISTEN` coroutine) are responsible
  for lifecycle management of the returned `Connection`.
- **`DatabaseConfig` MUST be constructed only through `DatabaseConfig.from(config)`.** The primary constructor is `private`; no caller may instantiate it directly.
- **`DatabaseConfig.from` MUST return `Result.failure` if `database.jdbcUrl` or
  `database.user` is blank.** Both fields are validated via `getNonBlankString`.
- **`DatabaseConfig` MUST treat an absent `database.password` path as `null`.**
  An explicit empty string for `password` is permitted (local dev).
- **`Database` MUST be closed by its owner** by calling `Database.close()` at
  application shutdown, which closes the underlying HikariCP pool.
- **The `db` module NEVER owns domain logic.** It exposes infrastructure
  (`Database`, `DatabaseConfig`) only; all query logic lives in `dao/`.

---

## III. Behavioral Contracts

### `DatabaseConfig.from(config: Config): Result<DatabaseConfig>`

- **Side Effects**: None. Pure parse — reads HOCON keys, constructs value object.
- **Config keys consumed**:
  - `database.jdbcUrl` — required, non-blank string.
  - `database.user` — required, non-blank string.
  - `database.password` — optional path; `null` if absent, any string
    (including empty) if present.
  - `database.maximumPoolSize` — required integer.
  - `database.connectionTimeout` — required long (milliseconds).
- **Error Handling**: Returns `Result.failure(Throwable)` if any required key is
  missing or blank. The exception is the raw exception thrown by
  `getNonBlankString` or the Config API. Callers MUST call `.getOrThrow()` at
  startup to convert a misconfigured classpath into a hard process failure.
- **Idempotent**: yes — stateless parse; safe to call multiple times.

---

### `Database(config: DatabaseConfig)`

- **Side Effects**: On construction, creates and starts a `HikariDataSource`
  with the parameters from `config`. Hikari initiates pool connections to
  PostgreSQL at this point.
- **Error Handling**: Construction throws if HikariCP cannot initialize (e.g.,
  invalid JDBC URL, unreachable host). No `Result` wrapper — callers must handle
  via try/catch or let the exception propagate to crash startup.
- **Idempotent**: no — construction is a stateful side-effecting operation.

---

### `Database.withConnection(block: (`[`SqlSession`](./dao/SqlSession.kt)`) -> T): T`

- **Side Effects**: Opens a JDBC connection from the Hikari pool, sets
  `autoCommit = false`, executes `block`, commits the transaction, and returns
  the connection to the pool.
- **Transactional guarantee**: Any exception thrown inside `block` (or from
  `commit()`) triggers `conn.rollback()` before the connection is released.
- **`SqlSession` scope**: The [`SqlSession`](./dao/SqlSession.kt) passed to
  `block` is valid only for the duration of `block`. It MUST NOT be stored or
  used outside the lambda.
- **Error Handling**: Any exception from `block`, `commit()`, or `rollback()` is
  rethrown to the caller. DAO callers are responsible for wrapping these in their
  own result types (e.g., `DatabaseFailure`).
- **Idempotent**: no — executes a database transaction; side effects on
  persistent state.

---

### `Database.createRawConnection(): java.sql.Connection`

- **Side Effects**: Opens a new JDBC connection directly via `DriverManager`,
  bypassing Hikari pool accounting.
- **Use case**: Persistent `LISTEN` connections used by `QueueWorker` that must
  not be returned to the pool between `getNotifications()` calls.
- **Error Handling**: Throws `SQLException` if the connection cannot be
  established. Callers are responsible for retrying and lifecycle management.
- **Idempotent**: no — each call opens a new physical TCP connection.

---

### `Database.close()`

- **Side Effects**: Shuts down the underlying `HikariDataSource`, closing all
  pooled connections.
- **Error Handling**: Delegates to HikariCP's `close()`; no return value.
- **Idempotent**: yes — calling `close()` on an already-closed pool is a no-op
  per HikariCP contract.

---

## IV. Infrastructure & Environment

- **Module**: `db` Gradle module (`db/build.gradle.kts`).
- **Dependencies**: `common` (for `getNonBlankString`), `postgresql` (JDBC
  driver), `hikaricp` (connection pooling).
- **HOCON config file**: `db/src/main/resources/db.conf`. MUST be included in
  every `AppConfig.load(...)` call for any module that instantiates `Database`.
- **Environment variables** (substituted by HOCON):
  - `POSTGRES_DB` — database name suffix appended to the JDBC URL base.
  - `DATABASE_USER` — optional override for `database.user`.
  - `DATABASE_PASSWORD` — optional override for `database.password`.
  - `DATABASE_MAXIMUM_POOL_SIZE` — optional override for pool size (default: 10).
- **Defaults** (defined in `db.conf`):
  - `database.maximumPoolSize = 10`
  - `database.connectionTimeout = 30000` (30 seconds, in milliseconds)
- **`createRawConnection` callers MUST NOT hold the returned `Connection` inside
  a `withConnection` block** — raw connections are not pool-managed and create
  independent physical sessions.

---

## V. History

- [x] [RFC-14: Extract Database Module](../../../../../../../rfc/14-db-module.md)
- [x] [RFC-16: Queue Worker Framework](../../../../../../../rfc/16-queue-worker-framework.md)
