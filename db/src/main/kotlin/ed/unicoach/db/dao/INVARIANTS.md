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

## History

- [x] [RFC-62: DAO Capability Interfaces and Shared Query Scaffolding](../../../../../../../../rfc/62-dao-interfaces.md)
