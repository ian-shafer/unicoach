# INVARIANTS — rest-server/rest/auth

Per-request caller resolution: the `ResolvedCaller` value, its `call.attributes`
key, and the `ApplicationCall.resolveCaller` accessor that the
email-verification gate and the gated handlers share.

## Invariants

### `ResolvedCallerKey` is cached only after a real, non-null resolution

**Rule:** `resolveCaller` MUST store `ResolvedCallerKey` on `call.attributes`
only after `resolveSession` returns a non-null `AuthenticatedSession`. A missing
session cookie or a null resolution MUST cache nothing. The cached type MUST
stay non-null, so "attribute present" always implies "an actual lookup resolved
a caller."

**Why:** The gate and handlers treat the presence of the attribute as proof of a
resolved identity and skip re-resolution. If an unauthenticated or unresolved
request ever cached a null/anonymous entry, a downstream reader would mistake an
un-run or failed resolution for an authenticated caller — an authentication
bypass. On exempt paths the gate never runs, so a handler must always fall back
to a fresh lookup; caching on a null result would defeat that secure-by-default
fallback.

## History

- [x] [RFC-69: Email-Verification Gate + Error-Code Unification (Backend)](../../../../../../../../rfc/69-email-verification-gate.md)
