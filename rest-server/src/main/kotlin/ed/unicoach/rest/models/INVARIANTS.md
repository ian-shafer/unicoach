# INVARIANTS — rest-server/rest/models

The REST wire DTOs, including `ErrorResponse` and the `ErrorCode` enum that is
the single source of truth for every REST error code.

## Invariants

### `ErrorCode` is the sole source of every wire error code, and every code is lowercase snake_case

**Rule:** Every REST error code MUST be defined as an `ErrorCode` enum entry,
and `ErrorResponse.code` MUST stay typed as `ErrorCode` (never a free `String`).
Every `ErrorCode.wire` value MUST match `^[a-z][a-z0-9_]*$` (lowercase
snake_case). A new code MUST be added by editing the enum — never by
constructing a code string inline at a call site.

**Why:** The error code is a client contract: iOS branches on exact code strings
byte-for-byte. Before unification, codes were inline string literals with no
shared definition, and the `StudentRoutes` family silently drifted to UPPERCASE
— a casing split no one chose. Re-opening a `String`-typed `code`, or admitting
a mis-cased entry, re-creates that hole: a typo or a casing slip ships a code no
client matches, breaking the consumer without a compile error. The enum + the
typed field + the `^[a-z][a-z0-9_]*$` guard test together make a mis-cased or
stringly-typed code unconstructible.

## History

- [x] [RFC-69: Email-Verification Gate + Error-Code Unification (Backend)](../../../../../../../../rfc/69-email-verification-gate.md)
