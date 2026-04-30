# SPEC: `common/src/main/kotlin/ed/unicoach/common/config`

## I. Overview

Centralized configuration loading and secret-value handling for the `unicoach`
application. This package provides the single, canonical mechanism by which all
modules parse HOCON resources into typed configuration objects, and the
obfuscation wrapper that prevents sensitive values from leaking through default
JVM serialization.

---

## II. Invariants

- **INV-1**: `AppConfig` MUST be a Kotlin `object` (singleton). No instance
  state is permitted.
- **INV-2**: `AppConfig.load(vararg resources: String)` MUST merge resources
  such that right-most arguments have the highest precedence (last-wins
  strategy).
- **INV-3**: `AppConfig.load` MUST wrap all parsing logic in `runCatching` and
  MUST return `Result<Config>`. It MUST NOT throw directly.
- **INV-4**: The merged config MUST be passed through `ConfigFactory.load()`
  so that environment-variable substitutions defined in `.conf` files are
  resolved via OS environment variables automatically. Manual
  `System.getenv()` calls are NEVER used inside `AppConfig`.
- **INV-5**: `SecretString` MUST be a plain `class`, NEVER a `data class`.
  Kotlin's generated `copy()`, `componentN()`, and default `toString()`
  MUST NOT exist on this type.
- **INV-6**: `SecretString.toString()` MUST always return the literal string
  `"********"`, regardless of the underlying value.
- **INV-7**: `SecretString.equals()` and `SecretString.hashCode()` MUST
  delegate only to `value`, enabling safe use in equality checks without
  exposing the raw secret via reflection-based frameworks.
- **INV-8**: `Config.getNonBlankString(path: String)` MUST throw
  `IllegalArgumentException` (via `require`) when the resolved string is blank
  or empty. It MUST NOT return blank strings silently.
- **INV-9**: There MUST be exactly one `.conf` file per module. Environment-
  specific config files (e.g., `common-test.conf`) are NEVER created.
  Environment variation is expressed solely through environment variables
  substituted via HOCON `${ENV_VAR}` syntax.

---

## III. Behavioral Contracts

### `AppConfig.load(vararg resources: String): Result<Config>`

See [`AppConfig.kt`](./AppConfig.kt).

- **Side Effects**: Reads named classpath resources using
  `ConfigFactory.parseResourcesAnySyntax`. No network calls, no file-system
  side effects beyond classpath resolution.
- **Merge Semantics**: Resources are iterated in **reversed** index order,
  each folded into `mergedConfig` via `withFallback`. This means
  `resources[last]` has the highest priority. The resulting merged config is
  then passed to `ConfigFactory.load()` which applies system environment
  variable substitutions.
- **Error Handling**: Returns `Result.failure(e)` for any exception thrown
  during parsing (e.g., `ConfigException`, `IOException` for a missing
  resource). Callers MUST unwrap with `.getOrThrow()` or explicit error
  handling — silent swallowing of failures is forbidden by convention.
- **Idempotent**: Yes. Given identical inputs, repeated calls produce
  equivalent `Config` values.

### `Config.getNonBlankString(path: String): String`

See [`AppConfig.kt`](./AppConfig.kt).

- **Side Effects**: None. Reads a key from an already-loaded `Config` object.
- **Error Handling**:
  - If `path` is absent, throws `ConfigException.Missing` (from the underlying
    `Config.getString` call).
  - If the resolved string is blank, throws `IllegalArgumentException` with
    message `"[$path] cannot be blank"`.
- **Idempotent**: Yes.

### `SecretString`

See [`SecretString.kt`](./SecretString.kt).

- **Construction**: Accepts a single `String value`. No validation is performed
  on the value at construction time — callers are responsible for ensuring the
  value is non-blank before wrapping.
- **Side Effects**: None.
- **`toString()`**: Always returns `"********"`. Logging frameworks, Kotlin
  string templates, and reflection-based serializers that call `toString()`
  MUST NOT expose the raw secret.
- **`equals(other)`**: Returns `true` iff `other` is a `SecretString` and
  `other.value == this.value`.
- **`hashCode()`**: Returns `value.hashCode()`.
- **Note**: Pure value wrapper. Not an operation; idempotency is not applicable.

---

## IV. Infrastructure & Environment

- **Dependency**: `com.typesafe:config` is declared as an `api` dependency in
  `common/build.gradle.kts`. All downstream modules that call `AppConfig.load`
  or use `Config` types receive this dependency transitively.
- **Classpath Resources**: `AppConfig.load` resolves resources by name using
  `ConfigFactory.parseResourcesAnySyntax`, which searches the JVM classpath.
  Each module MUST bundle its own `.conf` file in
  `src/main/resources/` for it to be available at runtime.
- **Environment Variable Resolution**: `.conf` files MUST use HOCON
  substitution syntax (`${ENV_VAR}`) to bind deployment-specific values.
  The `.env.*` files (e.g., `.env.test`) act as the sole source of mutable
  deployment bounds, loaded into the process environment before JVM startup.
  No `System.getenv()` calls are permitted inside this package.

---

## V. History

- [x] [RFC-09: Global Config](../../../../../../../../rfc/09-global-config.md)
