# SPEC: `ed.unicoach.util`

## I. Overview

This package is the **platform-wide cryptographic utility layer** for the
`service` module. Its singular responsibility is to provide stateless,
general-purpose primitive helpers — currently limited to secure token
generation — that are agnostic of any domain concept (sessions, auth,
password reset, etc.) and are injected via constructor DI wherever needed.

---

## II. Invariants

- The package MUST contain only general-purpose helpers with zero domain
  knowledge (no session, auth, or user concepts).
- `TokenGenerator` MUST generate tokens using `java.security.SecureRandom` —
  no `java.util.Random` or other non-CSPRNG sources are permitted.
- `TokenGenerator` MUST produce exactly **256 bits of entropy** (a 32-byte
  `ByteArray`).
- The raw bytes MUST be encoded using `Base64Url` (URL-safe alphabet) **without
  padding**, producing exactly **43 characters** (32 bytes × 8 bits ÷ 6 = 42.67
  → ⌈42.67⌉, no padding suffix).
- `TokenGenerator` MUST be instantiable as a plain class (not an `object` or
  companion object) so that it can be mocked in tests via constructor injection.
- `TokenGenerator` MUST NEVER be wired as a static singleton; it MUST be
  injected downward strictly via constructor DI at every call site.
- The class MUST remain entirely agnostic of how its output is consumed (e.g.,
  MUST NOT reference `Session`, `Cookie`, or any HTTP concept).
- `TokenGenerator` MUST be safe for concurrent use without external
  synchronization.

---

## III. Behavioral Contracts

### `TokenGenerator`

See [`TokenGenerator.kt`](./TokenGenerator.kt).

**Constructor**

| Parameter | Type | Default | Description |
|---|---|---|---|
| `secureRandom` | `java.security.SecureRandom` | `SecureRandom()` | Entropy source; injectable for deterministic testing. |

**`generateToken(): String`**

- **Side Effects**: None. Reads entropy from the OS via `SecureRandom`; no I/O,
  no network calls, no DB writes.
- **Output**: A `Base64Url`-encoded string (no padding) derived from 32 random
  bytes. Output length is always exactly **43 characters**.
- **Error Handling**: No checked exceptions. `SecureRandom` is guaranteed
  available on all JVM platforms; no failure path exists under normal operating
  conditions.
- **Idempotency**: Not idempotent — each invocation produces a distinct token
  with overwhelming probability (2⁻²⁵⁶ collision probability).
- **Thread Safety**: Safe for concurrent use; `SecureRandom` is thread-safe on
  standard JVM implementations.

---

## IV. Infrastructure & Environment

- **No environment variables** are read by this package.
- **No external dependencies** beyond the JVM standard library (`java.security`,
  `java.util.Base64`).
- **No Nix, Docker, or database constraints** apply to this package.
- `SecureRandom` seeds from the OS entropy pool (`/dev/urandom` on Linux,
  `CryptGenRandom` on Windows). No configuration of the entropy source is
  exposed or required.

---

## V. History

- [x] [RFC-11: Sessions](../../../../../../../rfc/11-sessions.md)
