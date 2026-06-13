# 46 — Local Config Overlay

## Executive Summary

`AppConfig.load` (`common/src/main/kotlin/ed/unicoach/common/config/AppConfig.kt`)
gains an optional, out-of-source-control local overlay applied at highest
precedence on top of the named classpath resources, before final resolution.
The overlay is a single HOCON file at
`${XDG_CONFIG_HOME:-$HOME/.config}/unicoach/local.conf`. It lets a developer or
operator supply secrets (e.g. `chat.anthropic.apiKey`) and local key overrides
on the host filesystem, without environment variables and without editing any
tracked resource.

Today `load` folds the classpath resources right-to-left via `withFallback` and
passes the merge to `ConfigFactory.load` (config `SPEC.md`, INV-2/INV-4). This
RFC inserts one parse-and-fold step before that final resolution (mechanism in
Control flow); overlay keys win over classpath keys.

An absent overlay is a non-fatal no-op — the prod/CI default. A
present-but-malformed overlay surfaces as `Result.failure` carrying the
underlying typesafe `ConfigException` unmapped, matching the existing fail-fast
stance (config `SPEC.md`, INV-3). The overlay is an additional sanctioned home
for local secrets such as the Anthropic API key, alongside the existing
env-var path (`CHAT_ANTHROPIC_API_KEY` in `chat/src/main/resources/chat.conf`),
which this RFC leaves in place.

Scope is the `common` module only: the `load(vararg resources: String)`
signature is unchanged, no module's call site changes, and no specific secret is
wired here. Wiring `chat.conf` into `rest-server`'s `load` call is out of scope
and belongs to a later coaching-service RFC. No new dependency.

## Detailed Design

### Overlay file: a single `local.conf`

The overlay is one file, `local.conf`, overlaying everything — not a per-resource
local file (e.g. `chat.local.conf` beside each classpath resource). HOCON merges
by key path, not by file: a single `local.conf` containing
`chat.anthropic.apiKey = "…"` overlays onto whatever classpath layer defined
`chat.anthropic`, regardless of which resources a given process passed to `load`.
A single file gives the developer one place for all local secrets and overrides,
requires one filesystem probe, and does not force the developer to know which
classpath resource owns a key. (A per-resource scheme is declined: it
re-introduces the multi-file sprawl the module-name mandate fights and couples
the local file set to `load`'s argument list, which this RFC does not touch.)

Keys in `local.conf` are arbitrary HOCON; unread keys are inert. The file is
parsed with the explicit `.conf` syntax (not any-syntax probing), since its name
is fixed.

Config `SPEC.md` INV-9 mandates exactly one `.conf` per module and that
environment variation be expressed **solely** through HOCON `${ENV_VAR}`
substitution. The overlay does not add a per-module classpath `.conf` (it is a
single out-of-tree host file outside every module's `src/main/resources/`), so
INV-9's one-file-per-module clause holds. It does, however, add a second
host-variation mechanism beside `${ENV_VAR}`, which INV-9's "solely" clause
forbids as written. This RFC narrows that clause to permit the `local.conf`
overlay as a sanctioned host-variation source; the divergence is reconciled into
`SPEC.md` out of band by the spec-sync phase.

### Overlay path resolution

The overlay file path is `<base>/unicoach/local.conf`, where `<base>` is the
first of:

1. JVM system property `unicoach.config.dir`, when set and non-blank.
2. Environment variable `XDG_CONFIG_HOME`, when set and non-blank.
3. `${user.home}/.config`, where `user.home` is read via
   `System.getProperty("user.home")`.

The `unicoach.config.dir` system property sits **above** `XDG_CONFIG_HOME` so
that a test (or an operator) can redirect the base config directory
deterministically regardless of the ambient environment — the JVM cannot mutate
its own `getenv` view, so a property is the only hermetic seam, and it must
out-rank the env var or a developer with `XDG_CONFIG_HOME` set would read their
real config dir during tests.

Reading `XDG_CONFIG_HOME` requires `System.getenv`. The config `SPEC.md` INV-4
bans `System.getenv` inside `AppConfig`; that invariant governs config **value**
resolution (which must flow through HOCON `${ENV_VAR}` substitution), whereas
locating the overlay **file** is bootstrap that precedes any config tree and has
no HOCON expression. This RFC narrows INV-4 to value resolution and permits
`getenv`/`getProperty` solely for overlay-path bootstrap; the divergence is noted
here and reconciled into the `SPEC.md` out of band by the spec-sync phase.

Honoring `XDG_CONFIG_HOME` (the conventional config-base seam) with zero-config
`~/.config` fallback is the no-env-var developer UX this RFC targets; the two
`getenv`-free alternatives are declined because a `user.home`-only path would
not honor a relocated `XDG_CONFIG_HOME` and a `unicoach.config.dir`
property-only path would force every developer to set the property to get any
overlay at all.

### `AppConfig.load` control flow

`load(vararg resources: String): Result<Config>` keeps its signature and its
`runCatching { … }: Result<Config>` envelope. The body changes from two steps to
three, all inside the existing `runCatching`:

1. Fold the named classpath resources right-to-left into `mergedConfig` via
   `withFallback` (unchanged: config `SPEC.md`, INV-2, Merge Semantics).
2. Resolve the overlay file (above) and parse it with `ConfigFactory.parseFile`.
   `parseFile`'s default options set `allowMissing = true`, so an absent file
   parses to an empty `Config`. Fold the overlay on top:
   `overlay.withFallback(mergedConfig)`.
3. Pass the result to `ConfigFactory.load`, unchanged (config `SPEC.md`, INV-4
   substitution-resolution clause), which resolves `${?FOO}` substitutions and
   stacks JVM system properties above the merge as it does today.

A private helper resolves the overlay file:

```
private fun overlayFile(): java.io.File
```

It returns the `<base>/unicoach/local.conf` path; it performs no IO beyond
constructing the path. No public surface is added.

### Precedence

After this change, for any key path, precedence is (highest first): JVM system
properties (via `ConfigFactory.load`, pre-existing) > `local.conf` overlay >
right-most classpath resource > … > left-most classpath resource > bundled
`reference.conf`. Env-var substitutions (`${?FOO}`) written in any layer resolve
normally at `ConfigFactory.load`; a literal in `local.conf` at the same path
out-ranks a classpath layer's `${?FOO}` because the overlay is folded on top
before resolution.

### Error handling / edge cases

- **Absent overlay** (`local.conf` does not exist): no-op via
  `allowMissing = true`; `load` returns the classpath-only result. This is the
  prod/CI default — neither path supplies the file.
- **Malformed overlay** (file exists, invalid HOCON): `ConfigFactory.parseFile`
  throws `ConfigException.Parse`; the existing `runCatching` returns
  `Result.failure(e)` with the `ConfigException` unmapped (config `SPEC.md`,
  INV-3, Error Handling).
- **Overlay path is a directory / unreadable**: `parseFile` throws an
  `IOException`/`ConfigException`; surfaced as `Result.failure` unmapped
  (fail-fast, same as a missing classpath resource today).
- **`user.home` undefined and no override**: `System.getProperty("user.home")`
  is defined on every supported JVM; if absent the path construction throws and
  surfaces as `Result.failure`. Not separately handled.

### Security

The overlay is a sanctioned on-host home for local secrets (e.g. the Anthropic
API key). `AppConfig` performs no logging today and adds none: neither the
overlay path nor its contents are ever logged.
The file lives under the user's config directory, outside the working tree, so
no `.gitignore` change is required and no tracked resource is edited; the
implementation never writes the file. Secret rotation/management is out of scope.

### Dependencies

None added. `com.typesafe:config` is already an `api` dependency of `common`
(`common/build.gradle.kts`); `ConfigFactory.parseFile` is part of it.

## Tests

Five new tests cover overlay precedence, absence, malformation, literal-vs-
substitution, and substitution-survival, all run against a redirected base dir.
They live in
`common/src/test/kotlin/ed/unicoach/common/config/AppConfigTest.kt` and drive a
redirected base dir via the `unicoach.config.dir` system property pointed at a
JVM temp directory (`java.nio.file.Files.createTempDirectory`); each writes its
own `<temp>/unicoach/local.conf`. No test depends on the real `~/.config`, sets
no env var, and makes no network call. An `@AfterTest` clears
`unicoach.config.dir`, deletes the temp tree, and calls
`ConfigFactory.invalidateCaches()` (mirroring the existing substitution test).
The existing two tests are retained unchanged.

- **`overlay overrides a classpath resource key at highest precedence`**: write
  `local.conf` with `app.name = "from-local"`; `load("merge-base.conf")`;
  assert `app.name == "from-local"` (classpath `merge-base.conf` defines
  `base`) and `app.region == "base-region"` (untouched classpath key survives).
- **`absent overlay file is a non-fatal no-op`**: point `unicoach.config.dir` at
  a temp dir containing no `unicoach/local.conf`; `load("merge-base.conf")`
  succeeds and `app.name == "base"`.
- **`malformed overlay surfaces as failure carrying ConfigException`**: write
  `local.conf` with invalid HOCON (e.g. `app.name = "unterminated`); assert
  `load("merge-base.conf").isFailure` and that the exception is a
  `com.typesafe.config.ConfigException`.
- **`overlay literal wins over a classpath env substitution`**: with
  `APP_CONFIG_TEST_OVERRIDE` unset, write `local.conf` with `app.value = "local"`;
  `load("env-substitution.conf")` (whose `app.value = ${?APP_CONFIG_TEST_OVERRIDE}`
  defaults to `"default"`); assert `app.value == "local"`.
- **`classpath env substitution still resolves when overlay omits the key`**:
  write a `local.conf` that does **not** set `app.value`; set system property
  `APP_CONFIG_TEST_OVERRIDE = "from-environment"`; `load("env-substitution.conf")`;
  assert `app.value == "from-environment"` — confirms the overlay does not
  disturb substitution resolution.

The `XDG_CONFIG_HOME` env-var branch of path resolution is not unit-tested: the
JVM cannot set its own environment. It is covered indirectly — the
`unicoach.config.dir` branch exercises the same `<base>/unicoach/local.conf`
join — and this gap is documented rather than worked around with a process fork.
For the same reason, the tier-1-over-tier-2 ordering (`unicoach.config.dir`
out-ranking `XDG_CONFIG_HOME`) cannot be asserted in-process and is guaranteed
by construction rather than by a test; every test above relies on it implicitly
by setting only `unicoach.config.dir`.

## Implementation Plan

1. **Extend `AppConfig.load` with the overlay step.**
   In `common/src/main/kotlin/ed/unicoach/common/config/AppConfig.kt`: add the
   private `overlayFile(): java.io.File` helper implementing the three-tier base
   resolution (`unicoach.config.dir` property → `XDG_CONFIG_HOME` env →
   `${user.home}/.config`, then `/unicoach/local.conf`); inside the existing
   `runCatching`, after the classpath fold, parse the overlay with
   `ConfigFactory.parseFile(overlayFile())` and fold it on top via
   `overlay.withFallback(mergedConfig)` before `ConfigFactory.load`. Update the
   `load` KDoc to state the overlay, its path, and its highest precedence.
   - Verify: `nix develop -c ./gradlew :common:compileKotlin`
   - Verify: `nix develop -c ktlint common/src/main/kotlin/ed/unicoach/common/config/AppConfig.kt`

2. **Add the overlay tests.**
   In `common/src/test/kotlin/ed/unicoach/common/config/AppConfigTest.kt`: add
   the five tests above plus the temp-dir/`unicoach.config.dir` setup and the
   `@AfterTest` teardown. Reuse the existing `merge-base.conf` and
   `env-substitution.conf` test resources as the classpath layer; create no new
   resource files (overlay files are written to the temp dir at runtime).
   - Verify: `nix develop -c bin/test common --tests "ed.unicoach.common.config.AppConfigTest"`
   - Verify: `nix develop -c ktlint common/src/test/kotlin/ed/unicoach/common/config/AppConfigTest.kt`

3. **Full regression of the `common` module and dependents' config loads.**
   - Verify: `nix develop -c bin/test common --force`
   - Verify: `nix develop -c bin/test chat --force` (confirms `chat.conf` loads
     still pass with no overlay present)

## Files Modified

- `common/src/main/kotlin/ed/unicoach/common/config/AppConfig.kt` — add
  `overlayFile()` helper and the overlay parse/fold step inside `load`; update
  KDoc.
- `common/src/test/kotlin/ed/unicoach/common/config/AppConfigTest.kt` — add the
  five overlay tests, the `unicoach.config.dir`/temp-dir harness, and the
  `@AfterTest` teardown.
