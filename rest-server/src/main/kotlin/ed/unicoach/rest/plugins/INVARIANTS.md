# INVARIANTS — rest-server/rest/plugins

The cross-cutting Ktor interceptors installed in the `Plugins` phase —
`configureClientKeyGate`, `configureEmailVerificationGate`,
`configureStatusPages`, request-size limiting, and serialization.

## Invariants

### The email-verification gate exempts exactly `/healthz` and `/api/v1/auth/*`, and never gates an unauthenticated request

**Rule:** `configureEmailVerificationGate` MUST exempt exactly `/healthz` and
any path under the `/api/v1/auth/` prefix, and gate every other path by default.
It MUST act (respond `403 email_not_verified` + `finish()`) ONLY when
`resolveCaller` returns a caller whose `emailVerifiedAt` is null; a request with
no resolvable caller MUST pass through untouched.

**Why:** The auth family is how an unverified user verifies
(`resend-verification`, `verify-email`) or escapes (`logout`, `me`); dropping
that exemption deadlocks every unverified user out of the only endpoints that
can clear the gate. Gating by default (rather than per-route opt-in) keeps a
newly added protected family secure without remembering to list it. And
converting a no-caller request into a `403` would mask the handler's
`401 unauthorized`, telling an anonymous client "verify your email" for an
account it never authenticated as — a wrong, confusing contract and a state
leak.

### The verification gate registers after the client-key gate and after `configureStatusPages`

**Rule:** In `appModule`, `configureEmailVerificationGate` MUST be installed
after both `configureClientKeyGate` and `configureStatusPages` (all three are
`intercept(ApplicationCallPipeline.Plugins)`, which runs in registration order).

**Why:** Same-phase interceptors run in registration order. The client-key gate
must run first so an unverified caller with a bad/absent client key gets
`403 forbidden` (the coarse "unknown client" answer), not
`403 email_not_verified` — leaking verification state to an unauthorized client.
StatusPages must be installed first so a DB fault raised inside the gate's
`resolveSession` is caught and mapped (to `500 internal_error`) instead of
escaping the pre-handler intercept unhandled.

## History

- [x] [RFC-69: Email-Verification Gate + Error-Code Unification (Backend)](../../../../../../../../rfc/69-email-verification-gate.md)
