# RFC 88: Rename the `admin-server` module to `admin-web`

## Executive Summary

Rename the `admin-server` Gradle module to `admin-web`. The module is the
internal admin website (server-rendered HTML); "server" is redundant and "web"
names what it is. This is a mechanical, behaviour-preserving rename: no runtime
logic, routes, schema, or auth changes.

The Gradle module name is its directory name, so the rename propagates from the
directory through `settings.gradle.kts`, every `:admin-server:` Gradle path, the
`installDist` output paths, the `bin/` daemon and build scripts (filenames,
daemon label, log/pid paths), the packaged config resource, and the
`ADMIN_SERVER_*` environment variables. No `admin-server` / `ADMIN_SERVER` token
survives outside immutable `rfc/` history and LLM-/human-managed `SPEC.md` /
`INVARIANTS.md` prose (updated out-of-band).

The Kotlin package `ed.unicoach.admin` and all `Admin*` type names are
unchanged: "web" is the delivery mechanism, not the domain. The module is a leaf
application with no inbound Gradle dependencies, so no other module's
`build.gradle.kts` changes.

## Detailed Design

The rename touches six token classes. Each is a literal substitution of
`admin-server` → `admin-web` (and `ADMIN_SERVER` → `ADMIN_WEB`, `admin.server` →
`admin.web`).

**Module directory & Gradle wiring.** `admin-server/` relocates to `admin-web/`
via `git mv` (preserving history). `settings.gradle.kts`
`include("admin-server")` → `include("admin-web")`. Gradle then derives project
path `:admin-web`. `admin-web/build.gradle.kts` needs no edit: `mainClass` is
`ed.unicoach.admin.ApplicationKt` (package-derived, unchanged) and it carries no
hardcoded module string.

**Config resource, HOCON keys, and env vars.** `admin-server.conf` →
`admin-web.conf`. Inside it, the bind block `admin.server { host, port }` →
`admin.web { host, port }`, and the substitutions `${?ADMIN_SERVER_HOST}` /
`${?ADMIN_SERVER_PORT}` → `${?ADMIN_WEB_HOST}` / `${?ADMIN_WEB_PORT}`. The other
keys (`admin.session.*`, `admin.display.*`) and their `ADMIN_COOKIE_*` /
`ADMIN_DISPLAY_*` env vars are untouched — they carry no `server` token.
`AdminConfig.from` reads the block via `admin.getConfig("server")` →
`getConfig("web")`. The three resource-load sites that name the file by string —
`Application.kt`, `AdminTestSupport.kt`, `AdminConfigTest.kt` — update the
`"admin-server.conf"` literal to `"admin-web.conf"`.

**`bin/` scripts.** The six module-scoped scripts rename and rewrite their
internals: `build-admin-server`,
`admin-server-{up,down,check,wait-for-health,bounce}` → `build-admin-web`,
`admin-web-{up,down,check,wait-for-health,bounce}`. Their bodies reference the
Gradle task (`:admin-server:installDist`), the installDist binary path
(`admin-server/build/install/admin-server/bin/admin-server`), the daemon label
passed to `daemon-up`/`daemon-down`/`daemon-http-check` (which names
`var/run/admin-server.pid` and `var/log/admin-server.log`), the port env
(`ADMIN_SERVER_PORT`), usage text, and inter-script calls — all → `admin-web`.
`bin/admin-grant` is a distinct script (admin-user grant) and is **not**
renamed. The daemon label rename means running instances write
`admin-web.{log,pid}`; pre-existing `admin-server.{log,pid}` under `var/` are
stale runtime state (gitignored) and are left for manual cleanup.

**Module registries.** `bin/test` `MODULES` array (and its help text) replaces
`admin-server` → `admin-web`. `bin/build` `MODULES` array replaces
`build-admin-server` → `build-admin-web`, and its dependency-order comment
updates `admin-server` → `admin-web`.

**Cross-module code comments.** Four files in other modules name `admin-server`
in explanatory comments only (no behaviour): `public-web` `Application.kt` and
`PublicWebConfig.kt`; `service` `GoogleTokenVerifier.kt` and `AuthService.kt`.
These update to `admin-web` for accuracy.

**Out of scope.** RFC files under `rfc/` are immutable historical record and are
never edited. `SPEC.md` / `INVARIANTS.md` prose (inside the moved module, and in
`bin/`, `public-web/`, `service/`) still names `admin-server`; SPEC files are
resynced out-of-band by `spec-sync-loop`, and the `public-web` `INVARIANTS.md`
reference is left to the human-gated `invariants-writer`. Infra/Terraform has no
`admin-server` reference (admin is not yet AWS-deployed). No `.env*` file names
`ADMIN_SERVER_*`.

### Data Models

N/A. No schema, table, or persisted-data change.

### API Contracts

N/A. HTTP routes, request/response shapes, cookie name (`admin_session`), and
auth behaviour are unchanged. Only the internal bind-config env-var names
(`ADMIN_WEB_HOST`/`ADMIN_WEB_PORT`, default `127.0.0.1:8081`) change.

### Error Handling / Edge Cases

- A running `admin-server` daemon (old label) is not stopped by the renamed
  `admin-web-down`; stop it before switching, or free port 8081 manually. The
  renamed scripts otherwise behave identically.
- `installDist` output moves to
  `admin-web/build/install/admin-web/bin/admin-web`; a stale
  `admin-server/build/` from a prior build is orphaned (gitignored) and
  harmless.

### Dependencies

None added or removed. The module is a leaf application (no other module
declares `project(":admin-server")`).

## Tests

This is a behaviour-preserving rename; no new test code is authored. Correctness
is proven by the existing suite passing under the new module path plus a
residual-token gate.

- **`admin-web` module suite green under the new path.** Run
  `nix develop -c bin/test admin-web -f` and confirm every existing test class
  executes (non-zero executed count, all pass): `HealthTest`, `AdminConfigTest`,
  `auth/AdminAuthTest`, and `resources/` (`UsersResourceTest`,
  `StudentsResourceTest`, `SessionsResourceTest`, `CollegesResourceTest`,
  `ConvosResourceTest`, `ConvoRequestsResourceTest`, `ClaimsResourceTest`,
  `ObservationsResourceTest`, `ExtractionRunsResourceTest`,
  `SystemPromptsResourceTest`), `render/` (`CellRenderTest`, `StatusPagesTest`).
  This proves the config resource resolves under its new name (`AdminConfigTest`
  loads `admin-web.conf`) and the `admin.web.*` HOCON keys parse
  (`AdminConfig`).
- **`AdminConfigTest` config-resolution assertion.** The existing test parses
  the packaged config via `parseResourcesAnySyntax("admin-web.conf")` and
  asserts the bind host/port and display defaults — the direct regression net
  for the conf-file and HOCON-key rename.
- **Full repo suite green.** `nix develop -c bin/test` — every module still
  compiles and passes, proving the cross-module comment edits and the module
  registry (`bin/test` `MODULES`) are consistent.
- **Residual-token gate.**
  `grep -rIn -e 'admin-server' -e 'ADMIN_SERVER' -e 'admin\.server'` over
  tracked files, excluding `rfc/`, `.git/`, and `*SPEC.md` / `*INVARIANTS.md`,
  returns no matches. This is the completeness check for the full sweep.
- **Daemon lifecycle smoke.** `nix develop -c bin/build-admin-web`, then
  `nix develop -c bin/admin-web-up`, `nix develop -c bin/admin-web-check` (exit
  0, `GET /healthz` → 200), `nix develop -c bin/admin-web-down` (exit 0). Proves
  the renamed scripts, installDist path, and daemon label are internally
  consistent.

## Implementation Plan

1. **Relocate the module and re-wire the Gradle/module registries.**
   - `git mv admin-server admin-web`.
   - `settings.gradle.kts`: `include("admin-server")` → `include("admin-web")`.
   - `bin/test`: replace `admin-server` → `admin-web` in the `MODULES` array and
     the help-text module list.
   - Verify: `nix develop -c ./gradlew projects` lists `:admin-web` and not
     `:admin-server`; `nix develop -c ./gradlew :admin-web:compileKotlin`
     succeeds.

2. **Rename the config resource, HOCON keys, env vars, and load sites.**
   - `git mv admin-web/src/main/resources/admin-server.conf admin-web/src/main/resources/admin-web.conf`.
   - In `admin-web.conf`: rename block `admin.server` → `admin.web`;
     substitutions `ADMIN_SERVER_HOST`/`ADMIN_SERVER_PORT` →
     `ADMIN_WEB_HOST`/`ADMIN_WEB_PORT`.
   - `AdminConfig.kt`: `admin.getConfig("server")` → `getConfig("web")`; update
     the `admin-server.conf` doc-comment reference to `admin-web.conf`.
   - `Application.kt`, `AdminTestSupport.kt`, `AdminConfigTest.kt`: replace the
     `"admin-server.conf"` load literal with `"admin-web.conf"` (and the
     `AdminConfigTest` / `AdminTestSupport` comment references).
   - `render/Layout.kt`: update the two `admin-server` comment references to
     `admin-web`.
   - Verify:
     `nix develop -c ./gradlew :admin-web:compileKotlin :admin-web:compileTestKotlin`
     succeed; `nix develop -c bin/test admin-web -f` — all green, executed count
     matches declared.

3. **Rename and rewrite the `bin/` daemon and build scripts.**
   - `git mv` each: `bin/build-admin-server` → `bin/build-admin-web`;
     `bin/admin-server-up|down|check|wait-for-health|bounce` →
     `bin/admin-web-up|down|check|wait-for-health|bounce`.
   - In each renamed script, replace all `admin-server` → `admin-web` and
     `ADMIN_SERVER_PORT` → `ADMIN_WEB_PORT`: the `:admin-server:installDist`
     Gradle task, the installDist binary path, the daemon label argument, the
     port env default, log-path text, usage strings, and inter-script call
     paths.
   - `bin/build`: `MODULES` entry `build-admin-server` → `build-admin-web`;
     update the dependency-order comment `admin-server` → `admin-web`.
   - Verify: `nix develop -c bash -n` on each renamed script parses;
     `nix develop -c grep -rIn -e 'admin-server' -e 'ADMIN_SERVER' bin/` returns
     nothing (excluding `bin/SPEC.md`).

4. **Update cross-module code comments.**
   - Replace `admin-server` → `admin-web` in comments of
     `public-web/src/main/kotlin/ed/unicoach/web/Application.kt`,
     `public-web/src/main/kotlin/ed/unicoach/web/PublicWebConfig.kt`,
     `service/src/main/kotlin/ed/unicoach/auth/GoogleTokenVerifier.kt`,
     `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt`.
   - Verify:
     `nix develop -c ./gradlew :public-web:compileKotlin :service:compileKotlin`
     succeed.

5. **Whole-repo residual-token gate, full suite, and daemon smoke.**
   - Verify (residual gate):
     `nix develop -c bash -c "grep -rIn -e 'admin-server' -e 'ADMIN_SERVER' -e 'admin\.server' . --exclude-dir=.git --exclude-dir=rfc --exclude='*SPEC.md' --exclude='*INVARIANTS.md'"`
     returns no matches.
   - Verify (full suite): `nix develop -c bin/test` — all modules green.
   - Verify (daemon lifecycle): `nix develop -c bin/build-admin-web`;
     `nix develop -c bin/admin-web-up`; `nix develop -c bin/admin-web-check`
     (exit 0, `/healthz` 200); `nix develop -c bin/admin-web-down` (exit 0).

## Files Modified

**Moved (directory relocation via `git mv`, content otherwise unchanged unless
listed below):** the entire `admin-server/` tree → `admin-web/`. This carries
all Kotlin sources, tests, `logback.xml`, and the module's `SPEC.md` /
`INVARIANTS.md` files (whose prose is resynced out-of-band, not edited here).

**Moved and content-edited:**

- `admin-server/src/main/resources/admin-server.conf` →
  `admin-web/src/main/resources/admin-web.conf` — HOCON block `admin.server` →
  `admin.web`; env vars `ADMIN_SERVER_HOST/PORT` → `ADMIN_WEB_HOST/PORT`.
- `admin-web/src/main/kotlin/ed/unicoach/admin/AdminConfig.kt` —
  `getConfig("server")` → `getConfig("web")`; conf-name comment.
- `admin-web/src/main/kotlin/ed/unicoach/admin/Application.kt` —
  `"admin-server.conf"` load literal.
- `admin-web/src/main/kotlin/ed/unicoach/admin/render/Layout.kt` — comment
  references.
- `admin-web/src/test/kotlin/ed/unicoach/admin/AdminTestSupport.kt` — load
  literal
  - comment.
- `admin-web/src/test/kotlin/ed/unicoach/admin/AdminConfigTest.kt` —
  `parseResourcesAnySyntax` literal + comment.

**Renamed and content-edited (`bin/`):**

- `bin/build-admin-server` → `bin/build-admin-web`
- `bin/admin-server-up` → `bin/admin-web-up`
- `bin/admin-server-down` → `bin/admin-web-down`
- `bin/admin-server-check` → `bin/admin-web-check`
- `bin/admin-server-wait-for-health` → `bin/admin-web-wait-for-health`
- `bin/admin-server-bounce` → `bin/admin-web-bounce`

**Content-edited (not moved):**

- `settings.gradle.kts` — `include` module name.
- `bin/test` — `MODULES` array + help text.
- `bin/build` — `MODULES` array + dependency-order comment.
- `public-web/src/main/kotlin/ed/unicoach/web/Application.kt` — comments.
- `public-web/src/main/kotlin/ed/unicoach/web/PublicWebConfig.kt` — comment.
- `service/src/main/kotlin/ed/unicoach/auth/GoogleTokenVerifier.kt` — comment.
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt` — comment.
