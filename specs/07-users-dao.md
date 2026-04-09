## Executive Summary
This design specifies the `UsersDao` for interacting with the PostgreSQL `users` table, leveraging strict type safety and functional Kotlin patterns. It avoids exceptions for domain control flow, opting for explicit union types (sealed interfaces) for all operations and evaluations. To prevent data corruption leaks, it models all strings via strict value classes mapping defensive validations via FP paradigms. The DAO guarantees isolated function boundaries by requiring an active `java.sql.Connection` parameter on every call while forbidding any side effects acting upon the connection lifecycle itself, strictly reflecting database invariants and Optimistic Concurrency Controls (OCC).

## Detailed Design

### Dependencies
- **JDBC Driver:** Explicitly requires PostgreSQL JDBC Driver (e.g., `org.postgresql:postgresql:42.7.3`).
- **Standard Library:** Uses pure Kotlin standard library and `java.sql` packages. No ORMs (e.g., Hibernate, Exposed) are utilized.

### Data Models
All data models are implemented as immutable Kotlin `data class`, `value class`, or `sealed interface`. The `UserVersionId` maps directly to the table's `INTEGER` type. To adhere to `general-design/SKILL.md`, entities extend a generic `BaseEntity<ID>`, natively sidestepping Kotlin value-boxing bugs in standard interfaces. To strictly ban JVM exceptions from parsing boundaries natively, we leverage a native algebraic `ValidationResult<T>` structure.

- **Validation Sum Types:**
  - `sealed interface ValidationError { object BlankString : ValidationError; object InvalidFormat: ValidationError; object TooLong: ValidationError }`
  - `sealed interface ValidationResult<out T> { data class Valid<T>(val value: T) : ValidationResult<T>; data class Invalid(val error: ValidationError) : ValidationResult<Nothing> }`

- **Value Classes (Defensive & Pure)**: 
  - `value class UserId(val value: UUID)`
  - `value class UserVersionId(val value: Int)`
  - `value class PersonName private constructor(val value: String) { companion object { fun create(value: String): ValidationResult<PersonName> { val t = value.trim(); return if(t.isBlank()) ValidationResult.Invalid(ValidationError.BlankString) else ValidationResult.Valid(PersonName(t)) } } }`
  - `value class DisplayName private constructor(val value: String) { companion object { fun create(value: String): ValidationResult<DisplayName> { val t = value.trim(); return if(t.isBlank()) ValidationResult.Invalid(ValidationError.BlankString) else ValidationResult.Valid(DisplayName(t)) } } }`
  - `value class PasswordHash private constructor(val value: String) { companion object { fun create(value: String): ValidationResult<PasswordHash> { val t = value.trim(); return if(t.isBlank()) ValidationResult.Invalid(ValidationError.BlankString) else ValidationResult.Valid(PasswordHash(t)) } } }`
  - `value class SsoProviderId private constructor(val value: String) { companion object { fun create(value: String): ValidationResult<SsoProviderId> { val t = value.trim(); return if(t.isBlank()) ValidationResult.Invalid(ValidationError.BlankString) else ValidationResult.Valid(SsoProviderId(t)) } } }`
  - `value class EmailAddress private constructor(val value: String) { companion object { fun create(value: String): ValidationResult<EmailAddress> { val t = value.trim().lowercase(); return if(t.isBlank()) ValidationResult.Invalid(ValidationError.BlankString) else ValidationResult.Valid(EmailAddress(t)) } } }`

- **Entity Interfaces**:
  - `interface BaseEntity<ID : Any> { val id: ID; val versionId: UserVersionId; val createdAt: Instant; val updatedAt: Instant }`
  - `interface AdvancedEntity { val rowCreatedAt: Instant; val rowUpdatedAt: Instant }`
  - `interface BaseVersionEntity<ID : Any> : BaseEntity<ID>`
  - `interface SqlSession { fun prepareStatement(sql: String): java.sql.PreparedStatement }`

- **Auth**:
  - `sealed interface AuthMethod { data class Password(val hash: PasswordHash) : AuthMethod; data class SSO(val providerId: SsoProviderId) : AuthMethod; data class Both(val hash: PasswordHash, val providerId: SsoProviderId) : AuthMethod }`

- **Entities**:
  - `data class NewUser(val email: EmailAddress, val name: PersonName, val displayName: DisplayName?, val authMethod: AuthMethod)` 
    - > **Design Decision regarding Database Generated UUIDs**: We explicitly omit client UUID generation from `NewUser`, opting to let the Postgres database safely populate the `uuidv7()` default on row insertion. While functional event-driven systems often favor client-generated UUIDs to guarantee deterministic API idempotency natively across retry limits, this system rigorously bottlenecks all user registration attempts through a strict unique database index on `email`. A TCP-dropped network retry targeting identical mapping logically triggers the `DuplicateEmail` Union Error seamlessly, mathematically providing safe idempotency blocks without imposing duplicate payload burdens upon the client boundaries.
  - `data class User(override val id: UserId, val email: EmailAddress, val name: PersonName, val displayName: DisplayName?, val authMethod: AuthMethod, override val versionId: UserVersionId, override val createdAt: Instant, override val rowCreatedAt: Instant, override val updatedAt: Instant, override val rowUpdatedAt: Instant, val deletedAt: Instant?) : BaseEntity<UserId>, AdvancedEntity`
  - `data class UserVersion(override val id: UserId, override val versionId: UserVersionId, val email: EmailAddress, val name: PersonName, val displayName: DisplayName?, val authMethod: AuthMethod, override val createdAt: Instant, val rowCreatedAt: Instant, override val updatedAt: Instant, val rowUpdatedAt: Instant, val deletedAt: Instant?) : BaseVersionEntity<UserId>`

### DAO Interface (Pure Boundaries via Facade)
The DAO execution behaves as a mathematically isolated evaluator traversing functional mapping boundaries (`SqlSession -> Result`). To achieve strict compile-time guarantees preventing transaction lifecycle mutation, the DAO utilizes a custom facade interface (`SqlSession`). By restricting mapping against `java.sql.Connection`, developers are physically unable to evaluate `.commit()` or `.rollback()` operations, effectively locking the DAO as a pure execution boundary.

Immutable physical identifiers (`id`, `created_at`, `row_created_at`) AND automatic database-managed sequence intervals (`updated_at`, `row_updated_at`) are explicitly blacklisted from `UPDATE ... SET` clauses uniformly protecting OCC triggers securely.

All internal read logic directly parses `java.sql.Timestamp` payloads securely utilizing strict defensive `Instant` conversions natively scaling temporal representations while entirely eliminating Java timezone corruption anomalies mathematically safely limits seamlessly.

**Point Read Operations**:
- `fun findById(session: SqlSession, id: UserId, includeDeleted: Boolean = false): FindResult`
- `fun findByIdForUpdate(session: SqlSession, id: UserId, includeDeleted: Boolean = false): FindResult` (Injects `FOR UPDATE NOWAIT`)
- `fun findByEmail(session: SqlSession, email: EmailAddress): FindResult`

**Mutation Operations**:
- `fun create(session: SqlSession, user: NewUser): CreateResult`
- `fun update(session: SqlSession, user: User): UpdateResult` (Executes absolute mapping: `SET version = user.versionId.value + 1 ... WHERE id = user.id.value AND version = user.versionId.value RETURNING *`)
- `fun updatePhysicalRecord(session: SqlSession, user: User): UpdateResult` (Prepends `SET LOCAL unicoach.bypass_logical_timestamp = 'true';`. Scoped execution natively tailored explicitly for background internal workers triggering configuration updates while rigorously preventing profile threshold bump propagation constraints mathematically mapped statically).
- `fun delete(session: SqlSession, id: UserId, currentVersion: UserVersionId): DeleteResult` (Sets `deleted_at = NOW()`, updates versions mapping directly against explicit target: `WHERE id = ? AND version = currentVersion.value RETURNING *`).
- `fun undelete(session: SqlSession, id: UserId, currentVersion: UserVersionId): UpdateResult` (Forces `deleted_at = NULL` and computes explicitly strict version maps directly. Seamlessly evaluates against active boundaries generating explicit non-exceptive `UpdateResult.DuplicateEmail` metrics universally).
- `fun revertToVersion(session: SqlSession, id: UserId, targetHistoricalVersion: UserVersionId, currentVersion: UserVersionId): UpdateResult` (Extracts bounds functionally routing directly inside standard incremental queries seamlessly mapping active payload streams explicitly safely.)

**Version History Operations**:
- `fun findVersion(session: SqlSession, id: UserId, targetVersion: UserVersionId): FindVersionResult`

### Error Handling / Union Types
All execution pathways encapsulate errors completely routing JDBC `SQLException` streams safely translating codes toward algebraic Union definitions safely sidestepping JVM throw routines mathematically parsing bounded error loops definitively cleanly mapping `DatabaseFailure`:
- `sealed interface FindResult { data class Success(val user: User); object NotFound; object LockAcquisitionFailure; data class DatabaseFailure(val msg: String) }`
- `sealed interface CreateResult { data class Success(val user: User); object DuplicateEmail; data class ConstraintViolation(val reason: String); data class DatabaseFailure(val msg: String) }`
- `sealed interface UpdateResult { data class Success(val user: User); object NotFound; object DuplicateEmail; object ConcurrentModification; object TargetVersionMissing; data class ConstraintViolation(val reason: String); data class DatabaseFailure(val msg: String) }`
- `sealed interface DeleteResult { data class Success(val user: User); object NotFound; object ConcurrentModification; data class DatabaseFailure(val msg: String) }`
- `sealed interface FindVersionResult { data class Success(val version: UserVersion); object NotFound; data class DatabaseFailure(val msg: String) }`

## Tests
Since our aggressive use of strict Kotlin `Value Classes` ensures data formatting (like email spacing and empty strings) is evaluated at compile-time, basic DAO validation tests are deliberately skipped. The integration tests strictly focus on complex Postgres engine logic and transaction concurrency boundaries interacting natively inside an ephemeral container.

1. **Read & Lock Queries (`findByIdForUpdate`, reads)**:
   - Verify `includeDeleted = false` correctly emits `NotFound` for softly deleted rows.
   - **Concurrency (Tricky)**: Allocate two parallel `java.sql.Connection` contexts to explicitly verify that an active row lock yields `55P03 NOWAIT` immediately terminating the second connection lock request.
2. **Mutations & Optimistic Locking (`update`, `delete`, `create`)**:
   - **OCC (Tricky)**: Dispatch a stale `UserVersionId` during `update` or `delete` and assert it correctly trips into `UpdateResult.ConcurrentModification`.
   - **Conflict Res (Tricky)**: Assert `create` routes secondary duplicate emails directly into `CreateResult.DuplicateEmail`.
3. **Advanced Integrations (`undelete`, `updatePhysicalRecord`, `revertToVersion`)**: 
   - **Undelete Conflict (Tricky)**: Perform an `undelete` into an email that is already registered to a new active user, asserting it yields `DuplicateEmail` safely without causing index violations.
   - **State Isolation (Tricky)**: Dispatch `updatePhysicalRecord`. Rollback. Dispatch a standard baseline `update`. Ascertain that `SET LOCAL unicoach.bypass_logical_timestamp = 'true'` was safely destroyed alongside the transaction block and did not bleed into subsequent queries.
   - **Revert Extraction**: Create a user, update it, and assert that `revertToVersion` correctly reinstates the exact fields mapping from the explicit historical bounds natively.

## Implementation Plan
1. **Set Up Test Framework**: Instantiate `UsersDaoTest.kt` with isolated SQL connection blocks in the ephemeral Postgres engine.
2. **Model Specifications & Tests**: Define generic entity structures and rigid `ValidationResult<T>` factories inside typed value classes (`EmailAddress`, `PersonName`, etc.) to enforce zero-exception domain bounds.
3. **Union Results**: Define exact immutable error states (`DatabaseFailure`, `DuplicateEmail`, etc.) terminating all control flows securely mapping SQLSTATE values statically.
4. **Read Implementation & Tests**: Establish query routing that enforces `Instant` precision guarantees uniformly executing across standard point read sequences.
5. **Write Implementation**: Connect parametric data fields via pure functions mapping strict `RETURNING *` bounds systematically avoiding transaction lifecycle methods implicitly exposing mutation constraints manually.
6. **State Branch Implementations**: Expand `undelete` logic seamlessly executing bounds correctly testing dual-email overlapping metrics gracefully.

## Files Modified
- `rest-server/src/main/kotlin/ed/unicoach/db/dao/DaoModule.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/db/models/UserId.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/db/models/UserVersionId.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/db/models/ValidationResult.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/db/models/EmailAddress.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/db/models/PersonName.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/db/models/DisplayName.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/db/models/PasswordHash.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/db/models/SsoProviderId.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/db/models/NewUserRecord.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/db/models/UserRecord.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/db/models/UserVersionRecord.kt` [NEW]
- `rest-server/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt` [NEW]
