# 14 Extract Database Module

## Executive Summary

This specification extracts database infrastructure (`Database`, `SqlSession`,
`DatabaseConfig`) from the `service` module into a dedicated `db` Gradle module.
The `db` module absorbs the existing `db/schema/` migration directory, owning all
database concerns: connection pooling, session abstraction, configuration
parsing, and schema migrations. This decouples database infrastructure from
domain logic, enabling future modules (e.g., `queue`) to depend on `db` without
circular dependencies through `service`.

## Detailed Design

### Module Structure

- **New Gradle Module**: `db`
- **Package**: `ed.unicoach.db` (unchanged from current)
- **Dependencies**: `common` (for `AppConfig`, `getNonBlankString`), `postgresql`,
  `hikaricp`

The existing `db/` directory currently contains only `db/schema/`. Converting it
to a Gradle module adds `db/build.gradle.kts` and `db/src/` alongside the
existing `db/schema/` directory. Schema files remain at `db/schema/` since they
are consumed by shell scripts, not JVM code. The `DB_SCHEMA_DIR` default in
`bin/common` remains `$PROJECT_ROOT/db/schema` — no change required.

### Files Moved

The following files move from `service` to `db` with **no package name changes**:

- `Database.kt`: `service/.../db/` → `db/.../db/`
- `DatabaseConfig.kt`: `service/.../db/` → `db/.../db/`
- `SqlSession.kt`: `service/.../db/dao/` → `db/.../db/dao/`
- `DatabaseConfigTest.kt`: `service/.../db/` → `db/.../db/`

Since Kotlin packages remain identical (`ed.unicoach.db`, `ed.unicoach.db.dao`),
no import statements in consuming code change. Only Gradle dependency
declarations are updated.

### HOCON Configuration

Per the HOCON configuration skill ("exactly one `.conf` file per module"), the
`database` stanza migrates from `service.conf` to a new `db.conf`:

- Create `db/src/main/resources/db.conf` containing the `database { ... }` block
  (merged from `service/src/main/resources/service.conf` and the
  `database.connectionTimeout` default from `common/src/main/resources/common.conf`).
- Remove the `database { ... }` block from `service/src/main/resources/service.conf`
  (file may become empty).
- Remove the `database { ... }` block from `common/src/main/resources/common.conf`
  (file may become empty).
- Update all `AppConfig.load()` call sites to include `"db.conf"`.

### Dependency Graph Update

```
common
  ↑
  db       ← NEW (Database, SqlSession, DatabaseConfig, HikariCP)
  ↑
service    ← MODIFIED (removes postgresql, hikaricp; adds project(":db"))
  ↑
rest-server ← MODIFIED (adds project(":db"))
```

### Build File Changes

**`db/build.gradle.kts`** [NEW]:
- Plugins: `kotlin.jvm`, `ktlint`
- Dependencies: `implementation(project(":common"))`, `implementation(libs.postgresql)`,
  `implementation(libs.hikaricp)`
- Test: `testImplementation(libs.kotlin.test.junit5)`

**`service/build.gradle.kts`** [MODIFY]:
- Remove: `implementation(libs.postgresql)`, `implementation(libs.hikaricp)`
- Add: `implementation(project(":db"))`

**`rest-server/build.gradle.kts`** [MODIFY]:
- Add: `implementation(project(":db"))`

**`settings.gradle.kts`** [MODIFY]:
- Add: `include("db")`

### AppConfig.load() Call Site Updates

All sites that currently load `"common.conf", "service.conf"` must insert
`"db.conf"`:

| File | Before | After |
|:-----|:-------|:------|
| `Application.kt` | `"common.conf", "service.conf", "rest-server.conf"` | `"common.conf", "db.conf", "service.conf", "rest-server.conf"` |
| `UsersDaoTest.kt` | `"common.conf", "service.conf"` | `"common.conf", "db.conf", "service.conf"` |
| `SessionsDaoTest.kt` | `"common.conf", "service.conf"` | `"common.conf", "db.conf", "service.conf"` |
| `AuthServiceTest.kt` | `"common.conf", "service.conf"` | `"common.conf", "db.conf", "service.conf"` |
| `SessionCleanupTest.kt` | `"common.conf", "service.conf"` | `"common.conf", "db.conf", "service.conf"` |

## Tests

No new tests are required. This is a mechanical refactor.

### Verification

- `DatabaseConfigTest` (moved to `db` module): Must pass unchanged.
- `UsersDaoTest`: Must pass with updated AppConfig.load().
- `SessionsDaoTest`: Must pass with updated AppConfig.load().
- `AuthServiceTest`: Must pass with updated AppConfig.load().
- `SessionCleanupTest`: Must pass with updated AppConfig.load().

All tests are verified via: `./bin/test`

## Implementation Plan

1. **Create `db` Gradle module skeleton**: Add `db/build.gradle.kts` with
   `common`, `postgresql`, and `hikaricp` dependencies. Add `include("db")` to
   `settings.gradle.kts`. Verify: `./gradlew :db:build` succeeds.

2. **Move Kotlin sources**: Move `Database.kt`, `DatabaseConfig.kt` from
   `service/.../db/` to `db/.../db/`. Move `SqlSession.kt` from
   `service/.../db/dao/` to `db/.../db/dao/`. Move `DatabaseConfigTest.kt` from
   `service/.../db/` to `db/.../db/`. Delete originals from `service`. Verify:
   `./gradlew :db:build` compiles.

3. **Migrate HOCON configuration**: Create `db/src/main/resources/db.conf` with
   the merged `database { ... }` stanza (jdbcUrl, user, password,
   maximumPoolSize, connectionTimeout). Remove `database { ... }` from
   `service/src/main/resources/service.conf`. Remove `database { ... }` from
   `common/src/main/resources/common.conf`. Verify: `./gradlew :db:test`
   passes `DatabaseConfigTest`.

4. **Update service build dependencies**: In `service/build.gradle.kts`, remove
   `implementation(libs.postgresql)` and `implementation(libs.hikaricp)`. Add
   `implementation(project(":db"))`. Verify: `./gradlew :service:build`
   compiles.

5. **Update rest-server build dependencies**: In `rest-server/build.gradle.kts`,
   add `implementation(project(":db"))`. Verify: `./gradlew :rest-server:build`
   compiles.

6. **Update AppConfig.load() call sites**: Insert `"db.conf"` into all five
   call sites listed in the Detailed Design table. Verify: `./bin/test` — all
   existing tests pass.

## Files Modified

- `db/build.gradle.kts` [NEW]
- `db/src/main/kotlin/ed/unicoach/db/Database.kt` [NEW, moved from service]
- `db/src/main/kotlin/ed/unicoach/db/DatabaseConfig.kt` [NEW, moved from service]
- `db/src/main/kotlin/ed/unicoach/db/dao/SqlSession.kt` [NEW, moved from service]
- `db/src/main/resources/db.conf` [NEW]
- `db/src/test/kotlin/ed/unicoach/db/DatabaseConfigTest.kt` [NEW, moved from service]
- `settings.gradle.kts` [MODIFY]
- `service/build.gradle.kts` [MODIFY]
- `service/src/main/kotlin/ed/unicoach/db/Database.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/DatabaseConfig.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/dao/SqlSession.kt` [DELETE]
- `service/src/test/kotlin/ed/unicoach/db/DatabaseConfigTest.kt` [DELETE]
- `service/src/main/resources/service.conf` [MODIFY]
- `common/src/main/resources/common.conf` [MODIFY]
- `rest-server/build.gradle.kts` [MODIFY]
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` [MODIFY]
- `service/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt` [MODIFY]
- `service/src/test/kotlin/ed/unicoach/db/dao/SessionsDaoTest.kt` [MODIFY]
- `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt` [MODIFY]
- `service/src/test/kotlin/ed/unicoach/auth/SessionCleanupTest.kt` [MODIFY]
