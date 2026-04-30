# SPEC: `common/src/main/kotlin/ed/unicoach/util`

## I. Overview

Domain-agnostic cryptographic and validation utilities shared across all modules.
This package provides three facilities: password hashing via Argon2id
(`Argon2Hasher`), stateless JWT minting (`JwtGenerator`), and a generic
validation contract (`Validator<T>` / `ValidationErrors`). It has no knowledge
of HTTP semantics, domain entities, or persistence.

---

## II. Invariants

### Argon2Hasher

- The hasher MUST use algorithm `Argon2id` with salt length 16 bytes and hash
  length 32 bytes.
- `hash(password)` and `verify(hash, password)` MUST execute on
  `Dispatchers.IO` — NEVER on the Ktor Netty event-loop thread.
- Both `hash` and `verify` MUST be wrapped in `withTimeout(timeoutMs)`. The
  default timeout MUST be `2000 ms`.
- The plaintext password char array MUST be wiped via `argon2.wipeArray()`
  in a `finally` block regardless of outcome — NEVER left in memory after the
  call completes.
- Default tuning parameters MUST be: `iterations = 3`, `memory = 65536 KiB`,
  `parallelism = 1`. All four tunables (`iterations`, `memory`, `parallelism`,
  `timeoutMs`) MUST be injectable at construction time.
- _(Planned — not yet in code)_ RFC-10 requires `Argon2Hasher` to expose a
  `DUMMY_HASH` constant computed once at startup using the active Argon2id
  parameters. When implemented, it MUST NOT be a hardcoded string; it ensures
  dummy verification during login timing-attack mitigation absorbs the same
  computational penalty as a real hash.

### JwtGenerator

- Tokens MUST be signed with `HMAC256` using the injected `secret`.
- Token expiry MUST be exactly `+7 days` from the `issuedAt` instant as derived
  from the injected `Clock`.
- The injected `Clock` MUST be used for all `Instant.now()` calls — NEVER
  `Clock.systemUTC()` inline — to keep the class fully testable.
- `mint()` MUST set the `issuer`, `subject`, `issuedAt`, and `expiresAt` JWT
  claims on every token.
- Claim value types MUST be restricted to `String`, `Int`, `Boolean`, `Double`,
  and `Long`. Unsupported claim types MUST be silently dropped (no exception).

### Validator / ValidationErrors

- `Validator<T>` MUST be a pure interface with a single method
  `validate(input: T): ValidationErrors`.
- `ValidationErrors` MUST aggregate both free-form string errors (`errors:
  List<String>`) and structured field-level errors (`fieldErrors:
  List<FieldError>`).
- `ValidationErrors.hasErrors()` MUST return `true` if and only if either list
  is non-empty.
- Implementations MUST NOT throw exceptions for validation failures — they MUST
  return a `ValidationErrors` instance.

---

## III. Behavioral Contracts

### `Argon2Hasher.hash(password: String): String`

- **Side Effects**: None. No DB, no network, no file I/O.
- **Execution Context**: Suspends; dispatches to `Dispatchers.IO` internally.
- **Timeout**: Throws `kotlinx.coroutines.TimeoutCancellationException` if
  hashing exceeds `timeoutMs`.
- **Memory Safety**: Wipes the password char array in `finally` before
  returning.
- **Error Handling**: Propagates any `Argon2RuntimeException` or
  `TimeoutCancellationException` to the caller — no internal catch.
- **Idempotency**: No — each call produces a different salt and thus a different
  hash for the same input.
- **Return**: Encoded Argon2id hash string (PHC format).

### `Argon2Hasher.verify(hash: String, password: String): Boolean`

- **Side Effects**: None.
- **Execution Context**: Suspends; dispatches to `Dispatchers.IO` internally.
- **Timeout**: Throws `TimeoutCancellationException` if verification exceeds
  `timeoutMs`.
- **Memory Safety**: Wipes the password char array in `finally`.
- **Error Handling**: Propagates `Argon2RuntimeException` or
  `TimeoutCancellationException` to the caller.
- **Idempotency**: Yes — given the same hash and password, always returns the
  same Boolean.
- **Return**: `true` if the password matches the hash; `false` otherwise.

### `JwtGenerator.mint(subjectId: String, claims: Map<String, Any>): String`

- **Side Effects**: None. No DB, no network.
- **Execution Context**: Synchronous (not a suspend function).
- **Error Handling**: Propagates `com.auth0.jwt.exceptions.JWTCreationException`
  to the caller on signing failure.
- **Idempotency**: No — tokens embed a time-bound `issuedAt`; two calls at
  different instants produce different tokens.
- **Return**: A signed JWT string; never `null`.

### `Validator<T>.validate(input: T): ValidationErrors`

- **Side Effects**: None.
- **Execution Context**: Synchronous.
- **Error Handling**: MUST NOT throw. Returns `ValidationErrors` with populated
  `errors` or `fieldErrors` on failure.
- **Idempotency**: Yes — same input always yields the same `ValidationErrors`.
- **Return**: `ValidationErrors(errors=[], fieldErrors=[])` when valid;
  populated lists otherwise.

---

## IV. Infrastructure & Environment

- **Library**: `de.mkammerer:argon2-jvm` (version `2.12` or latest stable).
  Requires JNA (Java Native Access) transitive dependency to be resolved by the
  `common` module's build configuration.
- **Library**: `com.auth0:java-jwt` (v4.x). Declared as a dependency of the
  `common` module.
- **No environment variables** are read by this package directly. Tuning
  parameters (`argon2.iterations`, `argon2.memory`, `argon2.parallelism`,
  `jwt.secret`, `jwt.issuer`) are injected via constructor arguments sourced
  from HOCON at the application-wiring layer — not inside this package.
- **No HOCON parsing** occurs inside this package.
- **No Ktor or HTTP dependencies** are permitted in this package.
- _(Planned — not yet in code)_ RFC-10 specifies that Argon2 operations MUST
  run on a **dedicated bounded thread pool** (`Executors.newFixedThreadPool(16)
  .asCoroutineDispatcher()`) distinct from `Dispatchers.IO`, to prevent a
  login-flood attack from exhausting the global IO dispatcher used for DB access.
  Current implementation uses `Dispatchers.IO`.

---

## V. History

- [x] [RFC-08: Auth Registration](../../../../../../../rfc/08-auth-registration.md)
- [x] [RFC-10: Auth Login](../../../../../../../rfc/10-auth-login.md)
