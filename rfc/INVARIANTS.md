# INVARIANTS — rfc/

The numbered RFC design documents (`NN-title.md`) — the durable record of why
each change was made and how it was intended to work at the time.

## Invariants

### A committed RFC is immutable

**Rule:** After its initial commit, an RFC file MUST NOT be edited except for
cosmetic updates (e.g. formatting). A changed decision lands in a new,
higher-numbered RFC; the earlier file stays as committed.

**Why:** RFCs are the historical record of intent. Reviews, implementations, and
later RFCs were produced against the original text, so rewriting a committed RFC
falsifies the record they were built on and silently invalidates everything that
referenced it.

## History

- Codified from the standing `rfc/README.md` convention during the 2026-07
  SPEC.md removal (no originating RFC).
