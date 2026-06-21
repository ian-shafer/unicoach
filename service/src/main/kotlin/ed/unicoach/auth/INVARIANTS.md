# INVARIANTS — service/auth

The authentication and email-verification domain services (`AuthService`,
`EmailVerificationService`) and their result and config types.

## Invariants

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

- [x] [RFC-65: Email Verification (Backend)](../../../../../../../rfc/65-email-verification.md)
