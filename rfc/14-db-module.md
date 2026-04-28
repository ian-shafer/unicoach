# 14 Extract Database Module

## Executive Summary

This specification extracts all database infrastructure and data-access code
from the `service` module into a dedicated `db` Gradle module. The `db` module
absorbs the existing `db/schema/` migration directory and owns all database
concerns: connection pooling, session abstraction, configuration parsing, schema
migrations, DAOs, and domain models. This fully decouples the data layer from
domain logic, enabling future modules (e.g., `queue`) to depend on `db` without
circular dependencies through `service`.

## Detailed Design

### Module Structure

- **New Gradle Module**: `db`
- **Package**: `ed.unicoach.db` (unchanged from current)
- **Dependencies**: `common` (for `AppConfig`, `getNonBlankString`, `AppError`,
  `ExceptionWrapper`), `postgresql`, `hikaricp`

The existing `db/` directory currently contains only `db/schema/`. Converting it
to a Gradle module adds `db/build.gradle.kts` and `db/src/` alongside the
existing `db/schema/` directory. Schema files remain at `db/schema/` since they
are consumed by shell scripts, not JVM code. The `DB_SCHEMA_DIR` default in
`bin/common` remains `$PROJECT_ROOT/db/schema` — no change required.

### Files Moved

The following files move from `service` to `db` with **no package name
changes**:

**Infrastructure** (`service/.../db/` → `db/.../db/`):

- `Database.kt`
- `DatabaseConfig.kt`

**DAO layer** (`service/.../db/dao/` → `db/.../db/dao/`):

- `SqlSession.kt`
- `DaoModule.kt`
- `UsersDao.kt`
- `SessionsDao.kt`

**Models** (`service/.../db/models/` → `db/.../db/models/`):

- `AuthMethod.kt`
- `DisplayName.kt`
- `EmailAddress.kt`
- `Entity.kt`
- `NewSession.kt`
- `NewUser.kt`
- `PasswordHash.kt`
- `PersonName.kt`
- `Session.kt`
- `SsoProviderId.kt`
- `TokenHash.kt`
- `User.kt`
- `UserId.kt`
- `UserVersion.kt`
- `UserVersionId.kt`
- `ValidationResult.kt`

**Tests** (`service/.../db/` → `db/.../db/`):

- `DatabaseConfigTest.kt`
- `dao/UsersDaoTest.kt`
- `dao/SessionsDaoTest.kt`

Since Kotlin packages remain identical (`ed.unicoach.db`, `ed.unicoach.db.dao`,
`ed.unicoach.db.models`), no import statements in consuming code change. Only
Gradle dependency declarations are updated.

After this spec executes, `service/src/main/kotlin/ed/unicoach/db/` and
`service/src/test/kotlin/ed/unicoach/db/` will be completely empty and deleted.

### HOCON Configuration

Per the HOCON configuration skill ("exactly one `.conf` file per module"), the
`database` stanza migrates from `service.conf` to a new `db.conf`:

- Create `db/src/main/resources/db.conf` containing the `database { ... }` block
  (merged from `service/src/main/resources/service.conf` and the
  `database.connectionTimeout` default from
  `common/src/main/resources/common.conf`).
- Remove the `database { ... }` block from
  `service/src/main/resources/service.conf` (file will become empty).
- Remove the `database { ... }` block from
  `common/src/main/resources/common.conf` (file will become empty).
- Empty files are left as placeholders; cleanup is deferred.
- Update all `AppConfig.load()` call sites to include `"db.conf"`.

### Dependency Graph Update

```
common
  ↑
  db       ← NEW (Database, SqlSession, DAOs, models, DatabaseConfig, HikariCP)
  ↑
service    ← MODIFIED (removes postgresql, hikaricp; adds project(":db"))
  ↑
rest-server ← MODIFIED (adds project(":db"))
```

### Build File Changes

**`db/build.gradle.kts`** [NEW]:

- Plugins: `kotlin.jvm`, `ktlint`
- Dependencies: `implementation(project(":common"))`,
  `implementation(libs.postgresql)`, `implementation(libs.hikaricp)`
- Test: `testImplementation(libs.kotlin.test.junit5)`

**`service/build.gradle.kts`** [MODIFY]:

- Remove: `implementation(libs.postgresql)`, `implementation(libs.hikaricp)`
- Add: `implementation(project(":db"))`
- Leave `implementation(libs.kotlinx.coroutines.core)` untouched.

**`rest-server/build.gradle.kts`** [MODIFY]:

- Add: `implementation(project(":db"))`

**`settings.gradle.kts`** [MODIFY]:

- Add: `include("db")`

### AppConfig.load() Call Site Updates

All sites that currently load `"common.conf", "service.conf"` must be updated.
Tests moving to the `db` module replace `"service.conf"` with `"db.conf"` (since
`service.conf` is not on `db`'s classpath). Files remaining in `service` or
`rest-server` insert `"db.conf"` while keeping `"service.conf"`.

**Tests moving to `db` module** — replace `"service.conf"` with `"db.conf"`:

| File                 | Call sites       | Before                          | After                      |
| :------------------- | :--------------- | :------------------------------ | :------------------------- |
| `UsersDaoTest.kt`    | 2 (lines 28, 88) | `"common.conf", "service.conf"` | `"common.conf", "db.conf"` |
| `SessionsDaoTest.kt` | 1 (line 24)      | `"common.conf", "service.conf"` | `"common.conf", "db.conf"` |

Note: `UsersDaoTest.kt` has two separate `AppConfig.load()` calls — once in
`setupAll()` and once in the `findByIdForUpdate` test body. Both require the
update.

**Files remaining in `service`/`rest-server`** — insert `"db.conf"`:

| File                    | Call sites  | Before                                              | After                                                          |
| :---------------------- | :---------- | :-------------------------------------------------- | :------------------------------------------------------------- |
| `Application.kt`        | 1 (line 22) | `"common.conf", "service.conf", "rest-server.conf"` | `"common.conf", "db.conf", "service.conf", "rest-server.conf"` |
| `AuthServiceTest.kt`    | 1 (line 40) | `"common.conf", "service.conf"`                     | `"common.conf", "db.conf", "service.conf"`                     |
| `SessionCleanupTest.kt` | 1 (line 29) | `"common.conf", "service.conf"`                     | `"common.conf", "db.conf", "service.conf"`                     |

## Tests

No new tests are required. This is a mechanical refactor.

### Verification

- `DatabaseConfigTest` (moved to `db` module): Must pass unchanged.
- `UsersDaoTest` (moved to `db` module): Must pass with updated
  AppConfig.load().
- `SessionsDaoTest` (moved to `db` module): Must pass with updated
  AppConfig.load().
- `AuthServiceTest` (remains in `service`): Must pass with updated
  AppConfig.load().
- `SessionCleanupTest` (remains in `service`): Must pass with updated
  AppConfig.load().

All tests are verified via: `./bin/test`

## Implementation Plan

1. **Create `db` Gradle module skeleton**: Add `db/build.gradle.kts` with
   `common`, `postgresql`, and `hikaricp` dependencies. Add `include("db")` to
   `settings.gradle.kts`. Verify: `./gradlew :db:build` succeeds.

2. **Add `project(":db")` to downstream modules**: In
   `service/build.gradle.kts`, add `implementation(project(":db"))`. Leave
   existing `implementation(libs.postgresql)` and
   `implementation(libs.hikaricp)` — they are still needed until source files
   move. In `rest-server/build.gradle.kts`, add
   `implementation(project(":db"))`. Verify: `./gradlew build` compiles all
   modules.

3. **Move all main sources**: Move `Database.kt`, `DatabaseConfig.kt` from
   `service/.../db/` to `db/.../db/`. Move `SqlSession.kt`, `DaoModule.kt`,
   `UsersDao.kt`, `SessionsDao.kt` from `service/.../db/dao/` to
   `db/.../db/dao/`. Move all 16 model files from `service/.../db/models/` to
   `db/.../db/models/`. Delete originals. Infrastructure, DAOs, and models must
   move together — DAOs import model types, so splitting them across modules
   would break compilation. Delete the now-empty
   `service/src/main/kotlin/ed/unicoach/db/` directory tree. Verify:
   `./gradlew :db:build` and `./gradlew :service:build` both compile.

4. **Remove redundant service dependencies**: In `service/build.gradle.kts`,
   remove `implementation(libs.postgresql)` and `implementation(libs.hikaricp)`.
   These are now provided transitively via `project(":db")`. Leave
   `implementation(libs.kotlinx.coroutines.core)` untouched. Verify:
   `./gradlew :service:build` compiles.

5. **Move test sources**: Move `DatabaseConfigTest.kt` from `service/.../db/` to
   `db/.../db/`. Move `UsersDaoTest.kt` and `SessionsDaoTest.kt` from
   `service/.../db/dao/` to `db/.../db/dao/`. Delete originals and the now-empty
   `service/src/test/kotlin/ed/unicoach/db/` directory tree. Verify:
   `./gradlew :db:compileTestKotlin` compiles (tests will not pass until step
   7).

6. **Migrate HOCON configuration**: Create `db/src/main/resources/db.conf` with
   the merged `database { ... }` stanza (jdbcUrl, user, password,
   maximumPoolSize, connectionTimeout). Remove `database { ... }` from
   `service/src/main/resources/service.conf`. Remove `database { ... }` from
   `common/src/main/resources/common.conf`. Verify: `./gradlew :db:build`
   compiles.

7. **Update AppConfig.load() call sites**: Update all six call sites listed in
   the Detailed Design tables. For the three calls in `db` module tests
   (`UsersDaoTest.kt` ×2, `SessionsDaoTest.kt` ×1), replace `"service.conf"`
   with `"db.conf"`. For the three calls in `service`/`rest-server`
   (`Application.kt`, `AuthServiceTest.kt`, `SessionCleanupTest.kt`), insert
   `"db.conf"` while keeping `"service.conf"`. Verify: `./bin/test` — all
   existing tests pass.

## Files Modified

- `db/build.gradle.kts` [NEW]
- `db/src/main/kotlin/ed/unicoach/db/Database.kt` [NEW, moved from service]
- `db/src/main/kotlin/ed/unicoach/db/DatabaseConfig.kt` [NEW, moved from
  service]
- `db/src/main/kotlin/ed/unicoach/db/dao/SqlSession.kt` [NEW, moved from
  service]
- `db/src/main/kotlin/ed/unicoach/db/dao/DaoModule.kt` [NEW, moved from service]
- `db/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt` [NEW, moved from service]
- `db/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt` [NEW, moved from
  service]
- `db/src/main/kotlin/ed/unicoach/db/models/AuthMethod.kt` [NEW, moved from
  service]
- `db/src/main/kotlin/ed/unicoach/db/models/DisplayName.kt` [NEW, moved from
  service]
- `db/src/main/kotlin/ed/unicoach/db/models/EmailAddress.kt` [NEW, moved from
  service]
- `db/src/main/kotlin/ed/unicoach/db/models/Entity.kt` [NEW, moved from service]
- `db/src/main/kotlin/ed/unicoach/db/models/NewSession.kt` [NEW, moved from
  service]
- `db/src/main/kotlin/ed/unicoach/db/models/NewUser.kt` [NEW, moved from
  service]
- `db/src/main/kotlin/ed/unicoach/db/models/PasswordHash.kt` [NEW, moved from
  service]
- `db/src/main/kotlin/ed/unicoach/db/models/PersonName.kt` [NEW, moved from
  service]
- `db/src/main/kotlin/ed/unicoach/db/models/Session.kt` [NEW, moved from
  service]
- `db/src/main/kotlin/ed/unicoach/db/models/SsoProviderId.kt` [NEW, moved from
  service]
- `db/src/main/kotlin/ed/unicoach/db/models/TokenHash.kt` [NEW, moved from
  service]
- `db/src/main/kotlin/ed/unicoach/db/models/User.kt` [NEW, moved from service]
- `db/src/main/kotlin/ed/unicoach/db/models/UserId.kt` [NEW, moved from service]
- `db/src/main/kotlin/ed/unicoach/db/models/UserVersion.kt` [NEW, moved from
  service]
- `db/src/main/kotlin/ed/unicoach/db/models/UserVersionId.kt` [NEW, moved from
  service]
- `db/src/main/kotlin/ed/unicoach/db/models/ValidationResult.kt` [NEW, moved
  from service]
- `db/src/main/resources/db.conf` [NEW]
- `db/src/test/kotlin/ed/unicoach/db/DatabaseConfigTest.kt` [NEW, moved from
  service]
- `db/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt` [NEW, moved from
  service]
- `db/src/test/kotlin/ed/unicoach/db/dao/SessionsDaoTest.kt` [NEW, moved from
  service]
- `settings.gradle.kts` [MODIFY]
- `service/build.gradle.kts` [MODIFY]
- `service/src/main/kotlin/ed/unicoach/db/Database.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/DatabaseConfig.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/dao/SqlSession.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/dao/DaoModule.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/models/AuthMethod.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/models/DisplayName.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/models/EmailAddress.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/models/Entity.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/models/NewSession.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/models/NewUser.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/models/PasswordHash.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/models/PersonName.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/models/Session.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/models/SsoProviderId.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/models/TokenHash.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/models/User.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/models/UserId.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/models/UserVersion.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/models/UserVersionId.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/models/ValidationResult.kt` [DELETE]
- `service/src/test/kotlin/ed/unicoach/db/DatabaseConfigTest.kt` [DELETE]
- `service/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt` [DELETE]
- `service/src/test/kotlin/ed/unicoach/db/dao/SessionsDaoTest.kt` [DELETE]
- `service/src/main/resources/service.conf` [MODIFY]
- `common/src/main/resources/common.conf` [MODIFY]
- `rest-server/build.gradle.kts` [MODIFY]
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` [MODIFY]
- `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt` [MODIFY]
- `service/src/test/kotlin/ed/unicoach/auth/SessionCleanupTest.kt` [MODIFY]
