# INVARIANTS — db/dao

The persistence layer: concrete DAO `object`s, the à-la-carte capability
interfaces they declare ([Dao.kt](./Dao.kt)), and the shared `SqlSession`
query/mutate scaffolding ([SqlSessionQueries.kt](./SqlSessionQueries.kt)) every
DAO delegates to.

## Invariants

### Database access goes through `SqlSession`; the `Connection` never leaks

**Rule:** All database access — any code, not just DAOs — goes through the
`SqlSession` handed out by `Database.withConnection`; no code acquires or
operates on the raw `java.sql.Connection` or any driver handle, and `SqlSession`
never hands one upward (nor anything exposing `commit`/`rollback`/
`setAutoCommit`).

**Why:** `withConnection` owns connection lifecycle and transaction boundaries.
A leaked `Connection` lets a caller commit, roll back, or strand a half-open
transaction — corrupting the atomic unit of work — and escapes the `use`-scoped
cleanup that prevents connection exhaustion. (The no-leak-upward half is the
general `code-review-no-leaks` lens.)

### Caller data is never interpolated into SQL text

**Rule:** No caller- or LLM-supplied value may be concatenated into SQL text —
every such value MUST be a bound `?` parameter. Only DAO-fixed identifiers may
be interpolated: the `table` name and column keys in the shared generators
(`insertReturning`, `updateColumnsReturning`, `softDeleteReturning`,
`SoftDeleteScope.predicate`), and the fixed fragments plus generated `?, ?, …`
lists in the hand-rolled `CollegesDao.search` (list length may set the
placeholder count; the values are always bound).

**Why:** SQL can't parameterize an identifier, so these helpers splice
table/column names directly into the string — routing a caller value through one
makes it an injection vector across every DAO. `CollegesDao.search` is the sole
query that hand-rolls a variable-shape `WHERE`/`JOIN` instead of using the
generators, and its filters come from an LLM tool call — the least-trusted input
— so interpolating a filter value there (e.g. splicing `c.state IN ('CA','NY')`
instead of binding `?`s) reopens injection on exactly the query that takes
adversarial input.

### Email and verification state mutate only through dedicated isolated writers

**Rule:** The `email` and `email_verified_at` columns of `users` MUST be written
only by the dedicated `UsersDao` writers — `changeEmail` (rewrite + reset to
`NULL`), `markEmailVerified` (stamp), and the full-row restores
(`updatePhysicalRecord`/`revertToVersion`). They MUST NOT be added to the
generic `UserEdit`/`update` column set, nor reachable through any other generic
mutation surface.

**Why:** `email_verified_at` is the sole record of whether an address has been
proven, and `changeEmail` deliberately resets it to `NULL`. Folding either
column into the generic `update` path would give the ordinary profile-edit
surface a second, unguarded channel to rewrite the address or alter verification
state — letting a profile edit silently forge a verified address or clear a real
verification. Confining these columns to purpose-built writers keeps that
transition auditable and single-sourced.

## History

- [x] [RFC-62: DAO Capability Interfaces and Shared Query Scaffolding](../../../../../../../../rfc/62-dao-interfaces.md)
- [x] [RFC-63: Admin System Prompts](../../../../../../../../rfc/63-admin-system-prompts.md)
- [x] [RFC-65: Email Verification (Backend)](../../../../../../../../rfc/65-email-verification.md)
- [x] [RFC-67: College Knowledge](../../../../../../../../rfc/67-college-knowledge.md)
- [x] [RFC-70: Change-email flow](../../../../../../../../rfc/70-change-email.md)
