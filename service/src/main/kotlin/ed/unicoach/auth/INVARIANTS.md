# INVARIANTS — service/auth

The authentication and email-verification domain services (`AuthService`,
`EmailVerificationService`) and their result and config types: registration,
password login, Google federated sign-in, email verification, session
resolution, logout, and zombie-session cleanup. This layer owns credential
verification and bridges the HTTP boundary and the data layer.

## Invariants

### Every user always retains at least one usable credential

**Rule:** Every `users` row MUST always be reachable by at least one credential
— a non-null `password_hash` OR at least one `user_auth_identities` row. A flow
MUST NOT null a user's `password_hash`, MUST NOT delete its last identity, and
MUST create a user and its first credential atomically (Google provisioning
inserts the `users` row and its `user_auth_identities` row in one transaction).

**Why:** RFC 64 removed the row-local `users_auth_method_check` DB constraint
(migration `0017.drop-users-sso-provider-id.sql`) because the credential now
spans two tables and a row-local `CHECK` can no longer express it. The guarantee
is therefore enforced ONLY here. A user left with no credential is permanently
locked out with no recovery path, and a non-atomic provision can commit a
credential-less `users` row if the identity insert fails.

### Verification-email delivery is best-effort and never fails registration or resend

**Rule:** In `EmailVerificationService`, the post-commit `sendVerificationEmail`
MUST NOT propagate a failure that rolls back or fails registration
(`AuthService.register`) or `resend`. The token is persisted transactionally; a
send failure (provider rejection or subject/body construction) is logged and
folded into a `Result` the success path ignores.

**Why:** Token persistence and the provider send are non-transactional, and the
send happens after the transaction commits. Making the send all-or-nothing would
let a transient provider outage block account creation entirely — a
self-inflicted outage — when the user can simply resend. Best-effort delivery
over a blocked signup is the deliberate trade-off.

### `resend` never leaks verification state to the caller

**Rule:** `EmailVerificationService.resend` MUST return the same successful
outcome shape whether the user was already verified (`AlreadyVerified` — no
token issued, no mail sent) or a fresh token was issued and sent (`Sent`). The
HTTP layer collapses both to `204`.

**Why:** A distinguishable response (or an error on the already-verified branch)
would let an authenticated caller probe whether an address is already verified.
Keeping both branches a uniform success is the privacy boundary; a refactor that
surfaces "already verified" as a distinct status or error breaks it.

## History

- [x] [RFC-64: Google SSO Login](../../../../../../../rfc/64-google-sso-login.md)
- [x] [RFC-65: Email Verification (Backend)](../../../../../../../rfc/65-email-verification.md)
