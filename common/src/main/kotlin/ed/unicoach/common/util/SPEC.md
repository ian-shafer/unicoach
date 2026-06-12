# SPEC: `common/src/main/kotlin/ed/unicoach/common/util`

## I. Overview

Cross-cutting, domain-agnostic value types for the `unicoach` application. This
package houses reusable wrappers that attach a construction-time invariant to a
primitive, making an invalid value unrepresentable. Its current sole inhabitant
is `DataSize`, a non-negative byte count. See [DataSize.kt](./DataSize.kt).

---

## II. Invariants

- **INV-1**: A `DataSize` MUST wrap a single `Long` byte count (`bytes`) and
  carry no other state.
- **INV-2**: A `DataSize` MUST NOT represent a negative byte count. Construction
  with `bytes < 0` MUST fail with `IllegalArgumentException` (enforced via
  `require` in `init`). A negative `DataSize` is therefore unrepresentable, not
  merely discouraged.
- **INV-3**: No construction path MUST bypass the non-negativity check. The only
  public way to obtain a `DataSize` MUST be the `DataSize.ofBytes(Long)`
  factory, so every instance has been validated.
- **INV-4**: The public surface MUST remain minimal — the `bytes` accessor and
  the `ofBytes` factory only. The type MUST NOT acquire size-string parsing
  (`fromString`), unit constructors (`ofKibibytes`), or unit-conversion (`to*`)
  methods. `DataSize` owns the non-negative-byte invariant and nothing else;
  understanding human-readable size strings is not its responsibility.
- **INV-5**: The module MUST remain domain-agnostic. `DataSize` MUST NOT depend
  on any module other than the Kotlin standard library, and MUST NOT reference
  any specific application domain.

---

## III. Behavioral Contracts

### `DataSize.ofBytes(bytes: Long): DataSize`

See [DataSize.kt](./DataSize.kt).

- **Construction**: Returns a `DataSize` wrapping `bytes`.
- **Side Effects**: None. No I/O, no shared state.
- **Error Handling**: Throws `IllegalArgumentException` with message
  `"DataSize must be non-negative, got $bytes bytes"` when `bytes < 0`. This is
  a **contractual** failure surfaced to the caller, not a system error.
- **Boundary**: `bytes == 0` is valid and yields a zero-size `DataSize`.
- **Idempotency**: Pure. Identical input always yields an equal `DataSize`.

### `DataSize.bytes: Long`

- **Side Effects**: None. Exposes the wrapped byte count for callers whose API
  consumes a raw `Long`.
- **Guarantee**: The returned value is always `>= 0` (per **INV-2**).

---

## IV. Infrastructure & Environment

- **No module-specific dependencies.** `DataSize` relies only on the Kotlin
  standard library; it adds no entry to `common/build.gradle.kts`.
- **Test-engine requirement**: `common`'s unit tests (including `DataSizeTest`)
  are JUnit 5 tests discovered only when
  `tasks.withType<Test> { useJUnitPlatform() }` is declared in
  `common/build.gradle.kts`. Absent that block, Gradle's default runner silently
  skips them and the suite reports success without executing them.

---

## V. History

- [x] [RFC-29: Request Payload Size Limits](../../../../../../../../rfc/29-request-payload-limits.md)
