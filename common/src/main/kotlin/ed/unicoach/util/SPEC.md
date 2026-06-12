# SPEC: `common/src/main/kotlin/ed/unicoach/util`

## I. Overview

Domain-agnostic cryptographic and validation utilities shared across all
modules. This package provides three facilities: password hashing via Argon2id
(`Argon2Hasher`), stateless JWT minting (`JwtGenerator`), and a generic
validation contract (`Validator<T>` / `ValidationErrors`). It has no knowledge
of HTTP semantics, domain entities, or persistence.

---

## II. Invariants

### [Argon2Hasher](./Argon2Hasher.kt)

- The hasher MUST use algorithm `Argon2id` with salt length 16 bytes and hash
  length 32 bytes.
- `hash` and `verify` MUST offload work to the injected `dispatcher`, never the
  caller's coroutine context. The default `dispatcher` MUST be a dedicated
  CPU-bound pool.
- Both `hash` and `verify` MUST be wrapped in `withTimeout(timeoutMs)`. The
  default timeout MUST be `2000 ms`.
- The plaintext password char array MUST be wiped via `argon2.wipeArray()` in a
  `finally` block regardless of outcome — NEVER left in memory after the call
  completes.
- Default tuning parameters MUST be: `iterations = 3`, `memory = 65536 KiB`,
  `parallelism = 1`. All four tunables (`iterations`, `memory`, `parallelism`,
  `timeoutMs`) MUST be injectable at construction time, as MUST the execution
  `dispatcher`.

### [CryptoDispatcher](./CryptoDispatcher.kt)

- `Dispatchers.Crypto` MUST provide a CPU-bound dispatcher backed by a single
  process-wide thread pool, shared across all callers — NEVER a new pool per
  access.
- The pool size MUST be bound to the host's available processor count.

### [JwtGenerator](./JwtGenerator.kt)

- Tokens MUST be signed with `HMAC256` using the injected `secret`.
- Token expiry MUST be exactly `+7 days` from the `issuedAt` instant as derived
  from the injected `Clock`.
- The injected `Clock` MUST be used for all `Instant.now()` calls — NEVER
  `Clock.systemUTC()` inline — to keep the class fully testable.
- `mint()` MUST set the `issuer`, `subject`, `issuedAt`, and `expiresAt` JWT
  claims on every token.
- Claim value types MUST be restricted to `String`, `Int`, `Boolean`, `Double`,
  and `Long`. Unsupported claim types MUST be silently dropped (no exception).

### [Validator / ValidationErrors](./Validator.kt)

- `Validator<T>` MUST be a pure interface with a single method
  `validate(input: T): ValidationErrors`.
- `ValidationErrors` MUST aggregate both free-form string errors
  (`errors:
  List<String>`) and structured field-level errors
  (`fieldErrors:
  List<FieldError>`).
- `ValidationErrors.hasErrors()` MUST return `true` if and only if either list
  is non-empty.
- Implementations MUST NOT throw exceptions for validation failures — they MUST
  return a `ValidationErrors` instance.

---

## III. Behavioral Contracts

### `Argon2Hasher.hash(password: String): String`

- **Side Effects**: None. No DB, no network, no file I/O.
- **Execution Context**: Suspends; offloads to the injected `dispatcher`, never
  the caller's context.
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
- **Execution Context**: Suspends; offloads to the injected `dispatcher`, never
  the caller's context.
- **Timeout**: Throws `TimeoutCancellationException` if verification exceeds
  `timeoutMs`.
- **Memory Safety**: Wipes the password char array in `finally`.
- **Error Handling**: Propagates `Argon2RuntimeException` or
  `TimeoutCancellationException` to the caller.
- **Idempotency**: Yes — given the same hash and password, always returns the
  same Boolean.
- **Return**: `true` if the password matches the hash; `false` otherwise.

### `Dispatchers.Crypto: CoroutineDispatcher`

- **Side Effects**: None per access; lazily wraps the shared process-wide crypto
  thread pool.
- **Execution Context**: A CPU-bound dispatcher; the default execution context
  for `Argon2Hasher`.
- **Idempotency**: Yes — every access returns a dispatcher over the same
  underlying pool.

### `JwtGenerator.mint(subjectId: String, claims: Map<String, Any>): String`

- **Side Effects**: None. No DB, no network.
- **Claims**: `claims` defaults to empty; `mint(subjectId)` produces a token
  carrying only the standard issuer/subject/issuedAt/expiresAt claims.
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

---

## V. History

- [x] [RFC-08: Auth Registration](../../../../../../../rfc/08-auth-registration.md)
- [x] [RFC-10: Auth Login](../../../../../../../rfc/10-auth-login.md)
- [x] [RFC-26: Login](../../../../../../../rfc/26-login.md)
- [x] [RFC-28: Coroutine Context Refactor](../../../../../../../rfc/28-coroutine-context.md)
