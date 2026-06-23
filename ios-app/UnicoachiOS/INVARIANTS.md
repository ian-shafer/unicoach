# INVARIANTS — ios-app/UnicoachiOS

The SwiftUI client (MVVM: Views → ViewModels → Clients over a shared
`APIClient`), including how it matches backend error codes.

## Invariants

### Server error-code literals match the backend lowercase wire codes; client-synthesized codes stay UPPERCASE

**Rule:** Every error-code string the app compares against a _server_ response
(e.g. `"unauthorized"`, `"student_already_exists"` in `AppViewModel` /
`OnboardingViewModel`) MUST match the backend `ErrorCode.wire` value
byte-for-byte (lowercase snake_case). Codes the client _synthesizes_ for
transport/decoding failures (`TIMEOUT`, `NETWORK_ERROR`, `SERVER_ERROR`,
`UNKNOWN` in `APIClient`) MUST stay UPPERCASE, distinct from any backend wire
code.

**Why:** The app has no shared enum with the backend — these are bare string
literals, so a casing mismatch fails silently: an unmatched branch falls through
to `.serverError`, misclassifying (e.g.) an unauthenticated state as a server
failure with no compile error or test until the wrong screen renders. The
deliberate UPPERCASE-vs-lowercase namespace split keeps client-origin codes from
ever colliding with a backend code; collapsing the casing would let a
synthesized code be mistaken for a server one (or vice versa), routing the wrong
UX.

## History

- [x] [RFC-69: Email-Verification Gate + Error-Code Unification (Backend)](../../rfc/69-email-verification-gate.md)
