# RFC 40: Richer validation-error reporting across the common and db modules

## Executive Summary

`ValidationError` (`common/src/main/kotlin/ed/unicoach/common/models/ValidationResult.kt`)
is three payload-free `data object`s — `BlankString`, `InvalidFormat`, `TooLong` —
so `ValidationResult.Invalid(error)` names the failing variant but never the
requirement that was broken. Separately, three DAO row-reconstruction sites throw a
fixed `SQLException` string on `ValidationResult.Invalid`, discarding both the
structured error and the offending column value; the throwable is then re-wrapped by
`mapDatabaseError` into a `DatabaseException`, so the loss looks intentional.

This RFC enriches the variants and fixes the reconstruction sites along two layers:

- **Layer 1 — requirement metadata on the variant.** `TooLong(maxLength: Int)` and
  `InvalidFormat(expected: String)` become `data class`es carrying the schema
  requirement they enforce. This metadata is non-sensitive and safe to log. The
  no-requirement variant carries nothing and stays a `data object`, but is
  renamed `BlankString` → `Blank` (see Layer 1). Producers that emit the
  two enriched variants are updated for the metadata; producers that emit the
  no-requirement variant get a one-line rename only.
- **Layer 2 — the raw offending value is captured by the consumer (the DAO).** A new
  `CorruptPersistedValueException(value, error)` replaces the `SQLException` hack at
  the `PartialDate` reconstruction site, and a `CorruptPersistedAuthMethodException`
  carries the offending row's `UserId` at the `AuthMethod` reconstruction site. The
  raw value never lands on `ValidationError`, because that type runs on the
  least-trusted input in the system and would otherwise pipe PII/secrets into logs.

Both new exceptions are `PermanentError` `DaoException`s. This RFC also corrects the
StatusPages mapping for server-fault persistence errors: `DatabaseException` and the
two new corruption exceptions are mapped to `500 Internal Server Error` (today they
fall through to `400 Bad Request`, which misattributes a server-side data-integrity
fault to the client). Client-fault `PermanentError`s keep their existing `400`.

## Detailed Design

### Layer 1: enriched `ValidationError` variants

`common/.../ValidationResult.kt` becomes:

```
sealed interface ValidationError {
  data object Blank : ValidationError
  data class InvalidFormat(val expected: String) : ValidationError
  data class TooLong(val maxLength: Int) : ValidationError
}
```

The no-requirement variant is renamed `BlankString` → `Blank` and stays a
`data object` — blankness has no requirement to carry, and the design rule places
metadata per-variant, never on a shared base. The rename is mechanical: it touches
the variant definition, the 7 producer sites that emit it, and the 6 test
references to it (enumerated under Producers and Files Modified). `ValidationResult`
itself is unchanged. No `when` exhaustively matches `ValidationError` anywhere in
the tree (verified by grep), and every existing test uses `is ValidationError.X`
type checks, which compile unchanged against `data class`es; converting the two
variants is therefore non-breaking except at construction sites and the renamed
references, all of which are listed below.

**Producers updated (only the actual emitters of the two changed variants):**

| Producer | File | Change |
| --- | --- | --- |
| `EmailAddress.create` | `common/.../models/EmailAddress.kt` | `InvalidFormat` → `InvalidFormat(expected = "local@domain")` |
| `PartialDate.parse` / `PartialDate.of` | `db/.../models/PartialDate.kt` | 5 `InvalidFormat` sites → `InvalidFormat(expected = EXPECTED_FORM)` |
| `EmailBody.create` | `email/.../EmailBody.kt` | `TooLong` → `TooLong(maxLength = MAX_BODY_LENGTH)` |
| `EmailSubject.create` | `email/.../EmailSubject.kt` | `TooLong` → `TooLong(maxLength = MAX_SUBJECT_LENGTH)` |

`PartialDate` gains one private constant `const val EXPECTED_FORM = "YYYY | YYYY-MM |
YYYY-MM-DD"`, supplied by both `parse` (string path) and `of` (decomposed-column
path) so the expected shape is defined once and not duplicated across the five sites.

**The `email` module is mandatory `TooLong` scope; the four no-op `db/models` files
are dropped as metadata producers.** The producer set is corrected against ground
truth, which diverges from the original brief's list (`EmailAddress`, `DisplayName`,
`PersonName`, `PartialDate`, `PasswordHash`, `SsoProviderId`) in two ways:

1. **No producer in `common` or `db` emits `TooLong`.** The *only* `TooLong`
   producers in the entire codebase are `EmailBody.create` and `EmailSubject.create`
   in the `email` module. Converting `TooLong` to a `data class` with a required
   `maxLength` field therefore *forces* edits to those two files, or the `email`
   module fails to compile. The per-variant-metadata rule ("only add a field when a
   real producer actually supplies it") is satisfied *because of* these producers —
   they are what justify the `maxLength` field at all. The `email` module is in
   scope.
2. **`DisplayName`, `PersonName`, `PasswordHash`, `SsoProviderId` emit only the
   no-requirement variant** (verified — each `create` has a single `isBlank()`
   branch). They carry no `TooLong`/metadata change, so they are dropped from the
   producer set as metadata producers. They are **not** no-ops overall, however:
   the `BlankString` → `Blank` rename below touches each of them at its single
   emit site, so they reappear in Files Modified for that one-line rename only.

**The no-requirement variant is renamed `BlankString` → `Blank`.** Every emit site
guards with `isBlank()` (true for whitespace-only strings, not only empty ones), so
`Blank` names the actual failure state, where `BlankString` named the input type and
`EmptyString` would have mis-described a blank-but-non-empty input. The rename is
purely mechanical and carries no behavioural change. It touches:

- the definition in `common/.../ValidationResult.kt`;
- the 7 emit sites — `EmailAddress.create` (`common`); `DisplayName.create`,
  `PersonName.create`, `PasswordHash.create`, `SsoProviderId.create` (`db/models`);
  `EmailBody.create`, `EmailSubject.create` (`email`);
- the 6 test references — `EmailAddressTest` (`common`), `EmailBodyTest`,
  `EmailSubjectTest` (`email`), each at its `BlankString` test name and assertion.

Every one of these sites is in Files Modified.

Net change versus the brief's list: drop the four `db/models` files as metadata
producers, add the two `email` files as the `TooLong` producers.

### Layer 2: consumer-captured corruption exceptions

Two new members in `db/.../dao/DaoExceptions.kt`:

```
class CorruptPersistedValueException(
  val value: String,
  val error: ValidationError,
) : DaoException("Persisted value failed reconstruction"), PermanentError

class CorruptPersistedAuthMethodException(
  val userId: UserId,
) : DaoException("Persisted user row has no auth method"), PermanentError
```

`CorruptPersistedValueException` carries the raw column value (a `String`) and the
structured `ValidationError`. It is read back from our *own* database — not
untrusted input — so capturing the value here is safe and is exactly the context the
`SQLException` string discarded.

`AuthMethod` is not a `ValidationResult`, so it gets the equivalent typed exception
the design calls for. Its corruption branch fires only when **both** `password_hash`
and `sso_provider_id` columns are null, so there is no single offending string value
and no `ValidationError` to carry; the meaningful, non-sensitive diagnostic available
at the site is the row's `UserId` (read before the `when`). `CorruptPersistedAuth-
MethodException` carries that. This is the documented deviation from "carry the raw
column value": the raw value is "both null," so the row identity is carried instead.

**Reconstruction sites changed:**

- `StudentsDao.mapGraduationDate` — replace
  `throw SQLException("Persisted graduation date columns do not form a valid partial
  date")` with `throw CorruptPersistedValueException(value = "year=$year
  month=$month day=$day", error = result.error)` in the `ValidationResult.Invalid`
  branch. The `when (result)` over `ValidationResult` is already exhaustive
  (`Valid`/`Invalid`, no `else`) and stays so.
- `UsersDao.mapUser` and `UsersDao.mapUserVersion` — replace each
  `throw SQLException("Invalid AuthMethod state in database")` in the `else` branch
  with `throw CorruptPersistedAuthMethodException(userId = id)`. The `else` is the
  genuine fourth case of a condition-`when` (both columns null) and remains; only the
  thrown type changes. No `null`-`sqlState` `SQLException` is created, so the branch
  no longer relies on `mapDatabaseError` accidentally bucketing a null-state
  `SQLException` as non-transient.

**Required correctness fix — `mapDatabaseError` must pass `DaoException` through
unchanged.** `mapGraduationDate`/`mapUser`/`mapUserVersion` run inside DAO `try`
blocks. Both new exceptions extend `DaoException : RuntimeException` — not
`SQLException` — so on the methods that precede `catch (e: Exception)` with a
`catch (e: SQLException)` (the `*ForUpdate`/`create`/`update`/`delete`/`undelete`/
`revertToVersion` paths), they bypass that first catch and land in
`catch (e: Exception)`, which calls `mapDatabaseError(e)`; the two reconstruction-test
paths (`StudentsDao.findById`, `UsersDao.findVersion`) have only the single
`catch (e: Exception)`. On every reaching path the new exceptions therefore arrive at
`mapDatabaseError`. Today that wraps any non-`SQLException` in `DatabaseException(e)`,
so a thrown `CorruptPersistedValueException`/`CorruptPersistedAuthMethodException`
would be re-wrapped, defeating the typed exception. `mapDatabaseError` gains a leading
guard: if `e is DaoException`, return `e` unchanged. This is idempotent and required
for either new exception to survive to the caller. (`NotFoundException` etc. are returned via `Result.failure`,
not thrown through these catches, so they are unaffected today; the guard makes the
pass-through explicit and safe.)

### Out of scope (explicitly bounded)

`UsersDao` reconstructs `EmailAddress`, `PersonName`, `DisplayName`, `PasswordHash`,
and `SsoProviderId` via `(... .create(...) as ValidationResult.Valid).value`
unsafe casts (14 sites). On corruption these throw `ClassCastException`, a *different*
failure mode than the `SQLException` hacks this RFC targets, and the settled design
names only the `PartialDate` and `AuthMethod` sites. They are **not** changed here.
Noted so the implementer does not expand scope; a follow-up RFC could convert them to
`CorruptPersistedValueException` for symmetry.

### Error handling / StatusPages

`rest-server/.../plugins/StatusPages.kt` gains explicit `500 Internal Server Error`
cases for the three server-fault persistence exceptions. The `is PermanentError`
handler today routes `NotFoundException → 404`, `DuplicateEmailException → 409`, and
**everything else → `400 Bad Request`** via the branch's `else`. That `else` bucket
mixes client faults (`TargetVersionMissingException`, `StudentAlreadyExistsException`,
`ConstraintViolationException`) with server faults (`DatabaseException`), so the
existing `DatabaseException → 400` misattributes a server-side data-integrity fault
to the client. The inner `when (cause)` gains three cases mapping `DatabaseException`,
`CorruptPersistedValueException`, and `CorruptPersistedAuthMethodException` to
`HttpStatusCode.InternalServerError`:

```
is DatabaseException,
is CorruptPersistedValueException,
is CorruptPersistedAuthMethodException -> HttpStatusCode.InternalServerError
```

The `else` (and the client-fault types that rely on it) keeps `400`. Flipping the
`else` itself to `500` is rejected — it would reclassify the client-fault types. The
two new exceptions are mapped explicitly rather than via a shared supertype because
they are siblings of `DatabaseException` under `DaoException`, not subtypes; a
"server-fault" marker interface is a heavier abstraction than three concrete cases
warrant. This supersedes the brief's "map exactly as `DatabaseException`" — that
constraint described a `400` path that was itself the latent bug now being fixed;
`DatabaseException` moves to `500` alongside the corruption exceptions, so corruption
and `DatabaseException` remain identical, now at `500`.

`StatusPages.kt` must add imports for `DatabaseException`, `CorruptPersistedValue-
Exception`, and `CorruptPersistedAuthMethodException` from `ed.unicoach.db.dao`
(`NotFoundException`/`DuplicateEmailException` are already imported). No existing test
asserts `DatabaseException → 400` (the current `400` assertions cover client-input
validation only), so this changes no passing test.

### Dependencies

None added. `DaoExceptions.kt` already imports `PermanentError`; the `UserId` type is
already imported in `UsersDao.kt` and is in the `db` module alongside `DaoExceptions`.
The `email` module already depends on `common` (it imports `ValidationError`).

## Tests

### common (`nix develop -c bin/test common`)

`common/.../models/EmailAddressTest.kt` (extend existing):
- `invalid-format error carries the expected email shape` — `EmailAddress.create("nope")`
  yields `Invalid` whose `error` is `InvalidFormat` with `expected == "local@domain"`.
- The existing blank-input type-check test is renamed to assert
  `error is ValidationError.Blank` (was `BlankString`); the `InvalidFormat`
  type-check test remains and still passes.

### db (`nix develop -c bin/test db`)

`db/.../models/PartialDateTest.kt` (extend existing):
- `parse rejects with InvalidFormat carrying the canonical forms` —
  `PartialDate.parse("2028-13")` (and a non-canonical string) yields `InvalidFormat`
  with `expected == "YYYY | YYYY-MM | YYYY-MM-DD"`.
- `of rejects day-without-month with InvalidFormat carrying the canonical forms` —
  `PartialDate.of(2028, null, 15)` yields `InvalidFormat` with the same `expected`.

`db/.../dao/StudentsDaoTest.kt` (new test): corruption of `mapGraduationDate` is
unreachable through constraint-valid inserts (the `students` CHECK constraints
`grad_day_requires_month` / `grad_month_range` / `grad_date_valid` mirror
`PartialDate.of`), so the test forces it transactionally, replicating the local
raw-insert pattern of the existing `constraint backstops` test in `StudentsDaoTest.kt`
(its `insertRaw(year, month, day)` is a test-local function, not a shared helper:
autoCommit toggle, `setNull` columns, rollback) extended with the `DROP CONSTRAINT`
steps below:
- `findById on a corrupt graduation date returns failure with
  CorruptPersistedValueException` (the method returns `Result.failure`, it does not
  throw to the caller) — set `connection.autoCommit = false`; drop **both** CHECK
  constraints the corruption row violates: `ALTER TABLE students DROP CONSTRAINT
  grad_day_requires_month` **and** `ALTER TABLE students DROP CONSTRAINT
  grad_date_valid` (the `month`-null/`day`-non-null row trips both — the orphan-day
  rule and `make_date(year, NULL, day) IS NOT NULL`, which is NULL under Postgres
  strict-function semantics; `grad_month_range` is null-tolerant and stays); insert a
  row with `month` null and `day` non-null (referencing a created user); call
  `StudentsDao.findById(session, id)` where `session` is a `SqlSession` wrapping that
  same `connection` (so the uncommitted constraint-drops and corrupt row are visible
  to the read); assert the `Result` failure is `CorruptPersistedValueException` whose
  `error is ValidationError.InvalidFormat` and whose `value` contains the offending
  decomposed columns; `connection.rollback()` and restore `autoCommit` in a `finally`.
  DDL is transactional in Postgres, so both dropped constraints and the corrupt row
  vanish on rollback.

`db/.../dao/UsersDaoTest.kt` (new test): the base `users` table forbids the both-null
state (`users_auth_method_check`), but `users_versions` has **no** such constraint and
is read by `findVersion → mapUserVersion`, giving a clean reachable path:
- `findVersion on a both-null auth row returns failure with
  CorruptPersistedAuthMethodException` (returned as `Result.failure`, not thrown) —
  insert a `users` row (which trigger-writes its `users_versions` row), then
  `UPDATE users_versions SET password_hash = NULL, sso_provider_id = NULL` for that
  `(id, version)` to force the both-null state without violating any `users_versions`
  NOT NULL column (`version`, `created_at`, `row_created_at`, `updated_at`,
  `row_updated_at`, `email`, `name` all stay populated from the trigger-written row).
  A bare direct insert of only `id` + the two nulls would fail those NOT NULL
  constraints before reaching `mapUserVersion`, so the update path is used. Call
  `UsersDao.findVersion(session, id, targetVersion)`; assert the `Result` failure is
  `CorruptPersistedAuthMethodException` whose `userId` equals the inserted id.
- The `mapUser` base-table branch is unreachable (guarded by `users_auth_method_check`)
  and is documented as defensive; not separately tested via a constraint drop.

Both new exceptions are asserted to be `PermanentError` (the type check covers the
fall-through path; the explicit `500` mapping is covered by the `rest-server` test
below).

### rest-server (`nix develop -c bin/test rest-server`)

`rest-server/.../plugins/StatusPagesTest.kt` (new file): verify the server-fault
`→ 500` mapping in isolation, in an `Application` that calls `configureStatusPages()`
at `application {}` scope and registers throwaway `routing {}` probe routes that each
throw one exception, then asserting the response status. Driven through Ktor's
`testApplication` (the sibling plugin tests `RequestSizeLimitTest.kt` /
`SessionExpiryPluginTest.kt` in this same `plugins/` package use this harness; the
top-level routing tests do not):
- `DatabaseException maps to 500` — a route throwing `DatabaseException` yields
  `HttpStatusCode.InternalServerError` with `code == "permanent_error"`.
- `CorruptPersistedValueException maps to 500` — same, throwing
  `CorruptPersistedValueException(value, error)`.
- `CorruptPersistedAuthMethodException maps to 500` — same, throwing
  `CorruptPersistedAuthMethodException(userId)`.
- `a client-fault PermanentError still maps to 400` — a route throwing
  `StudentAlreadyExistsException` (or another `else`-bucket type) yields
  `HttpStatusCode.BadRequest`, pinning the regression boundary so the `else` is not
  accidentally flipped.

### email (`nix develop -c bin/test email`) — required by the `email` scope above

`email/.../EmailBodyTest.kt` and `EmailSubjectTest.kt` (extend existing): the
`TooLong` rejection tests assert `error is TooLong` with `maxLength == MAX_BODY_LENGTH`
(resp. `MAX_SUBJECT_LENGTH`). The existing blank-input type-check test in each is
renamed to assert `error is ValidationError.Blank` (was `BlankString`). These
modules must at minimum recompile and pass; the assertion additions verify
enrichment and the rename.

## Implementation Plan

1. **Enrich and rename the variants.** Edit `common/.../models/ValidationResult.kt`:
   make `InvalidFormat(val expected: String)` and `TooLong(val maxLength: Int)` data
   classes; rename the `data object BlankString` to `data object Blank`.
   - Verify: `nix develop -c ./gradlew :common:compileKotlin` fails only at the
     downstream construction sites (expected until steps 2–4); `grep -rn "data object
     InvalidFormat\|data object TooLong\|BlankString" common/src/main` returns nothing.
2. **Update `common` producer + test.** Edit `EmailAddress.kt` to pass
   `expected = "local@domain"` and emit `Blank` at its blank branch; extend
   `EmailAddressTest.kt` (rename the blank type-check assertion to `Blank`).
   - Verify: `nix develop -c bin/test common`.
3. **Update `db` producers + tests.** Edit `PartialDate.kt`: add `EXPECTED_FORM`
   constant, pass it at all 5 `InvalidFormat` sites. Rename `BlankString` →
   `Blank` at the single emit site in each of `DisplayName.kt`, `PersonName.kt`,
   `PasswordHash.kt`, `SsoProviderId.kt`. Extend `PartialDateTest.kt`.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin`; `grep -rn "BlankString"
     db/src/main` returns nothing.
4. **Update `email` producers + tests.** Edit `EmailBody.kt`
   (`maxLength = MAX_BODY_LENGTH`) and `EmailSubject.kt`
   (`maxLength = MAX_SUBJECT_LENGTH`), renaming `BlankString` → `Blank` at each
   blank branch; extend `EmailBodyTest.kt` / `EmailSubjectTest.kt` (rename the blank
   type-check assertions to `Blank`).
   - Verify: `nix develop -c bin/test email`.
5. **Add the corruption exceptions and the pass-through guard.** In
   `DaoExceptions.kt`, add `CorruptPersistedValueException` and
   `CorruptPersistedAuthMethodException` (import `UserId`, `ValidationError`); add the
   leading `if (e is DaoException) return e` guard to `mapDatabaseError`.
   - Verify: `nix develop -c ./gradlew :db:compileKotlin`.
6. **Replace the reconstruction hacks.** In `StudentsDao.kt`, throw
   `CorruptPersistedValueException` from `mapGraduationDate`'s `Invalid` branch
   (remove the now-unused `java.sql.SQLException` import if it becomes unused — it is
   still used elsewhere in the file, so keep). In `UsersDao.kt`, throw
   `CorruptPersistedAuthMethodException(id)` from both `else` branches
   (`mapUser`, `mapUserVersion`).
   - Verify: `nix develop -c ./gradlew :db:compileKotlin`; `grep -rn "Invalid
     AuthMethod state\|do not form a valid partial date" db/src/main` returns nothing.
7. **Add the DAO corruption tests** (StudentsDaoTest transactional drop of both
   `grad_day_requires_month` and `grad_date_valid`; UsersDaoTest `users_versions`
   both-null via post-insert `UPDATE`) per the Tests section.
   - Verify: `nix develop -c bin/test db`.
8. **Map server-fault exceptions to 500 + test.** In `StatusPages.kt`, import
   `DatabaseException`, `CorruptPersistedValueException`,
   `CorruptPersistedAuthMethodException` and add the three `→ InternalServerError`
   cases to the inner `when (cause)` of the `is PermanentError` branch; leave the
   `else → BadRequest` intact. Add `StatusPagesTest.kt` per the Tests section.
   - Verify: `nix develop -c bin/test rest-server`.
9. **Full regression of touched modules.**
   - Verify: `nix develop -c bin/test common && nix develop -c bin/test db &&
     nix develop -c bin/test email && nix develop -c bin/test rest-server`.

## Files Modified

All entries below are **modified in place** except `StatusPagesTest.kt`, the one
**new file** this RFC creates (the two corruption exceptions are added inside the
existing `DaoExceptions.kt`).

- `common/src/main/kotlin/ed/unicoach/common/models/ValidationResult.kt` — enrich
  `InvalidFormat`/`TooLong` to data classes; rename `BlankString` → `Blank`.
- `common/src/main/kotlin/ed/unicoach/common/models/EmailAddress.kt` — supply
  `expected`; rename `BlankString` → `Blank` at the blank branch.
- `common/src/test/kotlin/ed/unicoach/common/models/EmailAddressTest.kt` — assert
  enriched `InvalidFormat`; rename blank assertion to `Blank`.
- `db/src/main/kotlin/ed/unicoach/db/models/PartialDate.kt` — `EXPECTED_FORM`
  constant; supply `expected` at 5 sites.
- `db/src/main/kotlin/ed/unicoach/db/models/DisplayName.kt` — rename `BlankString` →
  `Blank` (one emit site).
- `db/src/main/kotlin/ed/unicoach/db/models/PersonName.kt` — rename `BlankString` →
  `Blank` (one emit site).
- `db/src/main/kotlin/ed/unicoach/db/models/PasswordHash.kt` — rename `BlankString` →
  `Blank` (one emit site).
- `db/src/main/kotlin/ed/unicoach/db/models/SsoProviderId.kt` — rename `BlankString`
  → `Blank` (one emit site).
- `db/src/test/kotlin/ed/unicoach/db/models/PartialDateTest.kt` — assert enriched
  `InvalidFormat` for `parse` and `of`.
- `db/src/main/kotlin/ed/unicoach/db/dao/DaoExceptions.kt` — add
  `CorruptPersistedValueException`, `CorruptPersistedAuthMethodException`;
  `DaoException` pass-through in `mapDatabaseError`.
- `db/src/main/kotlin/ed/unicoach/db/dao/StudentsDao.kt` — throw
  `CorruptPersistedValueException` from `mapGraduationDate`.
- `db/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt` — throw
  `CorruptPersistedAuthMethodException` from `mapUser` and `mapUserVersion`.
- `db/src/test/kotlin/ed/unicoach/db/dao/StudentsDaoTest.kt` — corruption test
  (transactional drop of `grad_day_requires_month` + `grad_date_valid`).
- `db/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt` — corruption test
  (`users_versions` both-null via post-insert `UPDATE`).
- `email/src/main/kotlin/ed/unicoach/email/EmailBody.kt` — supply `maxLength`; rename
  `BlankString` → `Blank` at the blank branch.
- `email/src/main/kotlin/ed/unicoach/email/EmailSubject.kt` — supply `maxLength`;
  rename `BlankString` → `Blank` at the blank branch.
- `email/src/test/kotlin/ed/unicoach/email/EmailBodyTest.kt` — assert enriched
  `TooLong`; rename blank assertion to `Blank`.
- `email/src/test/kotlin/ed/unicoach/email/EmailSubjectTest.kt` — assert enriched
  `TooLong`; rename blank assertion to `Blank`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/StatusPages.kt` — map
  `DatabaseException` + both corruption exceptions to `500`; import the three types.
- `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/StatusPagesTest.kt` (**new**)
  — assert `500` for the three server faults and `400` for a client-fault
  `PermanentError`.

Not modified (different failure mode, out of scope): the `UsersDao`
`as ValidationResult.Valid` unsafe casts (14 sites throwing `ClassCastException`).
