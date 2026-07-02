# Testing Guide — UnicoachiOS

Practical conventions for writing tests in this target. This is **guidance, not
a module spec** — for the DI / `MockURLProtocol` / xcodebuild mechanics, read
the code (`UnicoachiOS/` and this target's fixtures); this file captures _how to
write a good test_ on top of them.

## Running the suite

iOS tests run through **system Xcode**, not the Nix dev shell:

```sh
xcodebuild test \
  -project ios-app/UnicoachiOS.xcodeproj \
  -scheme UnicoachiOS \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

New test `.swift` files MUST be registered in
`ios-app/UnicoachiOS.xcodeproj/project.pbxproj` (explicit file references —
there is no file-system synchronization), or they silently never compile.

## Test doubles

- **Client tests** (`APIClient` / `AuthClient` / `StudentClient`): drive the
  real client through `MockURLProtocol` on an **ephemeral** `URLSession`
  injected via `APIClient(baseURL:session:)`. This exercises the real request
  building, status handling, and **decoding** — the boundary where bugs hide.
  See [StudentClientTests.swift](./StudentClientTests.swift).
- **View-model tests**: inject a **protocol mock** (`MockStudentClient`, or the
  `CapturingStudentClient` double inside the test file) — never the real client.
  View-model tests assert state transitions and the request captured, not the
  wire.

> A protocol mock bypasses JSON decoding entirely. A bug that only manifests
> when real bytes are decoded (see below) can ONLY be caught at the
> client/`MockURLProtocol` layer — never in a view-model test. Put
> decode-sensitive assertions there.

## Boundary fidelity (load-bearing)

**A fixture MUST match what the real peer actually emits — never Swift's
convenient default.** A fixture that diverges from the wire can stay green while
production breaks.

- The REST server serializes `Instant` via Jackson's `JavaTimeModule`, so
  `PublicStudent.createdAt` / `updatedAt` arrive as **ISO-8601 strings** with
  variable-precision fractional seconds and a trailing `Z`
  (`2025-01-07T22:16:27.092942Z`, or `2025-01-07T22:16:27Z` on a whole second).
- Therefore a mock `StudentResponse` body MUST encode those timestamps as
  ISO-8601 strings — either via a `JSONEncoder` with
  `.dateEncodingStrategy = .iso8601`, or via
  `RandomFixtures.studentResponseJSON`. **NEVER** round-trip a default-encoded
  Swift `StudentResponse`: the default `Date` strategy emits a _numeric_
  timestamp the default decoder happily reads back, so encoder and decoder agree
  on a format the real server never sends.
- Why this rule exists: a numeric round-tripped fixture let a real
  `DECODE_ERROR` ship green (the app routed re-login to `.serverError`). The
  fixture, not the code, was wrong. See
  [RandomFixtures.swift](./RandomFixtures.swift) and the
  `…DecodesRealServerTimestamps` tests in
  [StudentClientTests.swift](./StudentClientTests.swift).

## Randomized fixtures, deterministic coverage

`RandomFixtures` exercises the **whole valid range** so edge cases surface over
runs instead of one hand-picked value:

- **Seeded & reproducible.** A SplitMix64 generator drives every draw, and each
  builder `print`s its seed and produced values, so a failing random draw is
  replayable by pinning the logged seed.
- **Boundary-faithful generators.** Graduation dates at a random valid precision
  (`YYYY` | `YYYY-MM` | `YYYY-MM-DD`); server timestamps as
  microsecond-fractional ISO-8601 — the exact shapes the wire uses.

**Randomized coverage is ADDITIVE, never a replacement.** Known-important
discrete cases stay deterministically pinned in their own tests, regardless of
any random draw. Example: the three graduation-date precisions are asserted
explicitly through `submit()` (`testSubmitEmitsCanonicalStringForEachPrecision`)
**and** through `isoDate` (`testIsoDate*Precision`), not left to chance.

## Fixing a bug: failing test first

When resolving a reported bug, write a test that **reproduces the real failure
and fails first**, confirm it fails for the right reason, then fix and watch it
go green. The failing test must reproduce the bug at the real boundary (see
Boundary fidelity), not a synthetic stand-in. (Repo-wide policy: the `test`
skill.)
