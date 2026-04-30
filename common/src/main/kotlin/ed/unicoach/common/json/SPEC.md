# SPEC: `common/src/main/kotlin/ed/unicoach/common/json`

## I. Overview

This directory is the **JSON serialization utility layer** for the `common` module.
It provides two domain-agnostic Kotlin extension functions — `asJson()` and
`deserialize()` — that wrap `kotlinx.serialization.json` primitives to convert
between `@Serializable` data classes and `JsonObject` values. All modules that
require typed JSON payload handling depend on these utilities.
See [JsonExtensions.kt](./JsonExtensions.kt).

---

## II. Invariants

- The module MUST expose `asJson()` as an extension on any `@Serializable` type `T`,
  returning a `JsonObject` via `Json.encodeToJsonElement(this) as JsonObject`.
- The module MUST expose `deserialize()` as an extension on `JsonObject`,
  returning a typed `T` via `Json.decodeFromJsonElement(this)`.
- Both functions MUST be declared `inline` with a `reified` type parameter so
  that the concrete type is available at the call site without reflection.
- The target type `T` of `deserialize()` MUST be annotated with
  `@kotlinx.serialization.Serializable`; failure to do so is a compile-time
  error enforced by the `kotlin-serialization` compiler plugin.
- `asJson()` MUST cast the result of `encodeToJsonElement` to `JsonObject`.
  Callers MUST NOT invoke `asJson()` on types whose serialized form is not a
  JSON object (e.g., primitives or arrays); doing so WILL throw
  `ClassCastException` at runtime.
- The module MUST NOT introduce domain-specific logic (auth, queue, session,
  etc.). It MUST remain domain-agnostic.
- The module MUST NOT define a custom `Json` configuration (lenient mode,
  explicit nulls, etc.). It MUST use the default `Json` singleton from
  `kotlinx.serialization.json`.

---

## III. Behavioral Contracts

### `JsonObject.deserialize<T>()`

- **Signature**: `inline fun <reified T> JsonObject.deserialize(): T`
- **Input**: A `JsonObject` whose structure is compatible with the serialized
  form of `T`.
- **Output**: A fully populated instance of `T`.
- **Side Effects**: None. No DB writes, no network calls, no file I/O.
- **Error Handling**:
  - Throws `kotlinx.serialization.SerializationException` if the `JsonObject`
    structure does not match `T`'s schema (missing required fields, wrong types).
  - Throws `kotlinx.serialization.MissingFieldException` (a subtype of
    `SerializationException`) for absent required fields.
  - Callers MUST handle or propagate these exceptions.
- **Idempotency**: Yes. Pure function; same input always produces the same output.

### `T.asJson()`

- **Signature**: `inline fun <reified T> T.asJson(): JsonObject`
- **Input**: An instance of a `@Serializable` data class `T`.
- **Output**: A `JsonObject` representation of the instance.
- **Side Effects**: None. No DB writes, no network calls, no file I/O.
- **Error Handling**:
  - Throws `ClassCastException` at runtime if `T` serializes to a non-object
    JSON element (e.g., a `JsonPrimitive` or `JsonArray`).
  - `@Serializable` compliance is enforced at compile time by the
    `kotlin-serialization` plugin; no runtime `SerializationException` is
    expected for well-formed callers.
  - Callers MUST only invoke this function on types whose serialized form is a
    JSON object.
- **Idempotency**: Yes. Pure function; same input always produces the same output.

---

## IV. Infrastructure & Environment

- **Dependency**: `kotlinx-serialization-json` version `1.11.0`, declared as
  `api(libs.kotlinx.serialization.json)` in `common/build.gradle.kts`, exposing
  it transitively to all modules that depend on `common`.
- **Compiler Plugin**: `org.jetbrains.kotlin.plugin.serialization` (alias
  `libs.plugins.kotlin.serialization`) MUST be applied in `common/build.gradle.kts`.
  The plugin version MUST use `version.ref = "kotlin"` — an independent version
  entry causes a compile error.
- **Version Pinning**: The runtime library (`kotlinx-serialization-json`) and
  the compiler plugin have **separate version numbers**. The plugin is pinned to
  the Kotlin compiler version; the library is pinned independently. As of Kotlin
  `2.3.20`, the correct library version is `1.11.0`. To update: consult the
  [kotlinx.serialization releases page](https://github.com/Kotlin/kotlinx.serialization/releases)
  and verify compatibility with the project's Kotlin version.
- **No module-specific environment variables or config files.** All configuration
  is compile-time (Gradle) and runtime-stdlib (the default `Json` singleton).

---

## V. History

- [x] [RFC-15: Queue Data Layer](../../../../../../../../rfc/15-queue-data-layer.md)
