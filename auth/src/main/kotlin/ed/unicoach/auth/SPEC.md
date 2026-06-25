# SPEC: `auth` Domain Module

## I. Overview

The **Domain** of this module is the **single-use email-verification consume**:
exchanging a raw verification token from an email link for a verified-email
outcome. It exposes one port — the [`EmailVerifier`](./EmailVerifier.kt)
interface — with a database-backed implementation, plus the
[`VerifyEmailResult`](./VerifyEmailResult.kt) outcome type. It depends on `db`
alone, so any first-party service (`rest-server`, `public-web`) composes the
same verify in-process rather than hopping to a peer over HTTP.

## II. Behavioral Contracts

### `EmailVerifier` — the verify-email port

See [EmailVerifier.kt](./EmailVerifier.kt).

- **Behavior:** `verify(rawToken: String)` exchanges a raw token for a
  `Result<VerifyEmailResult>`.
- **Side Effects:** None of its own — the contract delegates persistence to the
  implementation.
- **Error Handling:** A successful exchange returns
  `Result.success(VerifyEmailResult)`; every domain outcome (including a
  rejected token) is a `success`. A persistence fault folds to `Result.failure`.
  The function does not throw.
- **Idempotency/Safety:** Defined by the implementation; the port itself
  prescribes only the no-throw, `Result`-wrapped contract.

### `DbEmailVerifier` — database-backed implementation

See [DbEmailVerifier.kt](./DbEmailVerifier.kt). Constructed with a `db`
`Database`.

- **Behavior:** `verify(rawToken)` runs a four-step flow in **one database
  transaction**:
  1. Hash the raw token to its SHA-256 `TokenHash` (only the hash is ever
     persisted; the raw token rides only in the email link).
  2. Compare-and-swap **consume** the single unconsumed, unexpired token row
     matching that hash.
  3. On a claimed token, **mark the user's email verified**, then **burn every
     remaining unconsumed token** for that user.
  4. Return `VerifyEmailResult.Success(user)`.
- **Zero-row consume classification:** When the consume claims no row (the DAO
  yields a not-found outcome), a second by-hash lookup classifies the failure
  into one of:
  - `VerifyEmailResult.InvalidToken` — no row matches the hash.
  - `VerifyEmailResult.AlreadyConsumed` — a matching row exists with a stamped
    `consumedAt`.
  - `VerifyEmailResult.Expired` — a matching row exists whose `expiresAt` is at
    or before now.
  - `VerifyEmailResult.InvalidToken` — a matching row exists that is neither
    consumed nor expired (residual/ambiguous case).
- **Side Effects:** Within a single transaction, **writes** the consumed token's
  `consumed_at`, the user's `email_verified_at` (and version bump), and
  `consumed_at` on the user's sibling tokens. The whole sequence **commits** on
  a returned outcome and **rolls back** on any thrown fault.
- **Error Handling:**
  - Every classified domain outcome above is returned inside `Result.success`.
  - A non-not-found fault from the consume or the classification lookup, and any
    fault from marking the user verified or burning siblings, propagates as a
    thrown exception out of the transactional block — caught at the boundary and
    folded to `Result.failure`. No exception escapes the method; a DB fault
    surfaces as `Result.failure`.
- **Idempotency/Safety:**
  - **Replaying a successful token is not a success twice.** The first call
    consumes the token (and burns siblings); a replay of the same raw token
    claims no row and returns `AlreadyConsumed`.
  - The verified-email mark is itself idempotent at the DAO level; the
    single-use consume gate stops a second verify of an already-consumed token
    before it reaches the mark step.

## II-B. Outcome Type

See [VerifyEmailResult.kt](./VerifyEmailResult.kt).

`VerifyEmailResult` is the sealed outcome of a verify-email attempt, relocated
into this module from `service`. `Success` carries the verified `User`; the
three rejection cases (`InvalidToken`, `Expired`, `AlreadyConsumed`) are
distinct, payload-free outcomes a caller renders into its own branded response.
All four are **successful** `Result` values — only a persistence fault is a
`Result.failure`.

## III. Infrastructure & Environment

- **Dependency surface:** `db` only (the `Database` handle and the
  `VerificationTokensDao` / `UsersDao` / `TokenHash` types). No email, queue, or
  chat dependencies, so the module is composable in-process by any service.
- **Transaction:** `verify` performs its consume → mark → burn sequence in a
  single connection-scoped transaction obtained from the supplied `Database`.
- No module-specific environment variables or config keys.

## IV. History

- [x] [RFC-71: Public-Web Email-Verification Page](../../../../../../../rfc/71-public-web-email-verification-page.md)
