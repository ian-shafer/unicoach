---
name: test
description: Test-driven development guidelines for resolving bugs.
---

# 🤖 Skill: Test-Driven Bug Fixing

This skill establishes the universal workflow for resolving bugs in the
codebase. You MUST adhere to this test-driven approach to ensure strict
validations against regressions.

## 📜 Core Philosophy

1. **Write a Failing Test First**
   - BEFORE altering any application code to fix a reported bug, you MUST write
     an automated test that explicitly exercises the broken behavior.
   - The test MUST fail.
   - You MUST run the test and verify its failure to confirm that the test
     accurately models the error — and that it fails for the _right reason_, not
     a setup typo.

2. **Fix the Bug**
   - After (and only after) proving the test fails, you may modify the
     application logic to address the flaw.

3. **Verify Success**
   - Re-run the test suite to ensure the previously failing test now passes
     successfully, and that no other adjacent tests have experienced a
     regression.

4. **Real Infrastructure Equivalency**
   - Have a STRONG preference to test against real infrastructure (e.g. a local
     postgres instance).

## 🎯 Reproduce the REAL Failure (Boundary Fidelity)

A passing suite is **not** proof of correctness when the fixtures diverge from
the real boundary. A test is only as honest as the data it feeds the code.

- The failing test MUST exercise the bug **as it actually manifests at the real
  boundary** — real wire formats, real serialization, real status codes and
  headers — not a synthetic stand-in that merely _looks_ plausible.
- When a bug lives at a serialization / network / storage boundary, derive the
  fixture from **what the real peer actually emits**: capture a real payload, or
  copy the canonical wire shape straight from the API spec / schema. NEVER
  hand-write the convenient shape that happens to pass.
- If a green test and a broken production path disagree, suspect the fixture
  before the code.

## 🎲 Exercise the Whole Valid Range (Randomized Fixtures)

A single convenient value tests a single point; bugs hide at the edges and in
the formats you didn't think to type by hand. When an input has a defined valid
range or set of valid shapes, **generate a random valid value within that range
each run** instead of hard-coding one.

- **Examples**
  - A name field valid at 1–255 chars → a random string of random length in
    `[1, 255]` (exercise both ends over time).
  - A variable-precision date → a random valid date that randomly selects one of
    its precisions (`YYYY` | `YYYY-MM` | `YYYY-MM-DD`) with valid component
    values (month `1–12`, leap-aware day), formatted exactly as the wire
    contract specifies.
  - An enum / status code → a random member of the valid set.
- **Generators live in a shared test utility**, never inline in each test, so
  the valid-range definition has a single home and the generator is reusable
  across suites (e.g. an iOS `RandomFixtures`/`TestData` helper in the test
  target).
- **Keep failures reproducible.** Pure randomness can produce a red run you
  can't repeat. The generator MUST log the value it produced (and SHOULD accept
  an optional seed) so any failure can be replayed exactly.

## ⚠️ Cautionary Example

RFC 42 (iOS student-profile onboarding) shipped with **66 passing tests** and
still broke in manual use: re-login landed on an error screen. Root cause —
`APIClient.decode` used a bare `JSONDecoder()` with no
`.dateDecodingStrategy = .iso8601`, so every response carrying a `Date`
(`createdAt`/`updatedAt`) threw `DECODE_ERROR`. The suite stayed green only
because the test fixtures used a date format the bare decoder happened to
accept, **not** the ISO8601 strings the real server emits. A boundary-faithful,
range-spanning random-date fixture would have caught it on the first run. The
fixtures were testing the wrong thing.
