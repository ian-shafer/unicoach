# INVARIANTS — ios-app/UnicoachiOS

The SwiftUI client (MVVM: Views → ViewModels → Clients over a shared
`APIClient`), including how it matches backend error codes.

## Invariants

### Server error-code literals match the backend lowercase wire codes; client-synthesized codes stay UPPERCASE

**Rule:** Every error-code string the app compares against a _server_ response
(e.g. `"unauthorized"`, `"student_already_exists"`, `"email_not_verified"` in
`AppViewModel` / `OnboardingViewModel`) MUST match the backend `ErrorCode.wire`
value byte-for-byte (lowercase snake_case). Codes the client _synthesizes_ for
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

### `ErrorResponse.status` is excluded from `Codable` and carried out-of-band

**Rule:** `ErrorResponse.CodingKeys` MUST list only the fields the server error
body actually carries (`code`, `message`, `fieldErrors`); `status` MUST stay out
of `CodingKeys`, defaulting to `nil` and being stamped in only by
`APIClient.decodeError` from the live `HTTPURLResponse.statusCode`.

**Why:** The server error JSON has no `status` field. Adding `status` to
`CodingKeys` would make `JSONDecoder.decode(ErrorResponse.self, …)` throw on
every real server error body; `decodeError` would then fall through to its
`SERVER_ERROR` catch-all, discarding the server's actual `code` and the true
status. `AppViewModel.resolveProfileState` routes on exactly these two
out-of-band facts — the matched `code` (e.g. `email_not_verified`) and
`status >= 500` (the `.serverError` vs `.unexpectedError` split) — so the
mistake silently misroutes every server error to the wrong screen with no
compile error. The split between a decoded body and an out-of-band, transport-
supplied status also keeps `nil` meaningful: a `nil` status marks a
client-synthesized error (transport/decode/non-HTTP) that never had one.

## History

- [x] [RFC-69: Email-Verification Gate + Error-Code Unification (Backend)](../../rfc/69-email-verification-gate.md)
- [x] [RFC-72: iOS Email-Verification UX](../../rfc/72-ios-email-verification-ux.md)
