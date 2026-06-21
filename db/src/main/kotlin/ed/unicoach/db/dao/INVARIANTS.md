# INVARIANTS — db/dao

The persistence layer: concrete DAO `object`s, the à-la-carte capability
interfaces they declare ([Dao.kt](./Dao.kt)), and the shared `SqlSession`
query/mutate scaffolding ([SqlSessionQueries.kt](./SqlSessionQueries.kt)) every
DAO delegates to.

## Invariants

### SqlSession is the DAOs' only database interface, and leaks no resources

**Rule:** DAOs MUST affect the database solely through `SqlSession`; no DAO may
acquire or operate on the raw `java.sql.Connection` or any other direct driver
handle. `SqlSession` MUST NOT leak those resources upward — it MUST NOT return,
or otherwise hand a caller, the underlying `Connection` or anything exposing
transaction control (`commit`/`rollback`/`setAutoCommit`). It surfaces only
narrow, scoped operations (today `prepareStatement`; any future method follows
the same no-leak rule).

**Why:** Connection lifecycle and transaction boundaries are owned exclusively
by `Database.withConnection`. A leaked `Connection` lets a single DAO commit,
roll back, or strand a half-open transaction — corrupting the caller's atomic
unit of work — and escapes the `use`-scoped cleanup that prevents connection
exhaustion.

### Generated SQL never interpolates caller data

**Rule:** In `insertReturning`, `updateColumnsReturning`, `softDeleteReturning`,
and `SoftDeleteScope.predicate`, the `table` name, the column-map keys, and the
`predicate` column MUST be fixed DAO-supplied identifiers. Only values bound
through `?` placeholders (the `Bind` closures) may carry caller data.

**Why:** These helpers concatenate `table` and column names directly into the
SQL string — there is no parameterization for an identifier. Routing any
caller-controlled value through a column name or table argument turns the shared
generator into a SQL-injection vector across every DAO that uses it.

### `SystemPromptsDao` exposes no mutate-or-delete path

**Rule:** `SystemPromptsDao` MUST expose only read and insert capabilities
(`Findable`, `Listable`, `Creatable`, plus `findByNameAndVersion`). It MUST NOT
gain an `update`/`delete` method, and MUST NOT adopt any `SoftDelete*` or
`OccDeletable` capability. A "new version" of a prompt is a new immutable row,
never a mutation of an existing one.

**Why:** `system_prompts` is an immutable entity: its triggers raise `P0001` on
any `UPDATE`/`DELETE` and it has no `deleted_at` column (schema 0007). Adding a
mutate/delete path would either dead-end at the trigger or — worse — present
prompts as editable, breaking the guarantee that an `id` pins one exact
`(name, version, body)` forever. Downstream integrity depends on this:
`convo_requests.system_prompt_id` is a permanent pin (`ON DELETE RESTRICT`), so
a mutated or deleted prompt would silently rewrite or orphan the prompt every
past coaching turn was generated from.

## History

- [x] [RFC-62: DAO Capability Interfaces and Shared Query Scaffolding](../../../../../../../../rfc/62-dao-interfaces.md)
- [x] [RFC-63: Admin System Prompts](../../../../../../../../rfc/63-admin-system-prompts.md)
