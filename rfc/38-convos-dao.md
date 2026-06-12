# RFC 38: Convos DAO

## Executive Summary

This RFC adds `ConvosDao`, the data-access layer over the coaching-conversation
tables created by RFC 32 (`convos`, `convo_requests`, `convo_responses`,
`convo_responses_raw`). RFC 32 was schema-only and deferred all reads and writes
to "later RFCs that will read this store"; this is that DAO.

`ConvosDao` follows the established `UsersDao`/`StudentsDao` conventions: a
stateless `object`, `SqlSession`-per-call with transaction boundaries owned by
`Database.kt`, `Result<T>` returns, `DaoException` classification, and
read-back-after-write via `RETURNING *`. It diverges where the schema forces it:

- **No OCC.** `convos` has no `version` column (RFC 32 disabled versioning), so
  mutating methods carry no optimistic-concurrency guard.
- **Two transaction boundaries per turn.** A coaching turn writes the request
  row (after the provider RPC is issued) and the response + raw rows (after the
  provider returns) in two separate transactions. The DAO enforces this by
  exposing `appendRequest` and `appendResponse` as distinct methods with no
  combined "write whole turn" method.
- **Generic three-state soft-delete read option.** A new `SoftDeleteScope`
  (`ACTIVE` / `DELETED` / `ALL`) controls whether reads return active, deleted,
  or all convos and their logs, superseding the two-state
  `includeDeleted:
  Boolean` for this DAO.

It also introduces the domain models for these tables and one shared read-option
type. No schema, dependency-injection, or `DaoExceptions` changes are required;
the work is additive.

## Detailed Design

### Data Models

All new model types live in `db/src/main/kotlin/ed/unicoach/db/models/` and
follow the package's existing conventions: `@JvmInline value class` for scalar
wrappers, the one-capability-each interfaces from `Entity.kt`
(`Identifiable`/`Created`/`Updated`/`SoftDeletable`), and `ValidationResult`
factories for validated values.

#### JSON columns

The four JSONB columns are modeled as `kotlinx.serialization.json` value types,
not raw `String`:

| Column                          | Type                                             |
| ------------------------------- | ------------------------------------------------ |
| `convo_requests.content`        | `JsonElement`                                    |
| `convo_requests.request_params` | `JsonObject?` (matches the DB `is_object` check) |
| `convo_responses.content`       | `JsonElement?`                                   |
| `convo_responses_raw.payload`   | `JsonElement`                                    |

`kotlinx-serialization-json` is an `api` dependency of `common`, so it reaches
`db`'s compile classpath through `implementation(project(":common"))`; no build
change is needed. `queue/dao/JobsDao.kt` is the in-repo precedent for the JSONB
bind/read pattern this DAO reuses: bind `element.toString()` into a `?::jsonb`
slot, read back via `rs.getString(col)` then `Json.parseToJsonElement`. Holding
`JsonElement` in `db/models` contradicts a current invariant:
`db/src/main/kotlin/ed/unicoach/db/models/SPEC.md` states the package MUST NOT
import kotlinx and calls a violation "an architectural boundary error."
`REQUIRES ARCHITECT DECISION`: the JSONB→`JsonElement` mapping (LOCKED) requires
amending that invariant to carve out `kotlinx.serialization.json` value types —
`JsonElement` is a pure value type used here like `UUID` or `Instant`, with no
serialization behavior invoked in the model layer, distinct from a persistence
or I/O framework. The Implementation Plan deliberately does not edit `SPEC.md`;
spec-sync reconciles the invariant out-of-band, so the implementor's kotlinx
import is expected, not a regression. The DAO does NOT parse JSON into
provider-specific shapes — content blocks remain opaque `JsonElement`; their
internal structure is owned by the service layer, per RFC 32's content-
representation decision.

#### Shared read option

`SoftDeleteScope` is an `enum { ACTIVE, DELETED, ALL }` declared alongside
`Entity.kt`. It is the read-time companion to the `SoftDeletable` capability and
is intentionally domain-agnostic so any DAO over a soft-deletable entity can
reuse it. `ACTIVE` filters `deleted_at IS NULL`, `DELETED` filters
`deleted_at IS
NOT NULL`, `ALL` applies no filter. Retrofitting
`UsersDao`/`StudentsDao` from `includeDeleted: Boolean` to `SoftDeleteScope` is
left to a focused follow-up; this RFC applies it only to `ConvosDao`.

#### Identity types

- `ConvoId` — `value class(UUID): Id`. Convo PK.
- `ConvoRequestId` — `value class(Long): Id`. `convo_requests.id` is `BIGINT`;
  `asString` derives from `value.toString()`.
- `ConvoResponseId` — `value class(Long): Id`. `convo_responses.id` is `BIGINT`.
- `SystemPromptId` — `value class(UUID): Id`, mirroring `ConvoId`.
  `convo_requests.system_prompt_id` is `UUID` referencing `system_prompts(id)`
  (RFC 33). Introduced here because RFC 33 shipped schema only and no Kotlin
  model, and `ConvoRequest` needs the typed FK.

#### Validated value type

- `ConvoName` — `value class(String)` with a private constructor and a
  `create(value: String): ValidationResult<ConvoName>` factory mirroring
  `DisplayName`/`PersonName`. `create` trims, returns `Invalid(BlankString)`
  when blank-after-trim and `Invalid(TooLong)` when length exceeds 255. These
  mirror the `convos_name_*` DB checks; the trim makes the persisted value
  satisfy `convos_name_trimmed_check`.

#### Aggregate records

- `Convo` — `Identifiable<ConvoId>, Created, Updated, SoftDeletable`. Fields:
  `id`, `studentId: StudentId`, `name: ConvoName`, `createdAt`, `updatedAt`,
  `deletedAt: Instant?`. It does NOT implement `Versioned` (no `version`
  column).
- `NewConvo` — `studentId: StudentId`, `name: ConvoName`. Sole input for
  `create`.
- `ConvoRequest` — `Identifiable<ConvoRequestId>, Created`. Fields: `id`,
  `convoId: ConvoId`, `createdAt`, `provider: String`, `modelRequested: String`,
  `systemPromptId: SystemPromptId`, `requestParams: JsonObject?`,
  `content: JsonElement`. `provider`/`modelRequested` are free-form provenance
  strings validated by DB checks, not value classes; `systemPromptId` is an FK
  into `system_prompts` (RFC 33), not a free-form pin.
- `NewConvoRequest` — `convoId`, `provider`, `modelRequested`, `systemPromptId`,
  `requestParams: JsonObject?`, `content: JsonElement`.
- `ConvoResponse` — `Identifiable<ConvoResponseId>, Created`. Fields: `id`,
  `requestId: ConvoRequestId`, `convoId: ConvoId`, `content: JsonElement?`,
  `modelResolved: String?`, `stopReason: String`, `inputTokens: Int?`,
  `outputTokens: Int?`, `cacheReadTokens: Int?`, `cacheWriteTokens: Int?`,
  `providerRequestId: String?`, `latencyMs: Int?`, `createdAt`.
- `NewConvoResponse` — the same fields as `ConvoResponse` minus
  `id`/`createdAt`. The raw payload is NOT a field; it is passed separately to
  `appendResponse`. `convoId` is carried explicitly because
  `convo_responses.convo_id` is denormalized (RFC 32); the caller supplies it
  and the DAO does not re-derive it from the request via an extra SELECT.
- `ConvoResponseRaw` — `responseId: ConvoResponseId`, `createdAt`,
  `payload: JsonElement`.
- `ConvoTurn` — `request: ConvoRequest`, `response: ConvoResponse?`. The replay
  unit. `response` is nullable: a request can exist with no response when the
  first transaction has committed but the second has not (provider in flight, or
  the response transaction never ran).

### API Contracts

`ConvosDao` is an `object`. Every method takes `SqlSession` first, issues SQL
via `PreparedStatement` (no interpolation of caller data), and returns
`Result<T>`. `SoftDeleteScope` predicates are fixed SQL fragments (no caller
data) and are safe to inline. Non-null JSON parameters bind as
`element.toString()` through a `?::jsonb` cast, mirroring `JobsDao`; the
NULL-JSON path (`setNull(Types.OTHER)` for `request_params`/`content`) is new to
this DAO, since `jobs.payload` is `NOT NULL`. JSON columns read back via
`rs.getString(col)` then the default `Json.parseToJsonElement`.

**Convo entity**

- `findById(session, id: ConvoId, scope: SoftDeleteScope = ACTIVE): Result<Convo>`
  — fetch by PK, then evaluate `scope` against `deletedAt` (`UsersDao.findById`
  style). `NotFoundException` if absent or filtered out.
- `listByStudent(session, studentId: StudentId, scope: SoftDeleteScope = ACTIVE): Result<List<Convo>>`
  — `WHERE student_id = ?` plus the scope predicate, `ORDER BY created_at, id`.
  Empty list when the student has no matching convos.
- `create(session, convo: NewConvo): Result<Convo>` — single
  `INSERT ... RETURNING *`. DB generates `id`/timestamps.
- `rename(session, id: ConvoId, name: ConvoName): Result<Convo>` —
  `UPDATE convos SET name = ? WHERE id = ? AND deleted_at IS NULL RETURNING *`.
  No version guard; concurrent renames are last-write-wins. `updated_at` is
  bumped by the DB trigger.
- `delete(session, id: ConvoId): Result<Convo>` —
  `UPDATE ... SET deleted_at = NOW() WHERE id = ? AND deleted_at IS NULL` (soft
  delete).
- `undelete(session, id: ConvoId): Result<Convo>` —
  `UPDATE ... SET deleted_at = NULL WHERE id = ? AND deleted_at IS NOT NULL`.

**Logs — write (two transaction boundaries)**

- `appendRequest(session, request: NewConvoRequest): Result<ConvoRequest>` — one
  `INSERT INTO convo_requests ... RETURNING *`. The caller invokes this in its
  own transaction, after the provider RPC has been issued.
- `appendResponse(session, response: NewConvoResponse, rawPayload: JsonElement?): Result<ConvoResponse>`
  — `INSERT INTO convo_responses ... RETURNING *`, then, when `rawPayload` is
  non-null, `INSERT INTO convo_responses_raw (response_id, payload)` using the
  returned id. Both inserts run inside the single transaction the caller
  provides, so the response and its raw sibling are atomic together. A null
  `rawPayload` is the transport-error turn (`stopReason = "error"`,
  `content = null`): only the response row is written.

**Logs — read**

- `listTurns(session, convoId: ConvoId, scope: SoftDeleteScope = ACTIVE): Result<List<ConvoTurn>>`
  — `convo_requests r LEFT JOIN convo_responses resp ON resp.request_id = r.id`,
  `JOIN convos c ON c.id = r.convo_id` with the scope predicate on
  `c.deleted_at`, `WHERE r.convo_id = ?`, `ORDER BY r.created_at, r.id`. Each
  row maps to a `ConvoTurn` with a nullable response. Served by the existing
  `convo_requests_convo_id_created_at_idx` and the `convo_responses.request_id`
  UNIQUE index; no new index is required.
- `findRawByResponseId(session, responseId: ConvoResponseId): Result<ConvoResponseRaw>`
  — `SELECT * FROM convo_responses_raw WHERE response_id = ?`.
  `NotFoundException` when absent (including error turns that wrote no raw row).

The raw payload is deliberately excluded from `listTurns`: it is the isolated,
verbatim fidelity backup (RFC 32) and is fetched on demand only.

### Error Handling / Edge Cases

A private `mapConvoError(e: SQLException): Exception` centralizes SQLSTATE
discrimination for the write paths; all other failures route through the shared
`mapDatabaseError`. No new `DaoException` types are introduced. The 23503 branch
derives its message from the violated FK constraint name in `e.message` —
`StudentsDao.mapCreateUpdateError` already uses this constraint-name matching
for `students_user_id_unique_idx` — so a single-arg `mapConvoError` resolves the
correct message without a call-site discriminator.

- `23503` (FK violation): `convos_student_id_fkey` →
  `NotFoundException("Owning student not found")` (`create`);
  `convo_requests_convo_id_fkey` → `NotFoundException("Convo not found")` and
  `convo_requests_system_prompt_id_fkey` →
  `NotFoundException("System prompt not found")` (`appendRequest`); the
  `convo_responses` request/convo FKs → `NotFoundException` (`appendResponse`,
  absent request or convo).
- `23505` (unique violation): mapped to `ConstraintViolationException`. In
  normal `appendResponse` flow the only reachable case is a second response for
  one request hitting the `convo_responses.request_id` `UNIQUE` constraint; the
  `convo_responses_raw.response_id` PRIMARY KEY is a second 23505 source,
  unreachable in normal flow (one raw row per fresh response id) but exercisable
  by a deliberate duplicate raw insert. Both are left generic rather than typed:
  no caller branches on a typed variant yet.
- `23514` (check violation): `ConstraintViolationException`. Covers the request
  content-size bound, the `provider` allowlist, the `request_params` is-object
  check, the response content/model presence checks, and the token/latency
  non-negative checks.
- Mutating-convo methods (`rename`/`delete`/`undelete`) that match zero rows
  return `NotFoundException`. Because there is no `version` column, there is no
  concurrent-modification path and no secondary discrimination `SELECT`; a
  `rename`/`delete` against a soft-deleted convo and an absent convo both map to
  `NotFoundException`.
- The append-only triggers (`prevent_log_update`/`prevent_log_delete`) and the
  convo `prevent_physical_delete` trigger raise `P0001`. These are unreachable
  from the DAO surface — no method updates or deletes a log row, and no method
  physically deletes a convo — so they require no specific mapping; were one
  ever hit, `mapDatabaseError` classifies it as `DatabaseException` (permanent).
- Read paths cannot encounter malformed JSON: the DB stores validated `jsonb`,
  and `JsonElement.toString()` on the write path always emits valid JSON, so the
  `::jsonb` cast cannot raise `22P02` for DAO-produced values.

### Dependencies

- PostgreSQL 18; `org.postgresql` JDBC driver (no ORM). Existing.
- `kotlinx-serialization-json` — reached via `common` (see JSON columns). No new
  build dependency.
- The `0006.create-coaching-conversations.sql` (RFC 32) and
  `0007.create-system-prompts.sql` (RFC 33) migrations. Both already applied.
  0007 superseded RFC 32's `convo_requests.system_prompt_version` TEXT pin with
  a `system_prompt_id` FK into `system_prompts`. This RFC's data models, error
  mapping, and file list are reconciled against the applied schema (0007), not
  the superseded RFC 32 text; the applied migration is the source of truth.

No new dependency, schema, or migration; the work is additive. Rollback is
removal of the new source files.

## Tests

Two new JUnit5 test classes, run against the local test database via the project
harness (`bin/test`), which migrates the schema before Gradle. The DAO test
mirrors `StudentsDaoTest`: a `DriverManager` connection, a `SqlSession` adapter,
and a `@BeforeEach`
`TRUNCATE TABLE convos, convo_requests,
convo_responses, convo_responses_raw, students, users CASCADE`,
with a `createStudent()` helper that inserts a `users` row and a `students` row
and returns the `StudentId`.

### `ConvoNameTest` (`db/.../models/ConvoNameTest.kt`)

- `create trims surrounding whitespace and returns Valid`.
- `create returns Invalid BlankString for blank-after-trim input`.
- `create returns Invalid TooLong for input longer than 255`.
- `create accepts a 255-character name` (boundary).

### `ConvosDaoTest` (`db/.../dao/ConvosDaoTest.kt`)

Convo entity:

1. `create returns convo with generated id, name, and equal created and updated timestamps`.
2. `create with absent student returns NotFoundException` (`23503`).
3. `findById ACTIVE returns an active convo`.
4. `findById ACTIVE returns NotFoundException for a soft-deleted convo`.
5. `findById DELETED returns a soft-deleted convo and NotFoundException for an active one`.
6. `findById ALL returns a convo regardless of deletion`.
7. `findById returns NotFoundException for an absent id`.
8. `listByStudent ACTIVE returns only active convos ordered by created_at then id`.
9. `listByStudent DELETED returns only soft-deleted convos`.
10. `listByStudent ALL returns active and deleted convos in order`.
11. `rename updates the name and bumps updated_at`.
12. `rename returns NotFoundException for a soft-deleted convo`.
13. `rename returns NotFoundException for an absent convo`.
14. `delete sets deleted_at and returns the convo`.
15. `delete returns NotFoundException for an already-deleted convo`.
16. `undelete clears deleted_at`.
17. `undelete returns NotFoundException for an active convo`.

Logs — write:

18. `appendRequest inserts a row and round-trips content and request_params JSON`.
19. `appendRequest accepts a null request_params`.
20. `appendRequest with an absent convo returns NotFoundException` (`23503`).
21. `appendRequest with content over 1 MiB returns ConstraintViolationException`
    (`23514`, `convo_requests_content_size_check`).
22. `appendRequest with a non-allowlisted provider returns ConstraintViolationException`
    (`23514`).
23. `appendResponse with a raw payload writes both the response and the raw row`,
    verified via `findRawByResponseId`.
24. `appendResponse without a raw payload writes only the response row`
    (`stopReason = "error"`, `content = null`); `findRawByResponseId` returns
    `NotFoundException`.
25. `appendResponse a second time for the same request_id returns ConstraintViolationException`
    (`23505`, the 1:1 guard).
26. `appendResponse with null content and a non-error stop_reason returns ConstraintViolationException`
    (content-presence check).
27. `appendResponse with an absent request returns NotFoundException` (`23503`).
28. `appendResponse with a negative token count returns ConstraintViolationException`.
29. `appendResponse writes the response and its raw row atomically within a caller transaction`
    — run with `autoCommit = false`; after commit both rows are present.
30. `appendResponse and its raw insert commit or roll back together in the caller transaction`
    — a raw-PK collision is unreachable from outside the DAO (a fresh response
    gets a fresh id; the append-only log triggers forbid deleting a response to
    reuse its id), so the transactional binding is proven directly: a successful
    `appendResponse` with a raw payload, then an explicit caller `rollback`,
    erases both the `convo_responses` row and its `convo_responses_raw` sibling
    together — proving both inserts share the one caller transaction.

Logs — read:

31. `listTurns returns turns ordered by created_at then id, each request paired with its response`.
32. `listTurns yields a null response for a request whose response has not been written`.
33. `listTurns ACTIVE excludes the turns of a soft-deleted convo`.
34. `listTurns DELETED returns only a soft-deleted convo's turns`.
35. `listTurns ALL returns turns regardless of convo deletion`.
36. `findRawByResponseId returns the stored payload`.
37. `findRawByResponseId returns NotFoundException for an absent response id`.

## Implementation Plan

Each step is verified inside the Nix dev shell (`nix develop -c …`), per the
project's toolchain rules.

1. **Add the shared read option and identity/value types.** Create
   `SoftDeleteScope.kt`, `ConvoId.kt`, `SystemPromptId.kt`, `ConvoRequestId.kt`,
   `ConvoResponseId.kt`, and `ConvoName.kt` in `db/.../models/`.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin`.
2. **Add the aggregate records.** Create `Convo.kt`, `NewConvo.kt`,
   `ConvoRequest.kt`, `NewConvoRequest.kt`, `ConvoResponse.kt`,
   `NewConvoResponse.kt`, `ConvoResponseRaw.kt`, and `ConvoTurn.kt`.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin`.
3. **Add and run `ConvoNameTest`.** Create `db/.../models/ConvoNameTest.kt`.
   - Verify:
     `nix develop -c bin/test db --tests "ed.unicoach.db.models.ConvoNameTest"`.
4. **Implement `ConvosDao` convo-entity methods.** Create `ConvosDao.kt` with
   the row mappers, `mapConvoError`, and `findById`, `listByStudent`, `create`,
   `rename`, `delete`, `undelete`.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin`.
5. **Implement the log write methods.** Add `appendRequest` and `appendResponse`
   (with the optional raw insert) plus the JSON bind/parse helpers.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin`.
6. **Implement the log read methods.** Add `listTurns` and
   `findRawByResponseId`.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin`.
7. **Add and run `ConvosDaoTest`.** Create `db/.../dao/ConvosDaoTest.kt`
   covering every case in Tests.
   - Verify:
     `nix develop -c bin/test db --tests "ed.unicoach.db.dao.ConvosDaoTest"`.

## Files Modified

All paths are relative to the project root; every file is new.

- `db/src/main/kotlin/ed/unicoach/db/models/SoftDeleteScope.kt` [NEW]
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoId.kt` [NEW]
- `db/src/main/kotlin/ed/unicoach/db/models/SystemPromptId.kt` [NEW]
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoRequestId.kt` [NEW]
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoResponseId.kt` [NEW]
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoName.kt` [NEW]
- `db/src/main/kotlin/ed/unicoach/db/models/Convo.kt` [NEW]
- `db/src/main/kotlin/ed/unicoach/db/models/NewConvo.kt` [NEW]
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoRequest.kt` [NEW]
- `db/src/main/kotlin/ed/unicoach/db/models/NewConvoRequest.kt` [NEW]
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoResponse.kt` [NEW]
- `db/src/main/kotlin/ed/unicoach/db/models/NewConvoResponse.kt` [NEW]
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoResponseRaw.kt` [NEW]
- `db/src/main/kotlin/ed/unicoach/db/models/ConvoTurn.kt` [NEW]
- `db/src/main/kotlin/ed/unicoach/db/dao/ConvosDao.kt` [NEW]
- `db/src/test/kotlin/ed/unicoach/db/dao/ConvosDaoTest.kt` [NEW]
- `db/src/test/kotlin/ed/unicoach/db/models/ConvoNameTest.kt` [NEW]
