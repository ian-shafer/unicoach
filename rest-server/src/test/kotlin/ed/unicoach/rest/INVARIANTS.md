# INVARIANTS — rest-server integration tests

End-to-end tests that drive real HTTP → real services → real DB (RFC 107).

## Invariants

### Never fake the database

**Rule:** Integration tests run against a real Postgres (the `bin/test`
harness); the database and its DAO writes are never faked or mocked.

**Why:** These tests exist to prove real DAO writes land in real tables under
real constraints. A faked database would not catch schema, constraint, or
mapping regressions, making the end-to-end claim false.
