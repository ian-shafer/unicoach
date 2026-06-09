# SPEC: `email/src/main/kotlin/ed/unicoach/email/dao`

## I. Overview

The persistence adapter for the `email` module: the sole gateway between Kotlin
code and the `email_sends` table — an **append-only ledger** of terminal
transactional-email outcomes (schema
[`0008.create-email-sends.sql`](../../../../../../../../db/schema/0008.create-email-sends.sql)).
[`EmailSendsDao`](./EmailSendsDao.kt) writes one immutable row per terminal send
outcome; [`NewEmailSend`](./NewEmailSend.kt) is its insert-input value type.

---

## II. Invariants

### Append-Only Ledger

- The DAO MUST only ever issue `INSERT` against `email_sends`. It MUST NEVER
  `UPDATE` or `DELETE` an existing row, and MUST NOT expose any method that
  does.
- A send's terminal outcome is recorded as **one row, written once**. The DAO
  MUST NOT model a status transition as a mutation of a prior row — each
  `insert` produces a new, independent ledger entry.
- The table's `prevent_log_update` / `prevent_log_delete` triggers enforce
  write-once at the database. The DAO relies on these guards rather than
  re-implementing the prohibition; any `UPDATE`/`DELETE` reaching the table
  raises and surfaces as a `Result.failure`.

### No Deduplication

- There is no idempotency key and no unique constraint on `email_sends`.
  Repeated `insert` calls with identical field values MUST each append a
  distinct row (each gets a fresh `uuidv7()` `id`). The DAO performs no dedup
  and offers no upsert.

### Write Mapping Fidelity

- `insert` MUST persist every `NewEmailSend` field to its column with no lossy
  domain transformation: `recipient → recipient_email`, `sender → sender_email`
  (each via `EmailAddress.value`), `subject`, `body` (via `.value`), `status`
  (via `EmailSendStatus.name`), `provider`,
  `providerMessageId →
  provider_message_id`, `errorMessage → error_message`.
- The DAO MUST NOT set `id`, `created_at`, or `row_created_at`; these are
  database-generated defaults (`uuidv7()`, `NOW()`).
- `status` MUST be one of the `EmailSendStatus` names (`SENT`, `REJECTED`); the
  table's `CHECK (status IN ('SENT','REJECTED'))` is the backstop and any
  out-of-domain value surfaces as a `Result.failure`.

### Transaction Boundary

- `insert` accepts a
  [`SqlSession`](../../../../../../../../db/src/main/kotlin/ed/unicoach/db/dao/SqlSession.kt)
  and issues all SQL through `PreparedStatement`. Connection acquisition and the
  transaction boundary (begin/commit/rollback) are owned by the caller's
  `Database`; the DAO MUST NOT manage them.

### Failure Classification

- On any database failure, `insert` MUST return `Result.failure` whose cause is
  routed through `ed.unicoach.db.dao.mapDatabaseError`. The DAO MUST NOT assert
  a single fixed `TransientError`/`PermanentError` trait: the trait is whatever
  `mapDatabaseError` derives from the SQLSTATE (e.g. `CHECK` / `NOT NULL`
  violations are permanent; connection/serialization/deadlock/resource classes
  are transient).

---

## II-B. Data Types

### `NewEmailSend` — [`NewEmailSend.kt`](./NewEmailSend.kt)

Insert-input value type. Address fields are domain types
(`ed.unicoach.common.models.EmailAddress`); `subject`/`body` are the module's
`EmailSubject`/`EmailBody` value types; `status` is `EmailSendStatus`.
`providerMessageId` is non-null for `SENT`, null for `REJECTED`; `errorMessage`
is the inverse. Neither pairing is enforced here — the consuming service
constructs the value consistently, and the columns are nullable.

### `SentEmail` (returned) — `ed.unicoach.email.SentEmail`

`insert` returns the inserted row projected to
`SentEmail(id, providerMessageId)`. Only `id` and `provider_message_id` are read
back from `RETURNING *`; the other persisted columns are not surfaced (the write
is lossless, the read projection is intentionally narrow — callers need only the
generated identity and the provider's message id).

---

## III. Behavioral Contracts

### `EmailSendsDao` — [`EmailSendsDao.kt`](./EmailSendsDao.kt)

`object` singleton (stateless). SQL is issued via `PreparedStatement`; no string
interpolation of caller data.

#### `insert(session: SqlSession, newSend: NewEmailSend): Result<SentEmail>`

- **Side Effects**: Write — `INSERT ... RETURNING *` of exactly one row into
  `email_sends`. No read-only path exists on this DAO.
- **Success**: `Result.success(SentEmail(id, providerMessageId))` mapped from
  the returned row.
- **Error Handling**:
  - Any thrown exception is mapped via `mapDatabaseError` and returned as
    `Result.failure(DaoException)`; the resulting trait
    (`TransientError`/`PermanentError`) is SQLSTATE-derived (see §II Failure
    Classification). `CHECK` and `NOT NULL` violations are permanent; an
    `UPDATE`/`DELETE` blocked by a log guard, if ever attempted, likewise
    surfaces here.
  - If the `INSERT` reports success but `RETURNING` yields no row, `insert`
    returns `Result.failure(DatabaseException(...))` (a permanent
    application-bug signal), never `Result.success`.
- **Idempotency**: No. Each call appends a new row with a fresh `id`; there is
  no dedup, upsert, or conflict resolution.

---

## IV. Infrastructure & Environment

- **Target table**: `email_sends`, created by migration
  [`0008.create-email-sends.sql`](../../../../../../../../db/schema/0008.create-email-sends.sql).
  It depends on the `prevent_log_update` / `prevent_log_delete` functions
  (migration `0006`) and on `uuidv7()`.
- **JDBC**: SQL is issued through `org.postgresql` via the injected
  `SqlSession`; no ORM. The DAO declares `compileOnly(postgresql)` — the driver
  is a runtime concern of the consuming application.
- No environment variables or config keys are read by this directory.

---

## V. History

- [x] [RFC-34: Transactional Email Service](../../../../../../../../rfc/34-transactional-email-service.md)
      — Created `EmailSendsDao` (`insert`) and `NewEmailSend` over the new
      append-only `email_sends` ledger; insert maps `NewEmailSend` 1:1 to
      columns, returns `SentEmail`, and classifies failures via
      `mapDatabaseError`.
